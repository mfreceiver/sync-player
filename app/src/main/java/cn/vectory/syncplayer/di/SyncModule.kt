package cn.vectory.syncplayer.di

import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.volume.MediaVolumeRegistry
import cn.vectory.syncplayer.sync.DualVolumeSyncStoragePort
import cn.vectory.syncplayer.sync.SyncStoragePort
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 外部 SD 加密媒体存储 v2（任务包 B）：同步/设置/维护链路的存储端口绑定。
 *
 * [DualVolumeSyncStoragePort] 包装实施者 A 的冻结契约实现（MediaFileStore v2 +
 * MediaVolumeRegistry + Room v5 卷列），并负责卷登记/默认卷偏好的持久化恢复。
 * sync 包内所有卷逻辑只面向 [SyncStoragePort]；契约变更的合入点是
 * [DualVolumeSyncStoragePort] 与本文件，不动管线/引擎。
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncStoragePort(
        mediaFileStoreLazy: Lazy<MediaFileStore>,
        registry: MediaVolumeRegistry,
        videoRepository: VideoRepository,
        datasetDao: StorageDatasetDao,
        userPreferencesRepository: UserPreferencesRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): SyncStoragePort = DualVolumeSyncStoragePort(
        storeLazy = mediaFileStoreLazy,
        registry = registry,
        videoRepository = videoRepository,
        datasetDao = datasetDao,
        userPreferencesRepository = userPreferencesRepository,
        ioDispatcher = ioDispatcher
    )
}
