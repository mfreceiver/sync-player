package cn.vectory.syncplayer.data.model

data class SyncRelation(
    val id: Long = 0,
    val name: String,
    val syncSourceId: Long,
    val remotePath: String,
    val localBasePath: String,
    val lastSyncTime: Long? = null,
    val lastSyncResult: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
