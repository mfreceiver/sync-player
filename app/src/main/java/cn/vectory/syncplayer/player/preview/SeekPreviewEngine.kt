package cn.vectory.syncplayer.player.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Single-owner engine for a preview decoder resource.
 *
 * Design (bounded protocol):
 *
 * - All mutable state lives behind a single intrinsic [lock]: the latest
 *   control command (at most one — new commands overwrite), one pending
 *   extract request, one in-flight extract request, and at most one active
 *   open command. No unbounded queues exist; a control storm while native is
 *   parked can only replace slots, never accumulate.
 * - Claim/registration, invalidation and the publish decision all happen
 *   inside that same lock. Native calls (factory/open/decode/close) only ever
 *   run on the leased lane, outside the lock, serialised by the lane itself —
 *   an old decoder is always closed before a new one is opened.
 * - Resource lifecycle is explicit: a [Res] is created before its decoder's
 *   first native call and transitions OPENING → INSTALLED → CLOSING → CLOSED
 *   with `close()` invoked exactly once, never retried. A null session slot
 *   means "no session", not "close finished" — close completion is observable
 *   through the generation's shared close signal.
 * - `end()`/`release()` are non-blocking signals. `end()` returns the close
 *   signal attached to the resource generation it targets (constant state —
 *   repeated calls share one Deferred). A close failure — including a
 *   CancellationException thrown by native `close()` — is delivered
 *   exceptionally to that generation's waiters and seals the engine; the
 *   stranded lane is never returned to the pool because its native release
 *   state is unknown.
 * - `begin()` suspends until its open is resolved (Opened/Superseded/
 *   Closed/NoCapacity) or throws the real factory/open failure. Queued and
 *   active init waiters are completed immediately when superseded — never
 *   left until the lane next runs. A cancelled begin-awaiter marks its own
 *   token un-installable without touching newer commands; a cancellation
 *   arriving after its own session was installed retires ONLY that resource
 *   (directional retirement) — queued newer controls, pending and in-flight
 *   requests always survive untouched.
 * - Two monotonic counters: `sessionEpoch` (session identity) and
 *   `requestGen` (publish identity). A decode result may only publish while
 *   its stamped generation is current.
 * - Failure delivery is FAST-FAIL: a native open/decode failure reaches its
 *   caller immediately, never waiting for a possibly-uninterruptible cleanup
 *   close. Cleanup-close failures are observable through the resource's
 *   fixed closeSignal and through `shutdown` (exceptionally) — they are
 *   never retro-attached to an already-delivered Throwable. The single
 *   deliberate exception: a decode that succeeded only yields its frame
 *   after its ephemeral close succeeded (close failure → no frame).
 * - A native call that never returns strands exactly one lane thread and a
 *   bounded number of decoders; it never blocks signal callers. Pool
 *   exhaustion degrades begin/extract immediately (NoCapacity / null).
 * - A rejected lane submit (executor refusing the worker) is atomically
 *   rolled back, split by whether native resources are still held: with
 *   NONE held, the affected signals receive the real rejection
 *   exceptionally (open init / end-attached close signal / pending+in-flight
 *   replies / shutdown), idempotent close/release commands stay queued for
 *   the next signal, and the engine returns to a retryable state — a
 *   rejection can never leave `workerScheduled` dangling or hang later
 *   extracts. With resources HELD (installed session / active open /
 *   mid-close resource), the engine seals TERMINALLY: all affected signals
 *   (held resources' fixed close signals included) terminate exceptionally,
 *   begin answers Closed and extract null from then on, and the lane is
 *   NEVER returned to the pool — its lease is consumed for the engine's
 *   lifetime so the pool bound stays honest. The engine performs NO
 *   guaranteed active native release here: the held decoders simply become
 *   unreachable when the facade drops them, and actual native release may
 *   wait until the objects are finalized or the process exits. The bound
 *   that limits NEW capacity is the pool: every rejection-with-resources
 *   strands exactly one lane lease forever, so `maxLanes` bounds how many
 *   decoders can be in that state at once.
 */
internal class SeekPreviewEngine<F>(
    private val decoderFactory: (String) -> PreviewDecoder<F>,
    private val lanePool: PreviewLanePool,
    private val logger: (String) -> Unit = {},
) {

    companion object {
        const val TAG = "SeekPreviewEngine"

        /** Sentinel delivered exceptionally to close-waiters of an already-closed engine. */
        private val CLOSED_SENTINEL = IllegalStateException("engine closed")
    }

    /** Result of [begin] — observable session-initialisation outcome. */
    sealed interface InitResult {
        /** Session decoder created and opened; session is installed. */
        object Opened : InitResult

        /** Superseded by a newer control (begin/end/release) before or during open. */
        object Superseded : InitResult

        /** Engine is released or sealed — the session was not opened. */
        object Closed : InitResult

        /** No lane available in the shared pool; nothing was created or queued. */
        object NoCapacity : InitResult
    }

    /** Published decode result plus the request generation it was produced under. */
    class Extracted<F>(val frame: F?, val gen: Long)

    /** Explicit resource lifecycle — OPENING → INSTALLED → CLOSING → CLOSED. */
    private enum class ResState { OPENING, INSTALLED, CLOSING, CLOSED }

    /**
     * A created decoder plus its lifecycle. `close()` runs exactly once
     * (claimed by CLOSING under lock), the recorded outcome lives in
     * [closeFailure], and [closeSignal] completes when this resource reaches
     * CLOSED — the "A's close finished" observation point.
     */
    private class Res<F>(val decoder: PreviewDecoder<F>) {
        var state: ResState = ResState.OPENING

        /**
         * Pre-built at construction: every end()/release()/drain of this
         * generation uses THIS deferred (constant state, shared by repeated
         * callers). A CLOSED resource hands back its already-completed
         * signal, so no fresh never-completing signal can ever be created
         * for an already-closed generation.
         */
        val closeSignal: CompletableDeferred<Unit> = CompletableDeferred()

        /** Close-waiters attached to the producing open before the Res existed. */
        var adoptedSignal: CompletableDeferred<Unit>? = null

        var closeFailure: Throwable? = null
        var closeReported = false

        /**
         * Directional retirement: set when the open command that OWNS this
         * resource is cancelled after install. Only this resource is
         * affected — the lane closes it after its current native call; the
         * global control/pending slots and generations are untouched, so
         * newer commands and requests always survive.
         */
        var retireRequested: Boolean = false
    }

    /** Control commands — at most one is retained (newest wins). */
    private sealed interface Cmd<F> {
        class Open<F>(
            val key: String,
            var epochAtSubmit: Long,
            val init: CompletableDeferred<InitResult>,
        ) : Cmd<F> {
            /** Close signal attached by end() while this open is queued/active. */
            var closeSignal: CompletableDeferred<Unit>? = null

            /** Set under lock when a newer intent/cancel must prevent install. */
            var installForbidden: Boolean = false

            /** The resource this open produced — registered until the open finally resolves. */
            var res: Res<F>? = null

            /** Real factory/open() failure — delivered exceptionally to [init]. */
            var openFailure: Throwable? = null

            /** Set under lock when the produced resource is installed as the session. */
            var installed: Boolean = false

            /** Set under lock when install was refused after open() returned. */
            var installMissed: Boolean = false
        }

        class CloseSession<F> : Cmd<F>
        class Release<F> : Cmd<F>
        class Decode<F>(val req: Req<F>) : Cmd<F>
    }

    /** A submitted extract request (pending or claimed in-flight). */
    private class Req<F>(
        val key: String,
        val positionMs: Long,
        val reply: CompletableDeferred<Extracted<F>?>,
    ) {
        /** Request generation stamped at submission (under lock). */
        var gen: Long = 0

        /** Cleared under lock when a newer request/control makes publishing stale. */
        var publishable: Boolean = true
    }

    private val lock = Any()

    // ---- mutable state (all guarded by [lock]) ----
    private var control: Cmd<F>? = null        // latest control only
    private var pending: Req<F>? = null        // at most one
    private var inFlight: Req<F>? = null       // at most one, claimed by worker
    private var activeOpen: Cmd.Open<F>? = null // queued-popped open, retained until its finally
    private var sessionEpoch = 0L              // session identity
    private var requestGen = 0L                // publish identity
    private var released = false
    private var sealedOff = false
    private var workerScheduled = false
    private var lane: PreviewLane? = null
    private var sessionDecoder: Res<F>? = null // INSTALLED resource (null ⇏ "close finished")
    private var sessionKey: String? = null

    /** The open command that installed [sessionDecoder] — cancel-safety token. */
    private var sessionToken: Cmd.Open<F>? = null
    /**
     * Resource mid-close (CLOSING), tracked so end() can attach to a close
     * that is already claimed but not finished.
     *
     * DEBT — single-slot register (by design, with two preconditions):
     * 1. Validity premise: the SINGLE-LANE serial protocol guarantees at most
     *    one native close executing at a time, so one slot can never lose a
     *    concurrent claim. Note [claimCloseLocked] provides NO cross-resource
     *    global mutex — it only rejects re-claims of the PASSED resource's
     *    own CLOSING/CLOSED state; the single slot is safe because lane
     *    serialization makes overlapping closes impossible, and the slot is
     *    cleared by [runClose] before the next close can start.
     * 2. Exception re-entry boundary: if that close fails, [seal] completes
     *    the slot's signal exceptionally, nulls the register and flips the
     *    engine terminal, so no later end() can observe a stale mid-close
     *    resource — post-seal ends take the CLOSED_SENTINEL branch instead.
     * If the engine ever grows concurrent closes (multiple lanes), do NOT
     * widen this register: the per-generation [Res.closeSignal] already
     * carries the observation point and this slot should be deleted.
     */
    private var closingRes: Res<F>? = null     // resource mid-close (CLOSING), tracked for end()
    private val owned = mutableSetOf<Res<F>>()
    private val shutdownSignal = CompletableDeferred<Unit>()

    /**
     * Completes when release/seal cleanup has fully finished; completes
     * exceptionally when any `close()` failed or the engine sealed. A second
     * release() returns this same Deferred — it is never completed by the
     * call site itself, only by the lane-side drain. If a native call is
     * parked forever, this legitimately never completes.
     */
    val shutdown: Deferred<Unit> get() = shutdownSignal

    /** Session identity for facade cache keying/invalidation; negative after release. */
    fun epoch(): Long = synchronized(lock) { if (released || sealedOff) -1L else sessionEpoch }

    /** Publish/barrier check: is `g` still the current request generation? */
    fun isCurrent(g: Long): Boolean =
        synchronized(lock) { !released && !sealedOff && g == requestGen }

    // ---------- public signals (non-blocking) ----------

    /**
     * Request a session for [key]. Suspends until the open resolves
     * (Opened/Superseded/Closed/NoCapacity) or throws the real exception from
     * factory/open — including CancellationException from native code. A
     * caller whose await is cancelled only disables its own token; if its
     * session was already installed, exactly that resource is retired —
     * newer commands and requests are never affected.
     */
    suspend fun begin(key: String): InitResult {
        val init = CompletableDeferred<InitResult>()
        val cmd = Cmd.Open<F>(key, 0L, init)
        val fire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (released || sealedOff) {
                init.complete(InitResult.Closed)
            } else {
                sessionEpoch++
                requestGen++
                cmd.epochAtSubmit = sessionEpoch
                collectSupersededLocked(fire)
                control = cmd
                if (!ensureWorkerLocked(fire)) {
                    // No capacity: fail fast, keep nothing queued.
                    control = null
                    init.complete(InitResult.NoCapacity)
                }
            }
        }
        fireAll(fire)
        return try {
            init.await()
        } catch (ce: CancellationException) {
            // Caller gave up: queued token → drop it (its close signal
            // completes since the resource never existed); active token →
            // forbid install so the native result is orphan-closed. Newer
            // commands are never touched. A caller cancelled AFTER its own
            // session was installed (but before it resumed) would otherwise
            // leak that session — the installed token retires exactly that
            // resource, never touching a newer session B.
            val fire2 = mutableListOf<() -> Unit>()
            synchronized(lock) {
                if (control === cmd) {
                    fire2 += { cmd.closeSignal?.complete(Unit) }
                    control = null
                }
                if (activeOpen === cmd) {
                    cmd.installForbidden = true
                    fire2 += { cmd.init.complete(InitResult.Superseded) }
                }
                if (!released && !sealedOff &&
                    sessionDecoder != null && sessionToken === cmd
                ) {
                    // DIRECTIONAL RETIREMENT: this caller's open owned the
                    // installed session. Mark THAT resource only — the lane
                    // closes it after its current native call returns. The
                    // global slots and generations are NOT touched: a newer
                    // begin(B)/extract submitted after this cancel keeps
                    // running untouched (if B is queued, B's execution closes
                    // this resource as its predecessor). Cancelling ONE old
                    // caller is not a new global intent — it must never
                    // supersede, bump or drop newer work.
                    sessionDecoder!!.retireRequested = true
                    if (control == null) ensureWorkerLocked(fire2)
                }
            }
            fireAll(fire2)
            throw ce
        }
    }

    /**
     * Signal end-of-session. Returns the close signal for the resource
     * generation this call targets — completed when that resource's close
     * has actually run (or immediately when there is nothing to close), or
     * exceptionally when the close itself failed. Repeated calls share one
     * Deferred per generation: constant state, no waiter list.
     */
    fun end(): Deferred<Unit> {
        var signal: CompletableDeferred<Unit>? = null
        val fire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (released || sealedOff) {
                val s = CompletableDeferred<Unit>()
                s.completeExceptionally(CLOSED_SENTINEL)
                signal = s
            } else {
                sessionEpoch++
                requestGen++
                // Attach to the generation this end targets — get-or-create so
                // repeated ends of the same generation SHARE one Deferred
                // (constant state, no waiter list).
                val target: Any? =
                    sessionDecoder ?: closingRes ?: activeOpen?.res ?: activeOpen ?: control
                when (target) {
                    is Res<*> -> {
                        // Fixed pre-built signal: shared by every end() of
                        // this generation; already completed if the resource
                        // reached CLOSED — never a fresh dangling deferred.
                        @Suppress("UNCHECKED_CAST")
                        signal = (target as Res<F>).closeSignal
                    }
                    is Cmd.Open<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val c = target as Cmd.Open<F>
                        val s = c.closeSignal ?: CompletableDeferred<Unit>().also {
                            c.closeSignal = it
                        }
                        signal = s
                    }
                    else -> {
                        val s = CompletableDeferred<Unit>()
                        s.complete(Unit) // nothing to close
                        signal = s
                    }
                }
                collectSupersededLocked(fire)
                control = Cmd.CloseSession()
                ensureWorkerLocked(fire)
            }
        }
        fireAll(fire)
        return signal!!
    }

    /**
     * Submit a frame request. At most one pending + one in-flight are kept; a
     * newer request immediately invalidates both — the old in-flight becomes
     * unpublishable at once even though its decode may still be parked.
     *
     * If the caller's await is cancelled, the caller's own slot is cleaned
     * under the same lock (pending removed / in-flight marked unpublishable).
     */
    suspend fun extract(key: String, positionMs: Long): Extracted<F>? {
        val req = Req<F>(key, positionMs, CompletableDeferred())
        val fire = mutableListOf<() -> Unit>()
        var submit = true
        synchronized(lock) {
            if (released || sealedOff) {
                submit = false
            } else {
                requestGen++
                req.gen = requestGen
                // A newer submitted target invalidates older work right now.
                inFlight?.let { it.publishable = false; fire += { it.reply.complete(null) } }
                pending?.let { fire += { it.reply.complete(null) } }
                pending = req
                if (!ensureWorkerLocked(fire)) {
                    pending = null
                    submit = false
                }
            }
        }
        fireAll(fire)
        if (!submit) return null
        return try {
            req.reply.await()
        } catch (ce: CancellationException) {
            // Awaiter cancelled: unclaim our own slot under the same lock.
            val cleanup = mutableListOf<() -> Unit>()
            synchronized(lock) {
                if (pending === req) pending = null
                if (inFlight === req) req.publishable = false
                cleanup += { req.reply.complete(null) }
            }
            fireAll(cleanup)
            throw ce
        }
    }

    /**
     * Non-blocking, idempotent shutdown signal. Returns the single
     * [shutdown] Deferred; it completes only after the lane-side drain has
     * finished closing everything (exceptionally on close failure).
     */
    fun release(): Deferred<Unit> {
        val fire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (!released) {
                released = true
                sessionEpoch++
                requestGen++
                collectSupersededLocked(fire)
                control = Cmd.Release()
                if (!ensureWorkerLocked(fire)) {
                    // No lane ever acquired → nothing to drain.
                    control = null
                    fire += { shutdownSignal.complete(Unit) }
                }
            }
        }
        fireAll(fire)
        return shutdownSignal
    }

    // ---------- locked-state helpers ----------

    /**
     * Invalidates queued/active init waiters (queued → Superseded
     * immediately; active → installForbidden + Superseded) and both request
     * slots. Must be called under lock; completion actions are appended to
     * [out] and fired AFTER unlocking so continuations never resume inside
     * the lock. Active resources are NOT closed here — the lane closes them
     * serially when their native call returns.
     */
    private fun collectSupersededLocked(out: MutableList<() -> Unit>) {
        (control as? Cmd.Open<F>)?.let { old ->
            out += {
                old.init.complete(InitResult.Superseded)
                old.closeSignal?.complete(Unit)
            }
        }
        control = null
        activeOpen?.let { active ->
            active.installForbidden = true
            out += { active.init.complete(InitResult.Superseded) }
        }
        pending?.let { out += { it.reply.complete(null) } }
        pending = null
        inFlight?.let { it.publishable = false; out += { it.reply.complete(null) } }
    }

    /**
     * Ensures a worker task is queued behind the current work. Returns false
     * when no lane can be acquired. Caller must already hold the lock;
     * submission is deferred to [out] so it runs after unlocking.
     *
     * A rejected `lane.submit` (saturated/dead executor) is never swallowed
     * and never left dangling: the catch atomically rolls the scheduling
     * attempt back via [rollbackSubmitRejection], which terminates every
     * registered waiter with the real rejection. The post-rollback state is
     * split by whether native resources are still held: with NONE held the
     * engine returns to a RETRYABLE state and its lease is relinquished, so
     * later signals re-acquire instead of hanging; with resources HELD the
     * engine seals TERMINALLY and keeps its lease forever (see
     * [rollbackSubmitRejectionTerminal]).
     */
    private fun ensureWorkerLocked(out: MutableList<() -> Unit>): Boolean {
        if (workerScheduled) return true
        if (lane == null) {
            lane = lanePool.tryAcquire() ?: return false
        }
        workerScheduled = true
        val l = lane!!
        out += {
            try {
                l.submit { drain() }
            } catch (t: Throwable) {
                val rb = mutableListOf<() -> Unit>()
                synchronized(lock) { rollbackSubmitRejection(control, l, t, rb) }
                fireAll(rb)
                try {
                    logger("$TAG lane submit rejected: ${t.javaClass.simpleName}")
                } catch (_: Throwable) {
                    // A broken logger must never throw into the signal caller
                    // and never mask the rejection already delivered above.
                }
            }
        }
        return true
    }

    private fun fireAll(actions: List<() -> Unit>) = actions.forEach { it() }

    // ---------- worker ----------

    /**
     * The single worker body for this engine: drains the locked state until
     * empty. At most one instance is queued/running per engine
     * (`workerScheduled` and state mutate under the same lock, so wakeups can
     * never be lost and submissions are bounded by one per engine).
     */
    private fun drain() {
        while (true) {
            var work: Cmd<F>? = null
            var done = false
            var skip = false
            val fire = mutableListOf<() -> Unit>()
            synchronized(lock) {
                // Priority: control → retired-session close → pending decode.
                // A continuous stream of pending requests must never starve a
                // retired resource's close; after the close, a pending request
                // whose session is gone simply takes the ephemeral path.
                val cmd: Cmd<F>? = control
                    ?: retiredSessionWorkLocked()
                    ?: pending?.let { Cmd.Decode(it) }
                when {
                    cmd == null -> {
                        workerScheduled = false
                        done = true
                    }
                    cmd is Cmd.Decode<F> && released -> {
                        // Decodes after release are skipped but still completed.
                        pending = null
                        skip = true
                        fire += { cmd.req.reply.complete(null) }
                    }
                    else -> {
                        if (cmd is Cmd.Decode<F>) {
                            pending = null
                            inFlight = cmd.req // claim + register atomically under lock
                        } else {
                            control = null
                            if (cmd is Cmd.Open<F>) activeOpen = cmd
                        }
                        work = cmd
                    }
                }
            }
            fireAll(fire)
            if (done) return
            if (skip) continue
            try {
                executeWork(work!!)
            } catch (t: Throwable) {
                seal(t)
                return
            }
        }
    }

    /**
     * Retirement work source: when no control and no pending request exist,
     * a resource whose owning open was cancelled (after install) still needs
     * its directional close — synthesized here as a plain CloseSession so the
     * lane performs it serially, in its turn, without any global mutation.
     */
    private fun retiredSessionWorkLocked(): Cmd<F>? =
        if (sessionDecoder?.retireRequested == true) Cmd.CloseSession<F>() else null

    /**
     * Atomic rollback after a rejected `lane.submit`. Runs entirely under
     * [lock] so no signal can observe a half-rolled-back state; completions
     * are queued into [fire] and delivered by the caller (after the lock is
     * released) so deferred continuations never run inside the critical
     * section.
     *
     * Strategy split by whether the engine still holds native resources
     * ([owned] non-empty / [activeOpen] present / [closingRes] present):
     *
     * - **No resources** → clean rollback ([rollbackSubmitRejectionRetryable]):
     *   the registered waiters are terminated with the real rejection
     *   [failure], idempotent close/release commands stay queued for the
     *   next signal, the lane lease is relinquished (lane=null,
     *   workerScheduled=false, giveBack) and the engine stays retryable.
     * - **Resources held** → terminal isolation
     *   ([rollbackSubmitRejectionTerminal]): there is no worker to run the
     *   native closes, so the engine must NOT pretend cleanup happened. It
     *   seals terminally, terminates every affected signal exceptionally
     *   (held resources' fixed close signals, pending/in-flight replies,
     *   [shutdownSignal]), and — critically — NEVER returns the lane to the
     *   pool: returning it would overstate remaining capacity while its
     *   native resources may never be deterministically released.
     */
    private fun rollbackSubmitRejection(
        cmd: Cmd<F>?,
        l: PreviewLane,
        failure: Throwable,
        fire: MutableList<() -> Unit>,
    ) {
        val holdsNativeResources =
            owned.isNotEmpty() || activeOpen != null || closingRes != null
        if (holdsNativeResources) {
            rollbackSubmitRejectionTerminal(cmd, l, failure, fire)
        } else {
            rollbackSubmitRejectionRetryable(cmd, l, failure, fire)
        }
    }

    /**
     * Clean rollback for a rejection while the engine holds NO native
     * resources. Everything registered for this worker attempt is terminated
     * with the real rejection [failure]:
     * - [Cmd.Open]: its init and any end-attached close signal complete
     *   exceptionally and the dead command leaves the control slot. The open
     *   is provably resource-less here — `res` is only assigned while an open
     *   executes as [activeOpen], and a rejected worker never ran — so there
     *   is nothing to own back. Re-queueing is forbidden: the waiters are
     *   already resolved and `sessionEpoch` was already bumped for it.
     * - [Cmd.CloseSession] / [Cmd.Release]: idempotent — re-queued so a later
     *   signal retries the drain.
     * - the 1-slot pending decode and any registered in-flight decode (the
     *   register-and-claim pairing in drain() makes a registered inFlight
     *   without a running worker impossible in the rejection path, but the
     *   rollback terminates it defensively if one is present).
     * - [shutdownSignal], when release already happened.
     *
     * Finally the lane lease is relinquished (lane=null, workerScheduled=false,
     * giveBack) so the next signal re-acquires — the engine stays retryable.
     */
    private fun rollbackSubmitRejectionRetryable(
        cmd: Cmd<F>?,
        l: PreviewLane,
        failure: Throwable,
        fire: MutableList<() -> Unit>,
    ) {
        when (cmd) {
            is Cmd.Open<F> -> {
                cmd.closeSignal?.let { s -> fire += { s.completeExceptionally(failure) } }
                fire += { cmd.init.completeExceptionally(failure) }
                if (control === cmd) control = null
            }
            is Cmd.CloseSession<F>,
            is Cmd.Release<F>,
            -> {
                control = cmd // idempotent — re-queue so a later signal retries
            }
            else -> Unit
        }
        if (cmd === activeOpen) activeOpen = null
        inFlight?.let { req ->
            inFlight = null
            fire += { req.reply.completeExceptionally(failure) }
        }
        pending?.let { req ->
            pending = null
            fire += { req.reply.completeExceptionally(failure) }
        }
        if (released) {
            val sig = shutdownSignal
            fire += { sig.completeExceptionally(failure) }
        }
        workerScheduled = false
        lane = null
        l.giveBackToPool(lanePool)
    }

    /**
     * Terminal isolation for a rejection while the engine still holds native
     * resources (an installed session, an active open, or a mid-close
     * resource). There is no worker left to run the exactly-once native
     * closes, and closing them on the SIGNAL CALLER's thread would block an
     * arbitrary UI thread inside an uninterruptible native call — the exact
     * failure mode the lane model exists to prevent. Therefore:
     *
     * - every affected signal is terminated with the real rejection
     *   [failure]: each held resource's fixed close signal (end() waiters of
     *   these generations get the rejection instead of hanging, and repeat
     *   ends share the same terminated Deferred), the 1-slot pending /
     *   in-flight replies, any queued open's init, and [shutdownSignal]
     *   (release already flipped [released]);
     * - the engine migrates to a terminal state (`sealedOff` + `released`):
     *   later begin() answers Closed, extract() answers null, and
     *   end()/release() return already-terminated signals idempotently — no
     *   command is re-queued because no worker can ever be scheduled again;
     * - the lane reference is KEPT and never given back: with its native
     *   resources possibly never deterministically released, returning the
     *   lane would lend out a thread whose predecessor may still own live
     *   decoders and would fake extra pool capacity. The engine consumes its
     *   lease for the rest of its life (same rule as [seal]).
     *
     * RESIDUAL-NATIVE BOUNDARY (deliberate, documented): no guaranteed
     * active native release happens here — the held decoders may persist
     * until they become unreachable (and are finalized) or the process
     * exits. The bound that limits NEW capacity is the pool: every
     * rejection-with-resources strands exactly one lane lease forever, so
     * `maxLanes` bounds how many decoders can be in that state at once.
     */
    private fun rollbackSubmitRejectionTerminal(
        cmd: Cmd<F>?,
        l: PreviewLane,
        failure: Throwable,
        fire: MutableList<() -> Unit>,
    ) {
        synchronized(lock) {
            sealedOff = true
            released = true
            activeOpen?.let { a ->
                a.installForbidden = true
                fire += { a.init.completeExceptionally(failure) }
                a.closeSignal?.let { s -> fire += { s.completeExceptionally(failure) } }
            }
            if (cmd === activeOpen) activeOpen = null
            (control as? Cmd.Open<F>)?.let { c ->
                fire += { c.init.completeExceptionally(failure) }
                c.closeSignal?.let { s -> fire += { s.completeExceptionally(failure) } }
            }
            control = null // terminal — no worker can ever drain a re-queued command
            for (r in owned) {
                fire += { r.closeSignal.completeExceptionally(failure) }
                r.adoptedSignal?.let { s ->
                    r.adoptedSignal = null
                    fire += { s.completeExceptionally(failure) }
                }
            }
            closingRes?.let { r ->
                fire += { r.closeSignal.completeExceptionally(failure) }
                r.adoptedSignal?.let { s ->
                    r.adoptedSignal = null
                    fire += { s.completeExceptionally(failure) }
                }
                closingRes = null
            }
            pending?.let { req ->
                pending = null
                fire += { req.reply.completeExceptionally(failure) }
            }
            inFlight?.let { req ->
                inFlight = null
                fire += { req.reply.completeExceptionally(failure) }
            }
            val sig = shutdownSignal
            fire += { sig.completeExceptionally(failure) }
            // Deliberate: workerScheduled=false (no worker exists), but the
            // lane reference is KEPT and NEVER given back — see KDoc.
            workerScheduled = false
        }
    }

    private fun PreviewLane.giveBackToPool(pool: PreviewLanePool) {
        pool.giveBack(this)
    }

    private fun executeWork(cmd: Cmd<F>) {
        when (cmd) {
            is Cmd.Open<F> -> executeOpen(cmd)
            is Cmd.CloseSession<F> -> closeCurrent()
            is Cmd.Release<F> -> {
                // Try every owned resource exactly once; the FIRST failure
                // seals (unknown native release state) — later failures are
                // suppressed so cleanup still drains everything.
                var failure: Throwable? = null
                try {
                    closeCurrent()
                } catch (t: Throwable) {
                    failure = t
                }
                try {
                    drainOwned()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else failure!!.addSuppressed(t)
                }
                if (failure != null) {
                    seal(failure!!)
                    return
                }
                giveBackLane()
                shutdownSignal.complete(Unit)
            }
            is Cmd.Decode<F> -> executeDecode(cmd.req)
        }
    }

    /**
     * Old installed decoder closes FIRST (a close failure propagates as this
     * open's failure and seals the engine — it is never silently retried or
     * masked), then factory → register → open() → install-or-orphan-close.
     *
     * The finally covers EVERY exit (including the early returns below): the
     * orphan's close is claimed in the SAME lock that removes [activeOpen] —
     * there is no window in which an end() could see "no resource" while a
     * close is imminent. Init is delivered right after unlocking (fast-fail:
     * it never waits for the native close); the already-claimed orphan is
     * then closed serially on this lane via [runCloseOrSeal].
     */
    private fun executeOpen(open: Cmd.Open<F>) {
        var orphanClose: Res<F>? = null
        var claimedOrphan: Res<F>? = null
        try {
            // Close failure of the predecessor is this open's real failure —
            // recorded so init gets the true exception, then the engine seals.
            try {
                closeCurrent()
            } catch (t: Throwable) {
                synchronized(lock) { open.openFailure = t }
                throw t
            }
            val created = try {
                decoderFactory(open.key)
            } catch (t: Throwable) {
                // Factory failed BEFORE any resource existed: no native
                // state, nothing to close, no lane isolation — the engine
                // stays retryable. The ORIGINAL failure (including a
                // CancellationException thrown by native code) is recorded
                // for init; the finally delivers it. Never masked, never
                // swallowed, never sealed.
                synchronized(lock) { open.openFailure = t }
                return
            }
            val res = Res(created)
            var becameCurrent = false
            synchronized(lock) {
                owned += res // owned before any native call
                open.res = res
                // Close-waiters attached to the command before the resource
                // existed are adopted by the resource — they complete when
                // the resource actually closes, from wherever it closes.
                res.adoptedSignal = open.closeSignal
                open.closeSignal = null
                becameCurrent =
                    !released && !sealedOff && !open.installForbidden &&
                        sessionEpoch == open.epochAtSubmit
            }
            if (!becameCurrent) {
                orphanClose = res
                return // finally claims the orphan, resolves init, closes after
            }
            try {
                created.open()
            } catch (t: Throwable) {
                synchronized(lock) { open.openFailure = t }
                orphanClose = res
                return
            }
            synchronized(lock) {
                val stillCurrent =
                    !released && !sealedOff && !open.installForbidden &&
                        sessionEpoch == open.epochAtSubmit
                if (stillCurrent) {
                    res.state = ResState.INSTALLED
                    sessionDecoder = res
                    sessionKey = open.key
                    sessionToken = open
                    open.installed = true
                } else {
                    open.installMissed = true
                }
            }
            if (open.installMissed) orphanClose = res // orphan — same lane, serial
        } finally {
            val fire = mutableListOf<() -> Unit>()
            synchronized(lock) {
                if (activeOpen === open) activeOpen = null
                // Orphan claim must happen in THIS lock section: after it,
                // end() attaches to the orphan's fixed closeSignal instead of
                // reporting "nothing to close" while a close is imminent.
                if (orphanClose != null &&
                    claimCloseLocked(orphanClose!!)
                ) {
                    claimedOrphan = orphanClose
                }
                when {
                    open.openFailure != null ->
                        fire += { open.init.completeExceptionally(open.openFailure!!) }
                    open.installed -> fire += { open.init.complete(InitResult.Opened) }
                    else -> fire += { open.init.complete(InitResult.Superseded) }
                }
                // An open that never produced a resource completes its
                // attached close signal itself; resource-produced signals are
                // completed by the resource's own close.
                if (open.res == null) {
                    open.closeSignal?.let { s -> fire += { s.complete(Unit) } }
                }
            }
            // Deliver init FIRST (fast-fail), then run the claimed orphan's
            // native close serially on this lane.
            fireAll(fire)
            claimedOrphan?.let { runCloseOrSeal(it) }
        }
    }

    private fun executeDecode(req: Req<F>) {
        // Claim already happened under lock (inFlight = req) in drain().
        var session: Res<F>? = null
        synchronized(lock) {
            session = sessionDecoder?.takeIf { sessionKey == req.key }
        }
        var ephRes: Res<F>? = null
        val res: Res<F> = if (session != null) {
            session!!
        } else {
            val created = try {
                decoderFactory(req.key)
            } catch (t: Throwable) {
                // No resource existed — retryable, nothing to close, the
                // ORIGINAL failure reaches the caller first.
                completeRequestFailed(req, t)
                return
            }
            val r = Res(created)
            synchronized(lock) { owned += r }
            try {
                created.open()
            } catch (t: Throwable) {
                // FAST-FAIL: the open failure is delivered immediately; the
                // ephemeral close runs afterwards, serially on this lane. A
                // close failure seals the engine (observable via shutdown)
                // and is never retro-attached to the delivered Throwable.
                completeRequestFailed(req, t)
                closeOrSeal(r)
                return
            }
            synchronized(lock) { r.state = ResState.INSTALLED }
            ephRes = r
            r
        }

        var result: F? = null
        var decodeError: Throwable? = null
        try {
            result = res.decoder.decode(req.positionMs)
        } catch (t: Throwable) {
            decodeError = t
        }

        if (decodeError != null) {
            // FAST-FAIL contract: the ORIGINAL decode failure is delivered
            // immediately — the engine never waits for a cleanup close
            // before handing a caller its primary failure. Cleanup failures
            // are observable via the resource's fixed closeSignal and via
            // shutdown (exceptionally), never retro-attached to the
            // delivered Throwable.
            completeRequestFailed(req, decodeError!!)
            if (ephRes != null) {
                closeOrSeal(ephRes!!)
            } else {
                // Session-decode failure poisons the session: detach + claim
                // CLOSING atomically (end() never sees a close-less void);
                // the native close runs only AFTER the reply was delivered.
                // The detached resource is ALREADY claimed — run its close
                // directly. Routing it through a re-claiming close function
                // would make the second claim return false and silently skip
                // the native close (leak + a close signal that never fires).
                detachPoisonedSession()?.let { runCloseOrSeal(it) }
            }
            return
        }

        if (ephRes != null) {
            // A decode that succeeded only yields its frame after its own
            // ephemeral close SUCCEEDED (close failure → no frame). This is
            // the single path where the reply deliberately waits for close.
            var closeFailure: Throwable? = null
            try {
                closeResource(ephRes!!)
            } catch (t: Throwable) {
                closeFailure = t
            }
            if (closeFailure != null) {
                completeRequestFailed(req, closeFailure!!)
                seal(closeFailure!!)
                return
            }
        }

        // Reply is completed exactly once, on every exit path; inFlight stays
        // registered until exactly this lock section, so a concurrent seal
        // either finds it here-not-yet-completed (no-op: this section wins)
        // or the reply was already delivered.
        val fire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (inFlight === req) inFlight = null
            val publishable =
                req.publishable && !released && !sealedOff && req.gen == requestGen
            if (publishable) {
                fire += { req.reply.complete(Extracted(result, req.gen)) }
            } else {
                fire += { req.reply.complete(null) }
            }
        }
        fireAll(fire)
    }

    /**
     * Detach a session poisoned by a decode failure — atomic detach + CLOSING
     * claim in ONE lock — and return the CLAIMED resource so the lane can run
     * its close via [runCloseOrSeal]; null when the slot was already cleared
     * (nothing to do, exactly-once still holds). Callers MUST NOT re-claim
     * the returned resource: a second claim returns false and would skip
     * the native close entirely.
     */
    private fun detachPoisonedSession(): Res<F>? {
        synchronized(lock) {
            val d = sessionDecoder ?: return null
            sessionDecoder = null
            sessionKey = null
            sessionToken = null
            return if (claimCloseLocked(d)) d else null
        }
    }

    /**
     * Cleanup close for an already-delivered failure, for a resource whose
     * close has NOT been claimed yet: claims under lock, then runs the
     * native close. Never rethrows — seals instead. For a resource whose
     * close was already claimed use [runCloseOrSeal].
     */
    private fun closeOrSeal(res: Res<F>) {
        try {
            closeResource(res)
        } catch (t: Throwable) {
            seal(t)
        }
    }

    /**
     * Native close for a resource whose close was ALREADY claimed by this
     * lane under lock (CLOSING) — e.g. the result of [detachPoisonedSession]
     * or the orphan claim inside `executeOpen`'s finally. Same contract as
     * [closeOrSeal] (failure seals, never rethrows) minus the re-claim:
     * `closeResource` on an already-claimed resource returns from
     * [claimCloseLocked] with false and would skip the close.
     */
    private fun runCloseOrSeal(res: Res<F>) {
        try {
            runClose(res)
        } catch (t: Throwable) {
            seal(t)
        }
    }

    /** Deliver a terminal failure to a claimed request, exactly once. */
    private fun completeRequestFailed(req: Req<F>, t: Throwable) {
        var f: (() -> Unit)? = null
        synchronized(lock) {
            if (inFlight === req) inFlight = null
            f = { req.reply.completeExceptionally(t) }
        }
        f?.invoke()
    }

    // ---------- close paths (always on lane thread) ----------

    /**
     * Close the installed session resource, if any. Detaching the session
     * slot AND registering the resource as CLOSING happen in ONE lock —
     * there is no window in which end() could see "no resource" while a
     * close is imminent. Throws the real close failure.
     */
    private fun closeCurrent() {
        var toClose: Res<F>? = null
        synchronized(lock) {
            val r = sessionDecoder
            sessionDecoder = null
            sessionKey = null
            sessionToken = null
            if (r != null && claimCloseLocked(r)) toClose = r
        }
        if (toClose != null) runClose(toClose!!)
    }

    /**
     * Atomically claims the close for [res]: OPENING/INSTALLED → CLOSING,
     * removes it from `owned` and registers it as [closingRes] — all under
     * the caller's held lock. Returns false when the resource is already
     * CLOSING/CLOSED (exactly-once guarantee). Native close NEVER runs here.
     */
    private fun claimCloseLocked(res: Res<F>): Boolean {
        if (res.state == ResState.CLOSING || res.state == ResState.CLOSED) return false
        owned.remove(res)
        res.state = ResState.CLOSING
        closingRes = res
        return true
    }

    /** Claim (under lock) + native close (outside lock). Throws close failure. */
    private fun closeResource(res: Res<F>) {
        val owns = synchronized(lock) { claimCloseLocked(res) }
        if (owns) runClose(res)
    }

    /**
     * Runs the exactly-once native close for an already-claimed resource,
     * serially on the lane thread, outside the lock. Records the outcome,
     * completes the resource's FIXED close signal (plus any adopted open-
     * command waiters) exactly once, and throws the close failure so callers
     * can seal the engine (unknown native release state → lane isolation).
     */
    private fun runClose(res: Res<F>) {
        var failure: Throwable? = null
        try {
            res.decoder.close()
        } catch (t: Throwable) {
            failure = t
        }
        var adopted: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            res.state = ResState.CLOSED
            res.closeFailure = failure
            res.closeReported = true
            if (closingRes === res) closingRes = null
            adopted = res.adoptedSignal
            res.adoptedSignal = null
        }
        if (failure != null) {
            try {
                res.closeSignal.completeExceptionally(failure)
                adopted?.completeExceptionally(failure)
                logger("$TAG decoder close failed: ${failure.javaClass.simpleName}")
            } catch (_: Throwable) {
                // A broken logger must never mask the recorded close outcome.
            }
            throw failure
        }
        res.closeSignal.complete(Unit)
        adopted?.complete(Unit)
    }

    /** Final cleanup — must not lose ownership of anything still registered. */
    private fun drainOwned() {
        while (true) {
            var toClose: Res<F>? = null
            synchronized(lock) {
                val r = owned.firstOrNull() ?: return
                if (claimCloseLocked(r)) toClose = r
            }
            // runClose records the outcome and completes the fixed signal;
            // its throw propagates so the caller seals (seal drains the rest).
            if (toClose != null) runClose(toClose!!)
        }
    }

    private fun giveBackLane() {
        var l: PreviewLane? = null
        synchronized(lock) {
            l = lane
            lane = null
        }
        if (l != null) lanePool.giveBack(l!!)
    }

    /**
     * Fatal sealing (close failure or non-decoder failure): closes the entry,
     * completes every outstanding waiter with the real cause, closes all
     * owned resources and finishes [shutdown] exceptionally. The lane is
     * NEVER returned to the pool here — an unknown native release state must
     * not mint fresh native capacity.
     */
    private fun seal(cause: Throwable) {
        val fire = mutableListOf<() -> Unit>()
        synchronized(lock) {
            sealedOff = true
            released = true
            activeOpen?.let {
                it.installForbidden = true
                fire += { it.init.completeExceptionally(cause) }
                it.closeSignal?.let { s -> fire += { s.completeExceptionally(cause) } }
            }
            (control as? Cmd.Open<F>)?.let {
                fire += { it.init.completeExceptionally(cause) }
                it.closeSignal?.let { s -> fire += { s.completeExceptionally(cause) } }
            }
            control = null
            pending?.let { fire += { it.reply.completeExceptionally(cause) } }
            pending = null
            inFlight?.let { fire += { it.reply.completeExceptionally(cause) } }
            closingRes?.let { r ->
                fire += { r.closeSignal.completeExceptionally(cause) }
                r.adoptedSignal?.let { s -> fire += { s.completeExceptionally(cause) } }
            }
            closingRes = null
            workerScheduled = false
        }
        fireAll(fire)
        try {
            closeCurrent()
        } catch (_: Throwable) {
            // already sealing — keep cleaning
        }
        drainOwnedQuietly()
        // Deliberate: no giveBackLane() — the stranded lane's native state is
        // unknown; its quota is consumed, which is what keeps the pool bound
        // honest. lane reference stays held so the engine never re-leases.
        shutdownSignal.completeExceptionally(cause)
    }

    /** drainOwned variant used while sealing: per-resource try, never aborts. */
    private fun drainOwnedQuietly() {
        while (true) {
            var toClose: Res<F>? = null
            synchronized(lock) {
                val r = owned.firstOrNull() ?: return
                if (claimCloseLocked(r)) toClose = r
            }
            if (toClose == null) continue
            try {
                runClose(toClose!!)
            } catch (_: Throwable) {
                // outcome recorded + signal completed by runClose — keep cleaning
            }
        }
    }

}
