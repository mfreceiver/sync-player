package cn.vectory.syncplayer.storage.volume

import android.content.Context
import android.net.Uri
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaVolumeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    /** 首次构建时确保内部卷根目录存在。 */
    private val internalVolume: FileVolume by lazy {
        FileVolume(File(context.filesDir, "userdata").also { it.mkdirs() })
    }

    suspend fun current(): MediaVolume {
        val treeUriStr = userPreferencesRepository.getMediaStorageTreeUri()
        return if (treeUriStr != null) {
            SafVolume(context, Uri.parse(treeUriStr))
        } else {
            internalVolume
        }
    }
}
