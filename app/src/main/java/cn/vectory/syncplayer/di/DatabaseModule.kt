package cn.vectory.syncplayer.di

import android.content.Context
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.dao.SyncLogDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "sync-player.db"
        )
            .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
            .build()
    }

    @Provides
    @Singleton
    fun provideSyncSourceDao(db: AppDatabase): SyncSourceDao = db.syncSourceDao()

    @Provides
    @Singleton
    fun provideSyncRelationDao(db: AppDatabase): SyncRelationDao = db.syncRelationDao()

    @Provides
    @Singleton
    fun provideVideoItemDao(db: AppDatabase): VideoItemDao = db.videoItemDao()

    @Provides
    @Singleton
    fun providePlaybackRecordDao(db: AppDatabase): PlaybackRecordDao = db.playbackRecordDao()

    @Provides
    @Singleton
    fun provideSyncLogDao(db: AppDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    @Singleton
    fun providePendingSyncActionDao(db: AppDatabase): PendingSyncActionDao = db.pendingSyncActionDao()

    @Provides
    @Singleton
    fun provideMasterKey(@ApplicationContext context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context,
        masterKey: MasterKey
    ): EncryptedSharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `pending_sync_action` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `relationId` INTEGER NOT NULL,
                    `relationName` TEXT NOT NULL,
                    `localBasePath` TEXT NOT NULL,
                    `actionGroup` INTEGER NOT NULL,
                    `actionType` TEXT NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `fileSize` INTEGER NOT NULL,
                    `remoteModifiedTime` INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_action_relationId` ON `pending_sync_action` (`relationId`)")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create index on syncRelationId to speed up lookups by relation
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_item_syncRelationId` ON `video_item` (`syncRelationId`)")
        }
    }

    // Migration from 11 to 12: add star column to video_item
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE video_item ADD COLUMN star INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Migration from 12 to 13: add timestamp index on sync_log
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_log_timestamp` ON `sync_log` (`timestamp`)")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE video_item ADD COLUMN parentPath TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_item_parentPath` ON `video_item` (`parentPath`)")
            db.execSQL("""
                WITH RECURSIVE
                last_slash(id, remaining, last_pos) AS (
                    SELECT id, relativePath, 0
                    FROM video_item
                    WHERE relativePath LIKE '%/%'
                    UNION ALL
                    SELECT id,
                           SUBSTR(remaining, INSTR(remaining, '/') + 1),
                           last_pos + INSTR(remaining, '/')
                    FROM last_slash
                    WHERE INSTR(remaining, '/') > 0
                ),
                computed_parent AS (
                    SELECT ls.id,
                           SUBSTR(vi.relativePath, 1, ls.last_pos - 1) as parentPath
                    FROM last_slash ls
                    JOIN video_item vi ON vi.id = ls.id
                    WHERE INSTR(ls.remaining, '/') = 0
                )
                UPDATE video_item SET parentPath = COALESCE(
                    (SELECT cp.parentPath FROM computed_parent cp WHERE cp.id = video_item.id),
                    parentPath
                )
                WHERE parentPath = ''
            """)
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                WITH RECURSIVE
                last_slash(id, remaining, last_pos) AS (
                    SELECT id, relativePath, 0
                    FROM video_item
                    WHERE relativePath LIKE '%/%'
                    UNION ALL
                    SELECT id,
                           SUBSTR(remaining, INSTR(remaining, '/') + 1),
                           last_pos + INSTR(remaining, '/')
                    FROM last_slash
                    WHERE INSTR(remaining, '/') > 0
                ),
                computed_parent AS (
                    SELECT ls.id,
                           SUBSTR(vi.relativePath, 1, ls.last_pos - 1) as parentPath
                    FROM last_slash ls
                    JOIN video_item vi ON vi.id = ls.id
                    WHERE INSTR(ls.remaining, '/') = 0
                )
                UPDATE video_item SET parentPath = COALESCE(
                    (SELECT cp.parentPath FROM computed_parent cp WHERE cp.id = video_item.id),
                    parentPath
                )
                WHERE parentPath = ''
            """)
        }
    }

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE video_item ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'VIDEO'")
            db.execSQL("ALTER TABLE video_item ADD COLUMN dateTaken INTEGER")

            // Backfill mediaType for image files based on file extension
            val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
            for (ext in imageExtensions) {
                db.execSQL("UPDATE video_item SET mediaType = 'IMAGE' WHERE fileName LIKE '%.$ext'")
            }

            // Backfill mediaType: anything not VIDEO and not IMAGE becomes OTHER
            val videoConditions = listOf(
                "fileName NOT LIKE '%.mp4'", "fileName NOT LIKE '%.mkv'",
                "fileName NOT LIKE '%.avi'", "fileName NOT LIKE '%.mov'",
                "fileName NOT LIKE '%.flv'", "fileName NOT LIKE '%.wmv'",
                "fileName NOT LIKE '%.webm'", "fileName NOT LIKE '%.ts'",
                "fileName NOT LIKE '%.m4v'", "fileName NOT LIKE '%.3gp'",
                "fileName NOT LIKE '%.mpeg'", "fileName NOT LIKE '%.mpg'"
            )
            val videoNotMatch = videoConditions.joinToString(" AND ")
            db.execSQL("UPDATE video_item SET mediaType = 'OTHER' WHERE mediaType = 'VIDEO' AND $videoNotMatch")
        }
    }
}
