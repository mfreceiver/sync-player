package cn.vectory.syncplayer.settings

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.sync.RemoteFileClient
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
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
