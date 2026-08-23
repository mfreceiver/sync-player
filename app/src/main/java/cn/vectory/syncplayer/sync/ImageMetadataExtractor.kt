package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ImageMetadataExtractor"
        const val THUMB_MAX_WIDTH = 640
        val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val EXIF_DATE_FORMAT_ALT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    private val store: EncryptedMediaStore get() = encryptedMediaStoreLazy.get()

    /** 经 MediaContentProvider 解密读取，提取宽高/拍摄时间并生成加密缩略图。 */
    suspend fun extractAndSave(item: VideoItemEntity): VideoItemEntity {
        val uri = MediaUris.mediaUri(item.id)
        var width: Int? = null
        var height: Int? = null
        var dateTaken: Long? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    width = bitmap.width
                    height = bitmap.height
                    val thumb = sampleThumbnail(bitmap, THUMB_MAX_WIDTH)
                    val baos = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                    store.writeThumb(item.id, baos.toByteArray())
                    if (thumb !== bitmap) thumb.recycle()
                    bitmap.recycle()
                }
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                try {
                    val exif = ExifInterface(input)
                    dateTaken = parseExifDate(exif)
                } catch (_: Exception) { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Image metadata extraction failed for ${item.fileName}", e)
            return item
        }
        val updated = item.copy(width = width, height = height, dateTaken = dateTaken)
        videoRepository.upsert(updated)
        return updated
    }

    private fun sampleThumbnail(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = src.width.toFloat() / maxWidth
        val w = maxWidth
        val h = (src.height / ratio).toInt().coerceAtLeast(1)
        return src.scale(w, h)
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
