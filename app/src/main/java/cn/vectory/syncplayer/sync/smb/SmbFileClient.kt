package cn.vectory.syncplayer.sync.smb

import android.content.Context
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.RemoteFileClient
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties

class SmbFileClient(@Suppress("UNUSED_PARAMETER") private val context: Context) : RemoteFileClient {

    private var cifsContext: jcifs.CIFSContext? = null
    private var currentHost: String = ""
    private var shareName: String = ""
    private var subPath: String = ""

    private fun smbErrorMessage(e: Exception, host: String): String {
        return when (e) {
            is UnknownHostException -> "无法解析主机地址: $host"
            is ConnectException -> "连接被拒绝，请检查主机地址 ${host}:445 是否可达"
            is SocketTimeoutException -> "连接超时，请检查网络连接和主机地址 ${host}"
            is SmbAuthException -> "SMB认证失败，请检查用户名和密码 (${e.message})"
            is SmbException -> {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("bad network name", ignoreCase = true) ||
                    msg.contains("share", ignoreCase = true) ||
                    msg.contains("not found", ignoreCase = true) ->
                        "无法访问共享文件夹，请检查路径是否正确 ($msg)"
                    msg.contains("access denied", ignoreCase = true) ->
                        "SMB访问被拒绝，请检查用户权限 ($msg)"
                    msg.contains("logon failure", ignoreCase = true) ->
                        "SMB登录失败，请检查用户名和密码 ($msg)"
                    else -> "SMB错误: ${e.javaClass.simpleName}: $msg"
            }
            else -> "SMB连接失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override suspend fun connect(config: ServerConfig): Unit = withContext(Dispatchers.IO) {
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
            subPath = pathParts.drop(1).joinToString("/")
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://${config.host}/${shareName}/${subPath}/"
            } else {
                "smb://${config.host}/${shareName}/"
            }
            val smbFile = SmbFile(smbUrl, cifsContext)
            smbFile.listFiles()
        } catch (e: Exception) {
            cifsContext = null
            throw Exception(smbErrorMessage(e, config.host), e)
        }
    }

    override suspend fun connect(source: SyncSource, remotePath: String): Unit = withContext(Dispatchers.IO) {
        try {
            val props = Properties()
            props.setProperty("jcifs.smb.client.minVersion", "SMB202")
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
            props.setProperty("jcifs.smb.client.responseTimeout", "30000")
            props.setProperty("jcifs.smb.client.soTimeout", "30000")
            val bc = BaseContext(jcifs.config.PropertyConfiguration(props))
            val authenticator = NtlmPasswordAuthenticator(null, source.username, source.password)
            cifsContext = bc.withCredentials(authenticator)
            currentHost = source.host
            val pathParts = remotePath.trim('/').split("/")
            shareName = pathParts.firstOrNull() ?: ""
            subPath = pathParts.drop(1).joinToString("/")
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://${source.host}/${shareName}/${subPath}/"
            } else {
                "smb://${source.host}/${shareName}/"
            }
            val smbFile = SmbFile(smbUrl, cifsContext)
            smbFile.listFiles()
        } catch (e: Exception) {
            cifsContext = null
            throw Exception(smbErrorMessage(e, source.host), e)
        }
    }

    override suspend fun disconnect() {
        cifsContext = null
    }

    override suspend fun testConnection(config: ServerConfig): Boolean {
        try {
            connect(config)
            disconnect()
            return true
        } catch (e: Exception) {
            disconnect()
            throw e
        }
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
        val ctx = cifsContext ?: throw Exception("SMB未连接，请先执行connect()")
        val result = mutableListOf<RemoteFile>()
        val pathParts = path.trim('/').split("/")
        val share = pathParts.firstOrNull() ?: ""
        val remoteSubPath = pathParts.drop(1).joinToString("/")
        val smbUrl = if (remoteSubPath.isNotEmpty()) {
            "smb://$currentHost/$share/$remoteSubPath/"
        } else {
            "smb://$currentHost/$share/"
        }
        val dir = SmbFile(smbUrl, ctx)
        if (!dir.exists() || !dir.isDirectory) throw Exception(
            "远程路径不存在或不是目录: $path"
        )
        listFilesRecursive(ctx, dir, share, remoteSubPath, result)
        result
    }

    override suspend fun listDirectories(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw Exception("SMB未连接，请先执行connect()")
        val result = mutableListOf<RemoteFile>()
        val pathParts = path.trim('/').split("/")
        val share = pathParts.firstOrNull() ?: ""
        val remoteSubPath = pathParts.drop(1).joinToString("/")
        val smbUrl = if (remoteSubPath.isNotEmpty()) {
            "smb://$currentHost/$share/$remoteSubPath/"
        } else {
            "smb://$currentHost/$share/"
        }
        val dir = SmbFile(smbUrl, ctx)
        if (!dir.exists() || !dir.isDirectory) throw Exception(
            "远程路径不存在或不是目录: $path"
        )
        val children = try {
            dir.listFiles() ?: return@withContext result
        } catch (e: Exception) {
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
                            size = child.contentLength.toLong(),
                            lastModified = child.lastModified
                        )
                    )
                }
            } catch (_: Exception) { }
        }
        result
    }
    override suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw Exception("SMB未连接，请先执行connect()")
        try {
            val smbUrl = if (subPath.isNotEmpty()) {
                "smb://$currentHost/$shareName/$subPath/$remotePath"
            } else {
                "smb://$currentHost/$shareName/$remotePath"
            }
            val smbFile = SmbFile(smbUrl, ctx)
            if (!smbFile.exists()) throw Exception("远程文件不存在: $remotePath")
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
            if (e is jcifs.smb.SmbException) {
                throw Exception("下载失败 $remotePath: ${smbErrorMessage(e, currentHost)}", e)
            }
            throw Exception("下载失败 $remotePath: ${e.message}", e)
        }
    }
    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = cifsContext ?: throw Exception("SMB未连接，请先执行connect()")
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
            throw Exception("删除远程文件失败 $remotePath: ${e.message}", e)
        }
    }
}
