package cn.vectory.syncplayer.navigation

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoQueue @Inject constructor() {
    // The payload carries the navigation target's videoItemId (anchor). AND-071:
    // two rapid navigations could let a stale payload be consumed by the wrong
    // view model — consumeIds(anchorId) only hands the list to its own target
    // and leaves a mismatched payload for the VM it was written for.
    private data class IdsPayload(val anchorId: Long, val ids: List<Long>)

    private val payloadRef = AtomicReference<IdsPayload?>(null)

    fun setIds(anchorId: Long, ids: List<Long>) {
        payloadRef.set(IdsPayload(anchorId, ids))
    }

    fun getIds(): List<Long> = payloadRef.get()?.ids ?: emptyList()

    fun consumeIds(anchorId: Long): List<Long> {
        val payload = payloadRef.get() ?: return emptyList()
        if (payload.anchorId != anchorId) return emptyList()
        // compareAndSet: only clear if still the same payload — a concurrent
        // setIds for a newer navigation must not be eaten by this consumer.
        return if (payloadRef.compareAndSet(payload, null)) payload.ids else emptyList()
    }
}
