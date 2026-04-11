package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import android.util.Log
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.RemoteFileClient
import cn.vectory.syncplayer.sync.checkNetworkAvailable
import cn.vectory.syncplayer.sync.mapNetworkErrorMessage
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.File
import java.io.FileOutputStream

class SftpFileClient(
    private val context: Context,
    private val userPrefs: UserPreferencesRepository
) : RemoteFileClient {

    private companion object { const val TAG = "SftpFileClient" }

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var remoteBasePath: String = ""

    private fun sftpErrorMessage(e: Exception, host: String): String {
        val commonMsg = mapNetworkErrorMessage(e, host)
        if (commonMsg != null) return commonMsg
        return when (e) {
            is UserAuthException -> "SSH认证失败，请检查用户名和密码 (${e.message})"
            is net.schmizz.sshj.transport.TransportException ->
                "SSH连接错误: ${e.message}"
            else -> "SFTP连接失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override suspend fun connect(source: SyncSource, remotePath: String): Unit = withContext(Dispatchers.IO) {
        checkNetworkAvailable(context)
        try {
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier())
            val timeoutMs = userPrefs.getConnectionTimeout() * 1000
            client.setConnectTimeout(timeoutMs)
            client.setTimeout(timeoutMs)
            client.connect(source.host, source.port)
            client.authPassword(source.username, source.password)
            sshClient = client
            sftpClient = client.newSFTPClient()
            remoteBasePath = remotePath.trim()
        } catch (e: Exception) {
            sshClient = null
            sftpClient = null
            throw Exception(sftpErrorMessage(e, source.host), e)
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
        val sftp = sftpClient ?: throw Exception("SFTP未连接，请先执行connect()")
        val result = mutableListOf<RemoteFile>()
        listFilesRecursive(sftp, path, path, result)
        result
    }

    override suspend fun listDirectories(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw Exception("SFTP未连接，请先执行connect()")
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
        maxFiles: Int = 10000
    ) {
        if (result.size >= maxFiles) return
        val entries = try {
            sftp.ls(currentPath)
        } catch (e: Exception) {
            return
        }
        for (entry in entries) {
            if (result.size >= maxFiles) return
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

    override suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw Exception("SFTP未连接，请先执行connect()")
        try {
            val fullRemotePath = if (remoteBasePath.isNotEmpty()) "$remoteBasePath/$remotePath" else remotePath
            val attrs = sftp.stat(fullRemotePath)
            val totalSize = attrs.size
            localFile.parentFile?.mkdirs()
            val buffer = ByteArray(64 * 1024)
            var transferred = 0L
            sftp.open(fullRemotePath).use { rf ->
                FileOutputStream(localFile).use { output ->
                    var offset = 0L
                    while (offset < totalSize) {
                        val len = minOf(buffer.size.toLong(), totalSize - offset).toInt()
                        val bytesRead = rf.read(offset, buffer, 0, len)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                        offset += bytesRead
                        transferred += bytesRead
                        onProgress?.invoke(transferred, totalSize)
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (localFile.exists()) localFile.delete()
            throw Exception("下载失败 $remotePath: ${e.message}", e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw Exception("SFTP未连接，请先执行connect()")
        try {
            val fullRemotePath = if (remoteBasePath.isNotEmpty()) "$remoteBasePath/$remotePath" else remotePath
            sftp.rm(fullRemotePath)
            true
        } catch (e: Exception) {
            throw Exception("删除远程文件失败 $remotePath: ${e.message}", e)
        }
    }
}
