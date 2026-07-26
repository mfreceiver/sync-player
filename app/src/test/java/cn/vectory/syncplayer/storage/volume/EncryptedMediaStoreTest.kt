package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.crypto.RangeDecryptor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
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
        store.openEncryptSink(id, iv, plain.size.toLong()).use { it.write(plain) }
        assertTrue(store.finalize(id))
        val key = CryptoScheme.deriveContentKey(master)
        // read back the full ciphertext and decrypt via the v2 RangeDecryptor (plaintext coords)
        val dec = store.openCiphertextRead(id).use { h ->
            RangeDecryptor(key, iv, plain.size.toLong()).readRange(h, 0L, plain.size)
        }
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
        val bytes = byteArrayOf(1, 2, 3)
        store.openEncryptSink(8L, CryptoScheme.newIv(), bytes.size.toLong()).use { it.write(bytes) }
        store.cancel(8L)
        assertFalse(store.exists(8L))
    }

    @Test fun twoArgSinkIsUnsupported() = runTest {
        val store = newStore()
        // openEncryptSink(id, iv) is suspend and must refuse without a plaintext length.
        var caught: Throwable? = null
        try {
            store.openEncryptSink(1L, CryptoScheme.newIv())
        } catch (e: IllegalStateException) {
            caught = e
        }
        assertNotNull("two-arg overload must throw IllegalStateException", caught)
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
        val bytesA = byteArrayOf(9, 8, 7)
        store.openEncryptSink(id, iv, bytesA.size.toLong()).use { it.write(bytesA) }
        assertTrue(store.finalize(id))
        // Written on volA
        assertTrue(File(rootA, MediaPaths.mediaRelPath(id)).exists())
        assertFalse(File(rootB, MediaPaths.mediaRelPath(id)).exists())

        // Next ops use volB (provider already returned volA twice above for open+finalize;
        // remaining returns are volB). Write another id onto B.
        val id2 = 43L
        val bytesB = byteArrayOf(1)
        store.openEncryptSink(id2, CryptoScheme.newIv(), bytesB.size.toLong()).use { it.write(bytesB) }
        assertTrue(store.finalize(id2))
        assertTrue(File(rootB, MediaPaths.mediaRelPath(id2)).exists())
        assertFalse(File(rootA, MediaPaths.mediaRelPath(id2)).exists())
    }

    @Test fun purgeLegacyIfNeededRemovesV1OrphansKeepsV2AndTmp() = runTest {
        val root = File(createTempDirectory().toFile(), "v").apply { mkdirs() }
        val store = newStore(FileVolume(root))
        val key = CryptoScheme.deriveContentKey(master)

        // A legacy v1-style ciphertext orphan: raw bytes, no SPM2 magic. id=7 → bucket 0.
        val legacyId = 7L
        val legacyRel = MediaPaths.mediaRelPath(legacyId)
        File(root, legacyRel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(256))

        // A valid v2 media file: must survive purge.
        val v2Id = 8L
        val v2Rel = MediaPaths.mediaRelPath(v2Id)
        val v2Plain = ByteArray(100) { it.toByte() }
        val v2Iv = CryptoScheme.newIv()
        store.openEncryptSink(v2Id, v2Iv, v2Plain.size.toLong()).use { it.write(v2Plain) }
        assertTrue(store.finalize(v2Id))

        // A stale .tmp leftover — must now be PRESERVED (never purged; overwritten on next sync).
        val tmpRel = MediaPaths.mediaRelPath(9L) + MediaPaths.TMP_SUFFIX
        File(root, tmpRel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(16))

        // A thumb.
        store.writeThumb(10L, ByteArray(40))

        val deleted = store.purgeLegacyIfNeeded()
        // legacy media + thumb = 2 deletions; v2 media AND tmp are preserved.
        assertEquals(2, deleted)
        assertFalse("legacy orphan must be removed", File(root, legacyRel).exists())
        assertTrue("tmp must be PRESERVED (no race with active downloads)", File(root, tmpRel).exists())
        assertFalse("thumb must be removed", File(root, MediaPaths.thumbRelPath(10L)).exists())
        assertTrue("v2 media must be preserved", File(root, v2Rel).exists())

        // marker guards re-run; second call is a no-op returning 0
        assertEquals(0, store.purgeLegacyIfNeeded())

        // v2 file still decryptable after purge
        val dec = store.openCiphertextRead(v2Id).use { h ->
            RangeDecryptor(key, v2Iv, v2Plain.size.toLong()).readRange(h, 0L, v2Plain.size)
        }
        assertArrayEquals(v2Plain, dec)
    }

    /**
     * Tasks 2-3: strict layout validation. Anything that cannot be *proven* to be a
     * MediaPaths-produced final-media entry is preserved — even if it looks legacy. The one
     * genuine legacy orphan in the correct bucket (control) is still removed.
     */
    @Test fun purgeSkipsNonMediaPathsLayoutEntries() = runTest {
        val root = File(createTempDirectory().toFile(), "v").apply { mkdirs() }
        val store = newStore(FileVolume(root))

        // Control: a genuine legacy orphan in the CORRECT bucket (id=5 → bucket 0). Deleted.
        val goodLegacy = File(root, "0/5.enc").apply { parentFile?.mkdirs() }
        goodLegacy.writeBytes(ByteArray(64))

        // Non-digit id: "notanid.enc" → preserved.
        File(root, "0/notanid.enc").writeBytes(ByteArray(64))
        // Double-suffixed (no .tmp): "5.enc.enc" → preserved.
        File(root, "0/5.enc.enc").writeBytes(ByteArray(64))
        // Signed ids → preserved.
        File(root, "0/-5.enc").writeBytes(ByteArray(64))
        File(root, "0/+5.enc").writeBytes(ByteArray(64))
        // id/1000 != bucket: id=5000 belongs in bucket 5, but physically placed in bucket 0.
        // Legacy bytes too, so it would be deleted without the bucket cross-check. Must be preserved.
        File(root, "0/5000.enc").writeBytes(ByteArray(64))
        // A non-.enc file → preserved (not enumerated for delete anyway).
        File(root, "0/6.txt").writeBytes(ByteArray(8))
        // A negative-named top-level dir containing a legacy-looking file → dir rejected as bucket.
        File(root, "-1/5.enc").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(64))

        val deleted = store.purgeLegacyIfNeeded()
        // Only the control legacy orphan (0/5.enc) is removed.
        assertEquals("only the proven legacy orphan in its correct bucket is deleted", 1, deleted)
        assertFalse(goodLegacy.exists())
        assertTrue("non-digit id preserved", File(root, "0/notanid.enc").exists())
        assertTrue("double-suffix preserved", File(root, "0/5.enc.enc").exists())
        assertTrue("signed '-5' preserved", File(root, "0/-5.enc").exists())
        assertTrue("signed '+5' preserved", File(root, "0/+5.enc").exists())
        assertTrue("misplaced bucket (id/1000 != bucket) preserved", File(root, "0/5000.enc").exists())
        assertTrue("non-.enc preserved", File(root, "0/6.txt").exists())
        assertTrue("negative-named dir not treated as bucket", File(root, "-1/5.enc").exists())
    }

    /**
     * Task 4: a `.enc` file whose header cannot be read (transient IO / SAF permission flap)
     * must be PRESERVED — only a confirmed non-SPM2 magic authorizes deletion.
     */
    @Test fun purgePreservesUnreadableFile() = runTest {
        val root = File(createTempDirectory().toFile(), "v").apply { mkdirs() }
        val backing = FileVolume(root)

        // A legacy-looking file: raw bytes, non-SPM2 magic. id=5 → bucket 0 → rel "0/5.enc".
        val rel = "0/5.enc"
        File(root, rel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(64))

        // Volume wrapper whose openRead() always throws for this path (simulating a transient
        // read failure). All other ops delegate to the real FileVolume (incl. marker write).
        val failingVol = object : MediaVolume {
            override val volumeId: String get() = backing.volumeId
            override suspend fun openWrite(rp: String) = backing.openWrite(rp)
            override suspend fun openRead(rp: String): SeekableReadHandle =
                throw IOException("simulated transient read failure")
            override suspend fun list(rp: String) = backing.list(rp)
            override suspend fun delete(rp: String) = backing.delete(rp)
            override suspend fun exists(rp: String) = backing.exists(rp)
            override suspend fun mkdirs(rp: String) = backing.mkdirs(rp)
            override suspend fun rename(from: String, to: String) = backing.rename(from, to)
            override fun usableSpace(): Long = backing.usableSpace()
        }
        val store = EncryptedMediaStore(fixedProvider(failingVol)) { master }

        val deleted = store.purgeLegacyIfNeeded()
        assertEquals("read failure must NOT cause deletion (fail-closed)", 0, deleted)
        assertTrue("unreadable file must be preserved", File(root, rel).exists())
    }

    /**
     * A file shorter than the magic length (truncated/empty) is a short read → unknown → preserved.
     */
    @Test fun purgePreservesTruncatedShortFile() = runTest {
        val root = File(createTempDirectory().toFile(), "v").apply { mkdirs() }
        val store = newStore(FileVolume(root))
        // 2-byte file: cannot yield a full 4-byte magic read → preserved.
        val rel = "0/5.enc"
        File(root, rel).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(0x00, 0x00))

        val deleted = store.purgeLegacyIfNeeded()
        assertEquals("short-read file must be preserved", 0, deleted)
        assertTrue(File(root, rel).exists())
    }
}
