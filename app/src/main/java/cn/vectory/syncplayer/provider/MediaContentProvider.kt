package cn.vectory.syncplayer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.crypto.DecryptingFdCallback
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StorageFormat
import cn.vectory.syncplayer.storage.StorageRef
import cn.vectory.syncplayer.storage.StorageState
import cn.vectory.syncplayer.storage.VolumeIds
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaProviderEntryPoint {
    fun appDatabase(): AppDatabase
    fun mediaFileStore(): MediaFileStore
}

/**
 * ContentProvider 暴露本地媒体文件与缩略图给 ExoPlayer 等外部消费者。
 *
 * ## 异常边界
 * openFile/getType/query 的内部异常（数据库、store、open/read、requireMedia/requireThumb等）
 * 不会将 URI/id/path/底层 message 暴露给调用方。
 * 所有内部异常转换为统一的 FileNotFoundException（使用 stringResource）。
 * CancellationException 不吞，直接传播。
 */
class MediaContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun ep(): MediaProviderEntryPoint =
        EntryPointAccessors.fromApplication(context!!, MediaProviderEntryPoint::class.java)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw FileNotFoundException(context!!.getString(R.string.media_write_not_supported))
        }
        val id = try {
            MediaUris.idOf(uri)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(context!!.getString(R.string.media_error_invalid))
        }
        return try {
            safeOpenFile(uri, id)
        } catch (e: CancellationException) {
            // 协程取消不得被转换成普通 Provider 错误。
            throw e
        } catch (_: Exception) {
            // 包括底层 FileNotFoundException（其 message 含文件路径）——不得把 message、
            // URI、路径或其他内部信息传播给 Provider 调用方，统一转换为安全文案。
            throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        }
    }

    /**
     * 安全打开文件，所有内部异常在 openFile 的外层捕获并转换。
     * v2 双卷：单一 entity 快照 → PENDING 不公开 → internal PLAIN 走 PFD 快路径；
     * external CTR_V2 走 openProxyFileDescriptor + DecryptingFdCallback（专用 HandlerThread）。
     */
    private fun safeOpenFile(uri: Uri, id: Long): ParcelFileDescriptor {
        val store = ep().mediaFileStore()
        // 单一 entity 快照：后续全部判定基于同一行，避免中途行变更导致混合状态。
        val entity = ep().appDatabase().videoItemDao().getByIdSync(id)
            ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        if (entity.storageState != StorageState.READY.name) {
            // PENDING/未知：对外即「不可用」，绝不暴露半成品密文。
            throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        }
        return if (MediaUris.isMedia(uri)) {
            MediaUris.requireMedia(uri)
            openMedia(store, entity)
        } else {
            MediaUris.requireThumb(uri)
            openThumb(store, entity)
        }
    }

    private fun openMedia(store: MediaFileStore, entity: VideoItemEntity): ParcelFileDescriptor {
        if (entity.volumeId == VolumeIds.INTERNAL) {
            if (entity.storageFormat != StorageFormat.PLAIN.name) {
                // 不一致组合 fail-closed：绝不把密文当明文供出。
                throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
            }
            // 快路径：明文本地文件直接 PFD（历史行为不变）。
            return ParcelFileDescriptor.open(
                store.mediaFile(entity.id),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        }
        val ref = refOf(entity)
        val key = store.contentKey(ref)
        val handle = runBlocking(Dispatchers.IO) { store.openRead(ref) }
        val callback = DecryptingFdCallback(handle, key, ref.iv!!, ref.plainSize)
        val sm = context!!.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        return try {
            sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, proxyHandler())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // openProxy 失败必须释放已打开句柄，避免 FD 泄漏。
            try {
                handle.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun openThumb(store: MediaFileStore, entity: VideoItemEntity): ParcelFileDescriptor {
        if (entity.volumeId == VolumeIds.INTERNAL) {
            if (entity.storageFormat != StorageFormat.PLAIN.name) {
                throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
            }
            return ParcelFileDescriptor.open(
                store.thumbFile(entity.id),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        }
        val ref = refOf(entity)
        val bytes = runBlocking(Dispatchers.IO) { store.readThumbBytes(ref) }
            ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        val pipe = ParcelFileDescriptor.createPipe()
        val writeSide = pipe[1]
        val readSide = pipe[0]
        FileOutputStream(writeSide.fileDescriptor).use { it.write(bytes) }
        return readSide
    }

    /** entity 单快照 → StorageRef（datasetId 来自 storage_dataset 单行）。 */
    private fun refOf(entity: VideoItemEntity): StorageRef = StorageRef(
        id = entity.id,
        volumeId = entity.volumeId,
        datasetId = ep().appDatabase().storageDatasetDao().getSync()?.datasetId,
        generation = entity.generation,
        format = runCatching { StorageFormat.valueOf(entity.storageFormat) }
            .getOrElse { StorageFormat.CTR_V2 }, // 未知格式由 StorageRef 组合校验 fail-closed
        iv = entity.iv,
        plainSize = entity.fileSize
    )

    // ---- proxy 回调线程（懒启动；测试可关闭）----

    @Volatile
    private var handlerThread: HandlerThread? = null

    private fun proxyHandler(): Handler {
        val existing = handlerThread
        if (existing != null && existing.isAlive) return Handler(existing.looper)
        synchronized(this) {
            val t = handlerThread?.takeIf { it.isAlive } ?: HandlerThread("media-provider-io").apply {
                isDaemon = true
                start()
            }
            handlerThread = t
            return Handler(t.looper)
        }
    }

    @VisibleForTesting
    fun shutdownProxyThread() {
        synchronized(this) {
            handlerThread?.quitSafely()
            handlerThread = null
        }
    }

    override fun getType(uri: Uri): String? {
        val id = try {
            MediaUris.idOf(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val entity = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return null
            getMimeType(entity.fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 内部异常不泄露，返回 null 表示无法确定类型
            null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val empty = MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), 0)
        val id = try {
            MediaUris.idOf(uri)
        } catch (_: IllegalArgumentException) {
            return empty
        }
        return try {
            val entity = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return empty
            val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cur = MatrixCursor(cols, 1)
            val row = cur.newRow()
            cols.forEach { c ->
                row.add(
                    when (c) {
                        OpenableColumns.DISPLAY_NAME -> entity.fileName
                        OpenableColumns.SIZE -> entity.fileSize
                        else -> null
                    }
                )
            }
            cur
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 内部异常不泄露，返回空 Cursor
            empty
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** 根据文件名返回准确的 MIME 类型。 */
        @JvmStatic
        fun getMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg" -> "video/$ext"
                "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif" -> "image/$ext"
                else -> "application/octet-stream"
            }
        }
    }
}
