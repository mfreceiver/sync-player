package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayOutputStream

class CryptoSchemeTest {
    private val master: SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val thumbKey = CryptoScheme.deriveThumbKey(master)

    private fun dec(key: SecretKey, iv: ByteArray, cipher: ByteArray, offset: Long): ByteArray {
        val startBlock = offset / 16
        val skip = (offset - startBlock * 16).toInt()
        val aligned = startBlock.toInt() * 16
        val plain = CryptoScheme.decryptBlockAlignedRange(key, iv, startBlock, cipher, aligned, cipher.size - aligned)
        return plain.copyOfRange(skip, plain.size)
    }

    @Test fun ctrRoundTripFull() {
        val iv = CryptoScheme.newIv(); val plain = "hello world 0123456789".toByteArray()
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        assertArrayEquals(plain, dec(contentKey, iv, cipher, 0))
    }

    @Test fun ctrSeekArbitraryOffset() {
        val iv = CryptoScheme.newIv(); val plain = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        for (off in listOf(0, 1, 15, 16, 17, 1000, 4095)) {
            val want = plain.copyOfRange(off, off + 1)
            assertEquals("offset=$off", want[0], dec(contentKey, iv, cipher, off.toLong())[0])
        }
    }

    @Test fun ivUniqueness() {
        val set = (1..1000).map { CryptoScheme.newIv().toList() }.toSet()
        assertEquals(1000, set.size)
    }

    @Test fun gcmThumbRoundTrip() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain)
        assertArrayEquals(plain, CryptoScheme.decryptThumb(thumbKey, encoded))
    }

    @Test fun gcmThumbTamper() {
        val plain = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain).copyOf().apply { this[20] = (this[20].toInt() xor 0xFF).toByte() }
        assertThrows(Exception::class.java) { CryptoScheme.decryptThumb(thumbKey, encoded) }
    }

    @Test fun seekMatchesSequentialSkip() {
        val iv = CryptoScheme.newIv(); val plain = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        val fromBlock100 = CryptoScheme.decryptBlockAlignedRange(contentKey, iv, 100L, cipher, 100 * 16, cipher.size - 100 * 16)
        assertArrayEquals(plain.copyOfRange(100 * 16, 101 * 16), fromBlock100.copyOfRange(0, 16))
    }
}