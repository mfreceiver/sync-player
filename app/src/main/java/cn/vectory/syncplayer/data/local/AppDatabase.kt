package cn.vectory.syncplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.SyncLogDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity

@Database(
    entities = [
        VideoItemEntity::class,
        PlaybackRecordEntity::class,
        SyncLogEntity::class,
        SyncSourceEntity::class,
        SyncRelationEntity::class,
        PendingSyncActionEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoItemDao(): VideoItemDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncSourceDao(): SyncSourceDao
    abstract fun syncRelationDao(): SyncRelationDao
    abstract fun pendingSyncActionDao(): PendingSyncActionDao
}
