package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.MediaFinalProbe
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.StoragePaths
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Task 6 迁移测试：[SyncRemoteScanner] 从 [SyncEngine] 迁出后的扫描行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T6-C1：
 *  - 扫描异常 → DryRunResult 两列表空 + error != null（E8：部分远端快照绝不推导 DELETE_LOCAL）；
 *  - 远端 A,B / 本地 A,C → DOWNLOAD B + DELETE_LOCAL C；
 *  - 亚秒 mtime 差异不触发重下（E6：秒级截断比较）。
 */
class SyncRemoteScannerTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val client = mockk<RemoteFileClient>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>()
    private val context = mockk<android.content.Context>(relaxed = true)

    private val relation = SyncRelation(
        id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SFTP, host = "h", port = 22,
        username = "u", password = "p"
    )

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { clientFactory.create(SyncProtocol.SFTP) } returns client
        every { storagePaths.toLocalRelativePath("videos", any()) } answers { secondArg() }
    }

    private fun remote(p: String, size: Long, mtime: Long) = RemoteFile(
        path = p, name = p.substringAfterLast('/'), isDirectory = false, size = size, lastModified = mtime
    )

    private fun localItem(p: String, size: Long, mtime: Long) = mockk<VideoItemEntity> {
        every { id } returns 42L // A8: probe 调用点读取 local.id（必填契约下所有已有行都会探测）
        every { relativePath } returns p
        every { fileSize } returns size
        every { remoteModifiedTime } returns mtime
    }

    // ------------------------------------------------ A7: 本地 final 完整性探测（预览只读）

    /** 带真实主键的本地行（A7 probe 以 local.id 定位 final，mock localItem 无 id 不可用）。 */
    private fun localRow(p: String = "A.mp4", size: Long = 1L, mtime: Long? = 5_000L) = VideoItemEntity(
        id = 42L,
        syncRelationId = 1L,
        relativePath = p,
        fileName = p.substringAfterLast('/'),
        fileSize = size,
        remoteModifiedTime = mtime
    )

    /** 显式注入探针的扫描器（生产接线后 SyncEngine 传入 store 背景实现）。 */
    private fun scannerWithProbe(
        probe: MediaFinalProbe,
        publisher: SyncProgressPublisher = SyncProgressPublisher(),
    ) = SyncRemoteScanner(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, context, publisher, probe
    )

    private fun constProbe(state: MediaProbeState) = MediaFinalProbe { _, _ -> state }

    private fun stubMatchedPair() {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 5_000L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow())
    }

    @Test
    fun `scan failure yields empty actions and non-null error (E8)`() = runTest {
        stubBasics()
        coEvery { client.connect(any(), any()) } throws IOException("scan blew up")
        coEvery { videoRepository.getBySyncRelationId(1L) } returns emptyList()

        // 探针在本用例不可达（connect 即抛），VALID 仅作显式占位
        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertNotNull(r.error)
    }

    @Test
    fun `listFiles throwing SCAN_INCOMPLETE yields empty download and delete sets with scanError code`() = runTest {
        stubBasics()
        coEvery { client.listFiles(any()) } throws RemoteScanIncompleteException()
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("a.mp4", 1L, 0L), localItem("b.mp4", 1L, 0L)
        )

        // 探针在本用例不可达（listFiles 即抛），VALID 仅作显式占位
        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        assertEquals(SyncErrorCode.SCAN_INCOMPLETE, r.error?.code)
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
    }

    @Test
    fun `remote A,B and local A,C yields DOWNLOAD B and DELETE_LOCAL C`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 0L), remote("B.mp4", 2L, 0L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("A.mp4", 1L, 0L), localItem("C.mp4", 3L, 0L)
        )

        // A 行元数据相等且 final 完好在场（既有文件可读场景）→ VALID → SKIP
        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        assertEquals(listOf("B.mp4"), r.toDownload.map { it.relativePath })
        assertEquals(listOf("C.mp4"), r.toDelete.map { it.relativePath })
    }

    @Test
    fun `sub-second mtime difference does not trigger re-download (E6)`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 1_700_000_000_500L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localItem("A.mp4", 1L, 1_700_000_000_000L)
        )

        // 秒级截断后元数据相等 + final 完好在场（既有文件可读场景）→ VALID → SKIP
        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        assertTrue(r.toDownload.isEmpty())
    }

    // ------------------------------------------------ A7: 探针驱动的扫描自愈（预览只读）

    @Test
    fun `matching row with probe VALID still skips`() = runTest {
        stubMatchedPair()

        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
    }

    @Test
    fun `matching row with final MISSING yields same-path DOWNLOAD repair candidate without any write`() = runTest {
        stubMatchedPair()

        val r = scannerWithProbe(constProbe(MediaProbeState.MISSING)).dryRunLocked(1L)

        assertEquals(1, r.toDownload.size)
        val action = r.toDownload.single()
        assertEquals(SyncActionType.DOWNLOAD, action.type)
        assertEquals("A.mp4", action.relativePath)
        assertEquals(1L, action.fileSize)
        assertEquals(5_000L, action.remoteModifiedTime)
        assertTrue(r.toDelete.isEmpty())
        // 预览只读：扫描阶段绝不写 DB（同 id 修复由执行阶段按 relativePath 命中现有行）
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        coVerify(exactly = 0) { videoRepository.updateSyncMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.deleteById(any()) }
    }

    @Test
    fun `matching row with SIZE_MISMATCH yields same-path DOWNLOAD repair candidate`() = runTest {
        stubMatchedPair()

        val r = scannerWithProbe(constProbe(MediaProbeState.SIZE_MISMATCH)).dryRunLocked(1L)

        assertEquals(listOf("A.mp4"), r.toDownload.map { it.relativePath })
        assertEquals(listOf(SyncActionType.DOWNLOAD), r.toDownload.map { it.type })
        assertTrue(r.toDelete.isEmpty())
    }

    @Test
    fun `probe IO_ERROR after a candidate was accumulated clears both plans and fails the scan`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(
            remote("new.mp4", 2L, 6_000L), // 本地无行 → 先累积一个 DOWNLOAD 候选
            remote("A.mp4", 1L, 5_000L)     // 匹配行 → 探针 IO_ERROR
        )
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow())
        val publisher = SyncProgressPublisher()

        val r = scannerWithProbe(constProbe(MediaProbeState.IO_ERROR), publisher).dryRunLocked(1L)

        // fail-closed：已累积候选必须被清空，不得伪装成 NoChanges 或保留执行计划
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertEquals(SyncErrorCode.STORAGE_ERROR, r.error?.code)
        assertEquals(SyncPhase.FAILED, publisher.value.phase)
    }

    @Test
    fun `cancellation from probe propagates as CE instead of becoming scan error`() = runTest {
        stubMatchedPair()
        val ce = CancellationException("probe-ce")

        val thrown = try {
            scannerWithProbe(MediaFinalProbe { _, _ -> throw ce }).dryRunLocked(1L)
            null
        } catch (t: Throwable) {
            t
        }

        assertSame(ce, thrown)
    }

    @Test
    fun `null DB mtime with epoch-zero remote mtime is different and probes the local row first`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 0L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(mtime = null))
        val probed = mutableListOf<Pair<Long, Long>>()

        val r = scannerWithProbe(MediaFinalProbe { id, size ->
            probed.add(id to size)
            MediaProbeState.VALID
        }).dryRunLocked(1L)

        // null ≠ 有效 epoch0：陈旧行不得因 `?: 0L` 假相等而 SKIP。
        // A8 契约：已有本地行无论如何先探测（本用例 VALID + 元数据不等 → DOWNLOAD）。
        assertEquals(listOf("A.mp4"), r.toDownload.map { it.relativePath })
        assertEquals(1, probed.size)
        // 探测入参 = DB 行主键 + DB fileSize（而非远端值）
        assertEquals(42L to 1L, probed[0])
    }

    @Test
    fun `null DB mtime with epoch-zero remote and probe IO_ERROR fails closed`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 0L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(mtime = null))
        val publisher = SyncProgressPublisher()

        val r = scannerWithProbe(constProbe(MediaProbeState.IO_ERROR), publisher).dryRunLocked(1L)

        // null-mtime 行同样受探测契约保护：IO_ERROR ⇒ STORAGE_ERROR / SCAN / 双计划清空
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertEquals(SyncErrorCode.STORAGE_ERROR, r.error?.code)
        assertEquals(SyncOperation.SCAN, r.error?.operation)
        assertEquals(SyncPhase.FAILED, publisher.value.phase)
    }

    @Test
    fun `zero DB mtime with epoch-zero remote mtime is equal and VALID probe skips`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 1L, 0L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(mtime = 0L))
        var probeCalls = 0

        val r = scannerWithProbe(MediaFinalProbe { _, _ ->
            probeCalls++
            MediaProbeState.VALID
        }).dryRunLocked(1L)

        // 非 null 的 0 与远端 epoch0 是真相等 → 由探针 VALID 决定 SKIP
        assertTrue(r.toDownload.isEmpty())
        assertEquals(1, probeCalls)
    }

    @Test
    fun `real store backed probe skips existing file and repairs missing file`() = runTest {
        val rootDir = File(System.getProperty("java.io.tmpdir"), "scanner-real-probe-${System.nanoTime()}")
        try {
            val filesContext = mockk<android.content.Context>()
            every { filesContext.filesDir } returns File(rootDir, "files")
            val store = MediaFileStore(StoragePaths(filesContext), Dispatchers.Unconfined)
            val probe = MediaFinalProbe { id, size -> store.probeFinalized(id, size) }
            stubMatchedPair()

            // 真实 final 在场且长度一致（1 字节 == DB fileSize 1）→ VALID → SKIP
            store.mediaFile(42L).parentFile.mkdirs()
            store.mediaFile(42L).writeBytes(byteArrayOf(7))
            assertTrue(scannerWithProbe(probe).dryRunLocked(1L).toDownload.isEmpty())

            // final 丢失 → MISSING → 同 id 修复候选
            store.mediaFile(42L).delete()
            val r = scannerWithProbe(probe).dryRunLocked(1L)
            assertEquals(listOf("A.mp4"), r.toDownload.map { it.relativePath })
            assertTrue(r.toDelete.isEmpty())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    // ------------------------------------------------ A8: 覆盖写分支同样 fail-closed

    @Test
    fun `metadata differs with final VALID yields overwrite DOWNLOAD`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 999L, 5_000L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(size = 1L))
        val probed = mutableListOf<Pair<Long, Long>>()

        // 本地 final 完好可读（探测 VALID），远端元数据变化 → 覆盖重下
        val r = scannerWithProbe(MediaFinalProbe { id, size ->
            probed.add(id to size)
            MediaProbeState.VALID
        }).dryRunLocked(1L)

        assertEquals(listOf("A.mp4"), r.toDownload.map { it.relativePath })
        assertEquals(listOf(SyncActionType.DOWNLOAD), r.toDownload.map { it.type })
        assertTrue(r.toDelete.isEmpty())
        // A10：探针入参 = DB fileSize=1（不是远端 999）——探测以 DB 记录为准
        assertEquals(listOf(42L to 1L), probed)
    }

    @Test
    fun `metadata differs with final MISSING still yields DOWNLOAD repair`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(remote("A.mp4", 999L, 5_000L))
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(size = 1L))

        // 元数据不等 + final 缺失：DOWNLOAD 本身即修复计划，不因缺失而阻塞
        val r = scannerWithProbe(constProbe(MediaProbeState.MISSING)).dryRunLocked(1L)

        assertEquals(listOf("A.mp4"), r.toDownload.map { it.relativePath })
        assertTrue(r.toDelete.isEmpty())
    }

    @Test
    fun `metadata differs with probe IO_ERROR fails closed after accumulated candidate`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(
            remote("new.mp4", 2L, 6_000L),  // 本地无行 → 先累积 DOWNLOAD 候选
            remote("A.mp4", 999L, 5_000L)    // 元数据不等（将覆盖写）+ 探针 IO_ERROR
        )
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(localRow(size = 1L))
        val publisher = SyncProgressPublisher()

        val r = scannerWithProbe(constProbe(MediaProbeState.IO_ERROR), publisher).dryRunLocked(1L)

        // 安全契约：将被覆盖写的行探测失败 ⇒ 整次扫描 fail-closed，绝不绕过未知本地状态
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertEquals(SyncErrorCode.STORAGE_ERROR, r.error?.code)
        assertEquals(SyncPhase.FAILED, publisher.value.phase)
    }

    // ------------------------------------------------ A9: 计划非空实证清空

    /**
     * A9：证明清空发生在「两列表确实已有内容」之后——DOWNLOAD 先累积、
     * DELETE_LOCAL 已 add 且日志环节抛普通异常 ⇒ E8 catch 清空两计划。
     * 红证据（假想回归）：若 E8 清空被移除/条件化，两断言 isEmpty 将失败。
     */
    @Test
    fun `ordinary failure after both lists have content clears both plans with SCAN operation`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(
            remote("new.mp4", 2L, 6_000L),  // 本地无行 → DOWNLOAD 累积
            remote("A.mp4", 1L, 5_000L)     // 与本地行相等 → 探针 VALID → SKIP
        )
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localRow(),                          // A.mp4 命中 SKIP
            localRow(p = "C.mp4", size = 3L)     // C.mp4 不在远端 → DELETE_LOCAL
        )
        // toDelete.add 之后、CONFIRMING 前批量 flush 抛普通异常（扫描中途故障）。
        // P2 迁移：原逐条 log 注入点随批量通道上移到 flush；证据目标不变——
        // 抛异常前两列表确实已有内容，且 DOWNLOAD(new.mp4)/DELETE_LOCAL(C.mp4)
        // 两行均已入缓冲并被该次 flush 携带（mockk 先记录调用再抛）。
        val batches = mutableListOf<List<SyncLogEntity>>()
        coEvery { syncLogRepository.logAll(capture(batches)) } throws RuntimeException("bulk flush boom")
        val publisher = SyncProgressPublisher()

        val r = scannerWithProbe(constProbe(MediaProbeState.VALID), publisher).dryRunLocked(1L)

        // flush 抛异常前缓冲确实携带三行（列表已有内容的直接证据）：
        // DOWNLOAD(new.mp4) → SKIP(A.mp4) → DELETE_LOCAL(C.mp4)，扫描顺序
        coVerify(exactly = 1) { syncLogRepository.logAll(any()) }
        val rows = batches.flatten()
        assertEquals(
            "flush batch carries rows in scan order",
            listOf(
                "DOWNLOAD" to "new.mp4",
                "SKIP" to "A.mp4",
                "DELETE_LOCAL" to "C.mp4"
            ),
            rows.map { it.action to it.relativePath }
        )
        assertEquals(listOf("PENDING", "SKIPPED", "PENDING"), rows.map { it.status })
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.fileSize })
        assertTrue(rows.all { it.dryRun })
        // DOWNLOAD(new.mp4) 与 DELETE_LOCAL(C.mp4) 均已入列表，随后中途失败 ⇒ 全清空
        assertTrue("accumulated DOWNLOAD plan must be cleared", r.toDownload.isEmpty())
        assertTrue("accumulated DELETE plan must be cleared", r.toDelete.isEmpty())
        assertEquals(SyncErrorCode.UNKNOWN, r.error?.code)
        assertEquals(SyncOperation.SCAN, r.error?.operation)
        assertEquals(SyncPhase.FAILED, publisher.value.phase)
        // E8 汇总行仍走单条 log（错误路径不批量）
        coVerify(exactly = 1) { syncLogRepository.log("SCAN", "", "FAILED", true, any(), any()) }
    }

    // ============================================================ P2: 扫描日志批量落库

    /** 131 个全新远端文件（无本地行）→ 131 行 DOWNLOAD 日志。 */
    private fun stubBulkRemotes(n: Int) {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns List(n) { remote("f_$it", 1L, 5_000L) }
        coEvery { videoRepository.getBySyncRelationId(1L) } returns emptyList()
    }

    @Test
    fun `scan batches log rows into bulk inserts instead of per-row inserts`() = runTest {
        stubBulkRemotes(131)
        val batches = mutableListOf<List<SyncLogEntity>>()
        coEvery { syncLogRepository.logAll(capture(batches)) } returns Unit

        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        // ceil(131/128) = 2 次批量落库（128 阈值 + CONFIRMING 前余量 3）
        coVerify(exactly = 2) { syncLogRepository.logAll(any()) }
        assertEquals(listOf(128, 3), batches.map { it.size })
        val rows = batches.flatten()
        assertEquals(131, rows.size)
        assertEquals("扫描顺序 = 批内顺序 = 落库顺序", List(131) { "f_$it" }, rows.map { it.relativePath })
        assertTrue(rows.all { it.action == "DOWNLOAD" && it.status == "PENDING" && it.dryRun && it.fileSize == 1L })
        // 逐条 insert 通道在扫描循环内彻底消失
        coVerify(exactly = 0) { syncLogRepository.log(any(), any(), any(), any(), any(), any()) }
        assertEquals(131, r.toDownload.size)
    }

    @Test
    fun `log buffer is fully flushed before CONFIRMING phase`() = runTest {
        stubBulkRemotes(200)
        val batches = mutableListOf<List<SyncLogEntity>>()
        coEvery { syncLogRepository.logAll(capture(batches)) } returns Unit
        val publisher = SyncProgressPublisher()

        val r = scannerWithProbe(constProbe(MediaProbeState.VALID), publisher).dryRunLocked(1L)

        // 阈值批 128 + CONFIRMING 前余量批 72；返回时缓冲必空、相位已 CONFIRMING
        assertEquals(listOf(128, 72), batches.map { it.size })
        assertEquals(200, batches.sumOf { it.size })
        assertEquals(SyncPhase.CONFIRMING, publisher.value.phase)
        assertEquals(200, r.toDownload.size)
    }

    @Test
    fun `cancellation drops unflushed buffer rows without bulk write`() = runTest {
        stubBasics()
        coEvery { client.listFiles("/remote") } returns listOf(
            remote("A.mp4", 1L, 5_000L), remote("B.mp4", 1L, 5_000L), remote("C.mp4", 1L, 5_000L)
        )
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(
            localRow("A.mp4"), localRow("B.mp4"), localRow("C.mp4")
        )
        val ce = CancellationException("probe-ce")
        val probeCalls = mutableListOf<Long>()
        val probe = MediaFinalProbe { id, _ ->
            probeCalls.add(id)
            if (probeCalls.size == 3) throw ce else MediaProbeState.VALID
        }
        coEvery { syncLogRepository.logAll(any()) } returns Unit

        val thrown = runCatching {
            scannerWithProbe(probe).dryRunLocked(1L)
        }.exceptionOrNull()

        assertSame(ce, thrown) // CE 优先传播
        // A/B 两行 SKIP 已入缓冲但未满阈值：取消即丢弃，零批量、零逐条落库
        coVerify(exactly = 0) { syncLogRepository.logAll(any()) }
        coVerify(exactly = 0) { syncLogRepository.log(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bulk flush failure follows existing E8 failure path without double logging`() = runTest {
        stubBulkRemotes(131)
        coEvery { syncLogRepository.logAll(any()) } throws IOException("db bulk boom")

        val r = scannerWithProbe(constProbe(MediaProbeState.VALID)).dryRunLocked(1L)

        // 第 128 行触发阈值 flush 即失败：不重试、不二次 flush
        coVerify(exactly = 1) { syncLogRepository.logAll(any()) }
        // E8：两计划清空 + SCAN FAILED 汇总行恰一条（既有错误路径）
        assertTrue(r.toDownload.isEmpty())
        assertTrue(r.toDelete.isEmpty())
        assertEquals(SyncErrorCode.UNKNOWN, r.error?.code)
        coVerify(exactly = 1) { syncLogRepository.log("SCAN", "", "FAILED", true, any(), any()) }
    }
}
