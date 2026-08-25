package cn.vectory.syncplayer.sync

import app.cash.turbine.test
import cn.vectory.syncplayer.data.model.SyncPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AND-013: 完成事件（completeDownload/markDeleted/beginDownload）走 500ms 节流；
 * phase 变化与终态强制立即 flush，终态 completedFiles 计数精确。
 *
 * 用 unconfined collector 捕捉 StateFlow 每一次 conflated 发射（与 Turbine 等价），
 * 时钟注入 fake，虚拟推进节流窗口。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncProgressPublisherTest {

    private fun publisher(clock: () -> Long) = SyncProgressPublisher(currentTimeMs = clock)

    @Test
    fun `completion burst within throttle window emits at most 2 intermediates then flushes on phase change`() = runTest {
        var clock = 1_000L
        val sut = publisher { clock }

        val received = mutableListOf<cn.vectory.syncplayer.data.model.SyncProgress>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.progress.collect { received += it }
        }

        // 100ms 内 50 次完成事件
        repeat(50) { i ->
            clock += 2L // 全程 <500ms 窗口
            sut.completeDownload("f$i", transferredSoFar = 0L, totalDownloadBytes = 0L, completedFiles = i + 1, totalFiles = 50)
        }

        // 中间 emission ≤2（首帧 leading publish + conflation 余量）
        val intermediates = received.count { it.completedFiles in 1..49 }
        assertTrue("intermediate emissions=${intermediates}", intermediates <= 2)
        // 终值尚未刷出（脏状态等待 flush）
        assertTrue(received.last().completedFiles < 50)

        // 推进节流窗口后，phase 变化（DOWNLOADING→DELETING）强制 flush
        clock += 600L
        sut.value = sut.value.copy(phase = SyncPhase.DELETING)

        assertEquals(50, sut.progress.value.completedFiles)
        assertEquals(50, received.last().completedFiles)
        assertEquals(50, sut.progress.value.completedPaths.size)
        assertEquals(SyncPhase.DELETING, received.last().phase)
        collector.cancel()
    }

    @Test
    fun `terminal state forces immediate flush within throttle window`() = runTest {
        var clock = 1_000L
        val sut = publisher { clock }

        val received = mutableListOf<cn.vectory.syncplayer.data.model.SyncProgress>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.progress.collect { received += it }
        }

        repeat(10) { i ->
            clock += 5L
            sut.completeDownload("f$i", 0L, 0L, i + 1, 10)
        }
        // 仍在 500ms 窗口内（距上次 publish 仅 ~50ms）即写终态
        clock += 50L
        sut.value = sut.value.copy(phase = SyncPhase.COMPLETED, isRunning = false)

        // 终态 emission 无条件：completedFiles 精确
        assertEquals(10, sut.progress.value.completedFiles)
        assertEquals(1, received.count { it.completedFiles == 10 && it.phase == SyncPhase.COMPLETED })
        assertEquals(10, sut.progress.value.completedPaths.size)
        collector.cancel()
    }

    @Test
    fun `markDeleted defers path publication and flushes on phase change`() = runTest {
        var clock = 1_000L
        val sut = publisher { clock }

        val received = mutableListOf<cn.vectory.syncplayer.data.model.SyncProgress>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.progress.collect { received += it }
        }

        repeat(5) { i ->
            clock += 2L
            sut.markDeleted("d$i")
        }
        // 窗口内：路径集不逐条发布
        assertTrue(received.count { it.completedPaths.isNotEmpty() } <= 1)

        clock += 600L
        sut.value = sut.value.copy(phase = SyncPhase.COMPLETED, isRunning = false)

        assertEquals(setOf("d0", "d1", "d2", "d3", "d4"), sut.progress.value.completedPaths)
        collector.cancel()
    }

    @Test
    fun `single completion then terminal state keeps exact counters`() = runTest {
        var clock = 1_000L
        val sut = publisher { clock }

        sut.completeDownload("only.mp4", 100L, 1000L, completedFiles = 1, totalFiles = 1)
        clock += 10L
        sut.value = sut.value.copy(phase = SyncPhase.COMPLETED, isRunning = false, completedFiles = 1)

        assertEquals(1, sut.progress.value.completedFiles)
        assertEquals(1, sut.progress.value.totalFiles)
        assertEquals(setOf("only.mp4"), sut.progress.value.completedPaths)
    }

    @Test
    fun `progress publication retains speed measurement`() = runTest {
        var clock = 1_000L
        val sut = publisher { clock }
        sut.value = sut.value.copy(
            isRunning = true,
            phase = SyncPhase.DOWNLOADING,
            startTimeMs = clock
        )

        clock += 2_000L
        sut.reportDownloadProgress(
            relativePath = "video.mp4",
            fileName = "video.mp4",
            transferred = 1_000L,
            total = 1_000L,
            fileTotalBytes = 1_000L,
            transferredSoFar = 0L,
            totalDownloadBytes = 1_000L,
            completedFiles = 0,
            totalFiles = 1
        )

        assertEquals(500L, sut.progress.value.speedBytesPerSec)
    }
}
