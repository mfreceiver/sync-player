package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.util.formatFileSize
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Compat shim: maps stdlib network exception types to a resource string.
 * Returns null for unrecognized exceptions. Resolves purely via [SyncErrorCode] /
 * exception type — never via substring-matching the thrown message (which is null for
 * [SyncException] anyway).
 */
fun mapNetworkErrorMessage(e: Throwable, host: String, context: Context): String? = when (e) {
    is SyncException -> syncErrorMessage(e, context, host)
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

/**
 * Classifies a remote-browse failure into a coarse [RemoteBrowseError].
 *
 * Classification reads `(e as? SyncException)?.code` (the throw site already classified
 * the underlying cause) plus stdlib exception types. Substring-matching of the thrown
 * message is intentionally NOT used — [SyncException] carries no message text, and
 * relying on prose would be fragile across locales/libraries.
 */
fun classifyRemoteError(e: Throwable): RemoteBrowseError {
    val code = (e as? SyncException)?.code
    return when {
        code == SyncErrorCode.PATH_NOT_FOUND -> RemoteBrowseError.PATH_NOT_FOUND
        code == SyncErrorCode.TIMEOUT ||
            code == SyncErrorCode.UNKNOWN_HOST ||
            code == SyncErrorCode.NETWORK_UNAVAILABLE -> RemoteBrowseError.TIMEOUT
        e is SocketTimeoutException -> RemoteBrowseError.TIMEOUT
        e is UnknownHostException -> RemoteBrowseError.TIMEOUT
        e is ConnectException -> RemoteBrowseError.OTHER
        else -> RemoteBrowseError.OTHER
    }
}

/**
 * Resolve a user-facing display string for [e] purely from its [SyncErrorCode]
 * (or stdlib type for non-sync throwables). Never leaks [Throwable.getMessage] —
 * for unrecognized errors a generic message is returned.
 *
 * [host] overrides the exception's embedded host for message formatting when the caller
 * has more specific context (e.g. the source being tested).
 */
fun syncErrorMessage(e: Throwable, context: Context, host: String? = null): String {
    val code = (e as? SyncException)?.code ?: when (e) {
        is SocketTimeoutException -> SyncErrorCode.TIMEOUT
        is UnknownHostException -> SyncErrorCode.UNKNOWN_HOST
        is ConnectException -> SyncErrorCode.CONNECTION_REFUSED
        else -> SyncErrorCode.UNKNOWN
    }
    val effectiveHost = host ?: (e as? SyncException)?.host
    return when (code) {
        SyncErrorCode.NETWORK_UNAVAILABLE ->
            context.getString(R.string.sync_error_network_unavailable)
        SyncErrorCode.UNKNOWN_HOST ->
            context.getString(R.string.error_unknown_host, effectiveHost.orEmpty())
        SyncErrorCode.CONNECTION_REFUSED ->
            context.getString(R.string.error_connection_refused, effectiveHost.orEmpty())
        SyncErrorCode.TIMEOUT ->
            context.getString(R.string.error_connection_timeout, effectiveHost.orEmpty())
        SyncErrorCode.AUTHENTICATION_FAILED ->
            context.getString(R.string.sync_error_authentication_failed)
        SyncErrorCode.ACCESS_DENIED ->
            context.getString(R.string.sync_error_access_denied)
        SyncErrorCode.PATH_NOT_FOUND ->
            context.getString(R.string.relation_remote_path_not_found)
        SyncErrorCode.NOT_CONNECTED ->
            context.getString(R.string.sync_error_not_connected)
        SyncErrorCode.SCAN_INCOMPLETE ->
            context.getString(R.string.sync_error_scan_incomplete)
        SyncErrorCode.DOWNLOAD_FAILED ->
            context.getString(R.string.sync_error_download_failed)
        SyncErrorCode.REMOTE_DELETE_FAILED ->
            context.getString(R.string.sync_error_remote_delete_failed)
        SyncErrorCode.INVALID_FILE_SIZE ->
            context.getString(R.string.sync_error_invalid_file_size)
        SyncErrorCode.INSUFFICIENT_SPACE -> {
            val se = e as? SyncException
            val need = se?.expectedBytes?.let(::formatFileSize).orEmpty()
            val have = se?.availableBytes?.let(::formatFileSize).orEmpty()
            context.getString(R.string.sync_error_insufficient_space, need, have)
        }
        SyncErrorCode.FINALIZE_FAILED ->
            context.getString(R.string.sync_error_finalize_failed)
        SyncErrorCode.STORAGE_ERROR ->
            context.getString(R.string.sync_error_storage_error)
        SyncErrorCode.THUMBNAIL_ERROR ->
            context.getString(R.string.sync_error_thumbnail_error)
        SyncErrorCode.DATABASE_ERROR ->
            context.getString(R.string.sync_error_database_error)
        SyncErrorCode.UNKNOWN ->
            context.getString(R.string.sync_error_unknown)
    }
}
