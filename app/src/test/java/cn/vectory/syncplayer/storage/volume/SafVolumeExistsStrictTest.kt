package cn.vectory.syncplayer.storage.volume

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * BUG-007 存储层组（计划 S5–S9）：[SafVolume.existsStrict] 全路径 strict traversal。
 *
 * 锁定拆分：null cursor / provider 异常 / 中间目录段失败 / `EXTRA_LOADING=true`
 * 都必须上抛 IOException（→ probePendingTmp 收敛 UNKNOWN → 保守分支），绝不与
 * 「未找到」合并折算 ABSENT——误报缺失 = 误授权删旧 final（数据丢失）。
 *
 * 手法：mockkStatic(DocumentsContract) + mock ContentResolver（repo JVM 纪律内的既有
 * mockk 手法，见 BiometricAuthManagerTest/SettingsViewModelTest 的 mockkStatic 先例）。
 * 断言异常类型与布尔结果——不断言用户文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafVolumeExistsStrictTest {

    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()
    private val treeUri = mockk<Uri>()
    private lateinit var volume: SafVolume

    @Before
    fun setup() {
        mockkStatic(DocumentsContract::class)
        every { context.contentResolver } returns contentResolver
        // SafVolume.<init> derives volumeId via treeUri.normalizeScheme() — stub the chain.
        every { treeUri.normalizeScheme() } returns treeUri
        every { treeUri.toString() } returns "content://com.example/tree/root"
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "primary:root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk<Uri>()
        volume = SafVolume(context, treeUri)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    private fun extrasBundle(loading: Boolean): Bundle {
        val extras = mockk<Bundle>()
        every { extras.getBoolean(any(), any()) } returns loading
        return extras
    }

    /** 完整遍历无命中的 cursor：非 null、无 loading 标志、0 行。 */
    private fun emptyCursor(): Cursor = mockk {
        every { moveToNext() } returns false
        every { getExtras() } returns extrasBundle(loading = false)
        every { close() } returns Unit
    }

    /** 单行命中 cursor：首行 displayName 即命中。 */
    private fun hitCursor(displayName: String): Cursor = mockk {
        every { moveToNext() } returnsMany listOf(true, false)
        every { getColumnIndexOrThrow(any()) } returns 0
        every { getString(0) } returns displayName
        every { getExtras() } returns extrasBundle(loading = false)
        every { close() } returns Unit
    }

    // S5（正向对照）：全部段完整遍历命中 → true
    @Test
    fun `existsStrict returns true when every segment resolves`() = runTest {
        every { contentResolver.query(any(), any(), any(), any(), any()) } returnsMany listOf(
            hitCursor("0"), hitCursor("42.enc")
        )

        assertTrue(volume.existsStrict("0/42.enc"))
    }

    // S5：正常空 cursor（完整遍历无命中、无 loading 标志）→ false
    @Test
    fun `existsStrict returns false only on confirmed absence`() = runTest {
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns emptyCursor()

        assertFalse(volume.existsStrict("0/42.enc"))
    }

    // S6（红：现行宽松 exists 对 null cursor 返回 false）：null cursor → IOException
    @Test
    fun `existsStrict throws on null cursor instead of returning false`() = runTest {
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val thrown = runCatching { volume.existsStrict("0/42.enc") }.exceptionOrNull()

        assertTrue("expected IOException but was $thrown", thrown is IOException)
    }

    // S7：provider 查询异常 → 上抛（至 probePendingTmp 收敛 UNKNOWN）
    @Test
    fun `existsStrict propagates provider exceptions`() = runTest {
        every { contentResolver.query(any(), any(), any(), any(), any()) } throws
            RuntimeException("simulated provider crash")

        val thrown = runCatching { volume.existsStrict("0/42.enc") }.exceptionOrNull()

        assertNotNull("provider failure must never be collapsed into ABSENT", thrown)
    }

    // S8（v3.1 新增，复审 #4）：中间目录段 null cursor → IOException——复用宽松
    // resolveDocId/findChildDocId 会把中间目录失败吞成「整路径不存在」→ 误 ABSENT。
    @Test
    fun `existsStrict throws when intermediate directory query fails`() = runTest {
        every { contentResolver.query(any(), any(), any(), any(), any()) } returnsMany listOf(
            null, hitCursor("42.enc")
        )

        val thrown = runCatching { volume.existsStrict("0/42.enc") }.exceptionOrNull()

        assertTrue("expected IOException but was $thrown", thrown is IOException)
    }

    // S9（v3.1 新增，复审 #4）：EXTRA_LOADING=true → IOException——非 null cursor 的
    // 空/部分列表不构成「确认缺失」，不得折算 ABSENT。
    @Test
    fun `existsStrict throws when child listing is still loading`() = runTest {
        val loadingCursor = mockk<Cursor> {
            every { moveToNext() } returns false
            every { getExtras() } returns extrasBundle(loading = true)
            every { close() } returns Unit
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns loadingCursor

        val thrown = runCatching { volume.existsStrict("0/42.enc") }.exceptionOrNull()

        assertTrue("expected IOException but was $thrown", thrown is IOException)
    }
}
