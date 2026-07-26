package cn.vectory.syncplayer.sync.sftp

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SftpFileClient(
    private val context: Context
) : RemoteFileClient {

    private companion object {
        const val TAG = "SftpFileClient"
        // 与 SmbFileClient 保持一致；超出即视为扫描不完整而失败（见 RemoteScanIncompleteException）。
        const val MAX_REMOTE_FILES = 10000
    }

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var remoteBasePath: String = ""

    /**
     * Classify an sshj/stdlib exception into a [SyncErrorCode] by exception TYPE.
     * Never parses prose message text for classification.
     */
    private fun classifySftpException(e: Throwable): SyncErrorCode = when (e) {
        is UserAuthException -> SyncErrorCode.AUTHENTICATION_FAILED
        is net.schmizz.sshj.transport.TransportException -> {
            // TransportException covers most connection-level SFTP failures.
            val cause = e.cause
            if (cause is SocketTimeoutException || cause is UnknownHostException) {
                SyncErrorCode.TIMEOUT
            } else {
                SyncErrorCode.UNKNOWN
            }
        }
        is SocketTimeoutException -> SyncErrorCode.TIMEOUT
        is UnknownHostException -> SyncErrorCode.UNKNOWN_HOST
        is ConnectException -> SyncErrorCode.CONNECTION_REFUSED
        is net.schmizz.sshj.connection.ConnectionException -> SyncErrorCode.UNKNOWN
        else -> SyncErrorCode.UNKNOWN
    }

    override suspend fun connect(source: SyncSource, remotePath: String): Unit = withContext(Dispatchers.IO) {
        checkNetworkAvailable(context)
        // E4: `client` 必须是局部变量并在失败时显式 disconnect。
        // 旧实现只把字段置 null —— 若 connect() 已成功但随后的 authPassword()/newSFTPClient()
        // 抛异常，那条已建立的 SSH 连接就再无引用可关，造成连接泄漏（同步重试会持续累积）。
        var client: SSHClient? = null
        try {
            client = SSHClient(net.schmizz.sshj.DefaultSecurityProviderConfig())
            client.addHostKeyVerifier(SyncPlayerHostKeyVerifier(context))
            val timeoutMs = 5000
            client.setConnectTimeout(timeoutMs)
            client.setTimeout(timeoutMs)
            client.connect(source.host, source.port)
            client.authPassword(source.username, source.password)
            val sftp = client.newSFTPClient()
            sshClient = client
            sftpClient = sftp
            remoteBasePath = remotePath.trim()
        } catch (e: Exception) {
            try { client?.disconnect() } catch (closeError: Exception) {
                Log.w(TAG, "Error closing partially-established SFTP connection", closeError)
            }
            sshClient = null
            sftpClient = null
            throw SyncException(classifySftpException(e), SyncOperation.CONNECT, host = source.host, cause = e)
        }
    }

    override suspend fun disconnect() {
        try {
            sftpClient?.close()
            sshClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SFTP connection", e)
        }
        sftpClient = null
        sshClient = null
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
        val sftp = sftpClient ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.BROWSE)
        val result = mutableListOf<RemoteFile>()
        listFilesRecursive(sftp, path, path, result)
        result
    }

    override suspend fun listDirectories(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.BROWSE)
        val result = mutableListOf<RemoteFile>()
        val entries = try {
            sftp.ls(path)
        } catch (e: Exception) {
            return@withContext result
        }
        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            if (entry.isDirectory) {
                val fullPath = if (path.endsWith("/")) "$path${entry.name}" else "$path/${entry.name}"
                result.add(
                    RemoteFile(
                        path = fullPath.removePrefix(path).removePrefix("/"),
                        name = entry.name,
                        isDirectory = true,
                        size = entry.attributes.size,
                        lastModified = entry.attributes.mtime * 1000L
                    )
                )
            }
        }
        result
    }

    private fun listFilesRecursive(
        sftp: SFTPClient,
        currentPath: String,
        rootPath: String,
        result: MutableList<RemoteFile>,
        maxFiles: Int = MAX_REMOTE_FILES
    ) {
        // E8: 达到上限必须失败（而非静默截断），否则 dryRun 会基于残缺快照生成误删。
        if (result.size >= maxFiles) {
            throw RemoteScanIncompleteException(maxFiles = maxFiles)
        }
        // E8 (a): 列目录异常直接上抛，不得 catch 后 return（原子树静默丢失会触发误删）。
        val entries = sftp.ls(currentPath)
        for (entry in entries) {
            if (result.size >= maxFiles) {
                throw RemoteScanIncompleteException(maxFiles = maxFiles)
            }
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = if (currentPath.endsWith("/")) {
                "$currentPath${entry.name}"
            } else {
                "$currentPath/${entry.name}"
            }
            if (entry.isDirectory) {
                listFilesRecursive(sftp, fullPath, rootPath, result, maxFiles)
            } else if (entry.isRegularFile) {
                val relPath = fullPath.removePrefix(rootPath).removePrefix("/")
                result.add(
                    RemoteFile(
                        path = relPath,
                        name = entry.name,
                        isDirectory = false,
                        size = entry.attributes.size,
                        lastModified = entry.attributes.mtime * 1000L
                    )
                )
            }
        }
    }

    override suspend fun downloadTo(
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: ((transferred: Long, total: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.DOWNLOAD)
        try {
            val fullRemotePath = if (remoteBasePath.isNotEmpty()) "$remoteBasePath/$remotePath" else remotePath
            val attrs = sftp.stat(fullRemotePath)
            val totalSize = attrs.size
            val buffer = ByteArray(64 * 1024)
            var transferred = 0L
            sftp.open(fullRemotePath).use { rf ->
                var offset = 0L
                var zeroReadRetries = 0
                while (offset < totalSize) {
                    val len = minOf(buffer.size.toLong(), totalSize - offset).toInt()
                    val bytesRead = rf.read(offset, buffer, 0, len)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) {
                        zeroReadRetries++
                        if (zeroReadRetries > 5) {
                            throw SyncException(
                                SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD,
                                path = remotePath, cause = java.io.IOException("SFTP stream stalled (0 bytes repeated)")
                            )
                        }
                        // Brief yield to avoid busy spin
                        kotlinx.coroutines.yield()
                        continue
                    }
                    zeroReadRetries = 0
                    sink.write(buffer, 0, bytesRead)
                    offset += bytesRead
                    transferred += bytesRead
                    onProgress?.invoke(transferred, totalSize)
                    // Cooperative cancellation check after each chunk read
                    currentCoroutineContext().ensureActive()
                }
                if (offset < totalSize) {
                    throw SyncException(
                        SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD,
                        path = remotePath, expectedBytes = totalSize, actualBytes = offset,
                        cause = java.io.IOException("SFTP download truncated")
                    )
                }
            }
            true
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD, path = remotePath, cause = e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.DELETE)
        try {
            val fullRemotePath = if (remoteBasePath.isNotEmpty()) "$remoteBasePath/$remotePath" else remotePath
            sftp.rm(fullRemotePath)
            true
        } catch (e: Exception) {
            throw SyncException(
                SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE,
                path = remotePath, cause = e
            )
        }
    }
}
