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
    val lastSyncResult: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
