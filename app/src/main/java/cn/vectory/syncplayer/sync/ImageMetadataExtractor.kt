package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.DatasetIdSource
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StorageFormat
import cn.vectory.syncplayer.storage.StorageRef
import cn.vectory.syncplayer.storage.VolumeIds
import dagger.Lazy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    @Binds
    @Singleton
    abstract fun bindExifDateReader(impl: AndroidExifDateReader): ExifDateReader
}

/**
 * AND-011 同型薄 EXIF 读取层，便于 JVM 单测注入 fake（ExifInterface 无 shadow 可控）。
 * 契约：返回 null = 解析成功但无拍摄时间（合法缺失，按列提交 null 清除陈旧值）；
 * 抛异常 = EXIF 读取失败（提取失败，保留既有列值，绝不当成功）。
 */
interface ExifDateReader {
    fun readDateTaken(stream: InputStream): Long?
}

/** prod 实现：只读三个 DATETIME 标签，解析失败降级 null（合法缺失）。 */
@Singleton
class AndroidExifDateReader @Inject constructor() : ExifDateReader {
    override fun readDateTaken(stream: InputStream): Long? {
        val exif = ExifInterface(stream)
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

    // A3（rev-gpt）：SimpleDateFormat 非线程安全——并发提取共享实例有串台风险，
    // 改为每次局部实例（仅在解析路径构造，成本可忽略），同文件内消除，不扩大架构。
    private fun parseDateString(dateStr: String): Long? {
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr)?.time
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val videoRepository: VideoRepository,
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    // P1-11（sol）：外部 CTR_V2 行的缩略图需按已提交 ref 定位（datasetId 参与
    // 加密路径）；DI 绑定见 di/StorageModule.kt（provideDatasetIdSource）。
    private val datasetIdSource: DatasetIdSource,
    @ApplicationContext private val context: Context,
    private val bitmapDecoder: BitmapDecoder,
    private val exifDateReader: ExifDateReader,
) {
    private companion object {
        const val TAG = "ImageMetadataExtractor"
        const val THUMB_MAX_WIDTH = 640
    }

    private val store: MediaFileStore get() = mediaFileStoreLazy.get()

    /**
     * 经 MediaContentProvider 读取，提取宽高/拍摄时间并生成缩略图。
     *
     * A1-05 失败语义（rev-gpt）：null 流（bounds/EXIF 打不开）、非法 bounds、
     * EXIF 读取异常都是**提取失败**——早退不触 DB，既有列值保留；只有
     * 「解析成功但字段缺失」（无 DATETIME 标签）才按成功缺失提交 null 清除陈旧值。
     * 旧实现把 null 流/非法 bounds 当成功缺失 ⇒ 提交 (null,null,null) 清空既有元数据。
     * 缩略图 pass 是 best-effort：宽高已确认可解码，缩略流打不开/解码 null 只跳过缩略。
     */
    suspend fun extractAndSave(item: VideoItemEntity): VideoItemEntity {
        var width: Int? = null
        var height: Int? = null
        var dateTaken: Long? = null
        try {
            // A1-04：uri 构造纳入保护区——初始化失败即提取失败（早退不触 DB）。
            val uri = MediaUris.mediaUri(item.id)
            // pass1 bounds：流打不开/尺寸非法 ⇒ 提取失败（绝不是「成功但缺失」）
            val boundsIn = context.contentResolver.openInputStream(uri)
                ?: throw IOException("open bounds stream failed")
            val bounds = boundsIn.use { bitmapDecoder.decodeBounds(it) }
            val w = bounds.getOrElse(0) { -1 }
            val h = bounds.getOrElse(1) { -1 }
            if (w <= 0 || h <= 0) throw IOException("image bounds not decodable")
            width = w
            height = h
            // pass2 缩略图（best-effort，见方法注释）；每遍重开流是预期形态（加密流不可 reset）。
            context.contentResolver.openInputStream(uri)?.use { input ->
                val thumb = bitmapDecoder.decodeSampled(input, computeInSampleSize(w, h, THUMB_MAX_WIDTH))
                if (thumb != null) {
                    // A3（rev-gpt）：finally recycle——compress/writeThumb 抛出（含 CE）时
                    // 位图内存必被回收。异常语义不变：写缩略失败仍按提取失败上抛处理。
                    try {
                        val baos = ByteArrayOutputStream()
                        thumb.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        writeThumbRouted(item.id, baos.toByteArray())
                    } finally {
                        thumb.recycle()
                    }
                }
            }
            // pass3 EXIF：读取失败 ⇒ 提取失败（保留既有 dateTaken）；成功无标签 ⇒ null
            val exifIn = context.contentResolver.openInputStream(uri)
                ?: throw IOException("open exif stream failed")
            // A1-03：无内层 catch(Exception)——readDateTaken 内抛出的任何 CE
            // 经 use 原样上抛，由外层 CE-first 分支传播（不被吞成提取失败/成功缺失）。
            dateTaken = exifIn.use { exifDateReader.readDateTaken(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A1 敏感日志红线：安全摘要（仅异常类别），不拼 fileName、不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.metadataExtractionFailed(e))
            return item
        }
        // A1 列所有权：三列按列提交（含显式 null 清除陈旧值）。整行 upsert 会覆盖
        // 用户列（star/audioGainDb）且可能复活被删行；0 行 = 行已消失 ⇒ 不复活，
        // 原实体原样返回。提取失败 ⇒ 上方 early-return，不触 DB（既有列值保留）。
        val updated = item.copy(width = width, height = height, dateTaken = dateTaken)
        val committedRows = videoRepository.updateImageMetadata(
            id = item.id,
            width = width,
            height = height,
            dateTaken = dateTaken
        )
        return if (committedRows > 0) updated else item
    }

    /** 标准 inSampleSize 算法：2 幂向下取，使缩略图两边均 ≤ maxSize。 */
    /**
     * P1-11（sol）：缩略图按**当前已提交 ref** 路由——外部 CTR_V2 行的加密缩略图
     * 路径含 datasetId/generation，旧 `writeThumb(id, bytes)` 只写内部明文路径。
     * 外部已提交行走 `writeThumb(ref, bytes)`；内部行/未提交（PENDING）行沿用 by-id。
     */
    private suspend fun writeThumbRouted(id: Long, bytes: ByteArray) {
        val entity = try {
            videoRepository.getById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (entity != null && VolumeIds.isExternal(entity.volumeId) &&
            entity.storageFormat == StorageFormat.CTR_V2.name &&
            entity.iv != null && !entity.generation.isNullOrBlank()
        ) {
            val ref = StorageRef(
                id = id,
                volumeId = entity.volumeId,
                datasetId = datasetIdSource(),
                generation = entity.generation,
                format = StorageFormat.CTR_V2,
                iv = entity.iv,
                plainSize = entity.fileSize,
            )
            store.writeThumb(ref, bytes)
        } else {
            store.writeThumb(id, bytes)
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
