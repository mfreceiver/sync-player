package cn.vectory.syncplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoItemDao(): VideoItemDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncSourceDao(): SyncSourceDao
    abstract fun syncRelationDao(): SyncRelationDao
    abstract fun pendingSyncActionDao(): PendingSyncActionDao

    companion object {
        /**
         * v2 → v3：保留数据的显式迁移（T3 批准的破坏性迁移规范显式例外）。
         *
         * - 新增结构化终态三列（TEXT/TEXT/INTEGER，均可空）；
         * - 旧 lastSyncResult 非空的行一律置 lastSyncStatus='UNKNOWN'：旧文案无法可靠
         *   反推终态，**不默认成功也不默认失败**；lastSyncResult 为 NULL 的行保持
         *   NULL = 无记录；
         * - lastSyncResult 列保留（deprecated），代码不再读写。
         *
         * fallbackToDestructiveMigration 仍保留为最后兜底，但 v2→v3 升级必须走本迁移。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_relation ADD COLUMN lastSyncStatus TEXT")
                db.execSQL("ALTER TABLE sync_relation ADD COLUMN lastSyncErrorCode TEXT")
                db.execSQL("ALTER TABLE sync_relation ADD COLUMN lastSyncFailedCount INTEGER")
                db.execSQL(
                    "UPDATE sync_relation SET lastSyncStatus = 'UNKNOWN' " +
                        "WHERE lastSyncResult IS NOT NULL"
                )
            }
        }
    }
}
