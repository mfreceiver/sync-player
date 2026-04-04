package cn.vectory.syncplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.ServerConfigDao
import cn.vectory.syncplayer.data.local.dao.SyncLogDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.VideoMetadataCacheDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.ServerConfigEntity
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.local.entity.VideoMetadataCacheEntity

@Database(
    entities = [
        ServerConfigEntity::class,
        VideoItemEntity::class,
        PlaybackRecordEntity::class,
        SyncLogEntity::class,
        VideoMetadataCacheEntity::class,
        SyncSourceEntity::class,
        SyncRelationEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun videoItemDao(): VideoItemDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun videoMetadataCacheDao(): VideoMetadataCacheDao
    abstract fun syncSourceDao(): SyncSourceDao
    abstract fun syncRelationDao(): SyncRelationDao
}
