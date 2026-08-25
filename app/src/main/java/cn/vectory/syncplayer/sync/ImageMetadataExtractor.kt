package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.Lazy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** AND-011: 薄解码层，便于 JVM 单测注入 fake；prod 实现包装 BitmapFactory。 */
interface BitmapDecoder {
    /** inJustDecodeBounds=true 只读宽高（不分配像素内存）。返回 [w, h]；无法解析为负值。 */
    fun decodeBounds(stream: InputStream): IntArray

    /** 以 inSampleSize 解码缩略图；数据损坏返回 null（与 BitmapFactory 语义一致）。 */
    fun decodeSampled(stream: InputStream, sampleSize: Int): Bitmap?
}

/** prod 实现：全尺寸像素从不进内存（bounds 走 inJustDecodeBounds，缩略走 inSampleSize）。 */
@Singleton
class BitmapFactoryDecoder @Inject constructor() : BitmapDecoder {
    override fun decodeBounds(stream: InputStream): IntArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        return intArrayOf(opts.outWidth, opts.outHeight)
    }

    override fun decodeSampled(stream: InputStream, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeStream(stream, null, opts)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BitmapDecoderModule {
    @Binds
    @Singleton
    abstract fun bindBitmapDecoder(impl: BitmapFactoryDecoder): BitmapDecoder
}

@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val encryptedMediaStoreLazy: Lazy<EncryptedMediaStore>,
    @ApplicationContext private val context: Context,
    private val bitmapDecoder: BitmapDecoder,
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
            // AND-011 两遍解码：pass1 仅读宽高（inJustDecodeBounds 不分配像素内存），
            // pass2 以 inSampleSize 直接解码出 ≤640px 缩略图，替代旧全尺寸解码+scale
            // 的 ~192MB(48MP) 峰值内存。加密流不可 reset，每遍重开流是预期形态。
            val bounds = context.contentResolver.openInputStream(uri)?.use { input ->
                bitmapDecoder.decodeBounds(input)
            } ?: IntArray(0)
            val w = bounds.getOrElse(0) { -1 }
            val h = bounds.getOrElse(1) { -1 }
            if (w > 0 && h > 0) {
                width = w
                height = h
                val sampleSize = computeInSampleSize(w, h, THUMB_MAX_WIDTH)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val thumb = bitmapDecoder.decodeSampled(input, sampleSize)
                    if (thumb != null) {
                        val baos = ByteArrayOutputStream()
                        thumb.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        store.writeThumb(item.id, baos.toByteArray())
                        thumb.recycle()
                    }
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

    /** 标准 inSampleSize 算法：2 幂向下取，使缩略图两边均 ≤ maxSize。 */
    private fun computeInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
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
