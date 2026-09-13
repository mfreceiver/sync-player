package cn.vectory.syncplayer.storage

/** 媒体/缩略图在存储根（`filesDir/userdata`）下的相对路径布局。 */
object MediaPaths {
    const val TMP_SUFFIX = ".tmp"
    const val THUMBS_DIR = "thumbs"

    /** 分桶防单目录巨量文件：`"<id/1000>/<id>"`。 */
    fun mediaRelPath(id: Long): String = "${id / 1000}/$id"

    fun thumbRelPath(id: Long): String = "$THUMBS_DIR/$id"
}
