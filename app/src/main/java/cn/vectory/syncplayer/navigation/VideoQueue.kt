package cn.vectory.syncplayer.navigation

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoQueue @Inject constructor() {
    // AtomicReference makes consumeIds() an atomic read-and-clear. The previous
    // read-then-clear two-step could race across coroutines and double-consume
    // the same id list (this object is a @Singleton shared across view models).
    private val idsRef = AtomicReference<List<Long>>(emptyList())

    fun setIds(ids: List<Long>) {
        idsRef.set(ids)
    }

    fun getIds(): List<Long> = idsRef.get()

    fun consumeIds(): List<Long> = idsRef.getAndSet(emptyList())
}
