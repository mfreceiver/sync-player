package cn.vectory.syncplayer.navigation

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageQueue @Inject constructor() {
    private var _ids: List<Long> = emptyList()

    fun setIds(ids: List<Long>) {
        _ids = ids
    }

    fun getIds(): List<Long> = _ids

    fun consumeIds(): List<Long> {
        val ids = _ids
        _ids = emptyList()
        return ids
    }
}
