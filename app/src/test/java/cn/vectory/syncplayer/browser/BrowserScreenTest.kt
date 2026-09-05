package cn.vectory.syncplayer.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScreenTest {
    @Test
    fun `failed deferred commit does not resolve the browser pending marker`() {
        assertFalse(shouldClearDeferredDelete(UndoResult.Failed(
            cn.vectory.syncplayer.sync.SyncException(
                cn.vectory.syncplayer.sync.SyncErrorCode.STORAGE_ERROR,
                cn.vectory.syncplayer.sync.SyncOperation.DELETE
            )
        )))
        assertTrue(shouldClearDeferredDelete(UndoResult.Success))
        assertTrue(shouldClearDeferredDelete(UndoResult.NothingToUndo))
    }

    @Test
    fun `nothing to undo does not resolve a still matching committing debt`() {
        assertFalse(shouldClearDeferredDelete(UndoResult.NothingToUndo, hasAnyPendingDebt = true))
        assertTrue(shouldClearDeferredDelete(UndoResult.NothingToUndo, hasAnyPendingDebt = false))
    }

    @Test
    fun `stale snackbar cannot clear a newer pending operation`() {
        assertFalse(shouldClearDeferredDelete(UndoResult.NothingToUndo, hasAnyPendingDebt = true))
    }

    @Test
    fun `failed follow-up operation reconciles drained marker without clearing a newer marker`() {
        assertEquals(
            null to null,
            reconcilePendingDeleteMarker(
                localPendingId = 10L,
                latestPendingId = 10L,
                authoritativePendingId = null,
                expectedLocalPendingId = 10L,
            )
        )
        assertEquals(
            30L to 30L,
            reconcilePendingDeleteMarker(
                localPendingId = 30L,
                latestPendingId = 30L,
                authoritativePendingId = null,
                expectedLocalPendingId = 10L,
            )
        )
    }

    @Test
    fun `new delete may pass the UI guard only to drain an existing committing debt`() {
        assertTrue(shouldStartDeferredDelete(
            pendingDeleteId = 10L,
            requestedId = 20L,
            hasPendingDebt = true,
            hasCommittingDebt = true
        ))
        assertFalse(shouldStartDeferredDelete(
            pendingDeleteId = 10L,
            requestedId = 20L,
            hasPendingDebt = true,
            hasCommittingDebt = false
        ))
        assertTrue(shouldStartDeferredDelete(
            pendingDeleteId = 10L,
            requestedId = 10L,
            hasPendingDebt = true,
            hasCommittingDebt = true
        ))
    }
}
