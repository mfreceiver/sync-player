package cn.vectory.syncplayer.data.model

data class SyncRelation(
    val id: Long = 0,
    val name: String,
    val syncSourceId: Long,
    val remotePath: String,
    val localBasePath: String,
    val lastSyncTime: Long? = null,
    /** 结构化终态快照（F02/T6）；null = 无记录。取代旧的 lastSyncResult 文案列。 */
    val lastSyncOutcome: SyncOutcome? = null,
    val createdAt: Long = System.currentTimeMillis()
)
