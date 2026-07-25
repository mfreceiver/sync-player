package cn.vectory.syncplayer.storage.volume

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileVolumeTest {
    private fun newVol(): FileVolume = FileVolume(File(createTempDir(), "vol").apply { mkdirs() })

    @Test fun pathDerivation() {
        assertEquals("0/42.enc", MediaPaths.mediaRelPath(42))
        assertEquals("2/2500.enc", MediaPaths.mediaRelPath(2500))
        assertEquals("thumbs/7.enc", MediaPaths.thumbRelPath(7))
    }

    @Test fun writeReadSeek() = runTest {
        val v = newVol(); val payload = ByteArray(500) { (it and 0xFF).toByte() }
        v.openWrite("0/1.enc.tmp").use { it.write(payload) }
        v.rename("0/1.enc.tmp", "0/1.enc")
        v.openRead("0/1.enc").use { h ->
            assertEquals(500, h.size())
            val buf = ByteArray(500); assertEquals(500, h.read(buf, 0, 500))
            assertArrayEquals(payload, buf)
            h.seek(250); val half = ByteArray(250); assertEquals(250, h.read(half, 0, 250))
            assertArrayEquals(payload.copyOfRange(250, 500), half)
        }
    }

    @Test fun renameAtomicity() = runTest {
        val v = newVol(); v.openWrite("a").use { it.write(byteArrayOf(1)) }
        assertTrue(v.rename("a", "b"))
        assertFalse(v.exists("a")); assertTrue(v.exists("b"))
    }

    @Test fun deleteAndSpace() = runTest {
        val v = newVol(); v.openWrite("x").use { it.write(byteArrayOf(1,2,3)) }
        assertTrue(v.exists("x")); assertTrue(v.delete("x")); assertFalse(v.exists("x"))
        assertTrue(v.usableSpace() > 0)
    }
}