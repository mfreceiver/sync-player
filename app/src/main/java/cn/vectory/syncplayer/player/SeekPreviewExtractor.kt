package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import cn.vectory.syncplayer.player.preview.DaemonLanePool
import cn.vectory.syncplayer.player.preview.PreviewDecoder
import cn.vectory.syncplayer.player.preview.PreviewLanePool
import cn.vectory.syncplayer.player.preview.SeekPreviewEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin facade over [SeekPreviewEngine] preserving the original seek-preview API.
 *
 * [SeekPreviewEngine] owns resource lifetime behind a single state lock on a
 * shared, bounded lane pool ([PreviewLanePool]) — native calls never run on
 * caller threads. [dispatcher] only carries CPU-side post-decode scaling; it
 * never executes native calls (the dedicated lane does).
 *
 * ## Publish barrier (B6)
 *
 * Every intent change — [beginSession], [endSession], [release], a cache-hit
 * target read in [getCachedFrame], and every [extractFrameAtPosition] submit —
 * bumps a facade `intentTicket` under [publishLock]. An extraction captures
 * its ticket at submit time; after decode+scale, the validity check AND the
 * cache write happen inside ONE [publishLock] critical section. A result from
 * an older session or target can therefore never be returned or cached after
 * a newer intent registered — including the "pure cache hit" path, which the
 * engine cannot see (it never touches engine state). Engine-side identity
 * (`epoch`/`isCurrent(gen)`) is re-verified in the same section, so the
 * facade barrier and the engine barrier agree at the linearization point.
 * The one deliberate exception is [prefetchNeighbors]: it is not a UI intent
 * (it must not invalidate in-flight publishes), it runs on its OWN engine
 * channel (own mailbox/lane/native session — UI request identity is never
 * touched) and publishes cache-only under the facade session half of this
 * barrier.
 *
 * ## Failure & cancellation contract (B6, aligned with the B5 engine)
 *
 * - `CancellationException` — caller or native — propagates out of
 *   [beginSession] and [extractFrameAtPosition] FIRST; it is never logged and
 *   swallowed as an ordinary failure.
 * - Any other failure of a decode/open fast-fails the caller: [beginSession]
 *   degrades to a logged no-op (a failed preview session must not crash the
 *   UI), [extractFrameAtPosition] surfaces `null`. Cleanup never blocks the
 *   caller: native close failures are observed on the engine's fixed
 *   per-generation closeSignal and on [shutdownResult] (which completes
 *   exceptionally), never by waiting on the UI thread.
 * - Engine `NoCapacity` (bounded pool exhausted) and worker submit rejection
 *   degrade the same way: fast, non-throwing, no facade-side queued state.
 *   A submit rejection is rolled back engine-side — the affected signals
 *   ([shutdownResult] and the targeted per-generation close signal)
 *   terminate exceptionally with the real rejection instead of hanging. If
 *   the engine still held native resources (an installed session), the
 *   rejection is TERMINAL: the engine seals, the pool lease is consumed for
 *   the engine's lifetime (the pool bound stays honest — a new engine
 *   degrades to `NoCapacity` instead of inheriting a possibly-dirty lane).
 *   No guaranteed active native release happens in that case: the held
 *   decoders may persist until they become unreachable (and are finalized)
 *   or the process exits; the consumed lease bounds how many decoders can be
 *   in that state at once. Later facade calls on that instance keep
 *   answering from the terminal state (Closed / null / same terminated
 *   signals); a fresh engine instance recovers normal capacity.
 *
 * ## Cache ownership
 *
 * Entries are keyed by (uri, media generation = engine session `epoch`,
 * position bucket, output size class) so entries from an older session or a
 * different media/output spec are unreachable. Reads are disabled the moment
 * [release] flips the facade identity. Billing is by real byte count with a
 * 1 KB floor under a strict 4 MB budget — billed bytes are never less than
 * resident bytes, so the cache can never overshoot the budget. Cached
 * bitmaps are NEVER `recycle()`d — the UI may still hold them; eviction only
 * drops references, and the explicit release path is the idempotent,
 * non-recycling [releaseFrame].
 */
internal class SeekPreviewExtractor(
    context: Context,
    private val retrieverFactory: () -> MediaMetadataRetriever = { MediaMetadataRetriever() },
    /** Factory for the DEDICATED prefetch retriever — a distinct native
     * instance from the UI one (prefetch opens its own session on its own
     * lane), so the two channels can never contend for one resource. */
    private val prefetchRetrieverFactory: () -> MediaMetadataRetriever = retrieverFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO, // scale-only; never carries native
    lanePool: PreviewLanePool = SHARED_POOL,
    /** Prefetch runs on its OWN single-lane pool, never the UI pool: UI
     * capacity is untouchable by construction, and a prefetch that cannot
     * get its lane is skipped (`NoCapacity`), never queued. Defaults to the
     * process-level [Companion.SHARED_PREFETCH_POOL] so extractor churn
     * cannot leak daemon threads; injectable for tests. */
    prefetchLanePool: PreviewLanePool = SHARED_PREFETCH_POOL,
    /** Scale hook (facade-wide); nullable so a default can reference the
     * instance method. Injected for controllable-failure tests. */
    private val scale: ((Bitmap) -> Bitmap)? = null,
) {

    companion object {
        private const val TAG = "SeekPreviewExtractor"

        /** Hard cache budget in BYTES (4 MB). */
        private const val CACHE_MAX_SIZE_BYTES = 4 * 1024 * 1024

        /** Minimum billable BYTES per entry (1 KB) so tiny frames still count. */
        private const val MIN_BILLABLE_BYTES = 1024

        /** Max decoded bitmap edge after scaling. */
        const val MAX_WIDTH = 180
        const val MAX_HEIGHT = 180

        /** Absolute pixel budget for a decoded preview. */
        const val MAX_PIXELS = 64_800

        private const val POSITION_ROUNDING_MS = 500L

        /** Shared bounded execution capacity across all extractor instances. */
        private val SHARED_POOL: PreviewLanePool = DaemonLanePool(maxLanes = 2)

        /**
         * Process-level shared PREFETCH capacity: one daemon lane across
         * every extractor instance. Daemon lane threads block on an
         * unbounded `queue.take()` with no shutdown protocol, so a
         * per-instance pool would leak exactly one thread per player
         * open/close cycle — the shared pool bounds that leak at one thread
         * per PROCESS. Injection stays available for tests
         * ([sharedPrefetchPoolForTest]). A lane stuck or parked by a
         * torn-down instance keeps the shared slot busy: later prefetch
         * begins get `NoCapacity` and are skipped (best-effort) — never
         * queued, never able to grow the capacity.
         */
        private val SHARED_PREFETCH_POOL: PreviewLanePool by lazy { DaemonLanePool(maxLanes = 1) }

        /** Test access to the exact pool instance production uses by default. */
        internal fun sharedPrefetchPoolForTest(): PreviewLanePool = SHARED_PREFETCH_POOL
    }

    private class RetrieverDecoder(
        private val context: Context,
        private val uriString: String,
        private val retriever: MediaMetadataRetriever,
    ) : PreviewDecoder<Bitmap> {
        override fun open() {
            retriever.setDataSource(context, Uri.parse(uriString))
        }

        override fun decode(positionMs: Long): Bitmap? =
            retriever.getFrameAtTime(
                positionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )

        override fun close() {
            retriever.release()
        }
    }

    private val appContext = context.applicationContext

    private val engine = SeekPreviewEngine(
        decoderFactory = { key -> RetrieverDecoder(appContext, key, retrieverFactory()) },
        lanePool = lanePool,
        logger = { Log.w(TAG, it) },
    )

    /**
     * DEDICATED prefetch engine — the load-bearing identity isolation of
     * this facade. UI extractions go through [engine] and its latest-wins
     * request mailbox; prefetch goes through [prefetchEngine] with its own
     * mailbox, its own lane pool and its own native session. A prefetch can
     * therefore never bump the UI engine's request generation (never
     * supersedes an in-flight or pending UI request), and a UI request can
     * never invalidate prefetch work: the two request spaces are disjoint.
     */
    private val prefetchEngine = SeekPreviewEngine(
        decoderFactory = { key -> RetrieverDecoder(appContext, key, prefetchRetrieverFactory()) },
        lanePool = prefetchLanePool,
        logger = { Log.w(TAG, "prefetch $it") },
    )

    /**
     * Cache key: media identity (uri) + media generation (engine session
     * epoch at insertion) + position bucket + output size class, so a
     * different media with identical duration, an older session, or another
     * output spec can never be served from a foreign entry.
     */
    private data class CacheKey(
        val uri: String,
        val mediaGeneration: Long,
        val bucketMs: Long,
        val outWidth: Int,
        val outHeight: Int,
    )

    private fun cacheKeyFor(uri: String, mediaGeneration: Long, positionMs: Long) =
        CacheKey(uri, mediaGeneration, roundPosition(positionMs), MAX_WIDTH, MAX_HEIGHT)

    private val frameCache = object : LruCache<CacheKey, Bitmap>(CACHE_MAX_SIZE_BYTES) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int =
            maxOf(MIN_BILLABLE_BYTES, value.byteCount)
    }

    // ---------- facade publish barrier ----------

    /** Guards [intentTicket]/[releasedFlag]; NEVER held across native or
     * suspending calls (engine lock may nest inside, never the reverse). */
    private val publishLock = Any()

    /** Bumped on every intent change; extracted results carry the ticket of
     * the intent they were spawned under and may only publish while it is
     * still current. */
    private var intentTicket = 0L

    /** Facade-level identity flip: cache reads and publishes stop at once. */
    @Volatile
    private var releasedFlag = false

    /** Register a new intent; invalidates every in-flight publish ticket. */
    private fun registerIntent() {
        synchronized(publishLock) { intentTicket++ }
    }

    // ---------- bounded neighbor prefetch (U3b: dedicated channel) ----------

    /**
     * Root scope for prefetch work — cancelled ONLY by [release]. It uses
     * [dispatcher], which never carries native work (the prefetch engine's
     * dedicated lane does), so cancelling or finishing here can never
     * interrupt or block a native call.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Facade session generation: bumped at [beginSession]/[endSession].
     * A prefetch pass captures the token of the session that spawned it and
     * may only cache its result while that token is still current — a late
     * task from an ENDED session can never write into the new session's
     * cache. Guarded by [publishLock]. */
    private var sessionToken = 0L

    /** Cancellation umbrella for the CURRENT session's prefetch passes;
     * created per [beginSession], cancelled by [endSession] (and via the
     * scope by [release]). Guarded by [publishLock]. */
    private var sessionJob: Job? = null

    /**
     * Single prefetch-pass slot, claimed SYNCHRONOUSLY inside
     * [prefetchNeighbors] before any coroutine is created: at most ONE
     * prefetch pass is ever in flight, and a call that would queue behind it
     * is DROPPED at once — no launches pile up on a blocked dispatcher, no
     * neighbor list ever queues anywhere. Best-effort by design: the next
     * current-frame success re-fires the prefetch.
     */
    private val passGate = AtomicBoolean(false)

    /** Neighbor buckets registered by the in-flight pass. Guarded by
     * [publishLock]; the owning pass removes them exactly once in its
     * completion handler — including when it is cancelled before its body
     * ever ran ([Job.invokeOnCompletion] covers that case, a body-only
     * `finally` cannot). */
    private val inFlightPrefetchBuckets = mutableSetOf<Long>()

    /** One admitted prefetch pass: everything needed to run and validate it. */
    private data class PrefetchPass(
        val uri: String,
        val neighbors: List<Long>,
        val generation: Long,
        val token: Long,
    )

    /** Opened lazily, once per facade session, on the first admitted pass.
     * Reset on [endSession]/[release] so the next session re-begins. */
    @Volatile private var prefetchSessionToken = -1L

    /**
     * Engine epoch at the time the prefetch session was installed, paired
     * with [prefetchSessionToken]. The fast path in
     * [ensurePrefetchSession] only trusts the claim when the engine epoch
     * is STILL the one recorded at install time: any retirement (a
     * [endSession] phase 2, a cancellation-owner reclaim, [release]) bumps
     * the epoch, so a stale claim can never fast-path a live caller into
     * per-extract ephemeral opens (open/close per bucket).
     */
    @Volatile private var prefetchSessionGen = -1L

    /**
     * Warm the frame cache for the position buckets ADJACENT to
     * [positionMs] (±1 × 500 ms) so a horizontal drag into a neighbouring
     * bucket is served from cache instead of waiting on a native decode.
     * Fire-and-forget and best-effort by design:
     *
     * - **identity isolation**: runs on the dedicated [prefetchEngine] (own
     *   mailbox, own lane, own native session) — it can never supersede an
     *   in-flight or pending UI request on the UI engine, and never bumps
     *   [intentTicket], so it never invalidates an in-flight UI publish;
     * - results go ONLY into the frame cache under the FACADE session
     *   barrier (released / newer [sessionToken] / changed media generation
     *   all kill the result at the publish point) — never returned to the
     *   UI directly, never cached across a session boundary;
     * - **synchronous admission**: the single in-flight slot is claimed
     *   here, before any coroutine exists — busy means drop, immediately;
     *   the slot is released on real pass completion (an early superseded
     *   return is still in-flight work), and even when the launched job is
     *   cancelled before its body ever runs;
     * - idempotent: cached neighbors are skipped, a pass that would queue
     *   behind a running one is dropped;
     * - invalidated by [endSession] (session job cancelled + token bumped +
     *   prefetch engine session retired) and [release] (everything stops);
     * - every failure degrades to a log line; CancellationException never
     *   escapes into the scope's error handling.
     */
    fun prefetchNeighbors(uri: String, positionMs: Long, radiusBuckets: Int = 1) {
        require(radiusBuckets == 1) {
            "only radiusBuckets == 1 is supported (one rolling window per drag step), got $radiusBuckets"
        }
        synchronized(publishLock) {
            if (releasedFlag) return
            val active = sessionJob
            if (active == null || !active.isActive) return // no live session — nothing to warm
            val generation = engine.epoch()
            if (generation < 0) return // session not installed — nothing to warm
            val base = roundPosition(positionMs)
            val neighbors = buildList {
                for (k in 1..radiusBuckets) {
                    add(base - k * POSITION_ROUNDING_MS)
                    add(base + k * POSITION_ROUNDING_MS)
                }
            }.filter { it >= 0 }
            if (neighbors.isEmpty()) return
            if (!passGate.compareAndSet(false, true)) return // a pass is running — drop
            neighbors.forEach { inFlightPrefetchBuckets.add(it) }
            val token = sessionToken
            scope.launch(active) {
                runPrefetchPass(PrefetchPass(uri, neighbors, generation, token))
            }.invokeOnCompletion {
                // Real completion is the in-flight boundary: an early
                // superseded return is still in-flight work until here. This
                // also fires when the job was cancelled before its body ever
                // ran — a body-only `finally` would leak the slot then.
                //
                // Atomic cleanup-vs-admission: THIS pass's registrations are
                // cleared BEFORE the gate opens, inside ONE critical section.
                // Admission takes the same lock, so a pass admitted after the
                // gate opens — with overlapping buckets — can never have its
                // registrations deleted by this (older) handler. The previous
                // order (gate released first, clear second) allowed exactly
                // that interleaving and leaked the ledger.
                synchronized(publishLock) {
                    neighbors.forEach(inFlightPrefetchBuckets::remove)
                    passGate.set(false)
                }
            }
        }
    }

    /**
     * Cache-hit skip at execution time (registration happened at admission).
     * The generation is the pass's captured one — never re-read from the
     * engine here, so a mid-pass session change invalidates at the publish
     * barrier, not by silently re-keying.
     */
    private fun shouldStillPrefetch(uri: String, bucketMs: Long, generation: Long): Boolean =
        synchronized(publishLock) {
            if (releasedFlag) return false
            frameCache.get(cacheKeyFor(uri, generation, bucketMs)) == null
        }

    /**
     * Open the dedicated prefetch engine session once per facade session.
     * Any non-`Opened` outcome (including `NoCapacity` on the prefetch's own
     * single lane) is a logged SKIP — never a queue, never an error.
     *
     * Ownership rules (two guards, one ordering discipline):
     *
     * - **fast path** trusts the installed-session claim ONLY when the
     *   engine epoch still equals [prefetchSessionGen] (recorded at install
     *   time). Any retirement bumps the epoch, so a claim left behind by a
     *   retirement can never satisfy a later ensure — the pass re-begins
     *   instead of silently degrading to per-extract ephemeral opens.
     * - **install validation**: a `begin` suspends in native open; the
     *   session it was started for may be reaped while it is parked
     *   (identity swap / retirement). Before claiming ownership the result
     *   is validated UNDER the publish lock against the CURRENT identity
     *   and the epoch captured at entry — same ordering discipline as the
     *   non-suspending control commits (check identity + commit claim in
     *   one critical section). A stale install is retired by its own pass
     *   (safe: the pass gate serializes passes, so no newer pass can be
     *   mid-ensure while this one is still running) and the claim is left
     *   unowned for the next pass to re-begin.
     */
    private suspend fun ensurePrefetchSession(pass: PrefetchPass): Boolean {
        val genAtEntry = prefetchEngine.epoch()
        if (prefetchSessionToken == pass.token && prefetchSessionGen == genAtEntry) return true
        prefetchSessionToken = pass.token
        prefetchSessionGen = -1L // claim is optimistic; only validated installs set the gen
        val result = try {
            prefetchEngine.begin(pass.uri)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "prefetch session init failed: ${t.javaClass.simpleName}")
            prefetchSessionToken = -1L
            return false
        }
        if (result !== SeekPreviewEngine.InitResult.Opened) {
            Log.w(TAG, "prefetch session init result=$result")
            prefetchSessionToken = -1L
            return false
        }
        // The begin itself installs a fresh engine generation, so the
        // reference generation is captured AFTER the open: what matters is
        // that no retirement lands between the install and the claim (the
        // entry epoch cannot be used — the begin bumps it by installing).
        val installedGen = prefetchEngine.epoch()
        var staleInstall = false
        synchronized(publishLock) {
            val stillCurrent = !releasedFlag && sessionToken == pass.token && sessionJob?.isActive == true
            if (stillCurrent && prefetchEngine.epoch() == installedGen) {
                prefetchSessionToken = pass.token
                prefetchSessionGen = installedGen
            } else {
                staleInstall = true
                prefetchSessionToken = -1L // leave unowned: the next pass re-begins
            }
        }
        if (staleInstall) {
            // Retire the install THIS pass just made — its spawning session
            // is gone (or the engine was retired mid-open). No live caller
            // remains that could starve the retire close: this pass is the
            // only possible re-submitter and it stops here, and no newer
            // pass can be mid-ensure while the pass gate is still held by
            // this one.
            Log.w(TAG, "prefetch session install became stale before first use")
            prefetchEngine.end()
            return false
        }
        return true
    }

    /**
     * One admitted prefetch pass over the neighbor list, sequentially.
     * Extraction runs on the prefetch engine's lane; scaling on the
     * dispatcher; the publish is a cache-only write under the facade session
     * barrier. Every failure degrades to a log line; CancellationException
     * propagates (cancellation is how [endSession]/[release] stop a pass).
     */
    private suspend fun runPrefetchPass(pass: PrefetchPass) {
        for (bucket in pass.neighbors) {
            // Bucket-continuation guard: stop BEFORE any further native work
            // once the facade session that spawned this pass is gone. Phase 1
            // of endSession reaps the identity before stale.cancel() reaches
            // this (possibly parked) pass — without this guard a surviving
            // pass would keep submitting engine work after the retirement
            // decision, i.e. re-submits that can replace a queued
            // CloseSession and lose the retirement.
            synchronized(publishLock) {
                if (releasedFlag || sessionToken != pass.token) return
            }
            if (!shouldStillPrefetch(pass.uri, bucket, pass.generation)) continue
            // Extraction + scaling inside ONE degraded boundary: any failure
            // other than cancellation degrades to a log line and moves on to
            // the next bucket; CancellationException propagates (that is how
            // [endSession]/[release] stop a pass).
            val scaled = try {
                if (!ensurePrefetchSession(pass)) continue
                val raw = prefetchEngine.extract(pass.uri, bucket)?.frame ?: continue
                withContext(dispatcher) { (scale ?: ::scaleBitmap)(raw) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "prefetch work failed: ${t.javaClass.simpleName}")
                continue
            }
            // Cache-only publish: validity check + cache write in ONE
            // critical section, under the SAME degraded boundary — a fault
            // in the publish (e.g. a hostile byteCount or a failing cache
            // put) must not escape the launch and cancel the whole session.
            // Uses ONLY facade session identity (released / token / captured
            // generation) — deliberately NOT the prefetch engine's request
            // generation, whose lifecycle is private to prefetch work.
            try {
                synchronized(publishLock) {
                    if (releasedFlag) return
                    if (sessionToken != pass.token) return
                    val generation = engine.epoch()
                    if (generation < 0 || generation != pass.generation) return
                    if (scaled.byteCount <= CACHE_MAX_SIZE_BYTES) {
                        frameCache.put(cacheKeyFor(pass.uri, pass.generation, bucket), scaled)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "prefetch publish failed: ${t.javaClass.simpleName}")
            }
        }
        // NOTE: registration cleanup has a SINGLE owner — the launch's
        // [Job.invokeOnCompletion] handler, which is pass-identity-scoped
        // and also runs when the job is cancelled before its body ever runs.
        // The pass body deliberately does NOT touch the registration.
    }

    /**
     * Prefetch in-flight bookkeeping observable: count of neighbor buckets
     * registered by the current pass (0 when no pass is in flight).
     */
    internal fun inFlightPrefetchCountForTest(): Int =
        synchronized(publishLock) { inFlightPrefetchBuckets.size }

    /** Test seam: the prefetch engine's shutdown terminal state. */
    internal fun prefetchShutdownForTest(): Deferred<Unit> = prefetchEngine.shutdown

    /** Test seam: the UI engine's shutdown terminal state. */
    internal fun uiShutdownForTest(): Deferred<Unit> = engine.shutdown

    /**
     * Test seam: whether a facade session is currently live (identity
     * installed). engine.extract works without a session (ephemeral), so
     * liveness is not observable from the outside — linearization tests need
     * the facade's own identity state.
     */
    internal fun hasLiveSessionForTest(): Boolean =
        synchronized(publishLock) { sessionJob?.isActive == true }

    /**
     * Async-close completion port: completes once cleanup has fully finished
     * after [release] — across BOTH engines (UI and prefetch) — exceptionally
     * when any native close failed. Never starts release by itself.
     *
     * Deliberately NOT on the injected [dispatcher]: the injected dispatcher
     * may be frozen or single-threaded-parked (tests, instrumentation); this
     * body is a pure two-await join, so it needs an always-live dispatcher —
     * otherwise an eager construction-time dispatch would never run and
     * [shutdownResult] would never settle.
     */
    private val combinedShutdown: Deferred<Unit> = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ).async {
        // BOTH engines must reach a terminal state before this Deferred
        // completes: each await gets its own boundary, so the first failure
        // can never skip the second close. A natively-stuck close keeps this
        // pending (correct — cleanup is NOT finished).
        //
        // The primary/suppressed pairing is assembled from the ORIGINAL
        // terminal causes via getCompletionExceptionOrNull(): await() hands
        // back a stack-recovery COPY whose suppression is disabled, so
        // attaching on the copy would be silently dropped. The originals
        // accept suppression (mockk/engine-raised, JVM-default constructors).
        val mainDeferred = engine.shutdown
        val prefetchDeferred = prefetchEngine.shutdown
        val mainFailed = runCatching { mainDeferred.await() }.isFailure
        val prefetchFailed = runCatching { prefetchDeferred.await() }.isFailure
        val mainCause = mainDeferred.getCompletionExceptionOrNull()
        val prefetchCause = prefetchDeferred.getCompletionExceptionOrNull()
        if (mainCause != null && prefetchCause != null && prefetchCause !== mainCause) {
            mainCause.addSuppressed(prefetchCause)
        }
        when {
            mainCause != null -> throw mainCause
            prefetchCause != null -> throw prefetchCause
            else -> {
                check(!mainFailed && !prefetchFailed) { "shutdown await failed without a cause" }
            }
        }
    }

    internal val shutdownResult: Deferred<Unit> get() = combinedShutdown

    /**
     * Begin a preview session; suspends until the native session is actually
     * open. Superseded/Closed/NoCapacity outcomes are logged degradations.
     * CancellationException — caller cancellation or a native cancel signal —
     * propagates to the caller FIRST; other open failures degrade to a logged
     * no-op so a failed preview session never crashes the UI.
     */
    suspend fun beginSession(uri: Uri) {
        // Fresh per-session umbrella: every prefetch pass of THIS session is
        // a child of it; endSession cancels it (and only it).
        val freshSessionJob = Job(scope.coroutineContext[Job])
        val staleJob: Job?
        // LINEARIZED CONTROL COMMIT: the identity swap (job + token + intent)
        // and the engine Open submission share ONE critical section. The
        // UNDISPATCHED child runs engine.begin until its first suspension
        // (the native-open await) on THIS thread while the lock is held, so
        // the Open command is enqueued in the very same critical section that
        // makes the session live. A concurrent endSession — which commits its
        // retirement under the same lock — is therefore ordered either
        // entirely before this section (its end never sees this session) or
        // entirely after (this session survives it); the previous
        // commit-after-unlock split allowed an end to land between
        // "facade session live" and "engine Open submitted" and retire a
        // session the facade considers current. The open RESULT is awaited
        // outside the lock, and the child's post-suspension continuation is
        // pinned to Dispatchers.Default: the injected dispatcher may be a
        // manually stepped queue that nothing drains until after this call
        // returns, which would deadlock the begin handshake. Cancellation
        // propagates first.
        val submit: Deferred<SeekPreviewEngine.InitResult>
        synchronized(publishLock) {
            sessionToken++
            staleJob = sessionJob
            sessionJob = freshSessionJob
            registerIntent() // reentrant on publishLock
            submit = scope.async(freshSessionJob + Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                engine.begin(uri.toString())
            }
        }
        staleJob?.cancel() // in-flight old-session passes die at their barrier
        try {
            val result = submit.await()
            if (result !== SeekPreviewEngine.InitResult.Opened) {
                Log.w(TAG, "session init result=$result")
            }
        } catch (ce: CancellationException) {
            // CALLER-CANCELLATION OWNERSHIP: the Open child belongs to
            // freshSessionJob, NOT to this caller — rethrown cancellation
            // alone would leave the child (and any already-installed native
            // session) alive. Reclaim it:
            //  1. cancel the umbrella job — kills a still-parked Open (the
            //     engine cleans a cancelled caller's slot) and any prefetch
            //     pass already spawned under this session;
            //  2. retire the session ONLY if this begin still owns the
            //     facade identity, with the retirement committed in the
            //     SAME critical section as the check: engine.end() is a
            //     global command that is not bound to THIS begin's
            //     identity, so a commit after the unlock would let a newer
            //     begin install between the check and the commit and be
            //     retired by a session this caller no longer owns. If a
            //     newer begin has already taken over, ITS session must
            //     never be retired by THIS caller's cancellation.
            // This covers the "child already Opened, outer await resumed
            // later" window too: submit.cancel() cannot undo a completed
            // install, but the ownership-checked engine.end() retires
            // whatever this session installed.
            freshSessionJob.cancel()
            synchronized(publishLock) {
                if (sessionJob === freshSessionJob) {
                    sessionToken++
                    sessionJob = null
                    prefetchSessionToken = -1L
                    prefetchSessionGen = -1L
                    registerIntent() // reentrant on publishLock
                    // The ownership check and the main retirement commit in
                    // the SAME critical section: engine.end() is a global
                    // command (bumps the epoch, retires whatever the engine
                    // currently holds) and is not bound to THIS begin's
                    // identity. Committed after the unlock, a newer begin
                    // could slide its whole install between the check and the
                    // commit and be retired by a session this caller no
                    // longer owns.
                    engine.end()
                    if (!releasedFlag && prefetchSessionToken == -1L) prefetchEngine.end()
                }
            }
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "session init failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * End the current session. Returns without waiting for native close: the
     * engine's fixed per-generation closeSignal and [shutdownResult] carry
     * cleanup failures, so the caller (a UI `finally`) is never blocked on
     * an uninterruptible native release.
     */
    suspend fun endSession() {
        // Linearized teardown, phase 1: the facade identity (token + job +
        // intent) and the MAIN engine retirement commit in ONE critical
        // section. A concurrent beginSession takes the same lock, so it is
        // ordered either entirely before (its Open survives this end) or
        // entirely after (this end never touches it) — the commit-after-
        // unlock split this replaces allowed a paused end to retire a NEWER
        // session, or a registered-but-uncommitted begin to complete behind
        // an end and orphan an engine session the facade no longer owns.
        var stale: Job? = null
        synchronized(publishLock) {
            sessionToken++ // late old-session prefetch results die at their publish barrier
            stale = sessionJob
            sessionJob = null // admission sees no live session from here on
            prefetchSessionToken = -1L // next session re-begins the prefetch engine
            prefetchSessionGen = -1L // paired with the token reset
            registerIntent() // reentrant on publishLock
            engine.end()
        }
        // Phase 2, AFTER the parked session work is dead: stale.cancel() must
        // precede the prefetch retirement. The prefetch engine's end()
        // completes a still-alive in-flight extract's reply, and a live
        // caller immediately re-registers a follow-up extract whose Open can
        // REPLACE the queued CloseSession (single control slot) — losing the
        // retirement entirely. With the caller cancelled first there is no
        // re-submitter, and the retirement close runs in its lane turn.
        //
        // Phase 2 retires only an UNOWNED prefetch session: a claim
        // (`prefetchSessionToken != -1L`) means a NEWER session has taken or
        // is taking the prefetch engine (its pass installed after our phase
        // 1 reaped the identity), and our retirement must not touch it. The
        // token/gen guards in [ensurePrefetchSession] make any install that
        // raced this decision either validated against the live session
        // (kept) or self-retired by its own pass (stale) — this end never
        // needs to guess. A racing release() is covered by its own
        // prefetchEngine.release().
        stale?.cancel()
        synchronized(publishLock) {
            if (!releasedFlag && prefetchSessionToken == -1L) prefetchEngine.end()
        }
    }

    /** Non-blocking, idempotent. Immediately invalidates all reads/results. */
    fun release() {
        synchronized(publishLock) {
            releasedFlag = true
            intentTicket++
            sessionJob = null
            prefetchSessionToken = -1L
            prefetchSessionGen = -1L
        }
        scope.cancel() // cancels sessionJob and every pass with it
        engine.release()
        prefetchEngine.release()
    }

    /**
     * Peek the cache for the CURRENT target. A hit still registers the target
     * identity (bumping [intentTicket]), so an older in-flight extraction is
     * discarded at its publish barrier even though this call never touches
     * engine state. Identity is verified by construction: the key binds uri,
     * media generation, position bucket and output size.
     */
    fun getCachedFrame(uri: String, positionMs: Long): Bitmap? {
        synchronized(publishLock) {
            if (releasedFlag) return null
            intentTicket++ // target selection — register latest intent
            val generation = engine.epoch()
            if (generation < 0) return null
            return frameCache.get(cacheKeyFor(uri, generation, positionMs))
        }
    }

    /**
     * Decode a frame off-thread; the engine owns all native lifetime.
     * CancellationException propagates to the caller; any other failure
     * fast-fails as null (cleanup failures remain observable via
     * [shutdownResult]). A result whose intent ticket or engine generation
     * was invalidated while in flight is discarded — never returned, never
     * cached. The validity check and the cache write form one critical
     * section: the publish linearization point.
     */
    suspend fun extractFrameAtPosition(uri: String, positionMs: Long): Bitmap? {
        val myTicket: Long
        synchronized(publishLock) {
            if (releasedFlag) return null
            intentTicket++
            myTicket = intentTicket
        }
        val extracted = try {
            engine.extract(uri, positionMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "frame extraction failed: ${t.javaClass.simpleName}")
            return null
        } ?: return null
        val raw = extracted.frame ?: return null
        val gen = extracted.gen

        // CPU-side scale on the injected dispatcher — never native work.
        // Goes through the same injectable hook as prefetch so tests can
        // park the UI at exactly the scale step (never at native decode).
        val scaled = withContext(dispatcher) { (scale ?: ::scaleBitmap)(raw) }

        // Publish barrier: validity check + cache write in ONE critical
        // section. Older-session/older-target results die here even if the
        // engine still considered them publishable.
        synchronized(publishLock) {
            if (releasedFlag) return null
            if (intentTicket != myTicket) return null
            val generation = engine.epoch()
            if (generation < 0 || !engine.isCurrent(gen)) return null
            if (scaled.byteCount <= CACHE_MAX_SIZE_BYTES) {
                frameCache.put(cacheKeyFor(uri, generation, positionMs), scaled)
            }
        }
        return scaled
    }

    /**
     * Explicit UI release path for a preview bitmap: drops the cache
     * reference so GC can reclaim the frame once every holder lets go.
     * Idempotent and null-safe. NEVER recycles — the UI may still be
     * composing with this bitmap.
     */
    fun releaseFrame(frame: Bitmap?) {
        if (frame == null) return
        frameCache.snapshot().filterValues { it === frame }.keys.forEach(frameCache::remove)
    }

    private fun roundPosition(positionMs: Long): Long =
        (positionMs / POSITION_ROUNDING_MS) * POSITION_ROUNDING_MS

    internal fun cacheSizeForTest(): Int = frameCache.size()

    /**
     * Downscale toward preview size, capped by width, height and total pixels.
     * Never mutates the source bitmap.
     */
    private fun scaleBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        var scale = minOf(MAX_WIDTH.toFloat() / width, MAX_HEIGHT.toFloat() / height, 1f)
        if (scale <= 0f) scale = 1f

        var targetW = (width * scale).toInt().coerceAtLeast(1)
        var targetH = (height * scale).toInt().coerceAtLeast(1)
        val pixels = targetW.toLong() * targetH.toLong()
        if (pixels > MAX_PIXELS) {
            val reduce = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
            targetW = (targetW * reduce).toInt().coerceAtLeast(1)
            targetH = (targetH * reduce).toInt().coerceAtLeast(1)
        }
        if (targetW == width && targetH == height) return source
        return source.scale(targetW, targetH)
    }
}
