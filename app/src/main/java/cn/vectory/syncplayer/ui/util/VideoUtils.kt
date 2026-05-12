package cn.vectory.syncplayer.ui.util

internal val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg"
)

internal val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif"
)

internal fun isVideoFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

internal fun isImageFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

internal fun isMediaFile(fileName: String): Boolean = isVideoFile(fileName) || isImageFile(fileName)

internal fun extractParentPath(relativePath: String): String {
    val lastSlash = relativePath.lastIndexOf('/')
    return if (lastSlash >= 0) relativePath.substring(0, lastSlash) else ""
}

internal fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in VIDEO_EXTENSIONS -> "video/*"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "srt" -> "text/plain"
        "ass" -> "text/plain"
        "nfo" -> "text/plain"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
