package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_relation",
    foreignKeys = [
        ForeignKey(
            entity = SyncSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["syncSourceId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["syncSourceId"]),
        Index(value = ["localBasePath"], unique = true)
    ]
)
data class SyncRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val syncSourceId: Long,
    val remotePath: String,
    val localBasePath: String,  // relative to userdata root
    val lastSyncTime: Long? = null,
    /**
     * @deprecated F02/T6 后代码不再读写此列：旧实现存本地化文案（sync_result_success 等），
     * 已被结构化终态列取代。列仅为兼容 v2 库保留；v2→v3 迁移时旧行按
     * lastSyncResult 非空 → lastSyncStatus='UNKNOWN' 处理（不默认成功/失败）。
     */
    @Deprecated(message = "Legacy localized-text column; replaced by structured outcome columns")
    val lastSyncResult: String? = null,
    /** F02: 终态稳定标识（SyncOutcome.Status.name）；NULL = 无记录。 */
    val lastSyncStatus: String? = null,
    /** F02: 错误码稳定标识（SyncErrorCode.name）；仅 FAILED 携带。 */
    val lastSyncErrorCode: String? = null,
    /** F02: 失败动作数；未知为 NULL，不用 0 伪造。 */
    val lastSyncFailedCount: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
