package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_item",
    indices = [
        Index(value = ["relativePath", "syncRelationId"], unique = true),
        Index(value = ["syncRelationId"]),
        Index(value = ["parentPath"]),
        Index(value = ["volumeId"])
    ]
)
data class VideoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncRelationId: Long = 0,
    val relativePath: String,
    val parentPath: String = "",
    val fileName: String,
    val fileSize: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val audioGainDb: Float? = null,
    val dateSynced: Long = System.currentTimeMillis(),
    val remoteModifiedTime: Long? = null,
    val star: Int = 0,
    val mediaType: String = "VIDEO",
    val dateTaken: Long? = null,
    // ---- v5 双卷存储列（docs/ocmar/specs/2026-10-31 external-sd-encrypted-storage-v2）----
    /** `internal` 或 `external:<uuid>`（VolumeIds）。已有行固定走该卷，不随默认切换。 */
    val volumeId: String = "internal",
    /** StorageFormat 名（PLAIN/CTR_V2）。不合法组合 fail-closed，不按明文兜底。 */
    val storageFormat: String = "PLAIN",
    /** CTR_V2：8B IV；PLAIN：null。 */
    val iv: ByteArray? = null,
    /** CTR_V2：128 位随机 token（hex）；PLAIN：null。thumb IV 在 GCM 文件头不进 DB。 */
    val generation: String? = null,
    /** StorageState 名（PENDING/READY）。Provider 只公开 READY。 */
    val storageState: String = "READY"
)
