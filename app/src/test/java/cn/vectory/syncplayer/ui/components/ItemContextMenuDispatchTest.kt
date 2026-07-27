package cn.vectory.syncplayer.ui.components

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the file-menu fallback dispatch contract ([dispatchFileTrashAction] /
 * [dispatchFileDeleteAction]) used by [FileContextMenuDialog].
 *
 * The 4th-round review (FAIL 8.7) flagged that when a host (e.g. SearchScreen) does NOT supply
 * an undo-aware callback, the trash/delete buttons were silent no-ops (`onTrashed?.invoke(id)`
 * with a null callback does nothing). The fix: callback non-null → callback drives it (Browser);
 * callback null → fall back to the direct [FileContextMenuActions] call. Exactly one path runs.
 *
 * These tests pin that contract at the JVM level (no Compose instrumentation required) by
 * asserting call counts on a mocked [FileContextMenuActions].
 */
class ItemContextMenuDispatchTest {

    private fun actionsMock(): FileContextMenuActions = mockk(relaxed = true)

    @Test
    fun `trash action with null callback falls back to actions_toggleTrashed exactly once`() {
        val actions = actionsMock()
        val id = 100L

        dispatchFileTrashAction(id, callback = null, actions = actions)

        verify(exactly = 1) { actions.toggleTrashed(id) }
    }

    @Test
    fun `delete action with null callback falls back to actions_deleteFile exactly once`() {
        val actions = actionsMock()
        val id = 101L

        dispatchFileDeleteAction(id, callback = null, actions = actions)

        verify(exactly = 1) { actions.deleteFile(id) }
    }

    @Test
    fun `trash action with non-null callback runs callback and does NOT call actions fallback`() {
        // Browser supplies a callback and must NOT also trigger the direct actions call
        // (no double execution of the same delete).
        val actions = actionsMock()
        val id = 102L
        var callbackInvoked = false

        dispatchFileTrashAction(id, callback = { callbackInvoked = true }, actions = actions)

        assertTrue("callback must run when supplied", callbackInvoked)
        verify(exactly = 0) { actions.toggleTrashed(any()) }
    }

    @Test
    fun `delete action with non-null callback runs callback and does NOT call actions fallback`() {
        val actions = actionsMock()
        val id = 103L
        var callbackInvoked = false

        dispatchFileDeleteAction(id, callback = { callbackInvoked = true }, actions = actions)

        assertTrue("callback must run when supplied", callbackInvoked)
        verify(exactly = 0) { actions.deleteFile(any()) }
    }

    @Test
    fun `trash callback receives the exact id`() {
        val id = 104L
        var receivedId: Long? = null

        dispatchFileTrashAction(id, callback = { receivedId = it }, actions = actionsMock())

        assertTrue("callback must receive the exact id", receivedId == id)
    }

    @Test
    fun `delete callback receives the exact id`() {
        val id = 105L
        var receivedId: Long? = null

        dispatchFileDeleteAction(id, callback = { receivedId = it }, actions = actionsMock())

        assertTrue("callback must receive the exact id", receivedId == id)
    }
}
