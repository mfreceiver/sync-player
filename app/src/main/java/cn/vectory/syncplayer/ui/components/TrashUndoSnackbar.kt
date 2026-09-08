package cn.vectory.syncplayer.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TRASH_UNDO_SNACKBAR_DURATION_MS = 1_000L

/** Poll granularity for detecting that this request's snackbar actually got displayed. */
private const val DISPLAY_POLL_INTERVAL_MS = 10L

/**
 * Request-unique visuals: reference identity of this instance is the reliable marker that
 * the displayed [androidx.compose.material3.SnackbarData] belongs to this call, regardless
 * of host lock timing.
 */
private class TrashSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * Shows the reversible trash-state confirmation briefly while retaining its undo action.
 * A timeout is treated exactly like a normal dismissal: the state change is already applied.
 *
 * The whole body runs on [Dispatchers.Main.immediate]: the host must be touched from a
 * single thread, and keeping every check/dismiss pair suspension-free makes state reads
 * and cancels atomic with respect to the showing coroutines. All call sites already run
 * on Main, so for them this is a no-op.
 *
 * Slot policy:
 * 1. Preemption: this confirmation REPLACES any other-source snackbar currently displayed
 *    (its caller observes Dismissed).
 * 2. Other-source requests already queued for the host are never touched. If the display
 *    slot cannot be won within the budget, this confirmation is dropped (returns Dismissed)
 *    rather than dismissing someone else's.
 * 3. Display timing starts when THIS request's snackbar is observed on screen (identity =
 *    request-unique [SnackbarVisuals] reference). The poll interval is
 *    [DISPLAY_POLL_INTERVAL_MS]; detection latency is usually within one poll cycle,
 *    subject to dispatcher scheduling, so the display lasts at least durationMillis.
 *    At the budget deadline a synchronous final-state recheck (see below) guarantees a
 *    snackbar that was displayed but not yet observed is not killed by the timeout — it
 *    receives the full Phase 2 display duration.
 * 4. Timeout cancels only our own request: while displayed, the host is cleared; while
 *    queued on the host mutex, the request is cleanly removed without ever being published.
 * 5. Action delivery: once display is observed (Phase 2), a user action is delivered
 *    immediately as [SnackbarResult.ActionPerformed]; an action that completes during the
 *    Phase 1 detection window is delivered at most one poll cycle later.
 */
suspend fun SnackbarHostState.showTrashUndoSnackbar(
    message: String,
    actionLabel: String,
    durationMillis: Long = TRASH_UNDO_SNACKBAR_DURATION_MS,
): SnackbarResult = withContext(Dispatchers.Main.immediate) {
    val visuals = TrashSnackbarVisuals(message, actionLabel)
    // 1) Preempt whatever is currently displayed (explicit policy, see KDoc).
    currentSnackbarData?.dismiss()
    coroutineScope {
        val result = CompletableDeferred<SnackbarResult>()
        val showJob = launch { result.complete(showSnackbar(visuals)) }
        // Phase 1: wait until THIS request's snackbar is actually displayed (identity =
        // visuals reference, immune to lock-handoff timing). Bounded budget; polling via
        // delay() (not a busy yield loop). Virtual time advances through the polls.
        var observedDisplay = false
        withTimeoutOrNull(durationMillis) {
            while (currentSnackbarData?.visuals !== visuals && !result.isCompleted) {
                delay(DISPLAY_POLL_INTERVAL_MS)
            }
            observedDisplay = currentSnackbarData?.visuals === visuals
        }
        // Budget deadline: synchronous final-state recheck. There is no suspension between
        // this check and the cancels below, and we are on the single host thread, so the
        // check is atomic with respect to publication: a snackbar displayed in the gap
        // between the last poll and the deadline is kept and gets Phase 2, never cancelled.
        if (result.isCompleted) return@coroutineScope result.await()
        val displayedNow = observedDisplay || currentSnackbarData?.visuals === visuals
        if (!displayedNow) {
            // Slot not won in time: cancel only our own request (queued -> cleanly removed,
            // never published; per SnackbarHostState.showSnackbar cancellation semantics).
            showJob.cancel()
            result.complete(SnackbarResult.Dismissed)
            return@coroutineScope result.await()
        }
        // Phase 2: the display timer starts only now, from observed (or deadline-recheck)
        // display time, granting the full display duration from this point.
        val timer = launch {
            delay(durationMillis)
            if (showJob.isActive) showJob.cancel()
            // No-op if the user already actioned: their ActionPerformed result is kept.
            result.complete(SnackbarResult.Dismissed)
        }
        val r = result.await()
        timer.cancel()
        r
    }
}
