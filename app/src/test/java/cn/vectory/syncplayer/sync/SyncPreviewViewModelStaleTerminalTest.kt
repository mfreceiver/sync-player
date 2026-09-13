package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for CQ-002: stale terminal progress consumed on retry sync.
 *
 * When a previous sync ended in a terminal state (PARTIAL_FAILED, FAILED, CANCELLED),
 * tapping startSync again must not immediately consume that stale terminal state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreviewViewModelStaleTerminalTest {

    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var progress: MutableStateFlow<SyncProgress>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(
            listOf(SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "videos/a.enc", fileSize = 10L)),
            null,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(initial: SyncProgress): SyncPreviewViewModel {
        progress = MutableStateFlow(initial)
        every { syncEngine.progress } returns progress
        return SyncPreviewViewModel(
            syncEngine = syncEngine,
            syncRelationRepository = syncRelationRepository,
            context = context,
            savedStateHandle = SavedStateHandle(
                mapOf("relationId" to 1L, "initialTab" to "delete"),
            ),
        )
    }

    private fun staleTerminal() = SyncProgress(
        isRunning = false, phase = SyncPhase.PARTIAL_FAILED, relationId = 1L, failedFiles = 2
    )

    @Test
    fun `startSync with stale terminal in flow keeps syncing until a NEW terminal arrives`() = runTest {
        val vm = createVm(staleTerminal())
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        vm.startSync()

        assertTrue(
            "isSyncing must remain true after startSync until a NEW terminal arrives " +
                "(actual isSyncing=${vm.isSyncing.value}, syncComplete=${vm.syncComplete.value})",
            vm.isSyncing.value
        )
        assertFalse("syncComplete must not be set from a stale terminal", vm.syncComplete.value)
    }

    @Test
    fun `startSync with stale terminal ignores old terminal and consumes the fresh terminal`() = runTest {
        val vm = createVm(staleTerminal())
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        vm.startSync()
        assertTrue(vm.isSyncing.value)
        assertFalse(vm.syncComplete.value)

        // New sync emits downloading progress
        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        assertTrue("isSyncing remains true while downloading", vm.isSyncing.value)
        assertFalse(vm.syncComplete.value)

        // New sync emits fresh COMPLETED terminal
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        assertFalse("fresh terminal stops syncing", vm.isSyncing.value)
        assertTrue("fresh terminal marks complete", vm.syncComplete.value)
        assertTrue("COMPLETED branch cleared pending actions", vm.toDelete.value.isEmpty())
    }

    @Test
    fun `control - startSync from non-terminal state observes the fresh terminal`() = runTest {
        val vm = createVm(SyncProgress(isRunning = false, phase = SyncPhase.IDLE))
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        vm.startSync()
        assertTrue("isSyncing true right after start (no terminal yet)", vm.isSyncing.value)

        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        assertTrue("running sync reflected", vm.isSyncing.value)

        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        assertFalse("fresh terminal stops syncing", vm.isSyncing.value)
        assertTrue("fresh terminal completes", vm.syncComplete.value)
        assertTrue("COMPLETED branch cleared the pending list", vm.toDelete.value.isEmpty())
    }
}
