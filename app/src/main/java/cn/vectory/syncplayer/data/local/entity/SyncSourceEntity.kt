package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_source")
data class SyncSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,       // "SMB" or "SFTP"
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPassword: String,
    val createdAt: Long = System.currentTimeMillis()
)
