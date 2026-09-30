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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO, // scale-only; never carries native
    lanePool: PreviewLanePool = SHARED_POOL,
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

    /**
     * Async-close completion port: completes once engine cleanup has fully
     * finished after [release] — exceptionally when any native close failed.
     * Never starts release by itself.
     */
    internal val shutdownResult: Deferred<Unit> get() = engine.shutdown

    /**
     * Begin a preview session; suspends until the native session is actually
     * open. Superseded/Closed/NoCapacity outcomes are logged degradations.
     * CancellationException — caller cancellation or a native cancel signal —
     * propagates to the caller FIRST; other open failures degrade to a logged
     * no-op so a failed preview session never crashes the UI.
     */
    suspend fun beginSession(uri: Uri) {
        registerIntent()
        try {
            val result = engine.begin(uri.toString())
            if (result !== SeekPreviewEngine.InitResult.Opened) {
                Log.w(TAG, "session init result=$result")
            }
        } catch (ce: CancellationException) {
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
        registerIntent()
        engine.end()
    }

    /** Non-blocking, idempotent. Immediately invalidates all reads/results. */
    fun release() {
        synchronized(publishLock) {
            releasedFlag = true
            intentTicket++
        }
        engine.release()
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
        val scaled = withContext(dispatcher) { scaleBitmap(raw) }

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
