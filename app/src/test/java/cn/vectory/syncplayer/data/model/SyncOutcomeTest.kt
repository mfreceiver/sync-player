package cn.vectory.syncplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F02/T6: [SyncOutcome] 终态类型与持久化标识映射。
 *
 * 断言枚举与字段值，绝不断言本地化文案。
 */
class SyncOutcomeTest {

    // =========================================================================
    // fromStored — 旧文案 → UNKNOWN、NULL → 无记录
    // =========================================================================

    @Test
    fun `fromStored with all null columns means no record`() {
        assertNull(SyncOutcome.fromStored(null, null, null))
    }

    @Test
    fun `fromStored with unknown legacy status string maps to UNKNOWN`() {
        // 旧文案（迁移后的外来值）无法可靠反推终态 → 保守 UNKNOWN，绝不默认成功。
        val outcome = SyncOutcome.fromStored("成功：下载 2 个，删除 0 个", null, null)
        assertEquals(SyncOutcome.Status.UNKNOWN, outcome?.status)
    }

    @Test
    fun `fromStored with arbitrary garbage status string maps to UNKNOWN`() {
        val outcome = SyncOutcome.fromStored("not-a-status", "TIMEOUT", 3)
        assertEquals(SyncOutcome.Status.UNKNOWN, outcome?.status)
        assertEquals("TIMEOUT", outcome?.errorCode)
        assertEquals(3, outcome?.failedCount)
    }

    @Test
    fun `fromStored preserves typed status with code and count`() {
        val outcome = SyncOutcome.fromStored("FAILED", "INSUFFICIENT_SPACE", null)
        assertEquals(SyncOutcome.Status.FAILED, outcome?.status)
        assertEquals("INSUFFICIENT_SPACE", outcome?.errorCode)
        assertNull(outcome?.failedCount)
    }

    // =========================================================================
    // 稳定字符串标识：status.name 双向 round-trip（不存 ordinal、不存文案）
    // =========================================================================

    @Test
    fun `every status name round-trips through valueOf`() {
        for (status in SyncOutcome.Status.entries) {
            assertEquals(status, SyncOutcome.Status.valueOf(status.name))
        }
    }

    @Test
    fun `status set is exactly the approved terminal set`() {
        // T3 枚举全集：无记录由 NULL 表达，不设哨兵状态。
        assertEquals(
            setOf("SUCCESS", "PARTIAL_FAILED", "FAILED", "CANCELLED", "UNKNOWN"),
            SyncOutcome.Status.entries.map { it.name }.toSet()
        )
    }
}
