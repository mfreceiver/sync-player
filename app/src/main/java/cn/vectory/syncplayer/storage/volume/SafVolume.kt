package cn.vectory.syncplayer.storage.volume

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafVolume(
    private val context: Context,
    val treeUri: Uri,
) : MediaVolume {
    override val volumeId: String = "saf-" + treeUri.normalizeScheme().toString()

    /** 从 treeUri 中取出根 documentId。 */
    private val rootDocId: String by lazy { DocumentsContract.getTreeDocumentId(treeUri) }

    /**
     * 将相对路径 [relPath] 解析为目标文件的 documentId。
     * 若路径中某一段目录不存在则返回 null。
     */
    private suspend fun resolveDocId(relPath: String): String? {
        if (relPath.isEmpty() || relPath == "/") return rootDocId
        var docId = rootDocId
        for (segment in relPath.split('/').filter { it.isNotEmpty() }) {
            val child = findChildDocId(docId, segment) ?: return null
            docId = child
        }
        return docId
    }

    /** 在 [parentDocId] 的子文档中查找名为 [displayName] 的文档，返回其 docId。 */
    private suspend fun findChildDocId(parentDocId: String, displayName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                if (name == displayName) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                }
            }
        }
        return null
    }

    /** 确保 [dirRelPath] 所有目录段存在，返回最终的 docId。 */
    private suspend fun ensureDirectories(dirRelPath: String): String {
        if (dirRelPath.isEmpty() || dirRelPath == "/") return rootDocId
        var docId = rootDocId
        for (segment in dirRelPath.split('/').filter { it.isNotEmpty() }) {
            val child = findChildDocId(docId, segment)
            if (child != null) {
                docId = child
            } else {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val newDoc = DocumentsContract.createDocument(
                    context.contentResolver, parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR, segment
                ) ?: throw java.io.IOException("cannot create directory $segment")
                docId = DocumentsContract.getDocumentId(newDoc)
            }
        }
        return docId
    }

    override suspend fun openWrite(relPath: String): OutputStream = withContext(Dispatchers.IO) {
        val dirPath = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val parentDocId = ensureDirectories(dirPath)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        // 删除已存在同名文件
        val existing = findChildDocId(parentDocId, name)
        if (existing != null) {
            context.contentResolver.delete(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existing), null
            )
        }
        // 创建新文件（使用 MIME type）
        val newDocUri = DocumentsContract.createDocument(
            context.contentResolver, parentUri, "application/octet-stream", name
        ) ?: throw java.io.IOException("cannot create document $relPath")
        context.contentResolver.openOutputStream(newDocUri) ?: throw java.io.IOException("cannot open output $relPath")
    }

    override suspend fun openRead(relPath: String): SeekableReadHandle = withContext(Dispatchers.IO) {
        val docId = resolveDocId(relPath) ?: throw java.io.FileNotFoundException("document not found: $relPath")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("cannot open pfd $relPath")
        val fd = pfd.fileDescriptor
        // Verify the fd is seekable by probing lseek
        try {
            android.system.Os.lseek(fd, 0L, android.system.OsConstants.SEEK_CUR)
        } catch (e: android.system.ErrnoException) {
            pfd.close()
            throw UnsupportedOperationException("SAF document is not seekable: $relPath")
        }
        object : SeekableReadHandle {
            override fun seek(position: Long) {
                android.system.Os.lseek(fd, position, android.system.OsConstants.SEEK_SET)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                android.system.Os.read(fd, buffer, offset, length)
            override fun size(): Long = pfd.statSize
            override fun close() { pfd.close() }
        }
    }

    override suspend fun list(dirRelPath: String): List<String> = withContext(Dispatchers.IO) {
        val docId = resolveDocId(dirRelPath) ?: return@withContext emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val names = mutableListOf<String>()
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: continue
                names.add(name)
            }
        }
        names
    }

    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        val docId = resolveDocId(relPath) ?: return@withContext false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        try {
            context.contentResolver.delete(uri, null) > 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { false }
    }

    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        resolveDocId(relPath) != null
    }

    override suspend fun mkdirs(dirRelPath: String): Unit = withContext(Dispatchers.IO) {
        ensureDirectories(dirRelPath)
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fromDocId = resolveDocId(from) ?: return@withContext false
            val fromUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fromDocId)
            val toName = to.substringAfterLast('/')
            val toDirPath = to.substringBeforeLast('/', "")
            val fromParentDocId = resolveDocId(from.substringBeforeLast('/', "")) ?: return@withContext false
            val toParentDocId = resolveDocId(toDirPath) ?: return@withContext false
            // 仅支持同目录重命名（父目录 docId 相等）
            if (fromParentDocId != toParentDocId) return@withContext false
            val newUri = DocumentsContract.renameDocument(context.contentResolver, fromUri, toName) ?: return@withContext false
            // 确认新文件存在且源文件已消失
            isDocumentExists(newUri) && !isDocumentExists(fromUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 某些 SAF provider 不支持 rename（如外置 SD 卡/OTG 的某些实现）会抛
            // UnsupportedOperationException；归一化为 false 让调用方走 FINALIZE_FAILED，
            // 而不是让裸 UOE 传出。
            false
        }
    }

    /**
     * 可用空间（字节）。
     *
     * 解析 [treeUri] 的 document ID 中的卷标识符（"primary" 或 UUID），
     * 匹配到对应的 [StorageVolume] 后通过 [StatFs] 读取。
     *
     * @return 可用字节数，或 **-1L** 表示「无法确定」（卷未挂载 / 无目录 / 异常）。
     *         调用方（[EncryptedMediaStore.usableSpace] → SyncEngine 空间预检、
     *         SettingsViewModel 展示）需区分此哨兵值与「充足」——SyncEngine
     *         遇 -1L 会判定空间不足并终止同步（保守安全策略）。
     */
    override fun usableSpace(): Long = try {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volId = rootDocId.substringBefore(':')
        val storageVolume: StorageVolume? = if (volId.equals("primary", ignoreCase = true)) {
            sm.storageVolumes.firstOrNull { it.isPrimary }
        } else {
            sm.storageVolumes.firstOrNull { volId.equals(it.uuid, ignoreCase = true) }
        }
        val dir = storageVolume?.directory ?: return -1L
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) { -1L }

    /** 验证 URI 对应的文档是否存在。 */
    private fun isDocumentExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }
}
