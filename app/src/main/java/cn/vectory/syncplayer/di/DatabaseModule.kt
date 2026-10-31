package cn.vectory.syncplayer.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
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
            // v5: 双卷加密存储（video_item 恢复 volumeId/iv，新增 storageFormat/generation/
            // storageState + volumeId 索引；新表 storage_dataset）。破坏性迁移：DB 全表清空
            // （源/关系/媒体/播放/待执行/同步日志），用户需重建同步配置并重新同步；
            // 外部旧 epoch 不自动全删。EncryptedSharedPreferences/DataStore 不随 Room 清空。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(DatasetSeedCallback())
            .build()
    }

    /**
     * storage_dataset 单行补种（INSERT OR IGNORE 幂等）。
     *
     * P0-1：Room 2.7 破坏性迁移顺序是 dropAllTables → onDestructiveMigration 回调 →
     * createAllTables；onDestructiveMigration/onCreate 时机不可靠（前者新表尚未建、
     * 后者仅在首次创建触发）。onOpen 在一切建表/迁移之后必定执行，是唯一安全兜底；
     * INSERT OR IGNORE 保证幂等（首开/迁移/重复打开都只种一行）。
     */
    @VisibleForTesting
    internal class DatasetSeedCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            val datasetId = java.util.UUID.randomUUID().toString()
            db.execSQL(
                "INSERT OR IGNORE INTO storage_dataset(id, datasetId, createdAt) VALUES(1, ?, ?)",
                arrayOf(datasetId, System.currentTimeMillis())
            )
        }
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
    fun provideStorageDatasetDao(db: AppDatabase): StorageDatasetDao = db.storageDatasetDao()

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
}
