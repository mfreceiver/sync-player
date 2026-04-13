package cn.vectory.syncplayer.provider

sealed class DocumentId {
    object Root : DocumentId()
    data class Relation(val relationId: Long) : DocumentId()
    data class Directory(val relationId: Long, val relativePath: String) : DocumentId()
    data class Video(val videoItemId: Long) : DocumentId()

    companion object {
        fun encode(id: DocumentId): String = when (id) {
            Root -> "root"
            is Relation -> "rel:${id.relationId}"
            is Directory -> "dir:${id.relationId}:${id.relativePath}"
            is Video -> "vid:${id.videoItemId}"
        }

        fun decode(encoded: String): DocumentId = when {
            encoded == "root" -> Root
            encoded.startsWith("rel:") -> Relation(encoded.removePrefix("rel:").toLong())
            encoded.startsWith("dir:") -> {
                val withoutPrefix = encoded.removePrefix("dir:")
                val colonIndex = withoutPrefix.indexOf(':')
                Directory(withoutPrefix.substring(0, colonIndex).toLong(), withoutPrefix.substring(colonIndex + 1))
            }
            encoded.startsWith("vid:") -> Video(encoded.removePrefix("vid:").toLong())
            else -> throw IllegalArgumentException("Invalid document ID: $encoded")
        }
    }
}
