package cn.vectory.syncplayer.storage.volume

object MediaPaths {
    const val TMP_SUFFIX = ".enc.tmp"
    fun mediaRelPath(id: Long) = "${id / 1000}/${id}.enc"
    fun thumbRelPath(id: Long) = "thumbs/${id}.enc"
}