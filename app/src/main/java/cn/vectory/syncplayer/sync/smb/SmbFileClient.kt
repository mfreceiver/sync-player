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
