package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test for CQ-001: stale `assignedId` after attempt-1 placeholder deletion.
 *
 * When a NEW file download fails transiently on attempt 1, cleanUpAttempt deletes the
 * placeholder row. `assignedId` must be reset to 0L so attempt 2 recreates the placeholder row,
 * avoiding the false STORAGE_ERROR / deletion of the finalized file.
 */
class SyncDownloadPipelineRetryTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)
    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
    )

    private fun dlAction(name: String = "f.mp4") = SyncAction(SyncActionType.DOWNLOAD, name, 1000L, 0L)

    private val rows = LinkedHashMap<Long, VideoItemEntity>()
    private val nextId = AtomicLong(1L)
    private val storeCalls = CopyOnWriteArrayList<String>()

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { store.openWriteSink(any(), any()) } coAnswers {
            storeCalls.add("openSink:${firstArg<Long>()}")
            mockk<OutputStream>(relaxed = true)
        }
        coEvery { store.finalize(any()) } coAnswers {
            storeCalls.add("finalize:${firstArg<Long>()}")
            true
        }
        coEvery { store.delete(any()) } coAnswers {
            storeCalls.add("delete:${firstArg<Long>()}")
            true
        }
        coEvery { store.cancel(any()) } coAnswers {
            storeCalls.add("cancel:${firstArg<Long>()}")
            true
        }
        coEvery { store.verifyReadable(any<Long>(), any<Long>()) } coAnswers {
            storeCalls.add("verify:${firstArg<Long>()}")
        }
        // Stateful fake video repository: NEW file (no existing row); upsert assigns fresh ids.
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
        coEvery { videoRepository.getById(any()) } coAnswers { rows[firstArg<Long>()] }
        coEvery { videoRepository.upsert(any()) } coAnswers {
            val e = firstArg<VideoItemEntity>()
            if (e.id == 0L) {
                val id = nextId.getAndIncrement()
                rows[id] = e.copy(id = id)
                id
            } else {
                rows[e.id] = e
                e.id
            }
        }
        // A1: fake 列提交——行存在改 3 列返回 1；行消失返回 0（不复活）
        coEvery { videoRepository.updateSyncMetadata(any(), any(), any(), any()) } coAnswers {
            val id = firstArg<Long>()
            val row = rows[id] ?: return@coAnswers 0
            rows[id] = row.copy(
                fileSize = secondArg(),
                remoteModifiedTime = thirdArg(),
                dateSynced = arg(3),
            )
            1
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }
    }

    private fun stubFlakyClient(transientFailures: Int): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        val downloadCalls = AtomicInteger(0)
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            if (downloadCalls.incrementAndGet() <= transientFailures) {
                throw SyncException(SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD, path = "f.mp4")
            }
            true
        }
        return client
    }

    @Test
    fun `in-run retry of new file after transient failure succeeds and never deletes committed file`() = runTest {
        stubBasics()
        stubFlakyClient(transientFailures = 1)

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue("sync must succeed when retry attempt 2 completes the transfer", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { store.finalize(2L) }
        coVerify(exactly = 1) { store.verifyReadable(2L, 1000L) }
        assertEquals(1, rows.size)
        assertEquals(1000L, rows[2L]?.fileSize)
    }

    @Test
    fun `in-run retry of new file succeeds after two transient failures`() = runTest {
        stubBasics()
        stubFlakyClient(transientFailures = 2)

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue("sync must succeed on attempt 3 after 2 transient failures", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { store.finalize(3L) }
        assertEquals(1, rows.size)
        assertEquals(1000L, rows[3L]?.fileSize)
    }

    @Test
    fun `retry succeeds when reconnect encounters transient failure before succeeding`() = runTest {
        stubBasics()
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client

        // First download fails with transient error, triggering retry attempt 2 with reconnect
        coEvery { client.downloadTo(any(), any(), any()) } throws
            SyncException(SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD, path = "f.mp4") andThen true

        // Reconnect behavior:
        // 1st connect is initial (succeeds)
        // 2nd connect is attempt 2 first try (throws transient NETWORK_UNAVAILABLE)
        // 3rd connect is attempt 2 retry via connectWithRetry (succeeds)
        val connectCalls = AtomicInteger(0)
        coEvery { client.connect(any(), any()) } coAnswers {
            val call = connectCalls.incrementAndGet()
            if (call == 2) {
                throw SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.DOWNLOAD)
            }
            Unit
        }

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue("sync must succeed when reconnect succeeds after transient failure", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        coVerify(exactly = 0) { store.delete(any()) }
        coVerify(exactly = 1) { store.finalize(2L) }
        assertEquals(1, rows.size)
        assertEquals(1000L, rows[2L]?.fileSize)
    }
}
