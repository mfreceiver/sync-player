package cn.vectory.syncplayer.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageContractsTest {

    @Test
    fun internalRefIsCanonical() {
        val ref = StorageRef.internal(7, 123L)
        assertEquals(VolumeIds.INTERNAL, ref.volumeId)
        assertEquals(StorageFormat.PLAIN, ref.format)
        assertEquals(123L, ref.plainSize)
    }

    @Test
    fun ivIsDefensivelyCopied() {
        val iv = CryptoIvHolder.newIv()
        val original = iv.copyOf()
        val ref = ctrRef(iv)
        iv[0] = (iv[0] + 1).toByte()
        // 持有副本：外部修改不影响 ref
        assertArrayEquals(original, ref.iv)
    }

    private fun ctrRef(iv: ByteArray): StorageRef =
        StorageRef(9, VolumeIds.external("uuid-1"), "ds", "gen1", StorageFormat.CTR_V2, iv, 10L)

    @Test
    fun plainWithIvRejected() {
        assertThrows(IllegalStateException::class.java) {
            StorageRef(1, VolumeIds.INTERNAL, null, null, StorageFormat.PLAIN, CryptoIvHolder.newIv(), 1L)
        }
    }

    @Test
    fun ctrWithBadIvLengthRejected() {
        assertThrows(IllegalStateException::class.java) {
            StorageRef(1, VolumeIds.external("u"), "ds", "g", StorageFormat.CTR_V2, ByteArray(7), 1L)
        }
    }

    @Test
    fun ctrWithoutGenerationOrDatasetRejected() {
        val iv = CryptoIvHolder.newIv()
        assertThrows(IllegalStateException::class.java) {
            StorageRef(1, VolumeIds.external("u"), "ds", null, StorageFormat.CTR_V2, iv, 1L)
        }
        assertThrows(IllegalStateException::class.java) {
            StorageRef(1, VolumeIds.external("u"), null, "g", StorageFormat.CTR_V2, iv, 1L)
        }
    }

    @Test
    fun negativeSizeRejected() {
        assertThrows(IllegalStateException::class.java) {
            StorageRef(1, VolumeIds.INTERNAL, null, null, StorageFormat.PLAIN, null, -1L)
        }
    }

    @Test
    fun volumeIdsRoundtrip() {
        val id = VolumeIds.external("abcd-1234")
        assertTrue(VolumeIds.isExternal(id))
        assertEquals("abcd-1234", VolumeIds.externalUuid(id))
        assertEquals(null, VolumeIds.externalUuid(VolumeIds.INTERNAL))
        assertNotEquals(VolumeIds.EXTERNAL_PREFIX, id)
    }

    @Test
    fun writeAttemptRequiresCommittedRowId() {
        assertThrows(IllegalStateException::class.java) {
            WriteAttempt(StorageRef.internal(0, 1L), "a1")
        }
    }

    /** 测试内生成 8B IV。 */
    private object CryptoIvHolder {
        fun newIv(): ByteArray = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
    }
}
