package cn.vectory.syncplayer.sync

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun mapNetworkErrorMessage(e: Exception, host: String): String? = when (e) {
    is UnknownHostException -> "无法解析主机地址: $host"
    is ConnectException -> "连接被拒绝，请检查主机地址 ${host} 是否可达"
    is SocketTimeoutException -> "连接超时，请检查网络连接和主机地址 ${host}"
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
