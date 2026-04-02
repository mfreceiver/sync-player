package com.mfreceiver.syncplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mfreceiver.syncplayer.data.local.dao.PlaybackRecordDao
import com.mfreceiver.syncplayer.data.local.dao.ServerConfigDao
import com.mfreceiver.syncplayer.data.local.dao.SyncLogDao
import com.mfreceiver.syncplayer.data.local.dao.VideoItemDao
import com.mfreceiver.syncplayer.data.local.entity.PlaybackRecordEntity
import com.mfreceiver.syncplayer.data.local.entity.ServerConfigEntity
import com.mfreceiver.syncplayer.data.local.entity.SyncLogEntity
import com.mfreceiver.syncplayer.data.local.entity.VideoItemEntity

@Database(
    entities = [
        ServerConfigEntity::class,
        VideoItemEntity::class,
        PlaybackRecordEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun videoItemDao(): VideoItemDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun syncLogDao(): SyncLogDao
}
