package cn.vectory.syncplayer.sync

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Table-driven unit tests for the code-based error contract.
 *
 * Asserts exclusively on [RemoteBrowseError] enums and the null/non-null shape of
 * [mapNetworkErrorMessage] — never on user-facing message text (those live in strings.xml
 * and are resolved by [syncErrorMessage]).
 *
 * Classification reads `(e as? SyncException)?.code` plus stdlib exception types.
 * Substring-matching of thrown messages is intentionally NOT exercised (the contract
 * forbids relying on prose).
 */
class ErrorMessageMapperTest {

    private val context: Context = mockk(relaxed = true)
    private val host = "nas.local"

    // ---------------------------------------------------------------------------
    // classifyRemoteError — SyncException.code branches (authoritative source)
    // ---------------------------------------------------------------------------

    @Test
    fun `SyncException PATH_NOT_FOUND classified as PATH_NOT_FOUND`() {
        assertEquals(
            RemoteBrowseError.PATH_NOT_FOUND,
            classifyRemoteError(SyncException(SyncErrorCode.PATH_NOT_FOUND, SyncOperation.BROWSE))
        )
    }

    @Test
    fun `SyncException TIMEOUT classified as TIMEOUT`() {
        assertEquals(
            RemoteBrowseError.TIMEOUT,
            classifyRemoteError(SyncException(SyncErrorCode.TIMEOUT, SyncOperation.CONNECT))
        )
    }

    @Test
    fun `SyncException UNKNOWN_HOST classified as TIMEOUT`() {
        assertEquals(
            RemoteBrowseError.TIMEOUT,
            classifyRemoteError(SyncException(SyncErrorCode.UNKNOWN_HOST, SyncOperation.CONNECT))
        )
    }

    @Test
    fun `SyncException NETWORK_UNAVAILABLE classified as TIMEOUT`() {
        assertEquals(
            RemoteBrowseError.TIMEOUT,
            classifyRemoteError(SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.CONNECT))
        )
    }

    @Test
    fun `SyncException AUTHENTICATION_FAILED classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(SyncException(SyncErrorCode.AUTHENTICATION_FAILED, SyncOperation.CONNECT))
        )
    }

    @Test
    fun `SyncException DOWNLOAD_FAILED classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(SyncException(SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD))
        )
    }

    @Test
    fun `SyncException UNKNOWN classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(SyncException(SyncErrorCode.UNKNOWN, SyncOperation.CONNECT))
        )
    }

    // ---------------------------------------------------------------------------
    // classifyRemoteError — stdlib exception-type mapping (no SyncException wrapper)
    // ---------------------------------------------------------------------------

    @Test
    fun `SocketTimeoutException classified as TIMEOUT`() {
        assertEquals(
            RemoteBrowseError.TIMEOUT,
            classifyRemoteError(SocketTimeoutException("irrelevant message"))
        )
    }

    @Test
    fun `UnknownHostException classified as TIMEOUT`() {
        assertEquals(
            RemoteBrowseError.TIMEOUT,
            classifyRemoteError(UnknownHostException("irrelevant message"))
        )
    }

    @Test
    fun `ConnectException classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(ConnectException("irrelevant message"))
        )
    }

    @Test
    fun `generic RuntimeException classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(RuntimeException("permission denied"))
        )
    }

    @Test
    fun `exception with null message classified as OTHER`() {
        assertEquals(
            RemoteBrowseError.OTHER,
            classifyRemoteError(RuntimeException())
        )
    }

    // ---------------------------------------------------------------------------
    // classifyRemoteError — code wins over any stdlib type
    // ---------------------------------------------------------------------------

    @Test
    fun `SyncException PATH_NOT_FOUND beats stdlib timeout cause`() {
        // A SyncException wraps the real cause; its code is authoritative even if the
        // wrapped cause were a timeout type.
        assertEquals(
            RemoteBrowseError.PATH_NOT_FOUND,
            classifyRemoteError(
                SyncException(
                    SyncErrorCode.PATH_NOT_FOUND, SyncOperation.BROWSE,
                    cause = SocketTimeoutException()
                )
            )
        )
    }

    // ---------------------------------------------------------------------------
    // syncErrorMessage — returns a non-null resolved string per code (shape only)
    // ---------------------------------------------------------------------------

    @Test
    fun `syncErrorMessage resolves every code to a non-null string`() {
        for (code in SyncErrorCode.values()) {
            val e = SyncException(code, SyncOperation.CONNECT)
            val msg = syncErrorMessage(e, context, host)
            assertNotNull("code=$code must resolve to non-null", msg)
        }
    }

    @Test
    fun `syncErrorMessage resolves stdlib exception types`() {
        assertNotNull(syncErrorMessage(SocketTimeoutException("x"), context, host))
        assertNotNull(syncErrorMessage(UnknownHostException("x"), context, host))
        assertNotNull(syncErrorMessage(ConnectException("x"), context, host))
        assertNotNull(syncErrorMessage(RuntimeException("x"), context, host))
    }

    @Test
    fun `syncErrorMessage for UNKNOWN never leaks the thrown message`() {
        // The thrown message is intentionally sensitive; UNKNOWN must yield a generic
        // resource string, never echo the input message.
        val sensitive = "DEFINITELY_NOT_A_RESOURCE_STRING_42"
        val msg = syncErrorMessage(RuntimeException(sensitive), context, host)
        assertNotNull(msg)
        // Resolved string must not contain the raw exception message.
        assert(!msg!!.contains(sensitive)) { "UNKNOWN path leaked e.message: $msg" }
    }

    @Test
    fun `syncErrorMessage for SyncException UNKNOWN also hides message`() {
        // SyncException carries null message by contract, but guard regardless.
        val e = SyncException(SyncErrorCode.UNKNOWN, SyncOperation.CONNECT)
        val msg = syncErrorMessage(e, context, host)
        assertNotNull(msg)
    }

    // ---------------------------------------------------------------------------
    // mapNetworkErrorMessage — compat shim null / non-null contract
    // ---------------------------------------------------------------------------

    @Test
    fun `mapNetworkErrorMessage returns non-null for UnknownHostException`() {
        assertNotNull(mapNetworkErrorMessage(UnknownHostException(host), host, context))
    }

    @Test
    fun `mapNetworkErrorMessage returns non-null for ConnectException`() {
        assertNotNull(mapNetworkErrorMessage(ConnectException("refused"), host, context))
    }

    @Test
    fun `mapNetworkErrorMessage returns non-null for SocketTimeoutException`() {
        assertNotNull(mapNetworkErrorMessage(SocketTimeoutException("slow"), host, context))
    }

    @Test
    fun `mapNetworkErrorMessage returns null for unrecognized exception`() {
        assertNull(mapNetworkErrorMessage(RuntimeException("something else"), host, context))
    }

    @Test
    fun `mapNetworkErrorMessage returns null for IllegalStateException`() {
        assertNull(mapNetworkErrorMessage(IllegalStateException("bad state"), host, context))
    }

    @Test
    fun `mapNetworkErrorMessage resolves SyncException via code`() {
        // SyncException flows through syncErrorMessage (non-null) rather than returning null.
        assertNotNull(
            mapNetworkErrorMessage(
                SyncException(SyncErrorCode.AUTHENTICATION_FAILED, SyncOperation.CONNECT),
                host, context
            )
        )
    }

    // ---------------------------------------------------------------------------
    // 外部 SD v2（任务包 B）：三个新存储错误码必须各自映射到独立资源 id
//（断言资源 id，不断言文案）。
// ---------------------------------------------------------------------------

@org.junit.Test
fun `new storage error codes map to distinct string resources`() {
    io.mockk.every { context.getString(any<Int>()) } returns ""

    syncErrorMessage(
        SyncException(SyncErrorCode.STORAGE_UNAVAILABLE, SyncOperation.DOWNLOAD), context, host
    )
    io.mockk.verify { context.getString(cn.vectory.syncplayer.R.string.sync_error_storage_unavailable) }
    syncErrorMessage(
        SyncException(SyncErrorCode.STORAGE_IO_TRANSIENT, SyncOperation.DOWNLOAD), context, host
    )
    io.mockk.verify { context.getString(cn.vectory.syncplayer.R.string.sync_error_storage_io_transient) }
    syncErrorMessage(
        SyncException(SyncErrorCode.KEY_UNAVAILABLE, SyncOperation.DOWNLOAD), context, host
    )
    io.mockk.verify { context.getString(cn.vectory.syncplayer.R.string.sync_error_key_unavailable) }
}
}
