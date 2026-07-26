package cn.vectory.syncplayer.sync

/**
 * Typed error code for all sync-layer failures.
 *
 * Classification is performed at the throw site (where the underlying cause type is known)
 * and consumed by [ErrorMessageMapper.syncErrorMessage] to resolve a user-facing display
 * string from resources. The thrown exception MUST NOT carry display text — its
 * [Throwable.message] is intentionally null.
 */
enum class SyncErrorCode {
    NETWORK_UNAVAILABLE,
    UNKNOWN_HOST,
    CONNECTION_REFUSED,
    TIMEOUT,
    AUTHENTICATION_FAILED,
    ACCESS_DENIED,
    PATH_NOT_FOUND,
    NOT_CONNECTED,
    SCAN_INCOMPLETE,
    DOWNLOAD_FAILED,
    REMOTE_DELETE_FAILED,
    INVALID_FILE_SIZE,
    INSUFFICIENT_SPACE,
    FINALIZE_FAILED,
    STORAGE_ERROR,
    THUMBNAIL_ERROR,
    DATABASE_ERROR,
    UNKNOWN
}

/** Coarse operation classification for diagnostics. */
enum class SyncOperation { CONNECT, BROWSE, SCAN, DOWNLOAD, DELETE }

/**
 * Unified sync-layer exception. Carries a typed [code] + [operation] plus structured
 * context ([path], [host], byte counts) that the display resolver may use to build a
 * localized message. [message] is deliberately null — no Chinese/English display text
 * is carried by the exception itself.
 */
open class SyncException(
    val code: SyncErrorCode,
    val operation: SyncOperation,
    val path: String? = null,
    val host: String? = null,
    val expectedBytes: Long? = null,
    val actualBytes: Long? = null,
    val availableBytes: Long? = null,
    cause: Throwable? = null
) : Exception(null, cause)
