package com.mfreceiver.syncplayer.sync.sftp

import android.content.Context
import com.mfreceiver.syncplayer.data.model.RemoteFile
import com.mfreceiver.syncplayer.data.model.ServerConfig
import com.mfreceiver.syncplayer.sync.RemoteFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.FileOutputStream

class SftpFileClient(@Suppress("UNUSED_PARAMETER") private val context: Context) : RemoteFileClient {

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = SSHClient()
            client.addHostKeyVerifier { _, _, _ -> true }
            client.connect(config.host, config.port)
            client.authPassword(config.username, config.password)

            sshClient = client
            sftpClient = client.newSFTPClient()
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
            val remoteFile = sftp.open(remotePath)
            val totalSize = remoteFile.length()
            localFile.parentFile?.mkdirs()

            val buffer = ByteArray(64 * 1024)
            var transferred = 0L

            remoteFile.use { rf ->
                val inputStream = rf.newInputStream()
                FileOutputStream(localFile).use { output ->
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        transferred += read
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
