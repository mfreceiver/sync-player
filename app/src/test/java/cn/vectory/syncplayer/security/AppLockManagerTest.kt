package cn.vectory.syncplayer.security

import android.content.Context
import androidx.lifecycle.Lifecycle
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies AppLockManager cold-start cache gate (AND-005):
 * - L1-C1: Cold start (collector not running) with lock enabled → ON_START locks.
 * - L1-C2: Warm cache (already initialized to false) → ON_START does not lock.
 *
 * Cannot call register() in tests because ProcessLifecycleOwner.get() requires
 * Android framework. Instead we drive state via runBlocking fallback (which
 * initializes the cache) + ON_STOP/ON_START cycles.
 */
class AppLockManagerTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `cold start with lock enabled locks on first ON_START`() = runTest {
        // Arrange: lock enabled, collector not started (no register() call).
        // Use real MutableStateFlow so .first() works in runBlocking fallback.
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(true)

        val manager = AppLockManager(context, userPrefs)
        // Do NOT call register() — cold start, collector not running

        // Act: first ON_START triggers runBlocking fallback → reads true → locks
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        // Assert: locked
        assertTrue("cold start with lock enabled should lock", manager.isLocked.value)
    }

    @Test
    fun `cache warm path unchanged`() = runTest {
        // Arrange: lock disabled, cache will be initialized via runBlocking fallback
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(false)

        val manager = AppLockManager(context, userPrefs)
        // Do NOT call register() — ProcessLifecycleOwner.get() fails in unit test

        // First ON_START: runBlocking fallback reads false → cache initialized, no lock
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertFalse("first ON_START with pref=false should not lock", manager.isLocked.value)

        // Simulate going to background
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)

        // Act: second ON_START — cache already warm with false
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        // Assert: still not locked (cache warm path, skip runBlocking)
        assertFalse("warm cache with false should not lock", manager.isLocked.value)
    }
}
