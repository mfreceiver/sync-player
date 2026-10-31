package cn.vectory.syncplayer.di

import android.content.Context
import android.content.SharedPreferences
import cn.vectory.syncplayer.crypto.KeystoreKekProvider
import cn.vectory.syncplayer.crypto.MediaCryptoMaster
import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
import cn.vectory.syncplayer.data.local.entity.StorageDatasetEntity
import cn.vectory.syncplayer.storage.DatasetIdSource
import cn.vectory.syncplayer.storage.volume.MediaVolumeRegistry
import cn.vectory.syncplayer.storage.volume.VolumeResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.UUID
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaMasterPrefs

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /** 媒体主密钥 wrap blob 的独立 prefs（与 secure_prefs 分离：Keystore 失效不误伤别的条目）。 */
    @Provides
    @Singleton
    @MediaMasterPrefs
    fun provideMediaMasterPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences(MediaCryptoMaster.PREFS_NAME, Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun provideMediaCryptoMaster(
        @ApplicationContext context: Context,
        @MediaMasterPrefs prefs: SharedPreferences
    ): MediaCryptoMaster = MediaCryptoMaster(
        kekProvider = KeystoreKekProvider(context),
        loadBlob = { prefs.getString(MediaCryptoMaster.PREF_KEY, null)?.let { decodeHex(it) } },
        // P1-10：commit() 返回 false = 未持久化。必须抛异常让 MediaCryptoMaster 拒绝使用
        // 该 key（KEY_UNAVAILABLE），否则进程内缓存继续加密、重启后 blob 缺失静默换钥。
        storeBlob = { blob ->
            if (!prefs.edit().putString(MediaCryptoMaster.PREF_KEY, encodeHex(blob)).commit()) {
                throw IOException("media master blob persist rejected")
            }
        }
    )

    /** datasetId 源：storage_dataset 单行（启动 StorageBootstrap 已种；缺行 fail-closed）。 */
    @Provides
    @Singleton
    fun provideDatasetIdSource(dao: StorageDatasetDao): DatasetIdSource = DatasetIdSource {
        dao.get()?.datasetId ?: throw IllegalStateException("storage_dataset row missing")
    }

    private fun encodeHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun decodeHex(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModuleBinds {
    @Binds
    abstract fun bindVolumeResolver(impl: MediaVolumeRegistry): VolumeResolver
}

/**
 * 启动存储初始化：storage_dataset 补种（防 callback 之外的兜底，INSERT IGNORE 幂等）。
 * 卷登记/默认卷恢复的持久化归实施者 B；这里只保证 datasetId 可用。
 */
@Singleton
class StorageBootstrap @javax.inject.Inject constructor(
    private val dao: StorageDatasetDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun ensureDatasetId(): String {
        val existing = dao.get()
        if (existing != null) return existing.datasetId
        dao.ensure(StorageDatasetEntity(id = 1, datasetId = UUID.randomUUID().toString()))
        return dao.get()?.datasetId ?: throw IllegalStateException("storage_dataset seed failed")
    }
}
