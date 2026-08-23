package cn.vectory.syncplayer.sync.smb

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.RemoteFileClient
import cn.vectory.syncplayer.sync.RemoteScanIncompleteException
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.checkNetworkAvailable
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Properties

/**
 * Test seam only — production callers construct real [SmbFile] instances from
 * url + context (identical behavior).
 */
internal fun interface SmbFileFactory {
    fun create(url: String, context: jcifs.CIFSContext): SmbFile
}

class SmbFileClient internal constructor(
    private val context: Context,
    private val smbFileFactory: SmbFileFactory
) : RemoteFileClient {

    constructor(context: Context) : this(
        context,
        SmbFileFactory { url, ctx -> SmbFile(url, ctx) }
    )

    private companion object {
        const val TAG = "SmbFileClient"
        const val MAX_REMOTE_FILES = 10000
    }

    private var cifsContext: jcifs.CIFSContext? = null
    private var currentHost: String = ""
    private var shareName: String = ""
    private var subPath: String = ""

    /**
     * Classify a jcifs/stdlib exception into a [SyncErrorCode] by inspecting exception
     * TYPE plus stable English status keywords emitted by jcifs — never by parsing
     * Chinese prose (these English phrases are jcifs-internal classification keys, not
     * user-facing display text).
     */
    private fun classifySmbException(e: Throwable): SyncErrorCode = when (e) {
        is SmbAuthException -> SyncErrorCode.AUTHENTICATION_FAILED
        is SmbException -> {
            val msg = e.message.orEmpty().lowercase()
            when {
                msg.contains("bad network name") ||
                    msg.contains("no such file") ||
                    msg.contains("not found") -> SyncErrorCode.PATH_NOT_FOUND
                msg.contains("access denied") -> SyncErrorCode.ACCESS_DENIED
                msg.contains("logon failure") -> SyncErrorCode.AUTHENTICATION_FAILED
                else -> SyncErrorCode.UNKNOWN
            }
        }
        is java.net.SocketTimeoutException -> SyncErrorCode.TIMEOUT
        is java.net.UnknownHostException -> SyncErrorCode.UNKNOWN_HOST
        is java.net.ConnectException -> SyncErrorCode.CONNECTION_REFUSED
        else -> SyncErrorCode.UNKNOWN
    }

    /** Session state produced by a fully successful handshake, published only on success. */
    private data class SmbSession(
        val cifsContext: jcifs.CIFSContext,
        val host: String,
        val shareName: String,
        val subPath: String
    )

    override suspend fun connect(source: SyncSource, remotePath: String) {
        // Cancellation reaches connect() in two shapes that BOTH need cleanup:
        //  (A) an underlying jcifs call throws CancellationException directly, or
        //  (B) the coroutine is cancelled while a jcifs call blocks, the call then
        //      completes NORMALLY, and withContext throws prompt CancellationException
        //      at its return boundary — a shape no catch INSIDE the lambda can observe.
        // Therefore the whole withContext is wrapped by an OUTER try/catch, and session
        // fields are published only AFTER withContext returns normally.
        try {
            val session = withContext(Dispatchers.IO) {
                checkNetworkAvailable(context)
                val props = Properties()
                props.setProperty("jcifs.smb.client.minVersion", "SMB202")
                props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
                val timeoutMs = "5000"
                props.setProperty("jcifs.smb.client.responseTimeout", timeoutMs)
                props.setProperty("jcifs.smb.client.soTimeout", timeoutMs)
                val bc = BaseContext(jcifs.config.PropertyConfiguration(props))
                val authenticator = NtlmPasswordAuthenticator(null, source.username, source.password)
                val creds = bc.withCredentials(authenticator)
                val trimmedPath = remotePath.trim('/').ifEmpty { "/" }
                if (trimmedPath == "/") {
                    smbFileFactory.create("smb://${source.host}/", creds).listFiles()
                    SmbSession(creds, source.host, "", "")
                } else {
                    val pathParts = trimmedPath.split("/")
                    val shareName = pathParts.firstOrNull() ?: ""
                    val subPath = pathParts.drop(1).joinToString("/")
                    val smbUrl = if (subPath.isNotEmpty()) {
                        "smb://${source.host}/$shareName/$subPath/"
                    } else {
                        "smb://${source.host}/$shareName/"
                    }
                    smbFileFactory.create(smbUrl, creds).listFiles()
                    SmbSession(creds, source.host, shareName, subPath)
                }
            }
            cifsContext = session.cifsContext
            currentHost = session.host
            shareName = session.shareName
            subPath = session.subPath
        } catch (e: CancellationException) {
            // Covers both (A) and (B): discard the session so browsing terminates with
            // NOT_CONNECTED instead of continuing factory I/O on a cancelled session.
            cifsContext = null
            throw e
        } catch (e: SyncException) {
            // Pre-classified failures (e.g. NETWORK_UNAVAILABLE) propagate unwrapped.
            cifsContext = null
            throw e
        } catch (e: Exception) {
            cifsContext = null
            // CQ-001: 协程已取消时底层调用以普通异常收场 —— 不得包装成 SyncException
            // （否则调用方的取消守卫被绕过，取消被误报为连接失败）。
            currentCoroutineContext().ensureActive()
            throw SyncException(classifySmbException(e), SyncOperation.CONNECT, host = source.host, cause = e)
        }
    }

    override suspend fun disconnect() {
        cifsContext = null
    }

    override suspend fun close() {
        disconnect()
    }

    override suspend fun testConnection(source: SyncSource, remotePath: String): Boolean {
        try {
            connect(source, remotePath)
            disconnect()
            return true
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    override suspend fun listFiles(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.BROWSE)
        val result = mutableListOf<RemoteFile>()
        val pathParts = path.trim('/').split("/")
        val share = pathParts.firstOrNull() ?: ""
        val remoteSubPath = pathParts.drop(1).joinToString("/")
        val smbUrl = if (remoteSubPath.isNotEmpty()) {
            "smb://$currentHost/$share/$remoteSubPath/"
        } else {
            "smb://$currentHost/$share/"
        }
        val dir = smbFileFactory.create(smbUrl, ctx)
        if (!dir.exists() || !dir.isDirectory) throw SyncException(
            SyncErrorCode.PATH_NOT_FOUND, SyncOperation.BROWSE, path = path
        )
        listFilesRecursive(ctx, dir, share, remoteSubPath, result)
        result
    }

    suspend fun listShares(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.BROWSE)
        val result = mutableListOf<RemoteFile>()
        val smbFile = smbFileFactory.create("smb://$currentHost/", ctx)
        val shares = try {
            smbFile.listFiles() ?: return@withContext result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不降级为空列表。
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to list SMB shares", e)
            return@withContext result
        }
        for (share in shares) {
            try {
                if (share.isDirectory) {
                    val name = share.name.trimEnd('/')
                    result.add(
                        RemoteFile(
                            path = name,
                            name = name,
                            isDirectory = true,
                            size = 0,
                            lastModified = share.lastModified
                        )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不再 continue 访问后续 share 条目。
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to process SMB share entry", e)
        }
        }
        result
    }

    override suspend fun listDirectories(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.BROWSE)

        val trimmedPath = path.trim('/')
        if (trimmedPath.isEmpty()) {
            return@withContext listShares()
        }

        val result = mutableListOf<RemoteFile>()
        val pathParts = trimmedPath.split("/")
        val share = pathParts.firstOrNull() ?: ""
        val remoteSubPath = pathParts.drop(1).joinToString("/")
        val smbUrl = if (remoteSubPath.isNotEmpty()) {
            "smb://$currentHost/$share/$remoteSubPath/"
        } else {
            "smb://$currentHost/$share/"
        }
        val dir = smbFileFactory.create(smbUrl, ctx)
        if (!dir.exists() || !dir.isDirectory) throw SyncException(
            SyncErrorCode.PATH_NOT_FOUND, SyncOperation.BROWSE, path = path
        )
        val children = try {
            dir.listFiles() ?: return@withContext result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不降级为空列表。
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to list SMB directory: $path", e)
            return@withContext result
        }
        for (child in children) {
            try {
                if (child.isDirectory) {
                    val relPath = extractRelativePath(child.url.toString(), share, remoteSubPath)
                    result.add(
                        RemoteFile(
                            path = relPath,
                            name = child.name,
                            isDirectory = true,
                            size = child.length(),
                            lastModified = child.lastModified
                        )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不再 continue 访问后续目录条目。
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to process SMB directory entry", e)
        }
        }
        result
    }

    private fun listFilesRecursive(
        ctx: jcifs.CIFSContext,
        dir: SmbFile,
        share: String,
        remoteBasePath: String,
        result: MutableList<RemoteFile>,
        maxFiles: Int = MAX_REMOTE_FILES
    ) {
        // E8: 达到上限必须失败（而非静默截断），否则 dryRun 会基于残缺快照生成误删。
        if (result.size >= maxFiles) {
            throw RemoteScanIncompleteException(maxFiles = maxFiles)
        }
        // E8 (a): 列目录异常直接上抛，不得吞掉（原子树静默丢失会触发误删）。
        // listFiles() 返回 null 同样视为不可恢复的不完整。
        val children = dir.listFiles() ?: throw RemoteScanIncompleteException(remotePath = dir.path)

        for (child in children) {
            if (result.size >= maxFiles) {
                throw RemoteScanIncompleteException(maxFiles = maxFiles)
            }
            // E8 (c): 单条目属性读取异常（length/lastModified/isDirectory 等）直接上抛，
            // 不再 Log.w 后跳过——静默跳过单文件同样会产生残缺快照。
            if (child.isDirectory) {
                listFilesRecursive(ctx, child, share, remoteBasePath, result, maxFiles)
            } else {
                val relPath = extractRelativePath(child.url.toString(), share, remoteBasePath)
                result.add(
                    RemoteFile(
                        path = relPath,
                        name = child.name,
                        isDirectory = false,
                        size = child.length(),
                        lastModified = child.lastModified
                    )
                )
            }
        }
    }

    private fun extractRelativePath(smbUrl: String, share: String, remoteBasePath: String): String {
        val marker = if (remoteBasePath.isNotEmpty()) {
            "/$share/$remoteBasePath/"
        } else {
            "/$share/"
        }
        val idx = smbUrl.indexOf(marker)
        if (idx < 0) return smbUrl.substringAfterLast('/')
        return smbUrl.substring(idx + marker.length).trimEnd('/')
    }

    override suspend fun downloadTo(
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: ((transferred: Long, total: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.DOWNLOAD)
        try {
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://$currentHost/$shareName/$subPath/$remotePath"
            } else {
                "smb://$currentHost/$shareName/$remotePath"
            }
            val smbFile = smbFileFactory.create(smbUrl, ctx)
            if (!smbFile.exists()) throw SyncException(
                SyncErrorCode.PATH_NOT_FOUND, SyncOperation.DOWNLOAD, path = remotePath
            )
            val totalSize = smbFile.length()
            val buffer = ByteArray(64 * 1024)
            var transferred = 0L
            smbFile.getInputStream().use { input ->
                var read: Int
                var zeroReadRetries = 0
                while (true) {
                    read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) {
                        zeroReadRetries++
                        if (zeroReadRetries > 5) {
                            throw SyncException(
                                SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD,
                                path = remotePath, cause = java.io.IOException("SMB stream stalled (0 bytes repeated)")
                            )
                        }
                        // Brief yield to avoid busy spin
                        kotlinx.coroutines.yield()
                        continue
                    }
                    zeroReadRetries = 0
                    sink.write(buffer, 0, read)
                    transferred += read
                    onProgress?.invoke(transferred, totalSize)
                    currentCoroutineContext().ensureActive()
                }
                if (transferred < totalSize) {
                    throw SyncException(
                        SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD,
                        path = remotePath, expectedBytes = totalSize, actualBytes = transferred,
                        cause = java.io.IOException("SMB download truncated")
                    )
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不进入错误重分类/包装。
            currentCoroutineContext().ensureActive()
            // Re-classify the underlying cause (e.g. SmbAuthException → AUTHENTICATION_FAILED)
            // so the user gets an accurate message; default to DOWNLOAD_FAILED for download scope.
            val code = classifySmbException(e).let {
                if (it == SyncErrorCode.UNKNOWN) SyncErrorCode.DOWNLOAD_FAILED else it
            }
            throw SyncException(code, SyncOperation.DOWNLOAD, path = remotePath, cause = e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.DELETE)
        try {
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://$currentHost/$shareName/$subPath/$remotePath"
            } else {
                "smb://$currentHost/$shareName/$remotePath"
            }
            val smbFile = smbFileFactory.create(smbUrl, ctx)
            if (!smbFile.exists()) return@withContext false
            smbFile.delete()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CQ-001: 已取消则终结为 CE，不包装成 REMOTE_DELETE_FAILED。
            currentCoroutineContext().ensureActive()
            throw SyncException(
                SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE,
                path = remotePath, cause = e
            )
        }
    }
}
