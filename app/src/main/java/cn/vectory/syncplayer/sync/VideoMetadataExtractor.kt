package cn.vectory.syncplayer.sync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.storage.DatasetIdSource
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StorageFormat
import cn.vectory.syncplayer.storage.StorageRef
import cn.vectory.syncplayer.storage.VolumeIds
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
    // P1-11（sol）：外部 CTR_V2 行的缩略图需按已提交 ref 定位（datasetId 参与
    // 加密路径）；DI 绑定见 di/StorageModule.kt（provideDatasetIdSource）。
    private val datasetIdSource: DatasetIdSource,
    @ApplicationContext private val context: Context
) {
    private companion object { const val TAG = "VideoMetadataExtractor" }

    private val store: MediaFileStore get() = mediaFileStoreLazy.get()

    /** 经 MediaContentProvider 读取，提取时长/尺寸/码率/帧率并生成缩略图。 */
    suspend fun extractAndSave(videoItem: VideoItemEntity): VideoItemEntity {
        var duration: Long? = null
        var width: Int? = null
        var height: Int? = null
        var bitrate: Long? = null
        var frameRate: Float? = null
        try {
            // A1-04：uri 构造纳入保护区——初始化失败即提取失败（早退不触 DB），
            // 与读取阶段同一受保护边界（测试经 mockkObject(MediaUris) 在此注入故障）。
            val uri = MediaUris.mediaUri(videoItem.id)
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
            // A1 敏感日志红线：安全摘要（仅异常类别），不拼 fileName、不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.metadataExtractionFailed(e))
            return videoItem
        }
        // A1 列所有权：五列按列提交（含显式 null 清除陈旧值）。整行 upsert 会覆盖
        // 用户列（star/audioGainDb）且可能复活被删行；0 行 = 行已消失 ⇒ 不复活，
        // 原实体原样返回。提取失败 ⇒ 上方 early-return，不触 DB（既有列值保留）。
        val updated = videoItem.copy(
            duration = duration, width = width, height = height,
            bitrate = bitrate, frameRate = frameRate
        )
        val committedRows = videoRepository.updateVideoMetadata(
            id = videoItem.id,
            duration = duration,
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate
        )
        return if (committedRows > 0) updated else videoItem
    }

    /** 仅重新生成缩略图（不重新提取尺寸等元数据）。 */
    suspend fun repairThumbnailIfNeeded(videoItem: VideoItemEntity): VideoItemEntity {
        try {
            // A1-04：与 extractAndSave 对齐——uri 构造也在保护区内，失败按提取失败收敛。
            val uri = MediaUris.mediaUri(videoItem.id)
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                generateThumbnail(mmr, videoItem)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A1 敏感日志红线：安全摘要，不拼 fileName、不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.metadataExtractionFailed(e))
        }
        return videoItem
    }

    private suspend fun generateThumbnail(mmr: MediaMetadataRetriever, videoItem: VideoItemEntity) {
        try {
            val bitmap = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                writeThumbRouted(videoItem.id, baos.toByteArray())
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A1 敏感日志红线：安全摘要，不拼 fileName、不传原始 Throwable。
            Log.w(TAG, SyncSafeLog.metadataExtractionFailed(e))
        }
    }

    /**
     * P1-11（sol）：缩略图按**当前已提交 ref** 路由——外部 CTR_V2 行的加密缩略图
     * 路径含 datasetId/generation，旧 `writeThumb(id, bytes)` 只写内部明文路径，
     * 导致外部行缩略图「写到错误卷 + 读取永远 miss（反复重生成）」。现取行后按
     * 卷/格式构造 [StorageRef] 走 `writeThumb(ref, bytes)`；内部行或未提交
     * （PENDING/列缺失）行沿用 by-id 路径。
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
}
