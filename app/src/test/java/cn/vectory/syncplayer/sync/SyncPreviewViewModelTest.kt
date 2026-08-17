package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-2-2 regression lock: cancelling an in-flight delete MUST NOT surface as a
 * user-visible error. `cancelSync()` cancels the [SyncPreviewViewModel.startDelete]
 * job; the resulting CancellationException must be rethrown by the CE guard
 * instead of falling into the generic catch that sets the error StateFlow.
 *
 * Asserts on state shape only — never on user-facing string text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreviewViewModelTest {

    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): SyncPreviewViewModel {
        every { syncEngine.progress } returns MutableStateFlow(SyncProgress(isRunning = false))
        return SyncPreviewViewModel(
            syncEngine = syncEngine,
            syncRelationRepository = syncRelationRepository,
            context = context,
            savedStateHandle = SavedStateHandle(
                mapOf("relationId" to 1L, "initialTab" to "delete"),
            ),
        )
    }

    @Test
    fun `cancelSync during executeDelete does not set error state`() = runTest {
        // Arrange: preview with one pending delete action.
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(
            listOf(SyncAction(type = SyncActionType.DELETE_BOTH, relativePath = "a/video.enc", fileSize = 10L)),
            null,
        )
        // executeDelete suspends until cancelled; flag proves CE actually propagated.
        val cancellationReached = CompletableDeferred<Unit>()
        coEvery { syncEngine.executeDelete(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancellationReached.complete(Unit)
            }
        }

        val vm = createVm()
        vm.loadPreview()
        assertTrue("preview should hold one delete action", vm.toDelete.value.isNotEmpty())

        // Act: start the delete, then cancel it while executeDelete is suspended.
        vm.startDelete()
        assertTrue("isDeleting should be true while running", vm.isDeleting.value)
        vm.cancelSync()
        advanceUntilIdle()

        // Assert: cancellation was delivered (finally ran) and completed cleanly,
        // without the generic catch translating it into an error.
        assertTrue("executeDelete should have observed cancellation", cancellationReached.isCompleted)
        assertFalse("isDeleting should be reset by finally", vm.isDeleting.value)
        assertNull("cancellation must not surface as error state", vm.error.value)
    }
}
