package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v5 单行 dataset 表：随机 UUID，避免破坏性迁移（dropAllTables）后 id 重用碰撞旧卡
 * 上的文件（外部卷路径含 datasetId）。不是秘密——卡上只能看到不透明命名。
 * 必须在 DB 首次创建事务/启动初始化内建立（DatabaseModule callback + StorageBootstrap）。
 */
@Entity(tableName = "storage_dataset")
data class StorageDatasetEntity(
    @PrimaryKey val id: Int = 1,
    val datasetId: String,
    val createdAt: Long = System.currentTimeMillis()
)
