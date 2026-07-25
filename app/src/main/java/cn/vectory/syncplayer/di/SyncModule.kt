package cn.vectory.syncplayer.di

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import cn.vectory.syncplayer.crypto.KekProvider
import cn.vectory.syncplayer.crypto.KeystoreKekProvider
import cn.vectory.syncplayer.crypto.MediaCryptoMaster
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.storage.volume.MediaVolumeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideKekProvider(@ApplicationContext context: Context): KekProvider =
        KeystoreKekProvider(context)

    @Provides
    @Singleton
    fun provideMediaCryptoMaster(
        kekProvider: KekProvider,
        prefs: EncryptedSharedPreferences,
    ): MediaCryptoMaster {
        val loadBlob: () -> ByteArray? = {
            prefs.getString(MediaCryptoMaster.PREF_KEY, null)
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
        }
        val storeBlob: (ByteArray) -> Unit = { blob ->
            prefs.edit().putString(
                MediaCryptoMaster.PREF_KEY,
                Base64.encodeToString(blob, Base64.NO_WRAP)
            ).apply()
        }
        return MediaCryptoMaster(kekProvider, loadBlob, storeBlob)
    }

    // UserPreferencesRepository is already @Singleton via its own @Inject constructor; no need to @Provides.

    @Provides
    @Singleton
    fun provideEncryptedMediaStore(
        mediaVolumeProvider: MediaVolumeProvider,
        mediaCryptoMaster: MediaCryptoMaster,
    ): EncryptedMediaStore =
        // Resolve volume on each operation so Settings treeUri switch applies without restart.
        EncryptedMediaStore(mediaVolumeProvider) { mediaCryptoMaster.masterKey() }
}
