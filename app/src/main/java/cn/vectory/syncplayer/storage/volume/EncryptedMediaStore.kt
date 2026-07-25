package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking

/** Result of [EncryptedMediaStore.checkExistence]. */
sealed class MediaExistence {
    data object EXISTS : MediaExistence()
    data object NOT_FOUND : MediaExistence()
    data class IO_ERROR(val reason: String) : MediaExistence()
}

/**
 * Encrypted media I/O against the **current** active [MediaVolume].
 *
 * Volume is resolved on every operation via [MediaVolumeProvider.current] so that
 * switching storage in Settings takes effect without process restart.
 */
class EncryptedMediaStore(
    private val mediaVolumeProvider: MediaVolumeProvider,
    private val masterProvider: () -> SecretKey,
) {
    private val masterKey: SecretKey get() = masterProvider()
    fun contentKey() = CryptoScheme.deriveContentKey(masterKey)
    private fun thumbKey() = CryptoScheme.deriveThumbKey(masterKey)

    private suspend fun volume(): MediaVolume = mediaVolumeProvider.current()

    /**
     * Active volume id. Resolved live (not cached at construction).
     * Uses [runBlocking] so sync call sites (ContentProvider, SyncEngine field writes) stay simple.
     */
    val volumeId: String
        get() = runBlocking { volume().volumeId }

    private fun tmpMedia(id: Long) = MediaPaths.mediaRelPath(id) + MediaPaths.TMP_SUFFIX
    private fun finalMedia(id: Long) = MediaPaths.mediaRelPath(id)

    suspend fun openEncryptSink(id: Long, iv: ByteArray): java.io.OutputStream {
        val sink = volume().openWrite(tmpMedia(id))
        return CryptoScheme.encryptStream(contentKey(), iv, sink)
    }

    suspend fun finalize(id: Long): Boolean = volume().rename(tmpMedia(id), finalMedia(id))

    suspend fun cancel(id: Long) {
        volume().delete(tmpMedia(id))
    }

    suspend fun openCiphertextRead(id: Long): SeekableReadHandle = volume().openRead(finalMedia(id))

    suspend fun delete(id: Long): Boolean = volume().delete(finalMedia(id))

    suspend fun exists(id: Long): Boolean = volume().exists(finalMedia(id))

    suspend fun thumbExists(id: Long): Boolean = volume().exists(MediaPaths.thumbRelPath(id))

    /**
     * Three-way existence check for rebuild/orphan scan.
     * - [MediaExistence.EXISTS] / [MediaExistence.NOT_FOUND]: definitive
     * - [MediaExistence.IO_ERROR]: volume access failed (permission/SAF/IO); caller must not treat as orphan
     */
    suspend fun checkExistence(id: Long): MediaExistence = try {
        if (volume().exists(finalMedia(id))) MediaExistence.EXISTS else MediaExistence.NOT_FOUND
    } catch (e: Exception) {
        MediaExistence.IO_ERROR(e.message ?: e.javaClass.simpleName)
    }

    fun usableSpace(): Long = runBlocking { volume().usableSpace() }

    suspend fun writeThumb(id: Long, plain: ByteArray) {
        val encoded = CryptoScheme.encryptThumb(thumbKey(), plain)
        volume().openWrite(MediaPaths.thumbRelPath(id)).use { it.write(encoded) }
    }

    suspend fun readThumb(id: Long): ByteArray? {
        val vol = volume()
        if (!vol.exists(MediaPaths.thumbRelPath(id))) return null
        val buf = java.io.ByteArrayOutputStream()
        // 缩略图小文件，全量读；处理短读以获取完整数据
        buf.use { b ->
            vol.openRead(MediaPaths.thumbRelPath(id)).use { h ->
                val expectedSize = h.size()
                require(expectedSize in 29..1_048_576) { "invalid thumbnail size: $expectedSize" }
                val tmp = ByteArray(expectedSize.toInt())
                var totalRead = 0
                while (totalRead < expectedSize) {
                    val n = h.read(tmp, totalRead, (expectedSize - totalRead).toInt())
                    if (n <= 0) throw java.io.EOFException(
                        "Truncated thumbnail read: expected $expectedSize bytes, got $totalRead"
                    )
                    totalRead += n
                }
                b.write(tmp)
            }
        }
        val encoded = buf.toByteArray()
        require(encoded.size <= 1_048_576) { "thumbnail too large: ${encoded.size}" }
        return CryptoScheme.decryptThumb(thumbKey(), encoded)
    }

    suspend fun deleteThumb(id: Long): Boolean = volume().delete(MediaPaths.thumbRelPath(id))
}
