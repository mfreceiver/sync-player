package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { syncSourceRepository.getAllWithCredentials() } returns listOf(
            SyncSource(
                id = 1L, name = "smb", protocol = SyncProtocol.SMB,
                host = "h", port = 1, username = "u", password = "p"
            )
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
