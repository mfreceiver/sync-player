package cn.vectory.syncplayer.storage.volume

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafVolumeTest {
    // 测试必须使用真实、已持久化授权的 treeUri（由测试 fixture/设备参数提供）。
    // 不允许用 assertTrue(true) 伪造 SAF seek 能力；无法提供可 seek 的 Provider 时测试必须失败。
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun requirePersistedTreeUri(): Uri = Uri.parse(
        checkNotNull(InstrumentationRegistry.getArguments().getString("treeUri")) {
            "Pass -e treeUri <persisted ACTION_OPEN_DOCUMENT_TREE URI> to connectedAndroidTest"
        }
    )

    @Test fun writeReadRoundTrip_onInternalSubtree() {
        val treeUri = requirePersistedTreeUri()
        val volume = SafVolume(ctx, treeUri)
        runBlocking {
            val payload = ByteArray(257) { it.toByte() }
            volume.openWrite("probe/1.enc").use { it.write(payload) }
            volume.openRead("probe/1.enc").use { handle ->
                assertEquals(payload.size.toLong(), handle.size())
                handle.seek(129)
                val tail = ByteArray(payload.size - 129)
                assertEquals(tail.size, handle.read(tail, 0, tail.size))
                assertArrayEquals(payload.copyOfRange(129, payload.size), tail)
            }
        }
    }
}