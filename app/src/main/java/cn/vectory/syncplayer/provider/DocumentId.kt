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

        /** Null-safe decode: returns null for malformed or stale IDs instead of throwing. */
        fun decode(encoded: String): DocumentId? = try {
            decodeInternal(encoded)
        } catch (_: Exception) {
            null
        }

        /** Strict variant for callers that have validated input or must throw on failure. */
        fun decodeOrThrow(encoded: String): DocumentId =
            decode(encoded) ?: throw IllegalArgumentException("Invalid document ID: $encoded")

        private fun decodeInternal(encoded: String): DocumentId = when {
            encoded == "root" -> Root
            encoded.startsWith("rel:") -> Relation(encoded.removePrefix("rel:").toLong())
            encoded.startsWith("dir:") -> {
                val withoutPrefix = encoded.removePrefix("dir:")
                val colonIndex = withoutPrefix.indexOf(':')
                if (colonIndex <= 0) throw IllegalArgumentException("malformed dir id")
                Directory(withoutPrefix.substring(0, colonIndex).toLong(), withoutPrefix.substring(colonIndex + 1))
            }
            encoded.startsWith("vid:") -> Video(encoded.removePrefix("vid:").toLong())
            else -> throw IllegalArgumentException("Invalid document ID: $encoded")
        }
    }
}
