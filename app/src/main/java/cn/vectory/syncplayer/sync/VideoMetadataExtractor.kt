package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.LinkedBlockingQueue

@Singleton
class VideoMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context
) {
    private companion object { const val TAG = "VideoMetadataExtractor" }

    private val retrieverPool = LinkedBlockingQueue<MediaMetadataRetriever>(4)

    private fun acquireRetriever(): MediaMetadataRetriever {
        return retrieverPool.poll() ?: MediaMetadataRetriever()
    }

    private fun releaseRetriever(retriever: MediaMetadataRetriever) {
        try {
            retriever.reset()
            if (!retrieverPool.offer(retriever)) {
                retriever.release()
            }
        } catch (_: Exception) {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private val thumbnailDir: File
        get() = storagePaths.thumbnailsDir

    suspend fun extractAndSave(videoItem: VideoItemEntity): VideoItemEntity {
        val file = File(videoItem.localAbsolutePath)
        if (!file.exists()) {
            return videoItem
        }

        val retriever = acquireRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: videoItem.duration
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()

            var thumbnailPath: String? = videoItem.thumbnailPath
            val frame = retriever.getFrameAtTime(0)
            if (frame != null) {
                val thumbFile = File(thumbnailDir, "${videoItem.id}.jpg")
                FileOutputStream(thumbFile).use { fos ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                }
                thumbnailPath = thumbFile.absolutePath
            }

            val updated = videoItem.copy(
                duration = duration,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrate = bitrate,
                thumbnailPath = thumbnailPath
            )
            videoRepository.upsert(updated)
            return updated
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for ${videoItem.fileName}", e)
            return videoItem
        } finally {
            releaseRetriever(retriever)
        }
    }

    suspend fun repairThumbnailIfNeeded(videoItem: VideoItemEntity): VideoItemEntity {
        val thumbPath = videoItem.thumbnailPath
        if (thumbPath != null && File(thumbPath).exists()) return videoItem
        val localFile = File(videoItem.localAbsolutePath)
        if (!localFile.exists()) return videoItem
        return extractAndSave(videoItem)
    }
}
