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
