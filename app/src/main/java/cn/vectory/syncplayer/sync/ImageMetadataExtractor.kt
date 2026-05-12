package cn.vectory.syncplayer.sync

import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository
) {
    private companion object {
        const val TAG = "ImageMetadataExtractor"
        val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val EXIF_DATE_FORMAT_ALT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    suspend fun extractAndSave(item: VideoItemEntity): VideoItemEntity {
        val file = File(item.localAbsolutePath)
        if (!file.exists()) return item

        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val width = options.outWidth.takeIf { it > 0 }
            val height = options.outHeight.takeIf { it > 0 }

            val exif = ExifInterface(file.absolutePath)
            val dateTaken = parseExifDate(exif)

            val updated = item.copy(
                width = width ?: item.width,
                height = height ?: item.height,
                thumbnailPath = item.localAbsolutePath,
                mediaType = "IMAGE",
                dateTaken = dateTaken ?: item.dateTaken
            )
            videoRepository.upsert(updated)
            return updated
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract image metadata for ${item.fileName}", e)
            val fallback = item.copy(
                mediaType = "IMAGE",
                thumbnailPath = item.localAbsolutePath
            )
            videoRepository.upsert(fallback)
            return fallback
        }
    }

    private fun parseExifDate(exif: ExifInterface): Long? {
        for (tag in listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME
        )) {
            val dateStr = exif.getAttribute(tag) ?: continue
            parseDateString(dateStr)?.let { return it }
        }
        return null
    }

    private fun parseDateString(dateStr: String): Long? {
        return try {
            EXIF_DATE_FORMAT.parse(dateStr)?.time
        } catch (_: Exception) {
            try {
                EXIF_DATE_FORMAT_ALT.parse(dateStr)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
