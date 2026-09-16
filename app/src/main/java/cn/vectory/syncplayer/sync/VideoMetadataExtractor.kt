package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class VideoMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    @ApplicationContext private val context: Context
) {
    private companion object { const val TAG = "VideoMetadataExtractor" }

    private val store: MediaFileStore get() = mediaFileStoreLazy.get()

    /** 经 MediaContentProvider 读取，提取时长/尺寸/码率/帧率并生成缩略图。 */
    suspend fun extractAndSave(videoItem: VideoItemEntity): VideoItemEntity {
        val uri = MediaUris.mediaUri(videoItem.id)
        var duration: Long? = null
        var width: Int? = null
        var height: Int? = null
        var bitrate: Long? = null
        var frameRate: Float? = null
        try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                frameRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                generateThumbnail(mmr, videoItem)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, SyncSafeLog.failure("metadata extraction id=${videoItem.id}", e))
            return videoItem
        }
        val updated = videoItem.copy(
            duration = duration, width = width, height = height,
            bitrate = bitrate, frameRate = frameRate
        )
        videoRepository.upsert(updated)
        return updated
    }

    /** 仅重新生成缩略图（不重新提取尺寸等元数据）。 */
    suspend fun repairThumbnailIfNeeded(videoItem: VideoItemEntity): VideoItemEntity {
        val uri = MediaUris.mediaUri(videoItem.id)
        try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                generateThumbnail(mmr, videoItem)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, SyncSafeLog.failure("thumb repair id=${videoItem.id}", e))
        }
        return videoItem
    }

    private suspend fun generateThumbnail(mmr: MediaMetadataRetriever, videoItem: VideoItemEntity) {
        try {
            val bitmap = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                store.writeThumb(videoItem.id, baos.toByteArray())
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, SyncSafeLog.failure("thumb generation id=${videoItem.id}", e))
        }
    }
}
