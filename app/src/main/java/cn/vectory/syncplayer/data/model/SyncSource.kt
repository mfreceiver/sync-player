package cn.vectory.syncplayer.data.model

data class SyncSource(
    val id: Long = 0,
    val name: String,
    val protocol: SyncProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val createdAt: Long = System.currentTimeMillis()
)
