package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.sync.RemoteFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.FileOutputStream

class SftpFileClient(@Suppress("UNUSED_PARAMETER") private val context: Context) : RemoteFileClient {

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    // Base remote path on the server, e.g. "/Scan_share/test" -> "/Scan_share/test" (we store without trailing slash)
    private var remoteBasePath: String = ""

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(config.host, config.port)
            client.authPassword(config.username, config.password)

            sshClient = client
            sftpClient = client.newSFTPClient()
            // store the base remote path for later use (download/builds of full path)
            remoteBasePath = (config.remotePath ?: "").trim()
            true
        } catch (e: Exception) {
            sshClient = null
            sftpClient = null
            false
        }
    }

    override suspend fun disconnect() {
        try {
            sftpClient?.close()
            sshClient?.disconnect()
        } catch (_: Exception) {
        }
        sftpClient = null
        sshClient = null
    }

    override suspend fun testConnection(config: ServerConfig): Boolean {
        val result = connect(config)
        if (result) {
            try {
                val sftp = sftpClient ?: return false
                sftp.ls(config.remotePath)
            } catch (_: Exception) {
                disconnect()
                return false
            }
        }
        disconnect()
        return result
    }

    override suspend fun listFiles(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: return@withContext emptyList()
        try {
            val result = mutableListOf<RemoteFile>()
            listFilesRecursive(sftp, path, path, result)
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun listFilesRecursive(
        sftp: SFTPClient,
        currentPath: String,
        rootPath: String,
        result: MutableList<RemoteFile>
    ) {
        val entries = try {
            sftp.ls(currentPath)
        } catch (e: Exception) {
            return
        }

        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val fullPath = if (currentPath.endsWith("/")) {
                "$currentPath${entry.name}"
            } else {
                "$currentPath/${entry.name}"
            }

            if (entry.isDirectory) {
                listFilesRecursive(sftp, fullPath, rootPath, result)
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
        val sftp = sftpClient ?: return@withContext false
        try {
            // Prepend remoteBasePath to the requested path to form the full path on the server
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
            false
        }
    }
}
