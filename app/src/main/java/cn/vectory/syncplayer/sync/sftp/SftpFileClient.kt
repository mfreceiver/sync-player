package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.sync.RemoteFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
 import net.schmizz.sshj.transport.verification.PromiscuousVerifier
 import net.schmizz.sshj.userauth.UserAuthException
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
 import java.net.UnknownHostException

class SftpFileClient(@Suppress("UNUSED_PARAMETER") private val context: Context) : RemoteFileClient {

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var remoteBasePath: String = ""

    private fun sftpErrorMessage(e: Exception, host: String): String {
        return when (e) {
            is UnknownHostException -> "无法解析主机地址: $host"
