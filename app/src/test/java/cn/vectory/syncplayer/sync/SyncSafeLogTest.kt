package cn.vectory.syncplayer.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * 复审阻断（敏感日志红线）：[SyncSafeLog] 纯格式化函数的安全 token 契约——
 * 输入异常携带 path/fileName/cause message 也只产出「操作 + 错误码 + 异常类别」，
 * 绝无 path/文件名/底层 message。
 *
 * 边界（成文）：android.util.Log 在单测为 no-op 不可捕获——Log.w/e 站点以源码级验证
 * 收束（两文件 grep：无 `, e)` 形态的 Log 调用、无 relativePath/fileName 内插）。
 */
class SyncSafeLogTest {

    @Test
    fun `failure message contains only operation code and exception category`() {
        val e = SyncException(
            SyncErrorCode.STORAGE_ERROR,
            SyncOperation.DOWNLOAD,
            path = "secret-dir/f.mp4",
            cause = IOException("/remote/secret-dir/f.mp4 io-boom")
        )

        val msg = SyncSafeLog.failure("DOWNLOAD", e)

        assertEquals("DOWNLOAD failed: code=STORAGE_ERROR, error=SyncException", msg)
        assertFalse("must not leak path", msg.contains("secret-dir"))
        assertFalse("must not leak fileName", msg.contains("f.mp4"))
        assertFalse("must not leak cause message", msg.contains("io-boom"))
    }

    @Test
    fun `failure message for non sync exception falls back to category with null code`() {
        val msg = SyncSafeLog.failure("DELETE_BOTH remote", IOException("/mnt/xx/zz y/z.mkv boom"))

        assertEquals("DELETE_BOTH remote failed: code=null, error=IOException", msg)
        assertFalse(msg.contains("/mnt"))
        assertFalse(msg.contains("z.mkv"))
        assertFalse(msg.contains("boom"))
    }

    @Test
    fun `cleanup messages carry no path or identifiers`() {
        val local = SyncSafeLog.localDeleteIncomplete("DELETE_BOTH", 42L, true, false)
        assertEquals("DELETE_BOTH local failed id=42: mediaGone=true thumbGone=false", local)

        val cleanup = SyncSafeLog.cleanupFailed("deleteStoreMedia", 42L, IOException("/sdcard/x/y.enc boom"))
        assertEquals("deleteStoreMedia failed id=42: IOException", cleanup)
        assertFalse(cleanup.contains("/sdcard"))
        assertFalse(cleanup.contains("y.enc"))
        assertFalse(cleanup.contains("boom"))
    }

    @Test
    fun `metadata extraction failure message carries only exception category`() {
        val msg = SyncSafeLog.metadataExtractionFailed(RuntimeException("cannot open /videos/a b/c.mp4"))

        assertEquals("metadata extraction failed: RuntimeException", msg)
        assertFalse(msg.contains("c.mp4"))
        assertFalse(msg.contains("/videos"))
    }
}
