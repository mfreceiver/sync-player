package cn.vectory.syncplayer.data.model

data class FolderStats(
    val totalFileCount: Int,
    val totalVideoCount: Int,
    val totalImageCount: Int = 0,
    val totalSize: Long
)
