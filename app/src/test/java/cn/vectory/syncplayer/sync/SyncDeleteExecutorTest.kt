package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5 迁移测试：[SyncDeleteExecutor]（DELETE_BOTH 路径）从 [SyncEngine] 迁出后的行为等价性。
 *
 * 覆盖 plan 12.2.0 remediation T5-C1（断言点为返回值 + 零删除副作用，不写死 log 参数表）：
 *  - 远端删除异常 → false；
 *  - 本地 + 远端成功 → true。
 */
class SyncDeleteExecutorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val client = mockk<RemoteFileClient>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val sut = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )

    private val relation = SyncRelation(
        id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
    )
    private val source = SyncSource(
        id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
        username = "u", password = "p"
    )
    private val action = SyncAction(SyncActionType.DELETE_BOTH, "a.mp4", 10L, 0L)

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        every { storagePaths.toPathUnderBase(any(), any()) } returns "videos/a.mp4"
    }


    @Test
    fun `remote delete failure counts as FAILED`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))
    }

    @Test
    fun `local and remote success returns true`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } returns true

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))
    }

    // -------------------------------------------------------------------------
    // BUG-008 — 终态计数保留（计划 §BUG-008.3 #4，注入点 :190 catch-all）
    // -------------------------------------------------------------------------

    @Test
    fun `delete flow catch-all FAILED preserves counters`() = runTest {
        // 注入点：循环前的 connect 即抛异常 → 直接落入 executeDeleteLocked 尾部
        // catch-all 的 FAILED 终态写入。该写入构造 fresh SyncProgress 会把
        // DELETING 相位已发布的 totalFiles 清零，必须改为基于当前值 copy。
        stubBasics()
        coEvery { client.connect(any(), any()) } throws SyncException(
            SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE
        )

        val result = sut.executeDeleteLocked(1L, listOf(action))

        assertFalse(result)
        val p = publisher.value
        assertEquals(SyncPhase.FAILED, p.phase)
        assertEquals("terminal FAILED progress must keep totalFiles from DELETING phase", 1, p.totalFiles)
    }

    // -------------------------------------------------------------------------
    // F02 — 结构化终态协议（SUCCESS / PARTIAL_FAILED / FAILED+码 / CANCELLED）
    // -------------------------------------------------------------------------

    @Test
    fun `delete all ok persists SUCCESS outcome snapshot`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } returns true
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1)); Unit
        }

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))

        coVerify(exactly = 1) { syncRelationRepository.updateLastSync(any(), any()) }
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.SUCCESS, outcome.status)
        assertNull(outcome.errorCode)
        assertNull(outcome.failedCount)
    }

    @Test
    fun `delete partial failure persists PARTIAL_FAILED with failedCount`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns null
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1)); Unit
        }

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        val p = publisher.value
        assertEquals(SyncPhase.PARTIAL_FAILED, p.phase)
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.PARTIAL_FAILED, outcome.status)
        assertEquals(1, outcome.failedCount)
    }

    @Test
    fun `delete catch-all persists FAILED with the sync error code`() = runTest {
        stubBasics()
        coEvery { client.connect(any(), any()) } throws SyncException(
            SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE
        )
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1)); Unit
        }

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.FAILED, outcome.status)
        assertEquals("REMOTE_DELETE_FAILED", outcome.errorCode)
    }

    @Test
    fun `delete cancellation records CANCELLED and propagates CancellationException`() = runTest {
        stubBasics()
        coEvery { client.connect(any(), any()) } throws CancellationException("cancelled")
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1)); Unit
        }

        var caught: Throwable? = null
        try {
            sut.executeDeleteLocked(1L, listOf(action))
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue("cancellation must propagate", caught is CancellationException)
        val outcome = outcomes.single()
        assertEquals(SyncOutcome.Status.CANCELLED, outcome.status)
        assertEquals(SyncPhase.CANCELLED, publisher.value.phase)
    }

    // -------------------------------------------------------------------------
    // 取消收尾三态：CANCELLED 收尾写入（单条 UPDATE）自身的任何结果——成功 /
    // 普通异常 / 再抛新 CE——都不得替换/遮蔽原始 CancellationException（红线 3）。
    // -------------------------------------------------------------------------

    /** 取消收尾三态测试的运行结果（含原始 CE 实例，用于同一性断言）。 */
    private class CancelledRun(
        val originalCe: CancellationException,
        val caught: Throwable?,
        val outcomes: List<SyncOutcome>,
    )

    /** connect 即抛原始 CE；CANCELLED 收尾写入（updateLastSync）行为由 [finalizationWrite] 注入。 */
    private suspend fun runCancelledDelete(
        finalizationWrite: suspend () -> Unit
    ): CancelledRun {
        stubBasics()
        val originalCe = CancellationException("original-cancel")
        coEvery { client.connect(any(), any()) } throws originalCe
        val outcomes = mutableListOf<SyncOutcome>()
        coEvery { syncRelationRepository.updateLastSync(any(), any()) } coAnswers {
            outcomes.add(arg(1))
            finalizationWrite()
        }

        var caught: Throwable? = null
        try {
            sut.executeDeleteLocked(1L, listOf(action))
        } catch (t: Throwable) {
            caught = t
        }
        return CancelledRun(originalCe, caught, outcomes)
    }

    @Test
    fun `cancelled finalization write success propagates the original CancellationException`() = runTest {
        val run = runCancelledDelete { }

        assertTrue("cancellation must propagate", run.caught is CancellationException)
        assertSame(
            "must propagate the ORIGINAL cancellation instance",
            run.originalCe, run.caught
        )
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
        assertEquals(SyncPhase.CANCELLED, publisher.value.phase)
    }

    @Test
    fun `cancelled finalization write ordinary failure still propagates the original CancellationException`() = runTest {
        val run = runCancelledDelete { throw RuntimeException("finalization write boom") }

        assertTrue(run.caught is CancellationException)
        assertSame("finalization write failure must not replace the original cancellation", run.originalCe, run.caught)
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
    }

    @Test
    fun `cancelled finalization write new CancellationException cannot replace the original`() = runTest {
        val run = runCancelledDelete { throw CancellationException("finalization write itself cancelled") }

        assertTrue(run.caught is CancellationException)
        assertSame(
            "new CE thrown by the finalization write must NOT replace the original cancellation",
            run.originalCe, run.caught
        )
        assertEquals(SyncOutcome.Status.CANCELLED, run.outcomes.single().status)
    }

    // -------------------------------------------------------------------------
    // BUG-001 — DELETE_BOTH remote-first（计划 §BUG-001.3）
    // 远端未 ack 前不动本地：防「行已删 + 远端仍在」被下次 dryRun 复活为 DOWNLOAD。
    // -------------------------------------------------------------------------

    /** 既有行（既有删除路径放行，走到 local/remote 删除）。 */
    private fun ownedRow(): VideoItemEntity = mockk {
        every { id } returns 7L
    }

    @Test
    fun `remote delete failure preserves local row, ciphertext and playback record`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // 远端失败 → 本地状态必须原样保留（行/密文/缩略图/播放记录）
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 0) { store.deleteThumb(any<Long>()) }
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
        coVerify(exactly = 0) { playbackRepository.deleteByVideoId(any()) }
    }

    @Test
    fun `remote ack precedes local deletion`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        coEvery { store.delete(7L) } returns true
        coEvery { store.deleteThumb(7L) } returns true

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))

        // remote-first 语义：远端 ack 必须先于任何本地删除副作用
        coVerifyOrder {
            client.deleteFile(any())
            store.delete(7L)
        }
    }

    @Test
    fun `remote success with local delete failure keeps row for mirror DELETE_LOCAL self-heal`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        // 本地密文删不掉（delete false + checkExistence EXISTS → mediaGone=false）
        coEvery { store.delete(7L) } returns false
        coEvery { store.checkExistence(7L) } returns MediaExistence.EXISTS

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // 行必须保留：下次镜像 dryRun 会推导 DELETE_LOCAL 自愈；远端已删过恰一次
        coVerify(exactly = 1) { client.deleteFile(any()) }
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
    }

    @Test
    fun `local delete probe returns IO_ERROR preserves row and playback record`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        coEvery { store.delete(7L) } returns false
        coEvery { store.checkExistence(7L) } returns MediaExistence.IO_ERROR("AccessDeniedException")

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // Fail-closed 红线：探测失败绝不可视为文件不存在，必须保留行与播放记录
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
        coVerify(exactly = 0) { playbackRepository.deleteByVideoId(any()) }
    }

    @Test
    fun `local delete probe returns NOT_FOUND treats as deleted and cleans up row`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        coEvery { store.delete(7L) } returns false
        coEvery { store.checkExistence(7L) } returns MediaExistence.NOT_FOUND
        coEvery { store.deleteThumb(7L) } returns true

        assertTrue(sut.executeDeleteLocked(1L, listOf(action)))

        // 严格确认文件已不在（NOT_FOUND）时视为成功，清理行与播放记录
        coVerify(exactly = 1) { videoRepository.deleteByRelativePathAndRelationId("a.mp4", 1L) }
        coVerify(exactly = 1) { playbackRepository.deleteByVideoId(7L) }
    }

    @Test
    fun `local thumb delete probe returns IO_ERROR preserves row and playback record`() = runTest {
        stubBasics()
        coEvery { videoRepository.getByRelativePathAndRelationId("a.mp4", 1L) } returns ownedRow()
        coEvery { client.deleteFile(any()) } returns true
        coEvery { store.delete(7L) } returns true
        coEvery { store.deleteThumb(7L) } returns false
        coEvery { store.checkThumbExistence(7L) } returns MediaExistence.IO_ERROR("FileSystemException")

        assertFalse(sut.executeDeleteLocked(1L, listOf(action)))

        // 缩略图探测失败同样保护：不得擦除行与播放记录
        coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }
        coVerify(exactly = 0) { playbackRepository.deleteByVideoId(any()) }
    }
}
