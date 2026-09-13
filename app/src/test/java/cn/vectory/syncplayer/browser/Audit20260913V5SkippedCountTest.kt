package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.SyncEngine
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V5 / CQ-005: FolderDeleteSummary must not double-count SkippedNotFound as BOTH `skipped` AND `success`.
 */
class Audit20260913V5SkippedCountTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val mediaFileStore = mockk<MediaFileStore>(relaxed = true)
    private val mediaFileStoreLazy = mockk<Lazy<MediaFileStore>>()
    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private lateinit var useCase: FileOperationsUseCase

    /** Format args (as Ints, any nesting flattened) of every error_folder_delete_partial call. */
    private val partialFmtArgs = CopyOnWriteArrayList<List<Int>>()

    @Before
    fun setup() {
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()
        every { mediaFileStoreLazy.get() } returns mediaFileStore
        coEvery { syncRelationRepository.getByLocalBasePath(any()) } returns null
        coEvery { syncRelationRepository.getAllBasePaths() } returns emptyList()
        useCase = FileOperationsUseCase(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            mediaFileStoreLazy = mediaFileStoreLazy,
            syncEngine = syncEngine,
            context = context
        )
        every { context.getString(any<Int>(), any(), any(), any()) } answers {
            val nums = invocation.args.drop(1).flatMap { flattenInts(it) }
            if (nums.size == 3) partialFmtArgs.add(nums)
            ""
        }
    }

    private fun flattenInts(a: Any?): List<Int> = when (a) {
        is Array<*> -> a.flatMap { flattenInts(it) }
        is Int -> listOf(a)
        is Long -> listOf(a.toInt())
        else -> emptyList()
    }

    private fun videoEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "base/v$id.enc",
        fileName = "v$id.enc",
        fileSize = 1000L
    )

    private val scopeEscapes = CopyOnWriteArrayList<Throwable>()

    private fun runTwoItemFolderDelete() {
        coEvery { videoRepository.getByPathPrefix("base") } returns listOf(videoEntity(1), videoEntity(2))
        coEvery { videoRepository.getById(1L) } returns videoEntity(1)
        coEvery { videoRepository.getById(2L) } returns videoEntity(2)
        coEvery { mediaFileStore.deleteThumb(any()) } returns true
        coEvery { mediaFileStore.checkThumbExistence(any()) } returns MediaExistence.NOT_FOUND
        coEvery { mediaFileStore.checkExistence(1L) } returns MediaExistence.NOT_FOUND
        coEvery { mediaFileStore.checkExistence(2L) } returns MediaExistence.IO_ERROR("io boom")

        val done = CompletableDeferred<Unit>()
        val handler = CoroutineExceptionHandler { _, e -> scopeEscapes.add(e); done.complete(Unit) }
        useCase.deleteFolder(
            path = "base",
            basePaths = emptyList<cn.vectory.syncplayer.data.model.SyncRelationWithSource>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler),
            onError = { done.complete(Unit) },
            onSuccess = { done.complete(Unit) }
        )
        runBlocking { withTimeout(10_000) { done.await() } }
    }

    @Test
    fun `skipped items are not also counted as success`() {
        runTwoItemFolderDelete()

        assertEquals(
            "partial-failure message must have been produced " +
                "(captured=${partialFmtArgs}, escaped=${scopeEscapes.map { it.javaClass.simpleName + ":" + it.message }})",
            1,
            partialFmtArgs.size
        )
        val (success, skipped, failed) = partialFmtArgs[0]
        assertEquals(
            "success+skipped+failed must equal the item count (2); actual success=$success " +
                "skipped=$skipped failed=$failed — SkippedNotFound is double-counted as both " +
                "success and skipped",
            2,
            success + skipped + failed
        )
    }

    @Test
    fun `skipped item does not inflate success`() {
        runTwoItemFolderDelete()

        assertEquals(1, partialFmtArgs.size)
        val (success, skipped, failed) = partialFmtArgs[0]
        assertEquals(1, skipped)
        assertEquals(1, failed)
        assertEquals(0, success)
    }
}
