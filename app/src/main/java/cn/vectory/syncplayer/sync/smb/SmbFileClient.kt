package cn.vectory.syncplayer.sync.smb

import android.content.Context
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.sync.RemoteFileClient
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

class SmbFileClient(@Suppress("UNUSED_PARAMETER") private val context: Context) : RemoteFileClient {

    private var cifsContext: jcifs.CIFSContext? = null
    private var currentHost: String = ""
    private var shareName: String = ""
    // Sub-path under the share, e.g. for config.remotePath = "/Scan_share/test" -> "test"
    private var subPath: String = ""

    override suspend fun connect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val props = Properties()
            props.setProperty("jcifs.smb.client.minVersion", "SMB202")
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
            props.setProperty("jcifs.smb.client.responseTimeout", "30000")
            props.setProperty("jcifs.smb.client.soTimeout", "30000")

            val bc = BaseContext(jcifs.config.PropertyConfiguration(props))
            val authenticator = NtlmPasswordAuthenticator(null, config.username, config.password)
            cifsContext = bc.withCredentials(authenticator)
            currentHost = config.host

            val pathParts = config.remotePath.trim('/').split("/")
            shareName = pathParts.firstOrNull() ?: ""
            // Everything after the share is the subPath
            subPath = pathParts.drop(1).joinToString("/")

            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://${config.host}/${shareName}/${subPath}/"
            } else {
                "smb://${config.host}/${shareName}/"
            }
            val smbFile = SmbFile(smbUrl, cifsContext)
            smbFile.listFiles()
            true
        } catch (e: Exception) {
            cifsContext = null
            false
        }
    }

    override suspend fun disconnect() {
        cifsContext = null
    }

    override suspend fun testConnection(config: ServerConfig): Boolean {
        val result = connect(config)
        disconnect()
        return result
    }

    override suspend fun listFiles(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: return@withContext emptyList()
        try {
            val result = mutableListOf<RemoteFile>()
            val pathParts = path.trim('/').split("/")
            val share = pathParts.firstOrNull() ?: ""
            val remoteSubPath = pathParts.drop(1).joinToString("/")

            // Use full remote subPath context to determine base for relative extraction
            val smbUrl = if (remoteSubPath.isNotEmpty()) {
                "smb://$currentHost/$share/$remoteSubPath/"
            } else {
                "smb://$currentHost/$share/"
            }

            val dir = SmbFile(smbUrl, ctx)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

            listFilesRecursive(ctx, dir, share, remoteSubPath, result)
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun listFilesRecursive(
        ctx: jcifs.CIFSContext,
        dir: SmbFile,
        share: String,
        remoteBasePath: String,
        result: MutableList<RemoteFile>
    ) {
        val children = try {
            dir.listFiles() ?: return
        } catch (e: Exception) {
            return
        }

        for (child in children) {
            try {
                if (child.isDirectory) {
                    listFilesRecursive(ctx, child, share, remoteBasePath, result)
                } else {
                    val relPath = extractRelativePath(child.url.toString(), share, remoteBasePath)
                    result.add(
                        RemoteFile(
                            path = relPath,
                            name = child.name,
                            isDirectory = false,
                            size = child.contentLength.toLong(),
                            lastModified = child.lastModified
                        )
                    )
                }
            } catch (_: Exception) { }
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

    override suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: return@withContext false
        try {
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://$currentHost/$shareName/$subPath/$remotePath"
            } else {
                "smb://$currentHost/$shareName/$remotePath"
            }
            val smbFile = SmbFile(smbUrl, ctx)
            if (!smbFile.exists()) return@withContext false

            val totalSize = smbFile.contentLength.toLong()
            localFile.parentFile?.mkdirs()

            val buffer = ByteArray(64 * 1024)
            var transferred = 0L

            smbFile.getInputStream().use { input ->
                FileOutputStream(localFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
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

    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: return@withContext false
        try {
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://$currentHost/$shareName/$subPath/$remotePath"
            } else {
                "smb://$currentHost/$shareName/$remotePath"
            }
            val smbFile = SmbFile(smbUrl, ctx)
            if (!smbFile.exists()) return@withContext false
            smbFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
