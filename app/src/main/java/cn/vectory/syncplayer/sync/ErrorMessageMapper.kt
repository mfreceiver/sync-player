package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun mapNetworkErrorMessage(e: Exception, host: String, context: Context): String? = when (e) {
    is UnknownHostException -> context.getString(R.string.error_unknown_host, host)
    is ConnectException -> context.getString(R.string.error_connection_refused, host)
    is SocketTimeoutException -> context.getString(R.string.error_connection_timeout, host)
    else -> null
}

enum class RemoteBrowseError {
    PATH_NOT_FOUND,
    TIMEOUT,
    OTHER
}

fun classifyRemoteError(e: Exception): RemoteBrowseError {
    val msg = e.message.orEmpty().lowercase()
    return when {
        e is SocketTimeoutException -> RemoteBrowseError.TIMEOUT
        e is UnknownHostException -> RemoteBrowseError.TIMEOUT
        e is ConnectException -> RemoteBrowseError.OTHER
        msg.contains("not found") -> RemoteBrowseError.PATH_NOT_FOUND
        msg.contains("不存在") -> RemoteBrowseError.PATH_NOT_FOUND
        msg.contains("bad network name") -> RemoteBrowseError.PATH_NOT_FOUND
        msg.contains("share") && msg.contains("无法访问") -> RemoteBrowseError.PATH_NOT_FOUND
        msg.contains("no such file") -> RemoteBrowseError.PATH_NOT_FOUND
        msg.contains("timeout") || msg.contains("超时") -> RemoteBrowseError.TIMEOUT
        msg.contains("timed out") -> RemoteBrowseError.TIMEOUT
        else -> RemoteBrowseError.OTHER
    }
}
