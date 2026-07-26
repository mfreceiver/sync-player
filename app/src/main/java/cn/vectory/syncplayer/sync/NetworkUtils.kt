package cn.vectory.syncplayer.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun checkNetworkAvailable(context: Context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(network)
    if (network == null || caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        throw SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.CONNECT)
    }
}
