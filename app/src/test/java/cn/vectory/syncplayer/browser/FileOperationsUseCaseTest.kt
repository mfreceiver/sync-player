package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.SyncEngine
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Focused regression tests for [FileOperationsUseCase] CancellationException handling.
 *
 * The 3rd-round review (FAIL 7.6) flagged that the generic `catch (e: Exception)` blocks
 * scattered through [FileOperationsUseCase.deleteMediaByIdLocked] / [toggleTrashedChecked]
 * would swallow a [CancellationException] (it extends [Exception] in Kotlin coroutines),
 * silently breaking structured-concurrency cancellation. These tests pin each catch site
 * by forcing the underlying call to throw [CancellationException] and asserting that it
 * propagates verbatim instead of being masked as a [DeleteMediaResult.Failed] /
 * [ToggleTrashResult.Failed].
 *
 * Per project testing convention: assert on result types and coroutine propagation only —
 * never on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationsUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val mediaFileStore = mockk<MediaFileStore>(relaxed = true)
    private val mediaFileStoreLazy = mockk<Lazy<MediaFileStore>>()
    private val syncEngine = mockk<SyncEngine>(relaxed = true)

    private lateinit var useCase: FileOperationsUseCase
    private lateinit var context: android.content.Context
    private lateinit var cacheRoot: java.io.File

    @Before
    fun setup() {
        // SyncEngine exposes the storageOperationMutex as a public val; provide a real Mutex so
        // deleteMediaById can acquire it.
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()
        every { mediaFileStoreLazy.get() } returns mediaFileStore
        cacheRoot = tmp.newFolder("cache")
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheRoot
        useCase = FileOperationsUseCase(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            mediaFileStoreLazy = mediaFileStoreLazy,
            syncEngine = syncEngine,
            context = context
        )
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private fun videoEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "/base/test.mp4",
        fileName = "test.mp4",
        fileSize = 1000L
    )

    private fun matchingBasePaths(): List<SyncRelationWithSource> = listOf(
        SyncRelationWithSource(
            relation = SyncRelation(
                id = 1L,
                name = "rel",
                syncSourceId = 1L,
                remotePath = "/remote",
                localBasePath = "/base"
            ),
            sourceName = "src",
            sourceProtocol = SyncProtocol.SMB
        )
    )

    /**
     * Runs [block] in a real coroutine (not runTest virtual time — the storage mutex uses real
     * suspending) under a [supervisorScope] so a propagated [CancellationException] can be caught
     * at the child boundary without tearing down the test runner.
     *
     * Returns true iff a [CancellationException] propagated out of [block].
     */
    private fun propagatesCancellation(block: suspend () -> Unit): Boolean {
        var propagated = false
        runBlocking {
            supervisorScope {
                val child = launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        propagated = true
                        throw e // re-throw to surface on the child job
                    }
                }
                child.join()
            }
        }
        return propagated
    }

    // ===================================================================================
    // Tests — toggleTrashedChecked CancellationException propagation
    // ===================================================================================

    @Test
    fun `toggleTrashedChecked propagates CancellationException instead of returning Failed`() {
        val id = 1L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { playbackRepository.toggleTrashed(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation {
            useCase.toggleTrashedChecked(id, matchingBasePaths())
        }

        assertTrue("CancellationException must propagate, not be swallowed", propagated)
    }

    @Test
    fun `toggleTrashedChecked returns NotAllowed when video is missing`() = runTest {
        val id = 2L
        coEvery { videoRepository.getById(id) } returns null

        val result = useCase.toggleTrashedChecked(id, matchingBasePaths())

        assertEquals(ToggleTrashResult.NotAllowed, result)
    }

    @Test
    fun `toggleTrashedChecked returns NotAllowed when path is outside base dirs`() = runTest {
        val id = 4L
        // Entity path under a non-base dir.
        coEvery { videoRepository.getById(id) } returns VideoItemEntity(
            id = id,
            relativePath = "/other/test.mp4",
            fileName = "test.mp4",
            fileSize = 1000L
        )

        val result = useCase.toggleTrashedChecked(id, matchingBasePaths())

        assertEquals(ToggleTrashResult.NotAllowed, result)
    }

    @Test
    fun `toggleTrashedChecked returns Success on a normal toggle`() = runTest {
        val id = 3L
        val basePaths = matchingBasePaths()
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { playbackRepository.toggleTrashed(id) } returns Unit

        val result = useCase.toggleTrashedChecked(id, basePaths)

        assertEquals(ToggleTrashResult.Success, result)
    }

    // ===================================================================================
    // Tests — deleteMediaByIdLocked CancellationException propagation at each catch site
    // ===================================================================================

    @Test
    fun `deleteMediaById propagates CancellationException when fileMissing exists() throws`() {
        val id = 10L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from exists() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when delete() throws`() {
        val id = 11L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns true
        coEvery { mediaFileStore.delete(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from delete() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when stillThere exists() throws`() {
        val id = 12L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        // delete returns false → falls into the stillThere re-check branch
        coEvery { mediaFileStore.delete(id) } returns false
        // First exists() call (fileMissing check) returns true; second call (stillThere
        // re-check) throws CancellationException.
        var existsCallCount = 0
        coEvery { mediaFileStore.exists(id) } coAnswers {
            existsCallCount++
            if (existsCallCount == 1) true else throw CancellationException("cancel")
        }

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from stillThere exists() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when deleteThumb() throws`() {
        val id = 13L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns true
        coEvery { mediaFileStore.delete(id) } returns true
        coEvery { mediaFileStore.deleteThumb(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from deleteThumb() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when thumbExists re-check throws`() {
        val id = 14L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns true
        coEvery { mediaFileStore.delete(id) } returns true
        coEvery { mediaFileStore.deleteThumb(id) } returns false
        coEvery { mediaFileStore.thumbExists(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from thumbExists re-check must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when deleteMediaRecord throws`() {
        val id = 15L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns true
        coEvery { mediaFileStore.delete(id) } returns true
        coEvery { mediaFileStore.deleteThumb(id) } returns true
        coEvery { videoRepository.deleteMediaRecord(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from deleteMediaRecord() must propagate", propagated)
    }

    // ===================================================================================
    // Tests — normal (non-cancellation) behavior still works
    // ===================================================================================

    @Test
    fun `deleteMediaById returns Failed for non-cancellation exception in exists`() = runBlocking {
        val id = 20L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } throws java.io.IOException("real io error")

        val result = useCase.deleteMediaById(id)

        assertTrue("Non-cancellation exception must surface as Failed", result is DeleteMediaResult.Failed)
        assertFalse("Result must NOT propagate as Success/Skipped", result is DeleteMediaResult.Success)
    }

    @Test
    fun `deleteMediaById returns Success on a clean delete`() = runBlocking {
        val id = 21L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns true
        coEvery { mediaFileStore.delete(id) } returns true
        coEvery { mediaFileStore.deleteThumb(id) } returns true
        coEvery { videoRepository.deleteMediaRecord(id) } returns Unit

        val result = useCase.deleteMediaById(id)

        assertEquals(DeleteMediaResult.Success(id), result)
    }

    @Test
    fun `deleteMediaById returns SkippedNotFound when file is already missing`() = runBlocking {
        val id = 22L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { mediaFileStore.exists(id) } returns false
        coEvery { videoRepository.deleteMediaRecord(id) } returns Unit

        val result = useCase.deleteMediaById(id)

        assertEquals(DeleteMediaResult.SkippedNotFound(id), result)
    }

    // ===================================================================================
    // Tests — outer public launch-based methods propagate CancellationException (FAIL 8.7)
    // ===================================================================================
    //
    // The outer fire-and-forget methods (deleteFile / deleteFolder / etc.) wrap their body in
    // `scope.launch { try { … } catch (e: Exception) { errorFlow = … } }`. A bare `catch (Exception)`
    // swallows CancellationException (breaks structured concurrency). These tests force the
    // underlying call to throw CancellationException and assert that the launch block rethrows it
    // (does NOT write errorFlow and does NOT invoke onSuccess) — i.e. the child coroutine is
    // cancelled, not turned into a silent failure.

    /** Joins all child jobs of [scope] so the fire-and-forget launch completes before assertions. */
    private suspend fun joinChildren(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]!!
        // Children list is lazy; iterate to completion.
        job.children.toList().forEach { it.join() }
    }

    @Test
    fun `deleteFile propagates CancellationException without writing errorFlow or calling onSuccess`() = runBlocking {
        val id = 30L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // deleteFile → withContext(IO){ mutex.withLock{ deleteMediaByIdLocked(id) } }
        // deleteMediaByIdLocked → videoRepository.getById(id) → throws CancellationException.
        coEvery { videoRepository.getById(id) } throws CancellationException("cancel")

        useCase.deleteFile(id, scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        // CancellationException must propagate: no error written, no success callback.
        assertNull("errorFlow must not be written on cancellation", errorFlow.value)
        assertFalse("onSuccess must not run on cancellation", onSuccessCalled)
        scope.cancel()
    }

    @Test
    fun `deleteFolder propagates CancellationException without writing errorFlow or calling onSuccess`() = runBlocking {
        val path = "/otherfolder"
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Pass the guards: not a base dir, no descendant base, path not in basePaths (so
        // isFileInBaseDir returns false). Then deleteFolderContentsLocked → getByPathPrefix throws.
        coEvery { syncRelationRepository.getByLocalBasePath(path) } returns null
        coEvery { syncRelationRepository.getAllBasePaths() } returns emptyList()
        coEvery { videoRepository.getByPathPrefix(path) } throws CancellationException("cancel")

        useCase.deleteFolder(path, matchingBasePaths(), scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        assertNull("errorFlow must not be written on cancellation", errorFlow.value)
        assertFalse("onSuccess must not run on cancellation", onSuccessCalled)
        scope.cancel()
    }

    @Test
    fun `deleteFile writes errorFlow for a NON-cancellation exception`() = runBlocking {
        // Regression guard: only CancellationException is rethrown; real exceptions still surface.
        val id = 31L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { videoRepository.getById(id) } throws java.io.IOException("real io")

        useCase.deleteFile(id, scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        assertNotNull("non-cancellation exception must surface to errorFlow", errorFlow.value)
        assertFalse(onSuccessCalled)
        scope.cancel()
    }

    // ===================================================================================
    // Tests — buildShareIntent (direct provider URI path)
    // ===================================================================================

    @Test
    fun `buildShareIntent returns null when video is missing`() = runTest {
        val id = 100L
        coEvery { videoRepository.getById(id) } returns null

        val intent = useCase.buildShareIntent(id)

        assertNull("missing entity must surface as null (cannot share)", intent)
    }
}
