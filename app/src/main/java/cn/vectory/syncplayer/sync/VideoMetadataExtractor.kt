package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    private val thumbnailDir: File
        get() = File(context.filesDir, "thumbnails").also { it.mkdirs() }

    suspend fun extractAndSave(videoItem: VideoItemEntity): VideoItemEntity {
        val file = File(videoItem.localAbsolutePath)
        if (!file.exists()) return videoItem

        val retriever = MediaMetadataRetriever()
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
                bitrate = bitrate,
                thumbnailPath = thumbnailPath
            )
            videoRepository.upsert(updated)
            return updated
        } catch (_: Exception) {
            return videoItem
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
