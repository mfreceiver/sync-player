package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.entity.PendingSyncActionEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>()
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>()
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>()
    private val mediaFileStore = mockk<MediaFileStore>(relaxed = true)
    private val mediaFileStoreLazy = mockk<Lazy<MediaFileStore>>()

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mediaFileStoreLazy.get() } returns mediaFileStore
        syncEngine = SyncEngine(
            syncSourceRepository = syncSourceRepository,
            syncRelationRepository = syncRelationRepository,
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncLogRepository = syncLogRepository,
            videoMetadataExtractor = videoMetadataExtractor,
            imageMetadataExtractor = imageMetadataExtractor,
            pendingSyncActionDao = pendingSyncActionDao,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            mediaFileStoreLazy = mediaFileStoreLazy,
            context = context
        )
    }

    @Test
    fun `consumePendingTask should mark rows RUNNING`() = runTest {
        val entities = listOf(
            PendingSyncActionEntity(
                relationId = 1L,
                relationName = "test-relation",
                localBasePath = "/test",
                actionGroup = 0,
                actionType = "DOWNLOAD",
                relativePath = "video.mp4",
                fileSize = 1000L,
                remoteModifiedTime = 12345L
            )
        )
        coEvery { pendingSyncActionDao.resetRunningToPending(1L) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(1L) } returns entities
        coEvery { pendingSyncActionDao.markRunning(1L) } returns Unit

        val task = syncEngine.consumePendingTask(1L)

        assertNotNull(task)
        assertEquals(1, task!!.toDownload.size)
        assertEquals(0, task.toDelete.size)
        assertEquals("video.mp4", task.toDownload[0].relativePath)
        coVerify(exactly = 1) { pendingSyncActionDao.markRunning(1L) }
    }

    @Test
    fun `consumePendingTask should return null when no pending actions`() = runTest {
        coEvery { pendingSyncActionDao.resetRunningToPending(1L) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(1L) } returns emptyList()

        val task = syncEngine.consumePendingTask(1L)

        assertEquals(null, task)
        coVerify(exactly = 0) { pendingSyncActionDao.markRunning(any()) }
    }

    @Test
    fun `consumeAndExecute processes a task that becomes pending during the current run`() = runTest {
        val relationId = 1L
        val relation = SyncRelation(
            id = relationId,
            name = "rel",
            syncSourceId = 10L,
            remotePath = "/remote",
            localBasePath = "/local"
        )
        val source = SyncSource(
            id = 10L,
            name = "source",
            protocol = SyncProtocol.SFTP,
            host = "host",
            port = 22,
            username = "user",
            password = "password"
        )
        val firstTask = PendingSyncActionEntity(
            relationId = relationId,
            relationName = "rel",
            localBasePath = "/local",
            actionGroup = 1,
            actionType = SyncActionType.DELETE_LOCAL.name,
            relativePath = "first.mp4",
            fileSize = 1L
        )
        val secondTask = firstTask.copy(relativePath = "second.mp4")

        coEvery { pendingSyncActionDao.resetRunningToPending(relationId) } returns Unit
        coEvery { pendingSyncActionDao.getPendingByRelationId(relationId) } returnsMany listOf(
            listOf(firstTask),
            listOf(secondTask),
            emptyList()
        )
        coEvery { pendingSyncActionDao.markRunning(relationId) } returns Unit
        coEvery { pendingSyncActionDao.markCompleted(relationId) } returns Unit
        coEvery { pendingSyncActionDao.deleteByRelationId(relationId) } returns Unit
        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE
        coEvery {
            videoRepository.getByRelativePathAndRelationId(any(), relationId)
        } returns null

        val (lastTask, success) = syncEngine.consumeAndExecute(relationId)

        assertEquals("second.mp4", lastTask?.toDelete?.single()?.relativePath)
        assertEquals(true, success)
        coVerify(exactly = 2) { pendingSyncActionDao.markRunning(relationId) }
        coVerify(exactly = 2) { pendingSyncActionDao.markCompleted(relationId) }
        coVerify(exactly = 2) { videoRepository.getByRelativePathAndRelationId(any(), relationId) }
    }

    @Test
    fun `executeSync streams download through encrypted sink and finalizes`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"

        // 新文件：existing == null → upsert 占位返回 id
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 100L
        val savedEntity = VideoItemEntity(
            id = 100L, syncRelationId = relationId, relativePath = "video.mp4",
            fileName = "video.mp4", fileSize = 1000L
        )
        coEvery { videoRepository.getById(100L) } returns savedEntity
        coEvery { videoMetadataExtractor.extractAndSave(any()) } returns savedEntity
        // A2（rev-gpt 迁移）：成功路径的最终元数据走按列提交——本场景命中 1 行
        coEvery { videoRepository.updateSyncMetadata(100L, 1000L, any(), any()) } returns 1

        val sink = mockk<OutputStream>(relaxed = true)
        // AEAD v2: sink 需要明文长度以写入 header，故为三参重载。
        coEvery { mediaFileStore.openWriteSink(100L, any()) } returns sink
        coEvery { mediaFileStore.finalize(100L) } returns true

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        assertEquals(true, result)
        coVerify(atLeast = 1) { mediaFileStore.openWriteSink(100L, any()) }
        coVerify(atLeast = 1) { client.downloadTo(any(), any(), any()) }
        coVerify(atLeast = 1) { mediaFileStore.finalize(100L) }
        // 成功路径不应 cancel
        coVerify(exactly = 0) { mediaFileStore.cancel(100L) }
    }

    @Test
    fun `executeSync cancels and deletes placeholder when download fails`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        // downloadTo 始终失败
        coEvery { client.downloadTo(any(), any(), any()) } returns false
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"

        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 200L
        coEvery { videoRepository.deleteById(200L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(200L, any()) } returns sink
        coEvery { mediaFileStore.finalize(200L) } returns true
        coEvery { mediaFileStore.cancel(200L) } returns Unit

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        // 全部失败 → return false
        assertEquals(false, result)
        // 失败应 cancel 并删除占位
        coVerify(atLeast = 1) { mediaFileStore.cancel(200L) }
        coVerify(atLeast = 1) { videoRepository.deleteById(200L) }
    }

    @Test
    fun `executeSync rolls back finalize when read probe fails`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"

        // 新文件：existing == null → upsert 占位返回 id
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 300L
        coEvery { videoRepository.deleteById(300L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(300L, any()) } returns sink
        coEvery { mediaFileStore.finalize(300L) } returns true
        coEvery { mediaFileStore.cancel(300L) } returns Unit
        // finalize 成功，但可读性探针失败（模拟 SAF 不支持 seek / 密文不可读）
        coEvery { mediaFileStore.verifyReadable(300L, 1000L) } throws IOException("simulated unreadable")
        coEvery { mediaFileStore.delete(300L) } returns true

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val result = syncEngine.executeSync(relationId, listOf(action), emptyList())

        // 探针失败 → 回滚 → 整体失败
        assertEquals(false, result)
        // 探针回滚：删除已 finalize 的密文 + 删除占位 DB 行
        coVerify(atLeast = 1) { mediaFileStore.verifyReadable(300L, 1000L) }
        coVerify(atLeast = 1) { mediaFileStore.delete(300L) }
        coVerify(atLeast = 1) { videoRepository.deleteById(300L) }
    }

    @Test
    fun `executeSync propagates CancellationException from read probe without failed download`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 301L
        coEvery { videoRepository.deleteById(301L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(301L, any()) } returns sink
        coEvery { mediaFileStore.finalize(301L) } returns true
        coEvery { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) } coAnswers {
            throw CancellationException("cancel")
        }

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        val thrown = try {
            syncEngine.executeSync(relationId, listOf(action), emptyList())
            null
        } catch (e: Throwable) {
            e
        }
        coVerify(exactly = 1) { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) }

        assertEquals(SyncPhase.CANCELLED, syncEngine.progress.value.phase)
        assertNotNull(thrown)
        assertEquals(CancellationException::class, thrown!!::class)
        assertEquals(0, syncEngine.progress.value.failedFiles)
    }

    @Test
    fun `executeSync read probe rollback delete throwing CE keeps original failure and counts failed download`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 302L
        coEvery { videoRepository.deleteById(302L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(302L, any()) } returns sink
        coEvery { mediaFileStore.finalize(302L) } returns true
        coEvery { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) } coAnswers {
            throw IOException("simulated unreadable")
        }
        coEvery { mediaFileStore.delete(any()) } coAnswers {
            throw CancellationException("cancel during rollback")
        }

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        var returned = true
        val thrown = try {
            returned = syncEngine.executeSync(relationId, listOf(action), emptyList())
            null
        } catch (e: Throwable) {
            e
        }
        coVerify(exactly = 1) { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) }

        // BUG-007 v3.1 语义（guarded「原异常优先」）：COMMITTED 回滚 delete 抛出的 CE 被吞并，
        // 原 STORAGE_ERROR 胜出——计为 per-action 失败（PARTIAL_FAILED），不传播取消。
        assertNull(thrown)
        assertEquals(false, returned)
        assertEquals(SyncPhase.PARTIAL_FAILED, syncEngine.progress.value.phase)
        assertEquals(1, syncEngine.progress.value.failedFiles)
        coVerify(exactly = 1) { mediaFileStore.delete(302L) }
        coVerify(exactly = 1) { videoRepository.deleteById(302L) }
    }

    @Test
    fun `executeSync cleanup failure in committed rollback does not mask original error`() = runTest {
        val relationId = 1L
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L

        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE

        val client = mockk<RemoteFileClient>(relaxed = true)
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        every { storagePaths.toLocalRelativePath("/local", "video.mp4") } returns "video.mp4"
        coEvery { videoRepository.getByRelativePathAndRelationId("video.mp4", relationId) } returns null
        coEvery { videoRepository.upsert(any()) } returns 303L
        coEvery { videoRepository.deleteById(303L) } returns Unit

        val sink = mockk<OutputStream>(relaxed = true)
        coEvery { mediaFileStore.openWriteSink(303L, any()) } returns sink
        coEvery { mediaFileStore.finalize(303L) } returns true
        coEvery { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) } coAnswers {
            throw IOException("simulated unreadable")
        }
        coEvery { mediaFileStore.delete(any()) } coAnswers {
            throw CancellationException("cancel during rollback")
        }
        coEvery { mediaFileStore.cancel(303L) } coAnswers {
            throw IOException("cleanup failed")
        }

        val action = SyncAction(type = SyncActionType.DOWNLOAD, relativePath = "video.mp4", fileSize = 1000L)
        var returned = true
        val thrown = try {
            returned = syncEngine.executeSync(relationId, listOf(action), emptyList())
            null
        } catch (e: Throwable) {
            e
        }

        // BUG-007 v3.1 语义（guarded「原异常优先」）：回滚 delete 的 CE 被吞并、原 STORAGE_ERROR
        // 胜出计为失败；COMMITTED 分支不调 cancel；行清理在吞并后照常执行。
        assertNull(thrown)
        assertEquals(false, returned)
        assertEquals(SyncPhase.PARTIAL_FAILED, syncEngine.progress.value.phase)
        assertEquals(1, syncEngine.progress.value.failedFiles)
        coVerify(exactly = 1) { mediaFileStore.verifyReadable(any<Long>(), any<Long>()) }
        coVerify(exactly = 1) { mediaFileStore.delete(303L) }
        coVerify(exactly = 0) { mediaFileStore.cancel(303L) }
        coVerify(exactly = 1) { videoRepository.deleteById(303L) }
    }

    // ------------------------------------------------------------------
    // BUG-018（plan v3.1 §4 引擎侧表征用例 #18/#19）
    // ------------------------------------------------------------------

    /** 有状态 fake DAO：内存行表支撑 consume/reset/finalize 全套语义（#18/#19 专用）。 */
    private fun fakeDaoRows(seed: List<PendingSyncActionEntity>): MutableList<PendingSyncActionEntity> {
        val rows = mutableListOf<PendingSyncActionEntity>().apply { addAll(seed) }
        coEvery { pendingSyncActionDao.deleteByRelationId(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.removeAll { it.relationId == rel }
        }
        coEvery { pendingSyncActionDao.insertAll(any()) } coAnswers {
            rows.addAll(firstArg())
        }
        coEvery { pendingSyncActionDao.replaceForRelation(any(), any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.removeAll { it.relationId == rel }
            rows.addAll(secondArg())
        }
        coEvery { pendingSyncActionDao.getPendingByRelationId(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.filter { it.relationId == rel && it.status == "PENDING" }
        }
        coEvery { pendingSyncActionDao.resetRunningToPending(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.replaceAll { if (it.relationId == rel && it.status == "RUNNING") it.copy(status = "PENDING") else it }
        }
        coEvery { pendingSyncActionDao.markRunning(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.replaceAll { if (it.relationId == rel && it.status == "PENDING") it.copy(status = "RUNNING") else it }
        }
        coEvery { pendingSyncActionDao.markCompleted(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.replaceAll { if (it.relationId == rel) it.copy(status = "COMPLETED") else it }
        }
        coEvery { pendingSyncActionDao.markFailed(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.replaceAll { if (it.relationId == rel) it.copy(status = "FAILED") else it }
        }
        coEvery { pendingSyncActionDao.markCancelled(any()) } coAnswers {
            val rel = firstArg<Long>()
            rows.replaceAll { if (it.relationId == rel) it.copy(status = "CANCELLED") else it }
        }
        return rows
    }

    /** 轻量 pass 桩：空 toDownload + DELETE_LOCAL（无 DB 行→计成功）即可走完 executeSyncLocked。 */
    private fun stubLightPass(relationId: Long): SyncRelation {
        val relation = mockk<SyncRelation>()
        every { relation.id } returns relationId
        every { relation.syncSourceId } returns 10L
        every { relation.remotePath } returns "/remote"
        every { relation.localBasePath } returns "/local"
        every { relation.name } returns "rel"
        val source = mockk<SyncSource>()
        every { source.protocol } returns SyncProtocol.SFTP
        every { source.id } returns 10L
        coEvery { syncRelationRepository.getById(relationId) } returns relation
        coEvery { syncSourceRepository.getById(10L) } returns source
        every { mediaFileStore.usableSpace() } returns Long.MAX_VALUE
        every { remoteFileClientFactory.create(SyncProtocol.SFTP) } returns mockk(relaxed = true)
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), any()) } returns null
        return relation
    }

    @Test
    fun `consumeAndExecute does not observe rows enqueued mid-drain`() = runTest {
        val relation = stubLightPass(1L)
        val gate1 = CompletableDeferred<Unit>()
        val pass1Entered = CompletableDeferred<Unit>()
        coEvery { syncRelationRepository.getById(1L) } coAnswers {
            pass1Entered.complete(Unit)
            gate1.await()
            relation
        }
        val rows = fakeDaoRows(
            listOf(
                PendingSyncActionEntity(
                    relationId = 1L, relationName = "A", localBasePath = "/local",
                    actionGroup = 1, actionType = "DELETE_LOCAL", relativePath = "a-stale.mp4"
                )
            )
        )

        val drain = launch { syncEngine.consumeAndExecute(1L) }
        runCurrent()
        assertTrue("pass#1 must be inside executeSyncLocked holding the mutex", pass1Entered.isCompleted)

        // 并发入队 B：setPendingTask 阻塞在 storageOperationMutex（锁被整个 drain 持有）——
        // 锁序决定 B 行必然晚于 pass#1 退出写入（BUG-018 §1 根因的引擎侧现实）
        val enqueue = launch {
            syncEngine.setPendingTask(
                PendingSyncTask(
                    relationId = 1L, relationName = "B", localBasePath = "/local",
                    toDownload = emptyList(),
                    toDelete = listOf(SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "b-stale.mp4"))
                )
            )
        }
        runCurrent()
        assertFalse("B row cannot land while pass#1 holds the mutex", rows.any { it.relationName == "B" })

        gate1.complete(Unit)
        drain.join()
        enqueue.join()

        // pass#1 返回后：A 行被 finalize 清除；B 行在表（PENDING）——同一次 drain 不可见
        assertEquals(1, rows.size)
        assertEquals("B", rows.single().relationName)
        assertEquals("PENDING", rows.single().status)

        // 显式再次 drain（协调器的下一次 pass）才消费 B
        val (task, _) = syncEngine.consumeAndExecute(1L)
        assertNotNull(task)
        assertEquals("B", task!!.relationName)
        assertTrue("finalize must clear B rows after the recovery pass", rows.isEmpty())
    }

    @Test
    fun `pass cancelled while waiting for lock leaves row recoverable`() = runTest {
        stubLightPass(2L)
        val rows = fakeDaoRows(
            listOf(
                PendingSyncActionEntity(
                    relationId = 2L, relationName = "B", localBasePath = "/local",
                    actionGroup = 1, actionType = "DELETE_LOCAL", relativePath = "b-stale.mp4"
                )
            )
        )

        // 外部持锁：模拟另一 pass 正在 storageOperationMutex 内
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = launch {
            syncEngine.storageOperationMutex.withLock {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        runCurrent()
        assertTrue(lockHeld.isCompleted)

        // consumeAndExecute 已启动、阻塞在等锁——取消落在「取出后、获 M 前」
        val job = launch { syncEngine.consumeAndExecute(2L) }
        runCurrent()
        assertTrue("consumeAndExecute must be blocked on the mutex", job.isActive)
        job.cancelAndJoin()

        // B 行未被消费清除，状态仍 PENDING（markRunning 未发生）——弃置语义的行持久
        assertEquals(1, rows.size)
        assertEquals("PENDING", rows.single().status)

        releaseLock.complete(Unit)
        holder.join()

        // 恢复路径：同关系再次 drain 正常消费执行
        val (task, _) = syncEngine.consumeAndExecute(2L)
        assertNotNull(task)
        assertEquals("B", task!!.relationName)
        assertTrue(rows.isEmpty())
    }
}
