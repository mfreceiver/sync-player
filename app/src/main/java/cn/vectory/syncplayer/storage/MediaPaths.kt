package cn.vectory.syncplayer.storage

/**
 * 媒体/缩略图相对路径布局。
 *
 * 内部（明文，root = filesDir/userdata）：
 * - 正文：`"<id/1000>/<id>"`，tmp = 正文 + ".tmp"
 * - 缩略图：`"thumbs/<id>"`
 *
 * 外部（CTR_V2，FileVolume root = getExternalFilesDir 对应目录，relPath 含
 * `userdata-v2/<datasetId>/` 前缀）：
 * - 正文：`"userdata-v2/<ds>/<id/1000>/<id>.<generation>.enc"`，tmp 同路径 + ".tmp"
 * - 缩略图：`"userdata-v2/<ds>/thumbs/<id>.<generation>.enc"`（GCM）；重建同
 *   generation 使用 GCM 新 nonce，thumb.tmp → rename 由 store 编排。
 */
object MediaPaths {
    const val TMP_SUFFIX = ".tmp"
    const val THUMBS_DIR = "thumbs"
    const val ENC_EXT = ".enc"
    private const val EXTERNAL_ROOT_DIR = "userdata-v2"

    // ---- 内部布局 ----

    /** 分桶防单目录巨量文件：`"<id/1000>/<id>"`。 */
    fun mediaRelPath(id: Long): String = "${id / 1000}/$id"

    fun thumbRelPath(id: Long): String = "$THUMBS_DIR/$id"

    // ---- 外部布局（CTR_V2） ----

    /** 外部应用专属目录下的本应用数据根（relPath 前缀）。 */
    fun externalRootRel(datasetId: String): String = "$EXTERNAL_ROOT_DIR/$datasetId"

    fun externalMediaRelPath(datasetId: String, id: Long, generation: String): String =
        "${externalRootRel(datasetId)}/${id / 1000}/$id.$generation$ENC_EXT"

    fun externalThumbRelPath(datasetId: String, id: Long, generation: String): String =
        "${externalRootRel(datasetId)}/$THUMBS_DIR/$id.$generation$ENC_EXT"
}
