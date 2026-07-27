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
import android.util.Log
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.crypto.DecryptingFdCallback
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
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
    fun mediaStore(): EncryptedMediaStore
}

/**
 * ContentProvider 暴露加密媒体文件与缩略图给 ExoPlayer 等外部消费者。
 *
 * ## HandlerThread 生命周期
 * [HandlerThread] 在 [onCreate] 中启动后常驻。ContentProvider 没有标准销毁回调
 *（[android.content.ContentProvider.onDestroy] 不存在），因此在 Provider 生命周期内
 * 永久保留一个 IO 线程是业界常见做法（参见 Android 官方示例的 ContentProvider 实现）。
 * [onLowMemory]/[onTrimMemory] 退出线程并惰性重启的方案会引入线程重启竞态，
 * 而本 Provider 供 ExoPlayer 高频调用，竞态风险高于常驻线程的资源开销，故保持简单。
 *
 * ## volumeId 不缓存
 * [EncryptedMediaStore.volumeId] 每次访问都经由 [runBlocking] 解析当前 SAF treeUri
 *（见 [EncryptedMediaStore] 的 volume() 实现），按需从 UserPreferences 读取。
 * 用户在设置页面可随时切换存储卷，但 ContentProvider 无法感知偏好变更事件。
 * 若在此处缓存 volumeId，切换卷后会导致错误的路由（读旧卷文件或报错）。
 * 正确方案需 [EncryptedMediaStore] 提供失效通知机制，本侧不做缓存。
 *
 * ## 异常边界
 * openFile/getType/query 的内部异常（数据库、volume、store、open/read、requireMedia/requireThumb等）
 * 不会将 URI/id/path/volumeId/iv 长度/底层 message 暴露给调用方。
 * 所有内部异常转换为统一的 FileNotFoundException（使用 stringResource）。
 * CancellationException 不吞，直接传播。
 */
class MediaContentProvider : ContentProvider() {
    private lateinit var storageManager: StorageManager
    private lateinit var ioThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate(): Boolean {
        storageManager = context!!.getSystemService(StorageManager::class.java)
        // IO 线程常驻（见类 KDoc 说明），ContentProvider 无 onDestroy。
        ioThread = HandlerThread("media-provider-io").also { it.start() }
        handler = Handler(ioThread.looper)
        return true
    }

    private fun ep(): MediaProviderEntryPoint =
        EntryPointAccessors.fromApplication(context!!, MediaProviderEntryPoint::class.java)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = try {
            MediaUris.idOf(uri)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(context!!.getString(R.string.media_error_invalid))
        }
        return try {
            safeOpenFile(uri, mode, id)
        } catch (e: CancellationException) {
            // 协程取消不得被转换成普通 Provider 错误。
            throw e
        } catch (_: Exception) {
            // 包括底层 FileNotFoundException：不得把其 message、URI、路径、
            // volumeId 或其他内部信息传播给 Provider 调用方。
            throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        }
    }

    /**
     * 安全打开文件，所有内部异常在 openFile 的外层捕获并转换。
     */
    private fun safeOpenFile(uri: Uri, mode: String, id: Long): ParcelFileDescriptor? {
        val store = ep().mediaStore()
        val entity = ep().appDatabase().videoItemDao().getByIdSync(id)
            ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        if (entity.volumeId != store.volumeId) {
            // 对外使用统一不可区分的错误消息，不泄漏卷标识（Log 仅供调试，不展示给用户）
            throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        }
        return if (MediaUris.isMedia(uri)) {
            // 显式分支断言：此分支仅处理 media URI，不依赖 iv 长度等隐式条件来路由
            MediaUris.requireMedia(uri)
            // iv 长度校验作为健壮性检查保留（H2 lane 保证 iv 为 8 字节）
            if (entity.iv.size != 8) throw FileNotFoundException(context!!.getString(R.string.media_error_invalid))
            // Handle ownership: openCiphertextRead allocates a resource that MUST be
            // closed if anything between here and a successful openProxyFileDescriptor
            // (which takes over ownership via the callback) throws. Wrap the whole
            // construct-and-open sequence so contentKey()/DecryptingFdCallback ctor
            // failures also release the handle.
            val handle = runBlocking(Dispatchers.IO) { store.openCiphertextRead(id) }
            try {
                val key = store.contentKey()
                val callback = DecryptingFdCallback(handle, key, entity.iv, entity.fileSize)
                storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
            } catch (e: CancellationException) {
                handle.close()
                throw e
            } catch (e: Exception) {
                handle.close()
                throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
            }
        } else if (MediaUris.isThumb(uri)) {
            MediaUris.requireThumb(uri)
            val bytes = runBlocking(Dispatchers.IO) { store.readThumb(id) }
                ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
            openPipeHelper(uri, mode, null, bytes) { output, _, _, extras, data ->
                FileOutputStream(output.fileDescriptor).use { it.write(data as ByteArray) }
            }
        } else {
            throw FileNotFoundException(context!!.getString(R.string.media_error_invalid))
        }
    }

    override fun getType(uri: Uri): String? {
        val id = try {
            MediaUris.idOf(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val store = ep().mediaStore()
            val entity = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return null
            // D2-a / M8: do not leak metadata for entities on inactive volumes.
            if (entity.volumeId != store.volumeId) return null
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
            val store = ep().mediaStore()
            val entity = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return empty
            // D2-a / M8: volumeId mismatch → empty Cursor (same non-disclosure as getType=null).
            if (entity.volumeId != store.volumeId) return empty
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
