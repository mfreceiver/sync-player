package cn.vectory.syncplayer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

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
            throw FileNotFoundException(context!!.getString(R.string.media_error_invalid))
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
     * 文件均为明文本地文件，直接以只读 PFD 暴露。
     */
    private fun safeOpenFile(uri: Uri, id: Long): ParcelFileDescriptor {
        val store = ep().mediaFileStore()
        ep().appDatabase().videoItemDao().getByIdSync(id)
            ?: throw FileNotFoundException(context!!.getString(R.string.media_error_not_found))
        return if (MediaUris.isMedia(uri)) {
            MediaUris.requireMedia(uri)
            ParcelFileDescriptor.open(store.mediaFile(id), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            MediaUris.requireThumb(uri)
            ParcelFileDescriptor.open(store.thumbFile(id), ParcelFileDescriptor.MODE_READ_ONLY)
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
