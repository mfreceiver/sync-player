package cn.vectory.syncplayer.settings

import android.content.Context
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F04 回归锁：[StorageManagementViewModel.resolveTreeDisplayNameOrNull] 的异常协议。
 *
 *  - 普通异常（SecurityException 等）→ null 安全哨兵（URI 派生文本不得进 UI）；
 *  - CancellationException → 原样传播，绝不降级为 null（红线 3：取消不被吞）。
 *
 * 注入点：`DocumentsContract.getTreeDocumentId`——helper 内首个可注入的框架静态调用；
 * mockkStatic 拦截先于 android.jar stub 执行，无需 Robolectric（项目单测已有
 * `returnDefaultValues = true`，前置的 `toUri()` 解析不会抛 not-mocked）。
 */
class StorageManagementTreeDisplayNameTest {

    private val context = mockk<Context>(relaxed = true)
    private val treeUri = "content://tree/primary%3AMovies"

    @Before
    fun setUp() {
        mockkStatic(DocumentsContract::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun `helper CancellationException propagates instead of degrading to null sentinel`() {
        val original = CancellationException("resolver cancelled")
        every { DocumentsContract.getTreeDocumentId(any()) } throws original

        var caught: Throwable? = null
        try {
            StorageManagementViewModel.resolveTreeDisplayNameOrNull(context, treeUri)
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue(
            "CancellationException must propagate, never be swallowed as the null sentinel",
            caught is CancellationException
        )
        assertSame("propagated CE must be the original instance", original, caught)
    }

    @Test
    fun `helper ordinary failure still degrades to null sentinel`() {
        every { DocumentsContract.getTreeDocumentId(any()) } throws SecurityException("denied")

        assertNull(
            "non-CE failure must keep the null sentinel contract",
            StorageManagementViewModel.resolveTreeDisplayNameOrNull(context, treeUri)
        )
    }
}
