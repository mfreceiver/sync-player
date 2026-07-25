package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedMediaStoreTest {
    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    private fun fixedProvider(volume: MediaVolume): MediaVolumeProvider {
        val provider = mockk<MediaVolumeProvider>()
        coEvery { provider.current() } returns volume
        return provider
    }

    private fun newStore(volume: MediaVolume = FileVolume(File(createTempDirectory().toFile(), "v").apply { mkdirs() })): EncryptedMediaStore =
        EncryptedMediaStore(fixedProvider(volume)) { master }

    @Test fun encryptThenReadBack() = runTest {
        val store = newStore(); val id = 10L
        val iv = CryptoScheme.newIv()
        val plain = ByteArray(2048) { (it and 0xFF).toByte() }
        store.openEncryptSink(id, iv).use { it.write(plain) }
        assertTrue(store.finalize(id))
        val key = CryptoScheme.deriveContentKey(master)
        val cipher = ByteArrayOutputStream().apply {
            store.openCiphertextRead(id).use { h ->
                val b = ByteArray(2048); val n = h.read(b, 0, 2048); write(b, 0, n)
            }
        }.toByteArray()
        val dec = CryptoScheme.decryptBlockAlignedRange(key, iv, 0L, cipher, 0, cipher.size)
        assertArrayEquals(plain, dec)
    }

    @Test fun thumbRoundTrip() = runTest {
        val store = newStore(); val plain = ByteArray(300) { it.toByte() }
        store.writeThumb(5L, plain)
        assertArrayEquals(plain, store.readThumb(5L))
        store.deleteThumb(5L); assertNull(store.readThumb(5L))
    }

    @Test fun cancelCleansTmp() = runTest {
        val store = newStore()
        store.openEncryptSink(8L, CryptoScheme.newIv()).use { it.write(byteArrayOf(1, 2, 3)) }
        store.cancel(8L)
        assertFalse(store.exists(8L))
    }

    @Test fun volumeIdReflectsProviderSwitchWithoutRebuild() = runTest {
        val rootA = File(createTempDirectory().toFile(), "a").apply { mkdirs() }
        val rootB = File(createTempDirectory().toFile(), "b").apply { mkdirs() }
        val volA = FileVolume(rootA)
        val volB = FileVolume(rootB)
        // Both FileVolume use volumeId "internal"; wrap via provider that swaps instances
        // and assert IO goes to the new root after switch.
        val provider = mockk<MediaVolumeProvider>()
        coEvery { provider.current() } returnsMany listOf(volA, volA, volB, volB)

        val store = EncryptedMediaStore(provider) { master }
        val id = 42L
        val iv = CryptoScheme.newIv()
        store.openEncryptSink(id, iv).use { it.write(byteArrayOf(9, 8, 7)) }
        assertTrue(store.finalize(id))
        // Written on volA
        assertTrue(File(rootA, MediaPaths.mediaRelPath(id)).exists())
        assertFalse(File(rootB, MediaPaths.mediaRelPath(id)).exists())

        // Next ops use volB (provider already returned volA twice above for open+finalize;
        // remaining returns are volB). Write another id onto B.
        val id2 = 43L
        store.openEncryptSink(id2, CryptoScheme.newIv()).use { it.write(byteArrayOf(1)) }
        assertTrue(store.finalize(id2))
        assertTrue(File(rootB, MediaPaths.mediaRelPath(id2)).exists())
        assertFalse(File(rootA, MediaPaths.mediaRelPath(id2)).exists())
    }
}
