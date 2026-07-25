package cn.vectory.syncplayer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import cn.vectory.syncplayer.crypto.DecryptingFdCallback
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaProviderEntryPoint {
    fun appDatabase(): AppDatabase
    fun mediaStore(): EncryptedMediaStore
}

class MediaContentProvider : ContentProvider() {
    private lateinit var storageManager: StorageManager
    private lateinit var ioThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate(): Boolean {
        storageManager = context!!.getSystemService(StorageManager::class.java)
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
            throw FileNotFoundException("invalid uri: $uri")
        }
        val store = ep().mediaStore()
        val entity = ep().appDatabase().videoItemDao().getByIdSync(id)
            ?: throw FileNotFoundException("media id=$id not found")
        if (entity.volumeId != store.volumeId) {
            throw FileNotFoundException(
                "media id=$id belongs to volume=${entity.volumeId}, active=${store.volumeId}"
            )
        }
        return if (MediaUris.isMedia(uri)) {
            if (entity.iv.size != 8) throw FileNotFoundException("invalid iv for media id=$id: ${entity.iv.size}")
            val handle = runBlocking(Dispatchers.IO) { store.openCiphertextRead(id) }
            val key = store.contentKey()
            val callback = DecryptingFdCallback(handle, key, entity.iv, entity.fileSize)
            try {
                storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
            } catch (e: Exception) {
                handle.close()
                throw e
            }
        } else if (MediaUris.isThumb(uri)) {
            val bytes = runBlocking(Dispatchers.IO) { store.readThumb(id) } ?: throw FileNotFoundException("no thumbnail for id=$id")
            openPipeHelper(uri, mode, null, bytes) { output, _, _, extras, data ->
                FileOutputStream(output.fileDescriptor).use { it.write(data as ByteArray) }
            }
        } else {
            throw FileNotFoundException("unknown uri: $uri")
        }
    }

    override fun getType(uri: Uri): String? {
        val id = try {
            MediaUris.idOf(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val store = ep().mediaStore()
        val entity = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return null
        // D2-a / M8: do not leak metadata for entities on inactive volumes.
        if (entity.volumeId != store.volumeId) return null
        return getMimeType(entity.fileName)
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
        return cur
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
