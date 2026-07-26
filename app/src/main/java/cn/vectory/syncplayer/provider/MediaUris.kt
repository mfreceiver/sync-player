package cn.vectory.syncplayer.provider

import android.net.Uri

object MediaUris {
    const val AUTHORITY = "cn.vectory.syncplayer.media"
    private const val PATH_MEDIA = "media"
    private const val PATH_THUMB = "thumb"

    fun mediaUri(id: Long): Uri {
        val javaUri = java.net.URI("content", AUTHORITY, "/$PATH_MEDIA/$id", null, null)
        return Uri.parse(javaUri.toString())
            ?: throw IllegalArgumentException("invalid media URI for id=$id")
    }

    fun thumbUri(id: Long): Uri {
        val javaUri = java.net.URI("content", AUTHORITY, "/$PATH_THUMB/$id", null, null)
        return Uri.parse(javaUri.toString())
            ?: throw IllegalArgumentException("invalid thumb URI for id=$id")
    }

    fun isMedia(uri: Uri): Boolean =
        uri.scheme == "content" &&
        uri.authority == AUTHORITY &&
        uri.pathSegments?.size == 2 &&
        uri.pathSegments[0] == PATH_MEDIA

    fun isThumb(uri: Uri): Boolean =
        uri.scheme == "content" &&
        uri.authority == AUTHORITY &&
        uri.pathSegments?.size == 2 &&
        uri.pathSegments[0] == PATH_THUMB

    /** 断言 [uri] 是 media URI，否则抛出 [IllegalArgumentException]。 */
    fun requireMedia(uri: Uri) {
        require(isMedia(uri)) { "expected media URI, got: $uri" }
    }

    /** 断言 [uri] 是 thumb URI，否则抛出 [IllegalArgumentException]。 */
    fun requireThumb(uri: Uri) {
        require(isThumb(uri)) { "expected thumb URI, got: $uri" }
    }

    fun idOf(uri: Uri): Long {
        if (!isMedia(uri) && !isThumb(uri))
            throw IllegalArgumentException("invalid media uri: $uri")
        val idStr = uri.pathSegments?.getOrNull(1)
            ?: throw IllegalArgumentException("invalid media uri: $uri")
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid media uri: $uri")
        if (id <= 0L) throw IllegalArgumentException("invalid media uri: $uri")
        return id
    }
}
