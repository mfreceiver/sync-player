package cn.vectory.syncplayer.player.preview

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A single execution lane for preview-resource work.
 *
 * A lane serializes everything submitted to it, which is what makes
 * `close-before-open` and exactly-once-close provable without the native calls
 * ever racing each other. A lane that is parked inside an uninterruptible
 * native call simply stops draining — callers must degrade, not spawn more
 * lanes (see [PreviewLanePool]).
 */
internal interface PreviewLane {
    /**
     * Enqueue a task. MUST NOT block the caller and MUST NOT run `task`
     * synchronously on the caller thread — engine signal paths (end/release)
     * rely on enqueue-and-return semantics.
     */
    fun submit(task: () -> Unit)
}

/**
 * Bounded, lifecycle-independent execution capacity for preview decoders.
 *
 * The pool is shared by every [SeekPreviewEngine] instance so that N facades
 * cannot hold N dedicated threads plus N live native objects forever. When all
 * lanes are leased (e.g. one is parked inside a native call that never
 * returns), new engines degrade to null previews instead of queueing or
 * creating more threads. A leaked lane is never recovered — by design, an
 * uninterruptible native call can hold it forever — but the leak is bounded by
 * [DaemonLanePool]'s `maxLanes`.
 */
internal interface PreviewLanePool {
    /** Lease a lane, or return null when capacity is exhausted. Never blocks. */
    fun tryAcquire(): PreviewLane?

    /** Return a previously leased lane. The lane must not be submitted to after return. */
    fun giveBack(lane: PreviewLane)
}

/**
 * Lane backed by a single dedicated daemon thread. Dedicated threads are used
 * (rather than a shared coroutine dispatcher) because native calls are
 * uninterruptible: a parked native call must strand at most this thread, never
 * a shared dispatcher's workers.
 */
internal class DaemonPreviewLane : PreviewLane {
    private val queue = LinkedBlockingQueue<() -> Unit>()

    init {
        thread(isDaemon = true, name = "seek-preview-lane") {
            while (true) {
                val task = queue.take() // thread parks here; interrupt is never used
                try {
                    task()
                } catch (_: Throwable) {
                    // Engine work catches its own Throwables; this only guards
                    // the lane thread against foreign bugs so the lane survives.
                }
            }
        }
    }

    override fun submit(task: () -> Unit) {
        queue.put(task)
    }
}

/**
 * Bounded pool of [DaemonPreviewLane]s. `tryAcquire`/`giveBack` are O(1) and
 * non-blocking; capacity is fixed at construction.
 */
internal class DaemonLanePool(private val maxLanes: Int = 2) : PreviewLanePool {
    private val idle = java.util.concurrent.atomic.AtomicReference<List<PreviewLane>>(emptyList())
    private val leased = java.util.concurrent.atomic.AtomicInteger(0)

    override fun tryAcquire(): PreviewLane? {
        while (true) {
            val cur = idle.get()
            if (cur.isNotEmpty()) {
                if (idle.compareAndSet(cur, cur.drop(1))) return cur.first()
            } else {
                val n = leased.get()
                if (n >= maxLanes) return null
                if (leased.compareAndSet(n, n + 1)) return DaemonPreviewLane()
            }
        }
    }

    override fun giveBack(lane: PreviewLane) {
        while (true) {
            val cur = idle.get()
            if (idle.compareAndSet(cur, cur + lane)) return
        }
    }
}
