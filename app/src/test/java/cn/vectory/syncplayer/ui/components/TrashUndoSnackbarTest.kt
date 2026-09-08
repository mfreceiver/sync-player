package cn.vectory.syncplayer.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [showTrashUndoSnackbar] against the real material3 [SnackbarHostState]
 * under runTest virtual time (SnackbarHostState performs no timing itself; Indefinite never
 * self-completes, so every transition here is deterministic).
 *
 * Dispatcher adaptation: the helper wraps its body in `withContext(Dispatchers.Main.immediate)`.
 * Unit tests have no Android Main, so each test binds a StandardTestDispatcher that shares the
 * runTest scheduler as Main (project convention, cf. SearchViewModelTest's in-test setMain) —
 * the helper keeps its production structure and stays fully driven by virtual time. resetMain
 * in finally keeps the global TestMainDispatcher clean across tests.
 *
 * Timing asserts use inclusive windows (e.g. 1_000..1_020) because display detection is
 * poll-based with a 10 ms interval: completion lands within one poll cycle of the deadline.
 * Only enums / state / identity / timing are asserted, never user-facing copy (project
 * testing convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashUndoSnackbarTest {

    private fun runTestWithMain(block: suspend TestScope.() -> Unit): TestResult = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** 1) Idle host: displays, survives to 999 ms, then times out as Dismissed at ~1000 ms. */
    @Test
    fun idleHostDisplaysThenTimesOutWithDismissed() = runTestWithMain {
        val host = SnackbarHostState()
        val job = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent()
        assertNotNull(host.currentSnackbarData)
        advanceTimeBy(999)
        assertFalse(job.isCompleted)
        assertNotNull(host.currentSnackbarData)
        advanceTimeBy(11)
        assertEquals(SnackbarResult.Dismissed, job.await())
        assertTrue("t=$currentTime", currentTime in 1_000L..1_020L)
        assertNull(host.currentSnackbarData)
    }

    /** 2) User action during display: delivered immediately, before any timeout. */
    @Test
    fun userActionDuringDisplayReturnsActionPerformedImmediately() = runTestWithMain {
        val host = SnackbarHostState()
        val job = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent()
        advanceTimeBy(100)
        val displayed = host.currentSnackbarData
        assertNotNull(displayed)
        displayed!!.performAction()
        // Immediate delivery: the call must complete without any further virtual-time advance.
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(SnackbarResult.ActionPerformed, job.await())
        assertEquals("t=$currentTime", 100L, currentTime)
        assertNull(host.currentSnackbarData)
    }

    /**
     * 3) Slot lost to a non-releasing other-source request: ours is dropped within budget,
     * the queued other request is never published nor touched, and the previously displayed
     * one was already preempted (Dismissed).
     */
    @Test
    fun queuedOtherRequestIsNeverTouchedAndOursIsDroppedWithoutPublishing() = runTestWithMain {
        val host = SnackbarHostState()
        val aJob = async { host.showSnackbar("other-A", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        // B must queue BEFORE the helper starts: mutex handoff is FIFO, so after the
        // helper preempts A the slot goes to B, leaving ours queued on the mutex.
        val bJob = async { host.showSnackbar("other-B", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        val oursJob = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent() // helper preempts A; queued B publishes, ours stays queued
        assertEquals(SnackbarResult.Dismissed, aJob.await())
        val bData = host.currentSnackbarData
        assertNotNull(bData) // B holds the slot
        assertFalse(oursJob.isCompleted) // ours still queued
        advanceTimeBy(999)
        assertFalse(oursJob.isCompleted)
        advanceTimeBy(1) // t=1000: budget deadline, slot never won
        assertEquals(SnackbarResult.Dismissed, oursJob.await())
        assertTrue("t=$currentTime", currentTime in 1_000L..1_100L)
        assertSame(bData, host.currentSnackbarData) // B untouched
        bData!!.dismiss()
        bJob.join()
    }

    /**
     * 4) Slot arriving midway (B released at t=500): the display timer starts only from
     * observed display time, granting the full duration (Dismissed ~t=1510, not ~t=1000).
     */
    @Test
    fun slotArrivingMidwayGrantsFullDisplayFromObservation() = runTestWithMain {
        val host = SnackbarHostState()
        val aJob = async { host.showSnackbar("other-A", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        // B queues first (FIFO): after the helper's preempt the slot goes to B, not ours.
        val bJob = async { host.showSnackbar("other-B", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        val oursJob = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent() // preempt A; queued B publishes, ours stays queued
        assertEquals(SnackbarResult.Dismissed, aJob.await())
        val bData = host.currentSnackbarData
        assertNotNull(bData)
        assertFalse(oursJob.isCompleted)
        advanceTimeBy(500)
        bData!!.dismiss()
        bJob.join()
        runCurrent() // ours published at ~t=500
        val oursData = host.currentSnackbarData
        assertNotNull(oursData)
        assertNotSame(bData, oursData)
        advanceTimeBy(999) // t=1499
        assertNotNull(host.currentSnackbarData)
        assertFalse(oursJob.isCompleted)
        advanceTimeBy(12) // t=1511
        assertEquals(SnackbarResult.Dismissed, oursJob.await())
        assertTrue("t=$currentTime", currentTime in 1_500L..1_520L)
        assertNull(host.currentSnackbarData)
    }

    /** 5) Caller cancellation during display: this snackbar must be cleared from the host. */
    @Test
    fun callerCancellationClearsDisplayedSnackbar() = runTestWithMain {
        val host = SnackbarHostState()
        val job = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent()
        assertNotNull(host.currentSnackbarData)
        job.cancel()
        runCurrent()
        assertNull(host.currentSnackbarData)
    }

    /** 6) Preemption semantics: the displayed other-source snackbar observes Dismissed. */
    @Test
    fun preemptsDisplayedOtherSnackbarAndTimesOutNormally() = runTestWithMain {
        val host = SnackbarHostState()
        val aJob = async { host.showSnackbar("other-A", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        val oursJob = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        assertEquals(SnackbarResult.Dismissed, aJob.await()) // preempted
        runCurrent()
        assertNotNull(host.currentSnackbarData) // ours is displayed
        advanceTimeBy(1_011)
        assertEquals(SnackbarResult.Dismissed, oursJob.await())
        assertTrue("t=$currentTime", currentTime in 1_000L..1_020L)
        assertNull(host.currentSnackbarData)
    }

    /**
     * 7) Regression (fourth review): display published in the gap between the last poll and
     * the budget deadline (B released at t=995, deadline t=1000). The synchronous final-state
     * recheck must keep the displayed snackbar and grant the full Phase 2 duration
     * (Dismissed ~t=2000), never kill it at the old deadline.
     */
    @Test
    fun displayJustBeforeBudgetDeadlineKeepsFullPhase2Duration() = runTestWithMain {
        val host = SnackbarHostState()
        val aJob = async { host.showSnackbar("other-A", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        // B queues first (FIFO): after the helper's preempt the slot goes to B, not ours.
        val bJob = async { host.showSnackbar("other-B", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        val oursJob = async { host.showTrashUndoSnackbar(MESSAGE, ACTION) }
        runCurrent() // preempt A; queued B publishes at ~t=0, ours stays queued
        assertEquals(SnackbarResult.Dismissed, aJob.await())
        val bData = host.currentSnackbarData
        assertNotNull(bData)
        assertFalse(oursJob.isCompleted)
        advanceTimeBy(995) // t=995: released just before our deadline
        bData!!.dismiss()
        bJob.join()
        runCurrent() // ours published at ~t=995; timeout task (t=1000) already enqueued
        val oursData = host.currentSnackbarData
        assertNotNull(oursData)
        assertNotSame(bData, oursData)
        advanceTimeBy(999) // t=1994: past any single-deadline kill, before t=2000
        assertNotNull(host.currentSnackbarData) // still displayed thanks to the recheck
        assertFalse(oursJob.isCompleted)
        advanceTimeBy(11) // t=2005
        assertEquals(SnackbarResult.Dismissed, oursJob.await())
        assertTrue("t=$currentTime", currentTime in 2_000L..2_020L)
        assertNull(host.currentSnackbarData)
    }

    private companion object {
        const val MESSAGE = "trash undo snackbar message"
        const val ACTION = "undo"
    }
}
