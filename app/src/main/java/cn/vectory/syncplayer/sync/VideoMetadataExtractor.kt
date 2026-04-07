package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import cn.vectory.syncplayer.data.local.dao.VideoMetadataCacheDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.local.entity.VideoMetadataCacheEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val cacheDao: VideoMetadataCacheDao,
    private val storagePaths: StoragePaths,
    @ApplicationContext private val context: Context
) {
    private val thumbnailDir: File
        get() = storagePaths.thumbnailsDir

    suspend fun extractAndSave(videoItem: VideoItemEntity): VideoItemEntity {
        val file = File(videoItem.localAbsolutePath)
        if (!file.exists()) {
            cacheDao.deleteByKey(videoItem.localAbsolutePath, videoItem.fileName, videoItem.fileSize)
            return videoItem
        }

        val cached = cacheDao.getByKey(videoItem.localAbsolutePath, videoItem.fileName, videoItem.fileSize)
        if (cached != null && cached.fileSize == videoItem.fileSize) {
            val updated = videoItem.copy(
                duration = cached.duration,
                width = cached.width,
                height = cached.height,
                frameRate = cached.frameRate,
                bitrate = cached.bitrate,
                thumbnailPath = cached.thumbnailPath
            )
            videoRepository.upsert(updated)
            return updated
        }

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

            val cacheEntity = VideoMetadataCacheEntity(
                localAbsolutePath = videoItem.localAbsolutePath,
                fileName = videoItem.fileName,
                fileSize = videoItem.fileSize,
                duration = duration,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrate = bitrate,
                thumbnailPath = thumbnailPath
            )
            cacheDao.upsert(cacheEntity)

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
        } catch (_: Exception) {
            return videoItem
        } finally {
            try { retriever.release() } catch (_: Exception) {}
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
