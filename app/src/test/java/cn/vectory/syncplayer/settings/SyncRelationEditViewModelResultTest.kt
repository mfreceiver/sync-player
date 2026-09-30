package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.SyncSourceCredentialUncertainException
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClient
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * F09 contract tests — typed [RelationTestResult]/[RelationSaveResult] emissions from
 * [SyncRelationEditViewModel].
 *
 * The ViewModel carries ONLY the type + structured [SyncErrorCode] (display copy is
 * mapped at the Screen via stringResource). Error-code fallbacks for non-SyncException
 * throwables mirror [cn.vectory.syncplayer.sync.syncErrorMessage]'s classification so
 * the Screen can rebuild the same resources:
 *  - SocketTimeoutException → TIMEOUT
 *  - UnknownHostException   → UNKNOWN_HOST (timeout-family display)
 *  - anything else          → UNKNOWN
 *
 * Red lines under test: CancellationException propagates (never converted to a
 * failure result or an error message); structured errors never fall back to
 * Throwable.message. Conventions: assert types/enums/call counts, never user copy
 * (context stubs return "res-<id>" so the fetched resource is asserted).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRelationEditViewModelResultTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>(relaxed = true)
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val client = mockk<RemoteFileClient>(relaxed = true)

    private lateinit var vm: SyncRelationEditViewModel

    private fun createVm() = SyncRelationEditViewModel(
        syncRelationRepository, syncSourceRepository, remoteFileClientFactory,
        storagePaths, context, UnconfinedTestDispatcher(), SavedStateHandle()
    )

    private suspend fun <T> CompletableDeferred<T>.lateResult(): T = suspendCoroutine { continuation ->
        invokeOnCompletion { failure ->
            if (failure == null) continuation.resume(getCompleted())
            else continuation.resumeWithException(failure)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { syncSourceRepository.getAll() } returns listOf(
            SyncSource(
                id = 1L, name = "smb", protocol = SyncProtocol.SMB,
                host = "h", port = 1, username = "u", password = ""
            )
        )
        coEvery { syncSourceRepository.getById(1L) } returns SyncSource(
            id = 1L, name = "smb", protocol = SyncProtocol.SMB,
            host = "h", port = 1, username = "u", password = "fresh"
        )
        every { storagePaths.normalizeAndValidateRelativePath(any()) } returns "base"
        coEvery { syncRelationRepository.checkBasePathConflict(any(), any()) } returns emptyList()
        every { remoteFileClientFactory.create(any()) } returns client
        // Assert WHICH resource is fetched, never its localized value.
        every { context.getString(any()) } answers { "res-${firstArg<Int>()}" }
        every { context.getString(any(), any()) } answers { "res-${firstArg<Int>()}" }
        vm = SyncRelationEditViewModel(
            syncRelationRepository = syncRelationRepository,
            syncSourceRepository = syncSourceRepository,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(),
            savedStateHandle = SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // --- testRemotePath typed emissions ---

    @Test
    fun `list needs no credentials and probes read only selected source`() = runTest {
        coVerify(exactly = 0) { syncSourceRepository.getAllWithCredentials() }
        coVerify(exactly = 0) { syncSourceRepository.getById(any()) }
        assertEquals(1, vm.availableSources.value.size)
        vm.selectSource(1L)
        vm.setRemotePath("media")
        vm.testRemotePath()
        coVerify(exactly = 1) { syncSourceRepository.getById(1L) }
        coVerify(exactly = 1) { client.connect(match { it.password == "fresh" }, "media") }
    }

    @Test
    fun `uncertain selected credentials show recovery and never connect`() = runTest {
        coEvery { syncSourceRepository.getById(1L) } throws SyncSourceCredentialUncertainException(1L)
        vm.selectSource(1L)
        vm.setRemotePath("media")
        vm.testRemotePath()
        assertEquals(RelationTestResult.CredentialsRequired, vm.testResult.value)
        vm.browseRemote()
        assertEquals(true, vm.credentialsRequired.value)
        assertFalse(vm.isBrowsing.value)
        coVerify(exactly = 0) { client.connect(any(), any()) }
    }

    @Test
    fun `metadata load failure can retry and cancellation stays cancellation`() = runTest {
        coEvery { syncSourceRepository.getAll() } throws IllegalStateException("private detail")
        vm = createVm()
        assertEquals(RelationEditLoadState.FAILED, vm.loadState.value)
        coEvery { syncSourceRepository.getAll() } throws CancellationException()
        val job = vm.retryLoad()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(RelationEditLoadState.LOADING, vm.loadState.value)
        coEvery { syncSourceRepository.getAll() } returns emptyList()
        vm.retryLoad().join()
        assertEquals(RelationEditLoadState.READY, vm.loadState.value)
        coVerify(exactly = 0) { syncSourceRepository.getAllWithCredentials() }
    }

    @Test
    fun `old metadata success or failure cannot replace newer load and edit`() = runTest {
        for (failOld in listOf(false, true)) {
            val old = CompletableDeferred<List<SyncSource>>()
            val fresh = CompletableDeferred<List<SyncSource>>()
            var calls = 0
            coEvery { syncSourceRepository.getAll() } coAnswers {
                if (calls++ == 0) old.lateResult() else fresh.await()
            }
            try {
                val edit = createVm()
                edit.retryLoad()
                fresh.complete(listOf(SyncSource(2L, "fresh", SyncProtocol.SFTP, "h", 22, "u", "")))
                edit.setName("user edit")
                if (failOld) old.completeExceptionally(IllegalStateException()) else old.complete(emptyList())
                advanceUntilIdle()
                assertEquals(RelationEditLoadState.READY, edit.loadState.value)
                assertEquals("user edit", edit.name.value)
                assertEquals(listOf(2L), edit.availableSources.value.map { it.id })
                edit.retryLoad().join()
                assertEquals(2, calls)
            } finally {
                old.complete(emptyList())
                fresh.complete(emptyList())
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `switching source isolates late browse success uncertain and ordinary failure`() = runTest {
        val b = SyncSource(2L, "b", SyncProtocol.SFTP, "b", 22, "u", "fresh-b")
        val a = SyncSource(1L, "a", SyncProtocol.SMB, "a", 445, "u", "")
        coEvery { syncSourceRepository.getAll() } returns listOf(a, b.copy(password = ""))
        coEvery { syncSourceRepository.getById(2L) } returns b
        for (outcome in 0..2) {
            val old = CompletableDeferred<List<RemoteFile>>()
            val fresh = CompletableDeferred<List<RemoteFile>>()
            coEvery { client.listDirectories("/a/b") } coAnswers { old.lateResult() }
            coEvery { client.listDirectories("/b") } coAnswers { fresh.await() }
            try {
            val edit = createVm()
            edit.selectSource(1L)
            edit.setRemotePath("/a/b")
            edit.browseRemote()
            edit.navigateRemoteUp()
            assertEquals("/a/b", edit.currentRemoteBrowsePath.value)
            assertFalse(edit.canSelectRemotePath.value)
            edit.selectSource(2L)
            edit.setRemotePath("/b")
            edit.browseRemote()
            if (outcome != 0) {
                fresh.complete(emptyList())
                assertTrue(edit.canSelectRemotePath.value)
            }
            when (outcome) {
                0 -> old.complete(listOf(RemoteFile("/a/b/stale", "stale", true)))
                1 -> old.completeExceptionally(SyncSourceCredentialUncertainException(1L))
                else -> old.completeExceptionally(IllegalStateException())
            }
            advanceUntilIdle()
            if (outcome == 0) {
                assertTrue(edit.isBrowsing.value)
                assertFalse(edit.canSelectRemotePath.value)
                fresh.complete(emptyList())
                advanceUntilIdle()
            }
            assertEquals("/b", edit.currentRemoteBrowsePath.value)
            assertFalse(edit.credentialsRequired.value)
            assertNull(edit.browseError.value)
            assertFalse(edit.isBrowsing.value)
            assertTrue(edit.remoteDirectories.value.isEmpty())
            assertTrue(edit.canSelectRemotePath.value)
            assertTrue(edit.selectRemotePath())
            coVerify { client.connect(match { it.id == 1L }, "/a/b") }
            coVerify(exactly = 0) { client.listDirectories("/a") }
            } finally {
                old.complete(emptyList())
                fresh.complete(emptyList())
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `directory selection requires current successful listing including empty directory`() = runTest {
        coEvery { client.listDirectories(any()) } returns emptyList()
        vm.selectSource(1L)
        vm.setRemotePath("/valid")
        vm.browseRemote()
        assertTrue(vm.canSelectRemotePath.value)
        assertTrue(vm.selectRemotePath())
        for (failure in listOf(IllegalStateException(), SyncSourceCredentialUncertainException(1L))) {
            coEvery { syncSourceRepository.getById(1L) } throws failure
            vm.browseRemote()
            assertFalse(vm.canSelectRemotePath.value)
            assertFalse(vm.selectRemotePath())
        }
        vm.selectSource(99L)
        vm.browseRemote()
        assertFalse(vm.canSelectRemotePath.value)
        assertFalse(vm.selectRemotePath())
    }

    @Test
    fun `late probe cannot replace newer source result or its busy flag`() = runTest {
        val old = CompletableDeferred<List<RemoteFile>>()
        val fresh = CompletableDeferred<List<RemoteFile>>()
        coEvery { client.listDirectories("/old") } coAnswers { old.lateResult() }
        coEvery { client.listDirectories("/new") } coAnswers { fresh.await() }
        try {
        vm.selectSource(1L)
        vm.setRemotePath("/old")
        vm.testRemotePath()
        vm.setRemotePath("/new")
        vm.testRemotePath()
        old.completeExceptionally(SyncSourceCredentialUncertainException(1L))
        advanceUntilIdle()
        assertTrue(vm.isTesting.value)
        assertNull(vm.testResult.value)
        fresh.complete(emptyList())
        advanceUntilIdle()
        assertEquals(RelationTestResult.DirAccessible, vm.testResult.value)
        assertFalse(vm.credentialsRequired.value)
        assertFalse(vm.isTesting.value)
        } finally {
            old.complete(emptyList())
            fresh.complete(emptyList())
            advanceUntilIdle()
        }
    }

    @Test
    fun `credential read ordinary failure is structured and cancellation produces no result`() = runTest {
        vm.selectSource(1L)
        vm.setRemotePath("media")
        coEvery { syncSourceRepository.getById(1L) } throws IllegalStateException("private detail")
        vm.testRemotePath()
        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.UNKNOWN), vm.testResult.value)
        coEvery { syncSourceRepository.getById(1L) } throws CancellationException()
        vm.testRemotePath()
        assertNull(vm.testResult.value)
        assertFalse(vm.isTesting.value)
        assertFalse(vm.credentialsRequired.value)
        coVerify(exactly = 0) { client.connect(any(), any()) }
    }

    @Test
    fun `test without selected source emits PreconditionFailed NO_SOURCE and never connects`() = runTest {
        vm.selectSource(99L)
        vm.testRemotePath()

        assertEquals(
            RelationTestResult.PreconditionFailed(RelationTestResult.PreconditionFailed.Reason.NO_SOURCE),
            vm.testResult.value
        )
        coVerify(exactly = 0) { remoteFileClientFactory.create(any()) }
        coVerify(exactly = 0) { client.connect(any(), any()) }
        assertFalse(vm.isTesting.value)
    }

    @Test
    fun `test with blank path emits PreconditionFailed NO_PATH and never connects`() = runTest {
        vm.selectSource(1L)
        vm.setRemotePath("   ")
        vm.testRemotePath()

        assertEquals(
            RelationTestResult.PreconditionFailed(RelationTestResult.PreconditionFailed.Reason.NO_PATH),
            vm.testResult.value
        )
        coVerify(exactly = 0) { client.connect(any(), any()) }
    }

    @Test
    fun `successful dir probe emits DirAccessible`() = runTest {
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.testRemotePath()

        assertEquals(RelationTestResult.DirAccessible, vm.testResult.value)
        coVerify(exactly = 1) { client.connect(any(), any()) }
        assertFalse(vm.isTesting.value)
    }

    @Test
    fun `SyncException PATH_NOT_FOUND emits AccessFailed with same code`() = runTest {
        coEvery { client.connect(any(), any()) } throws
            SyncException(SyncErrorCode.PATH_NOT_FOUND, SyncOperation.CONNECT)
        vm.selectSource(1L)
        vm.setRemotePath("missing/dir")
        vm.testRemotePath()

        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.PATH_NOT_FOUND), vm.testResult.value)
        assertFalse(vm.isTesting.value)
    }

    @Test
    fun `socket timeout emits AccessFailed TIMEOUT`() = runTest {
        coEvery { client.connect(any(), any()) } throws SocketTimeoutException("timed out")
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.testRemotePath()

        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.TIMEOUT), vm.testResult.value)
    }

    @Test
    fun `unknown host emits AccessFailed UNKNOWN_HOST`() = runTest {
        coEvery { client.connect(any(), any()) } throws UnknownHostException("nope")
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.testRemotePath()

        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.UNKNOWN_HOST), vm.testResult.value)
    }

    @Test
    fun `listDirectories ACCESS_DENIED after successful connect emits AccessFailed not DirAccessible`() = runTest {
        // listDirectories fail-loud 契约：connect 成功但枚举被拒必须报 AccessFailed，
        // 不得因"吞成空列表"而误报 DirAccessible。
        coEvery { client.listDirectories(any()) } throws
            SyncException(SyncErrorCode.ACCESS_DENIED, SyncOperation.BROWSE)
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.testRemotePath()

        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.ACCESS_DENIED), vm.testResult.value)
        assertFalse(vm.isTesting.value)
    }

    @Test
    fun `generic failure emits AccessFailed UNKNOWN without carrying throwable message`() = runTest {
        coEvery { client.connect(any(), any()) } throws RuntimeException("internal boom detail")
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.testRemotePath()

        // Structured-code only: no message text may leak into the result (red line 6/7).
        assertEquals(RelationTestResult.AccessFailed(SyncErrorCode.UNKNOWN), vm.testResult.value)
    }

    // --- save typed emission + red lines ---

    @Test
    fun `save failure keeps saveResult null and reports error resource`() = runTest {
        coEvery { syncRelationRepository.save(any()) } throws
            SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD)
        fillForm()
        vm.save()

        assertNull(vm.saveResult.value)
        assertEquals("res-${R.string.relation_save_failed}", vm.error.value)
        coVerify(exactly = 1) { syncRelationRepository.save(any()) }
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `save cancellation propagates and never writes a result`() = runTest {
        // Red line 3: CE must be rethrown. If it were swallowed by catch(Exception),
        // it would surface as saveResult/error — both must stay null.
        coEvery { syncRelationRepository.save(any()) } throws CancellationException("cancelled")
        fillForm()
        vm.save()
        advanceUntilIdle()

        assertNull(vm.saveResult.value)
        assertNull(vm.error.value)
        assertFalse(vm.isSaving.value)
    }

    // --- deleteRelation typed emission ---

    @Test
    fun `delete success emits Deleted and deletes once`() = runTest {
        val editVm = SyncRelationEditViewModel(
            syncRelationRepository = syncRelationRepository,
            syncSourceRepository = syncSourceRepository,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(),
            savedStateHandle = SavedStateHandle(mapOf("relationId" to 5L))
        )
        editVm.deleteRelation()

        assertEquals(RelationSaveResult.Deleted, editVm.saveResult.value)
        coVerify(exactly = 1) { syncRelationRepository.delete(5L) }
    }

    @Test
    fun `delete failure emits DeleteFailed without crashing`() = runTest {
        coEvery { syncRelationRepository.delete(5L) } throws RuntimeException("sqlite boom")
        val editVm = SyncRelationEditViewModel(
            syncRelationRepository = syncRelationRepository,
            syncSourceRepository = syncSourceRepository,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(),
            savedStateHandle = SavedStateHandle(mapOf("relationId" to 5L))
        )
        editVm.deleteRelation()

        assertEquals(RelationSaveResult.DeleteFailed, editVm.saveResult.value)
    }

    private fun fillForm() {
        vm.setName("rel")
        vm.selectSource(1L)
        vm.setRemotePath("media/movies")
        vm.setLocalBasePath("movies")
    }
}
