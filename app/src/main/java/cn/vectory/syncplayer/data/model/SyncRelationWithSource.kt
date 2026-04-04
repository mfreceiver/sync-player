package cn.vectory.syncplayer.data.model

data class SyncRelationWithSource(
    val relation: SyncRelation,
    val sourceName: String,
    val sourceProtocol: SyncProtocol
)
