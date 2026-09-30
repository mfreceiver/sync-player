package cn.vectory.syncplayer.settings

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.sync.RemoteFileClient
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.SyncSourceCredentialUncertainException
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncProtocol
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
 * F09 contract tests — typed [SourceTestResult]/[SourceSaveResult] emissions from
 * [SyncSourceEditViewModel].
 *
 * The ViewModel carries ONLY the type + structured [SyncErrorCode]; display copy is
 * mapped at the Screen via stringResource against existing entries. Code fallbacks
 * for non-SyncException throwables mirror syncErrorMessage's classification
 * (SocketTimeout → TIMEOUT, UnknownHost → UNKNOWN_HOST, else → UNKNOWN).
 *
 * Delete contract: a source still referenced by sync relations is NEVER deleted —
 * the result is BlockedByRelations and delete is not called (fail-closed against
 * orphan ciphertext). CancellationException propagates (red line 3): it must never
 * be converted into a typed failure result.
 *
 * Conventions: assert types/enums/call counts, never user-visible text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSourceEditViewModelResultTest {

    private val syncSourceRepository = mockk<SyncSourceRepository>(relaxed = true)
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>(relaxed = true)
    private val client = mockk<RemoteFileClient>(relaxed = true)

    private lateinit var vm: SyncSourceEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { remoteFileClientFactory.create(any()) } returns client
        // sourceId defaults to 0L with an empty SavedStateHandle (create mode).
        vm = SyncSourceEditViewModel(
            syncSourceRepository = syncSourceRepository,
            remoteFileClientFactory = remoteFileClientFactory,
            savedStateHandle = SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // --- testConnection typed emissions ---

    private fun editVm() = SyncSourceEditViewModel(
        syncSourceRepository, remoteFileClientFactory, SavedStateHandle(mapOf("sourceId" to 7L))
    )

    private val info = SyncSource(7L, "nas", SyncProtocol.SFTP, "host", 22, "user", "untrusted")

    // Intentionally non-cancellable: completion resumes even after the VM cancels its job.
    private suspend fun <T> CompletableDeferred<T>.lateResult(): T = suspendCoroutine { continuation ->
        invokeOnCompletion { failure ->
            if (failure == null) continuation.resume(getCompleted())
            else continuation.resumeWithException(failure)
        }
    }

    @Test
    fun `old load success or failure cannot replace newer load and edits`() = runTest {
        for (failOld in listOf(false, true)) {
            val old = CompletableDeferred<SyncSource?>()
            val fresh = CompletableDeferred<SyncSource?>()
            var calls = 0
            coEvery { syncSourceRepository.getById(7L) } coAnswers {
                if (calls++ == 0) old.lateResult() else fresh.await()
            }
            try {
                val edit = editVm()
                edit.retryLoad()
                fresh.complete(info.copy(name = "fresh", password = "fresh-password"))
                edit.setName("user edit")
                if (failOld) old.completeExceptionally(IllegalStateException()) else old.complete(info)
                advanceUntilIdle()
                assertEquals(SourceEditLoadState.READY, edit.loadState.value)
                assertEquals("user edit", edit.name.value)
                assertEquals("fresh-password", edit.password.value)
                edit.retryLoad().join()
                assertEquals("user edit", edit.name.value)
                assertEquals(2, calls)
            } finally {
                old.complete(null)
                fresh.complete(null)
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `saving synchronously freezes all source inputs and prevents double submit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val save = CompletableDeferred<Long>()
        coEvery { syncSourceRepository.save(any()) } coAnswers { save.await() }
        vm.setName("name")
        vm.setHost("original")
        vm.setPassword("original-password")
        try {
        vm.save()
        vm.save()
        assertTrue(vm.isSaving.value)
        runCurrent()
        vm.setHost("changed")
        vm.setPassword("changed")
        vm.setName("changed")
        vm.setProtocol(SyncProtocol.SFTP)
        vm.setPort("99")
        vm.setUsername("changed")
        vm.confirmEmptyPassword(true)
        assertEquals("original", vm.host.value)
        assertEquals("original-password", vm.password.value)
        assertEquals("name", vm.name.value)
        assertEquals(SyncProtocol.SMB, vm.protocol.value)
        assertEquals("445", vm.port.value)
        assertEquals("", vm.username.value)
        assertFalse(vm.emptyPasswordConfirmed.value)
        save.complete(7L)
        advanceUntilIdle()
        assertEquals(SourceSaveResult.Saved, vm.saveResult.value)
        assertFalse(vm.isSaving.value)
        coVerify(exactly = 1) { syncSourceRepository.save(match { it.host == "original" && it.password == "original-password" }) }
        } finally {
            save.complete(7L)
            advanceUntilIdle()
        }
    }

    @Test
    fun `identity edits revoke empty confirmation but name edit does not`() = runTest {
        vm.confirmEmptyPassword(true)
        vm.setName("renamed")
        assertTrue(vm.emptyPasswordConfirmed.value)
        val changes = listOf<() -> Unit>(
            { vm.setHost("new") }, { vm.setPort("99") },
            { vm.setUsername("new") }, { vm.setProtocol(SyncProtocol.SFTP) }
        )
        changes.forEach { change ->
            vm.confirmEmptyPassword(true)
            change()
            assertFalse(vm.emptyPasswordConfirmed.value)
        }
    }

    @Test
    fun `late connection test cannot override changed identity and newer test`() = runTest {
        val old = CompletableDeferred<Boolean>()
        val fresh = CompletableDeferred<Boolean>()
        coEvery { client.testConnection(any(), any()) } coAnswers { old.lateResult() }
        try {
        vm.testConnection()
        vm.setHost("new")
        coEvery { client.testConnection(any(), any()) } coAnswers { fresh.await() }
        vm.testConnection()
        old.completeExceptionally(IllegalStateException())
        advanceUntilIdle()
        assertTrue(vm.isTesting.value)
        assertNull(vm.testResult.value)
        fresh.complete(true)
        advanceUntilIdle()
        assertEquals(SourceTestResult.Connected, vm.testResult.value)
        assertFalse(vm.isTesting.value)
        } finally {
            old.complete(false)
            fresh.complete(false)
            advanceUntilIdle()
        }
    }

    @Test
    fun `uncertain load uses metadata clears password and blocks implicit empty save`() = runTest {
        coEvery { syncSourceRepository.getById(7L) } throws SyncSourceCredentialUncertainException(7L)
        coEvery { syncSourceRepository.getAll() } returns listOf(info)
        val edit = editVm()
        assertEquals(SourceEditLoadState.CREDENTIAL_RECOVERY, edit.loadState.value)
        assertEquals(info.name, edit.name.value)
        assertEquals(info.host, edit.host.value)
        assertEquals(info.protocol, edit.protocol.value)
        assertEquals(info.username, edit.username.value)
        assertEquals("22", edit.port.value)
        assertEquals("", edit.password.value)
        edit.save()
        edit.testConnection()
        coVerify(exactly = 0) { syncSourceRepository.save(any()) }
        coVerify(exactly = 0) { client.testConnection(any(), any()) }
        edit.setPassword("replacement")
        coEvery { syncSourceRepository.save(any()) } throws IllegalStateException()
        edit.save()
        assertEquals(SourceEditLoadState.CREDENTIAL_RECOVERY, edit.loadState.value)
        assertTrue(edit.saveResult.value is SourceSaveResult.SaveFailed)
        coEvery { syncSourceRepository.save(any()) } returns 7L
        edit.save()
        assertEquals(SourceSaveResult.Saved, edit.saveResult.value)
        assertEquals(SourceEditLoadState.READY, edit.loadState.value)
        coVerify(exactly = 2) { syncSourceRepository.save(match { it.id == 7L && it.password == "replacement" }) }
    }

    @Test
    fun `recovery empty password requires explicit confirmation`() = runTest {
        coEvery { syncSourceRepository.getById(7L) } throws SyncSourceCredentialUncertainException(7L)
        coEvery { syncSourceRepository.getAll() } returns listOf(info)
        val edit = editVm()
        edit.confirmEmptyPassword(true)
        edit.save()
        coVerify(exactly = 1) { syncSourceRepository.save(match { it.password.isEmpty() }) }
        assertEquals(SourceEditLoadState.READY, edit.loadState.value)
    }

    @Test
    fun `metadata recovery failure blocks form and metadata cancellation propagates`() = runTest {
        coEvery { syncSourceRepository.getById(7L) } throws SyncSourceCredentialUncertainException(7L)
        coEvery { syncSourceRepository.getAll() } throws IllegalStateException()
        val edit = editVm()
        assertEquals(SourceEditLoadState.FAILED, edit.loadState.value)
        assertEquals("", edit.password.value)
        coEvery { syncSourceRepository.getAll() } throws CancellationException()
        val job = edit.retryLoad()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(SourceEditLoadState.LOADING, edit.loadState.value)
        coVerify(exactly = 0) { syncSourceRepository.save(any()) }
    }

    @Test
    fun `ordinary initialization failure is retryable and cancellation is not failure`() = runTest {
        coEvery { syncSourceRepository.getById(7L) } throws IllegalStateException("private detail")
        val edit = editVm()
        assertEquals(SourceEditLoadState.FAILED, edit.loadState.value)
        edit.save()
        coVerify(exactly = 0) { syncSourceRepository.save(any()) }
        coEvery { syncSourceRepository.getById(7L) } throws CancellationException()
        val job = edit.retryLoad()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(SourceEditLoadState.LOADING, edit.loadState.value)
        coEvery { syncSourceRepository.getById(7L) } returns info
        edit.retryLoad().join()
        assertEquals(SourceEditLoadState.READY, edit.loadState.value)
    }

    @Test
    fun `connection success emits Connected and clears testing flag`() = runTest {
        coEvery { client.testConnection(any(), any()) } returns true
        vm.testConnection()

        assertEquals(SourceTestResult.Connected, vm.testResult.value)
        assertFalse(vm.isTesting.value)
        coVerify(exactly = 1) { client.testConnection(any(), any()) }
    }

    @Test
    fun `connection failure emits TestFailed with structured code`() = runTest {
        coEvery { client.testConnection(any(), any()) } throws
            SyncException(SyncErrorCode.AUTHENTICATION_FAILED, SyncOperation.CONNECT)
        vm.testConnection()

        assertEquals(
            SourceTestResult.TestFailed(SyncErrorCode.AUTHENTICATION_FAILED),
            vm.testResult.value
        )
        assertFalse(vm.isTesting.value)
    }

    @Test
    fun `socket timeout maps to TIMEOUT code`() = runTest {
        coEvery { client.testConnection(any(), any()) } throws SocketTimeoutException("timed out")
        vm.testConnection()

        assertEquals(SourceTestResult.TestFailed(SyncErrorCode.TIMEOUT), vm.testResult.value)
    }

    @Test
    fun `unknown host maps to UNKNOWN_HOST code`() = runTest {
        coEvery { client.testConnection(any(), any()) } throws UnknownHostException("nope")
        vm.testConnection()

        assertEquals(SourceTestResult.TestFailed(SyncErrorCode.UNKNOWN_HOST), vm.testResult.value)
    }

    // --- save typed emissions ---

    @Test
    fun `save success emits Saved exactly once`() = runTest {
        vm.save()

        assertEquals(SourceSaveResult.Saved, vm.saveResult.value)
        coVerify(exactly = 1) { syncSourceRepository.save(any()) }
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `save failure emits SaveFailed with structured code`() = runTest {
        coEvery { syncSourceRepository.save(any()) } throws
            SyncException(SyncErrorCode.DATABASE_ERROR, SyncOperation.DOWNLOAD)
        vm.save()

        assertEquals(SourceSaveResult.SaveFailed(SyncErrorCode.DATABASE_ERROR), vm.saveResult.value)
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `save cancellation propagates and never writes a result`() = runTest {
        // Red line 3: CE must be rethrown; if swallowed by catch(Exception) it would
        // surface as a SaveFailed result — saveResult must stay null.
        coEvery { syncSourceRepository.save(any()) } throws CancellationException("cancelled")
        vm.save()
        advanceUntilIdle()

        assertNull(vm.saveResult.value)
        assertFalse(vm.isSaving.value)
    }

    // --- deleteSource typed emissions ---

    @Test
    fun `delete with relations emits BlockedByRelations and never deletes`() = runTest {
        coEvery { syncSourceRepository.getRelationCount(any()) } returns 3
        vm.deleteSource()

        assertEquals(SourceSaveResult.BlockedByRelations(3), vm.saveResult.value)
        coVerify(exactly = 0) { syncSourceRepository.delete(any()) }
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `delete success emits Deleted and deletes once`() = runTest {
        coEvery { syncSourceRepository.getRelationCount(any()) } returns 0
        vm.deleteSource()

        assertEquals(SourceSaveResult.Deleted, vm.saveResult.value)
        coVerify(exactly = 1) { syncSourceRepository.delete(any()) }
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `delete failure emits DeleteFailed with structured code`() = runTest {
        coEvery { syncSourceRepository.getRelationCount(any()) } returns 0
        coEvery { syncSourceRepository.delete(any()) } throws
            SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE)
        vm.deleteSource()

        assertEquals(
            SourceSaveResult.DeleteFailed(SyncErrorCode.REMOTE_DELETE_FAILED),
            vm.saveResult.value
        )
        assertFalse(vm.isSaving.value)
    }
}
