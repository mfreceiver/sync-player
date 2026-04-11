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
            .addMigrations(MIGRATION_9_10)
            .build()
    }

    @Provides
    fun provideSyncSourceDao(db: AppDatabase): SyncSourceDao = db.syncSourceDao()

    @Provides
    fun provideSyncRelationDao(db: AppDatabase): SyncRelationDao = db.syncRelationDao()

    @Provides
    fun provideVideoItemDao(db: AppDatabase): VideoItemDao = db.videoItemDao()

    @Provides
    fun providePlaybackRecordDao(db: AppDatabase): PlaybackRecordDao = db.playbackRecordDao()

    @Provides
    fun provideSyncLogDao(db: AppDatabase): SyncLogDao = db.syncLogDao()

    @Provides
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
}
