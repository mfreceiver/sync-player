package cn.vectory.syncplayer.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val storagePaths: StoragePaths
) {
    private companion object {
        const val TAG = "ImageMetadataExtractor"
        const val THUMB_MAX_WIDTH = 640
        val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val EXIF_DATE_FORMAT_ALT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    private val thumbnailDir: File
        get() = storagePaths.thumbnailsDir

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

            val thumbPath = generateThumbnail(item, file, options)

            val updated = item.copy(
                width = width ?: item.width,
                height = height ?: item.height,
                thumbnailPath = thumbPath ?: item.localAbsolutePath,
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

    private fun generateThumbnail(item: VideoItemEntity, source: File, bounds: BitmapFactory.Options): String? {
        if (item.id <= 0L) return null
        val srcWidth = bounds.outWidth
        if (srcWidth <= 0) return null
        return try {
            val sample = if (srcWidth > THUMB_MAX_WIDTH) {
                var sampleSize = 1
                while (srcWidth / sampleSize > THUMB_MAX_WIDTH) sampleSize *= 2
                sampleSize
            } else 1
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return null
            try {
                val scaled = if (decoded.width > THUMB_MAX_WIDTH) {
                    Bitmap.createScaledBitmap(decoded, THUMB_MAX_WIDTH, (decoded.height * THUMB_MAX_WIDTH.toFloat() / decoded.width).toInt(), true)
                } else decoded
                try {
                    val thumbFile = File(thumbnailDir, "${item.id}.jpg")
                    FileOutputStream(thumbFile).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                    }
                    thumbFile.absolutePath
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate thumbnail for ${item.fileName}", e)
            null
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
