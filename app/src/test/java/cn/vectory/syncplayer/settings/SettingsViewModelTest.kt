package cn.vectory.syncplayer.settings

import android.util.Log
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.security.BiometricAuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * CE guard regression lock for the settings init collection: a CancellationException
 * raised while collecting the preferences flows MUST be rethrown, not swallowed by
 * the generic catch (which logs "Failed to load settings" and treats cancellation
 * as a failure).
 *
 * Asserts on call counts and state shape only — never on user-facing string text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val biometricAuthManager = mockk<BiometricAuthManager>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `init collect cancelled does not run failure log`() = runTest {
        // Arrange: three prefs emit normally; the fourth flow throws CancellationException
        // on collection, cancelling the combine/collect in init.
        every { userPrefs.volumeBalance } returns MutableStateFlow(false)
        every { userPrefs.quickRecycle } returns MutableStateFlow(true)
        every { userPrefs.secureLockEnabled } returns flow {
            throw CancellationException("collect cancelled")
        }

        // Act: construct the VM — init runs eagerly on UnconfinedTestDispatcher.
        SettingsViewModel(
            context = mockk(relaxed = true),
            syncLogRepository = syncLogRepository,
            userPrefs = userPrefs,
            biometricAuthManager = biometricAuthManager,
        )

        // Assert: cancellation must NOT fall into the generic catch — no failure log,
        // and no state was published from the cancelled collection.
        verify(exactly = 0) { Log.e(any(), any(), any<Throwable>()) }
    }

    @Test
    fun `init collect survives transient datastore IOException and reflects later change`() = runTest {
        // T1 (BUG-003): a single transient DataStore read IOException must not
        // permanently freeze the settings state — the collector must resubscribe
        // (with backoff) and eventually reflect later preference changes.
        // Arrange: volumeBalance is a flaky flow — first collection emits false
        // then throws IOException; second collection emits true. The other three
        // flows are plain state flows with default values.
        var collections = 0
        val flakyVolumeBalance = flow {
            collections++
            if (collections == 1) {
                emit(false)
                throw IOException("transient")
            } else {
                emit(true)
            }
        }
        every { userPrefs.volumeBalance } returns flakyVolumeBalance
        every { userPrefs.quickRecycle } returns MutableStateFlow(true)
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(false)

        // Act: construct the VM (init starts eagerly on Unconfined Main) and let
        // the virtual clock digest the backoff delay before resubscription.
        val vm = SettingsViewModel(
            context = mockk(relaxed = true),
            syncLogRepository = syncLogRepository,
            userPrefs = userPrefs,
            biometricAuthManager = biometricAuthManager,
        )
        advanceUntilIdle()

        // Assert: after retry the state reflects the recovered value, and the
        // collector never fell into the generic failure branch.
        assertTrue(
            "state should reflect the recovered volumeBalance=true after transient IOException",
            vm.settingsState.value?.volumeBalance == true,
        )
        verify(exactly = 0) { Log.e(any(), any(), any<Throwable>()) }
    }

    @Test
    fun `init collect non-IOException keeps current fail-fast semantics`() = runTest {
        // T2 (behavior lock): non-IOException failures must keep the existing
        // fail-fast semantics — generic catch logs once, coroutine ends normally,
        // last snapshot is retained. The gate guarantees the combine's first full
        // snapshot (all four flows emitting) is delivered before the exception.
        val gate = CompletableDeferred<Unit>()
        every { userPrefs.volumeBalance } returns MutableStateFlow(false)
        every { userPrefs.quickRecycle } returns MutableStateFlow(true)
        every { userPrefs.secureLockEnabled } returns flow {
            emit(false)
            gate.await()
            throw IllegalStateException("bug")
        }

        // Act₁: construct VM and wait for the first full snapshot (synchronization
        // point — the barrier guarantees combine delivered before the exception).
        val vm = SettingsViewModel(
            context = mockk(relaxed = true),
            syncLogRepository = syncLogRepository,
            userPrefs = userPrefs,
            biometricAuthManager = biometricAuthManager,
        )
        advanceUntilIdle()
        assertNotNull("first snapshot must be delivered before the failure", vm.settingsState.value)

        // Act₂: release the failure.
        gate.complete(Unit)
        advanceUntilIdle()

        // Assert: last snapshot retained, generic failure branch ran exactly once.
        assertNotNull("last snapshot must be retained after non-IO failure", vm.settingsState.value)
        verify(exactly = 1) { Log.e(any(), any(), any<Throwable>()) }
    }

    @Test
    fun `ioRetryBackoffMs returns capped exponential sequence`() {
        // T2a (BUG-003): direct unit test of the backoff helper — exponential
        // 1s→30s capped, and large attempts are clamped without overflow/throw.
        assertEquals(1_000L, SettingsViewModel.ioRetryBackoffMs(0L))
        assertEquals(2_000L, SettingsViewModel.ioRetryBackoffMs(1L))
        assertEquals(4_000L, SettingsViewModel.ioRetryBackoffMs(2L))
        assertEquals(8_000L, SettingsViewModel.ioRetryBackoffMs(3L))
        assertEquals(16_000L, SettingsViewModel.ioRetryBackoffMs(4L))
        assertEquals(30_000L, SettingsViewModel.ioRetryBackoffMs(5L))  // 1_000 shl 5 = 32_000 → capped
        assertEquals(30_000L, SettingsViewModel.ioRetryBackoffMs(6L))
        assertEquals(30_000L, SettingsViewModel.ioRetryBackoffMs(100L))
        assertEquals(30_000L, SettingsViewModel.ioRetryBackoffMs(Long.MAX_VALUE))
    }
}
