package cn.vectory.syncplayer.provider

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.database.Cursor
import android.database.MatrixCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import android.graphics.Point
import android.content.res.AssetFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.storage.StoragePaths
import java.lang.IllegalArgumentException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** SAF DocumentsProvider exposing synced video files to third-party players. */
class SyncDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun videoItemDao(): VideoItemDao
        fun syncRelationDao(): SyncRelationDao
        fun storagePaths(): StoragePaths
    }

    private fun authority(c: Context): String =
        "${c.packageName}.documents"

    private fun entryPoint(c: Context): ProviderEntryPoint =
        EntryPointAccessors.fromApplication(c.applicationContext, ProviderEntryPoint::class.java)

    private fun videoItemDao(c: Context): VideoItemDao = entryPoint(c).videoItemDao()
    private fun syncRelationDao(c: Context): SyncRelationDao = entryPoint(c).syncRelationDao()
    private fun storagePaths(c: Context): StoragePaths = entryPoint(c).storagePaths()

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor? {
        val ctx = context ?: return MatrixCursor(arrayOf(), 0)
        val projectionUsed = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(projectionUsed)

        val rootDocId = "root"
        val icon = ctx.applicationInfo.icon
        val flags = DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH

        val row = ArrayList<Any?>(projectionUsed.size)
        for (col in projectionUsed) {
            when (col) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(rootDocId)
                DocumentsContract.Root.COLUMN_FLAGS -> row.add(flags)
                DocumentsContract.Root.COLUMN_ICON -> row.add(icon)
                DocumentsContract.Root.COLUMN_TITLE -> row.add("SyncPlayer")
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add(rootDocId)
                DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add("video/*")
                else -> row.add(null)
            }
        }
        cursor.addRow(row.toArray())
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildRootsUri(authority(ctx)))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor? {
        val ctx = context ?: return null
        val projectionUsed = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(projectionUsed)

        when (val id = DocumentId.decode(documentId)) {
            DocumentId.Root -> {
                val mime = DocumentsContract.Document.MIME_TYPE_DIR
                val name = "SyncPlayer"
                val row = ArrayList<Any?>(projectionUsed.size)
                for (col in projectionUsed) {
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add("root")
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(name)
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(null)
                        DocumentsContract.Document.COLUMN_FLAGS -> row.add(null)
                        DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                        else -> row.add(null)
                    }
                }
                cursor.addRow(row.toArray())
            }
            is DocumentId.Relation -> {
                val rel = runBlocking(Dispatchers.IO) { syncRelationDao(ctx).getById(id.relationId) }
                val mime = DocumentsContract.Document.MIME_TYPE_DIR
                val display = rel?.name ?: "Relation"
                val lastMod = rel?.lastSyncTime ?: 0
                val docId = DocumentId.encode(id)
                val row = ArrayList<Any?>(projectionUsed.size)
                for (col in projectionUsed) {
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(display)
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(lastMod)
                        DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                        DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                        else -> row.add(null)
                    }
                }
                cursor.addRow(row.toArray())
            }
            is DocumentId.Directory -> {
                val rel = runBlocking(Dispatchers.IO) { syncRelationDao(ctx).getById(id.relationId) }
                val mimeDir = DocumentsContract.Document.MIME_TYPE_DIR
                val displayName = if (id.relativePath.isEmpty()) rel?.name ?: "Directory" else id.relativePath.substringAfterLast('/')
                val lastMod = rel?.lastSyncTime ?: 0
                val docId = DocumentId.encode(id)
                val row = ArrayList<Any?>(projectionUsed.size)
                for (col in projectionUsed) {
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mimeDir)
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(lastMod)
                        DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                        DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                        else -> row.add(null)
                    }
                }
                cursor.addRow(row.toArray())
            }
            is DocumentId.Video -> {
                val video = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getById(id.videoItemId) }
                if (video == null) return null
                val mime = getMimeType(video.fileName)
                val docId = DocumentId.encode(id)
                val row = ArrayList<Any?>(projectionUsed.size)
                for (col in projectionUsed) {
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(video.fileName)
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(video.remoteModifiedTime ?: video.dateSynced)
                        DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                        DocumentsContract.Document.COLUMN_SIZE -> row.add(video.fileSize)
                        else -> row.add(null)
                    }
                }
                cursor.addRow(row.toArray())
            }
        }
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildDocumentUri(authority(ctx), documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val projectionUsed = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(projectionUsed)
        when (val parent = DocumentId.decode(parentDocumentId)) {
            DocumentId.Root -> {
                val relations = runBlocking(Dispatchers.IO) { syncRelationDao(ctx).getAll() }
                for (rel in relations) {
                    val id = DocumentId.Relation(rel.id)
                    val docId = DocumentId.encode(id)
                    val name = rel.name
                    val lastMod = rel.lastSyncTime ?: 0
                    val row = ArrayList<Any?>(projectionUsed.size)
                    for (col in projectionUsed) {
                        when (col) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(DocumentsContract.Document.MIME_TYPE_DIR)
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(name)
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(lastMod)
                            DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                            DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                            else -> row.add(null)
                        }
                    }
                    cursor.addRow(row.toArray())
                }
            }
            is DocumentId.Relation -> {
                val rel = runBlocking(Dispatchers.IO) { syncRelationDao(ctx).getById(parent.relationId) }
                if (rel != null) {
                    val basePath = rel.localBasePath
                    val items = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getItemsInFolder(basePath).first() }
                    val subFolders = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getSubFolders(basePath).first() }
                    // subfolders
                    for (sub in subFolders) {
                        val id = DocumentId.Directory(rel.id, sub)
                        val docId = DocumentId.encode(id)
                        val displayName = sub.substring(sub.lastIndexOf('/') + 1)
                        val row = ArrayList<Any?>(projectionUsed.size)
                        for (col in projectionUsed) {
                            when (col) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(DocumentsContract.Document.MIME_TYPE_DIR)
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(rel.lastSyncTime ?: 0)
                                DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                                DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                                else -> row.add(null)
                            }
                        }
                        cursor.addRow(row.toArray())
                    }
                    // videos
                    for (video in items) {
                        val id = DocumentId.Video(video.id)
                        val docId = DocumentId.encode(id)
                        val mime = getMimeType(video.fileName)
                        val row = ArrayList<Any?>(projectionUsed.size)
                        for (col in projectionUsed) {
                            when (col) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(video.fileName)
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(video.remoteModifiedTime ?: video.dateSynced)
                                DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                                DocumentsContract.Document.COLUMN_SIZE -> row.add(video.fileSize)
                                else -> row.add(null)
                            }
                        }
                        cursor.addRow(row.toArray())
                    }
                }
            }
            is DocumentId.Directory -> {
                val rel = runBlocking(Dispatchers.IO) { syncRelationDao(ctx).getById(parent.relationId) }
                if (rel != null) {
                    val basePath = if (parent.relativePath.isEmpty()) rel.localBasePath else rel.localBasePath + "/" + parent.relativePath
                    val items = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getItemsInFolder(basePath).first() }
                    val subFolders = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getSubFolders(basePath).first() }
                    for (sub in subFolders) {
                        val id = DocumentId.Directory(rel.id, sub)
                        val docId = DocumentId.encode(id)
                        val displayName = sub.substring(sub.lastIndexOf('/') + 1)
                        val row = ArrayList<Any?>(projectionUsed.size)
                        for (col in projectionUsed) {
                            when (col) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(DocumentsContract.Document.MIME_TYPE_DIR)
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(rel.lastSyncTime ?: 0)
                                DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                                DocumentsContract.Document.COLUMN_SIZE -> row.add(null)
                                else -> row.add(null)
                            }
                        }
                        cursor.addRow(row.toArray())
                    }
                    for (video in items) {
                        val vid = DocumentId.Video(video.id)
                        val docId = DocumentId.encode(vid)
                        val mime = getMimeType(video.fileName)
                        val row = ArrayList<Any?>(projectionUsed.size)
                        for (col in projectionUsed) {
                            when (col) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(video.fileName)
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(video.remoteModifiedTime ?: video.dateSynced)
                                DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                                DocumentsContract.Document.COLUMN_SIZE -> row.add(video.fileSize)
                                else -> row.add(null)
                            }
                        }
                        cursor.addRow(row.toArray())
                    }
                }
            }
            is DocumentId.Video -> {}
        }
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(ctx), parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException("Provider not initialized")
        val id = DocumentId.decode(documentId)
        val videoId = when (id) {
            is DocumentId.Video -> id.videoItemId
            else -> throw FileNotFoundException("Unsupported document for openDocument: $documentId")
        }
        val video = runBlocking(Dispatchers.IO) { videoItemDao(ctx).getById(videoId) } ?: throw FileNotFoundException("Video not found: $videoId")
        val file = File(video.localAbsolutePath)
        if (!file.exists()) throw FileNotFoundException("File does not exist: ${video.localAbsolutePath}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor? {
        return null
    }

    override fun querySearchDocuments(rootId: String?, query: String, projection: Array<out String>?): Cursor? {
        val ctx = context ?: return null
        val projectionUsed = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(projectionUsed)
        val results = runBlocking(Dispatchers.IO) { videoItemDao(ctx).searchByFileName(query).first() }
        for (video in results) {
            val docId = DocumentId.encode(DocumentId.Video(video.id))
            val mime = getMimeType(video.fileName)
            val row = ArrayList<Any?>(projectionUsed.size)
            for (col in projectionUsed) {
                when (col) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(video.fileName)
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(video.remoteModifiedTime ?: video.dateSynced)
                    DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                    DocumentsContract.Document.COLUMN_SIZE -> row.add(video.fileSize)
                    else -> row.add(null)
                }
            }
            cursor.addRow(row.toArray())
        }
        return cursor
    }

    // Helpers
    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-avi"
            "mov", "qt" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ts" -> "video/mp2t"
            "3gp", "3g2" -> "video/3gpp"
            "mpeg", "mpg" -> "video/mpeg"
            else -> "video/*"
        }
    }
}
