package cn.vectory.syncplayer.navigation

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageQueue @Inject constructor() {
    // Anchored payload — same contract as VideoQueue (AND-071).
    private data class IdsPayload(val anchorId: Long, val ids: List<Long>)

    private val payloadRef = AtomicReference<IdsPayload?>(null)

    fun setIds(anchorId: Long, ids: List<Long>) {
        payloadRef.set(IdsPayload(anchorId, ids))
    }

    fun getIds(): List<Long> = payloadRef.get()?.ids ?: emptyList()

    fun consumeIds(anchorId: Long): List<Long> {
        val payload = payloadRef.get() ?: return emptyList()
        if (payload.anchorId != anchorId) return emptyList()
        return if (payloadRef.compareAndSet(payload, null)) payload.ids else emptyList()
    }
}
