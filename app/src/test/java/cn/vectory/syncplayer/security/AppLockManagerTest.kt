package cn.vectory.syncplayer.security

import android.content.Context
import androidx.lifecycle.Lifecycle
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Verifies AppLockManager cold-start cache gate (AND-005):
 * - L1-C1: Cold start (collector not running) with lock enabled → ON_START locks.
 * - L1-C2: Warm cache (already initialized to false) → ON_START does not lock.
 *
 * Cannot call register() in tests because ProcessLifecycleOwner.get() requires
 * Android framework. Instead we drive state via runBlocking fallback (which
 * initializes the cache) + ON_STOP/ON_START cycles.
 *
 * T8–T16 (BUG-014): collector resilience (retryWhen + fail-closed during
 * DataStore outages), versioned cache publication protocol, bounded cold-start
 * read. Scheduling notes: resubscription is driven by runCurrent →
 * advanceTimeBy(5_000) → runCurrent (advanceUntilIdle stops early when only
 * backgroundScope tasks remain; advanceTimeBy does not run tasks landing
 * exactly at the target instant); cold-start waits are controlled via
 * coldStartReadTimeoutMs real-time injection (the inner runBlocking has its own
 * event loop — virtual time does not penetrate it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `collector survives transient IOException, resubscribes, and follows recovered source`() = runTest {
        // T8 (BUG-014, E3a regression + recovery evidence): a single transient
        // IOException must not permanently kill the preference collector — it
        // resubscribes after backoff and then follows the recovered source.
        val source = MutableStateFlow(false)
        var collections = 0
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow {
            collections++
            if (collections == 1) {
                emit(false)
                throw IOException("DataStore read corruption")
            } else {
                emitAll(source)
            }
        }
        val manager = AppLockManager(context, userPrefs)

        // Act: start collecting; first round emits false then fails; the fixed
        // predicate publishes the fail-closed value and backs off (virtual 5s).
        manager.startPreferenceCollection(backgroundScope)
        runCurrent()          // first round: emit(false) → fail → fail-closed true → delay(5s)
        advanceTimeBy(5_000)  // advance to (but not through) the backoff deadline
        runCurrent()          // execute the resubscription task

        // Assert (anti-false-positive): subscription count is the direct evidence
        // that resubscription happened — "fail-closed cache true but collector
        // permanently dead" would also pass a naive isLocked-only assertion.
        assertEquals("collector must have resubscribed exactly once", 2, collections)

        // Recovered source=true flows through → ON_START locks.
        source.value = true
        runCurrent()  // let the emitAll collector consume the new value
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertTrue("recovered source=true must lock on ON_START", manager.isLocked.value)

        // Recovered source=false flows through too → after unlock, no re-lock
        // from a stale fault cache (the live collector keeps delivering truth).
        source.value = false
        runCurrent()  // cache falls back to false
        manager.unlock()  // simulate successful authentication
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertFalse("recovered source=false must not re-lock", manager.isLocked.value)
    }

    @Test
    fun `during datastore outage lock preference is treated as enabled`() = runTest {
        // T9 (BUG-014): while the preference source keeps failing, the lock
        // preference is treated as enabled (fail-closed), never silently disabled.
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow { throw IOException("corrupt") }
        val manager = AppLockManager(context, userPrefs)

        // Act: run exactly the first failed round (predicate publishes the
        // fail-closed value and parks in the 5s backoff).
        manager.startPreferenceCollection(backgroundScope)
        runCurrent()
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        // Assert: outage ⇒ treated as enabled.
        assertTrue("during outage the lock must be treated as enabled", manager.isLocked.value)
    }

    @Test
    fun `cold start read failure is fail-closed not crash`() = runTest {
        // T10 (BUG-014, E3b regression): cold-start read failure must converge to
        // fail-closed (lock), not crash the main thread.
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow { throw IOException("corrupt") }
        val manager = AppLockManager(context, userPrefs)
        // No startPreferenceCollection — cold start path.

        // Act: must return normally instead of throwing.
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        // Assert: fail-closed.
        assertTrue("unreadable source at cold start must lock", manager.isLocked.value)
    }

    @Test
    fun `cold start rethrows CancellationException`() = runTest {
        // T11 (guard, red line 3): external cancellation must propagate through
        // the cold-start read — the narrow catch only handles IOException, and the
        // local timeout (which becomes null) is explicitly distinct from external
        // cancellation (which throws).
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow { throw CancellationException("cancelled") }
        val manager = AppLockManager(context, userPrefs)

        assertThrows(CancellationException::class.java) {
            manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        }
    }

    @Test
    fun `stale cold read cannot overwrite collector fail-closed during interleaved outage`() = runTest {
        // T12 (versioned publication ordering): the cold read snapshots the
        // generation, then the collector publishes its fail-closed value *while
        // the cold read is still in flight*; the stale cold value (false) must be
        // discarded — first ON_START still locks and the backoff-period cache
        // stays fail-closed true.
        var collections = 0
        val outageGate = CompletableDeferred<Unit>()
        val coldStartGate = CompletableDeferred<Unit>()
        val coldStarted = CompletableDeferred<Unit>()
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow {
            collections++
            if (collections == 1) {
                outageGate.await()
                throw IOException("outage")
            } else {
                coldStarted.complete(Unit)
                coldStartGate.await()
                emit(false)
            }
        }
        val manager = AppLockManager(context, userPrefs)
        // Large real-time timeout so the gate waits are never cut short.
        AppLockManager.coldStartReadTimeoutMs = 60_000

        // Act: collector parks at the outage gate; the cold start (real thread —
        // runBlocking would deadlock the test scheduler if run on it) takes the
        // 2nd collection and parks at coldStartGate.
        manager.startPreferenceCollection(backgroundScope)
        runCurrent()
        val coldThread = thread { manager.onStateChanged(mockk(), Lifecycle.Event.ON_START) }
        coldStarted.await()                 // deterministic: cold read is in flight
        outageGate.complete(Unit)
        runCurrent()                        // collector: IOException → fail-closed publish → backoff
        coldStartGate.complete(Unit)        // cold read returns the stale false
        coldThread.join(5_000)

        // Assert: first ON_START locked despite the stale cold read.
        assertTrue("stale cold read must not defeat the fail-closed value", manager.isLocked.value)
        // Without advancing virtual time (collector still in backoff):
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertTrue("backoff-period cache must stay fail-closed", manager.isLocked.value)
    }

    @Test
    fun `already-published fail-closed cache skips cold read entirely`() = runTest {
        // T12a (versioned publication ordering, complementary order): the
        // collector's fail-closed publish COMPLETES before the cold-start
        // decision — the fused critical section must observe initialized=true and
        // skip the cold read altogether (collections stays 1), and the cache
        // keeps the fault value across the backoff window.
        var collections = 0
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow {
            collections++
            if (collections == 1) throw IOException("outage") else emit(false)
        }
        val manager = AppLockManager(context, userPrefs)

        // Act: first collection fails → predicate publishes fail-closed (init=true)
        // → parks in backoff. Then a warm-path ON_START directly on the test thread.
        manager.startPreferenceCollection(backgroundScope)
        runCurrent()
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        // Assert: cold read was skipped entirely — the subscription count is the
        // direct evidence (a failed skip would issue a 2nd collection).
        assertEquals("cold read must be skipped when the cache is already published", 1, collections)
        assertTrue("fail-closed cache must lock on ON_START", manager.isLocked.value)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertTrue("backoff-period cache must stay fail-closed", manager.isLocked.value)
    }

    @Test
    fun `collector cancelled during backoff does not resubscribe`() = runTest {
        // T13 (guard): cancelling the collector scope during the backoff window
        // must not produce a resubscription (cancellation is not retried).
        var collections = 0
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow {
            collections++
            throw IOException("corrupt")
        }
        val manager = AppLockManager(context, userPrefs)
        // Self-owned cancellable scope — backgroundScope only cancels at test end.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        manager.startPreferenceCollection(scope)
        runCurrent()          // first failure → backoff
        scope.cancel()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("cancelled collector must not resubscribe", 1, collections)
    }

    @Test
    fun `cold start with no first value times out fail-closed and returns promptly`() = runTest {
        // T14 (bounded wait): a source that never emits a first value must not
        // hang the cold start forever — the local read times out (real time,
        // injected) and converges to fail-closed, promptly and without leaking
        // the worker thread. The cold-start call runs on a dedicated cleanable
        // thread because the pre-fix unbounded runBlocking would otherwise hang
        // the test scheduler itself (dispatchTimeoutMs is not a thread watchdog).
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow { awaitCancellation() }
        val manager = AppLockManager(context, userPrefs)
        AppLockManager.coldStartReadTimeoutMs = 100  // real-time injection: fast and deterministic
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit { manager.onStateChanged(mockk(), Lifecycle.Event.ON_START) }
            try {
                future.get(5, TimeUnit.SECONDS)  // bounded wait on the test thread
            } catch (e: TimeoutException) {
                throw AssertionError("cold start did not return within bounded time", e)
            }
            // Local read timeout = treated as enabled (same semantics as IOException).
            assertTrue("cold-start timeout must be fail-closed", manager.isLocked.value)
        } finally {
            pool.shutdownNow()  // interrupt → runBlocking converts to cancellation
            assertTrue(
                "worker thread must be reclaimed after interrupt",
                pool.awaitTermination(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun `process rebuild with still-unreadable source fails closed again`() = runTest {
        // T15 (process rebuild): a fresh manager (volatile cache gone with the
        // process) against a still-unreadable source takes the cold path and
        // fails closed again — no reliance on any previous-process state.
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns flow { throw IOException("corrupt") }
        val manager = AppLockManager(context, userPrefs)

        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)

        assertTrue("rebuilt process with unreadable source must fail closed", manager.isLocked.value)
    }

    @Test
    fun `locked state persists across background cycles until unlock`() = runTest {
        // T16 (behavior lock, "no authenticator available ⇒ stay locked" row):
        // without an unlock() (the authentication-success callback), background
        // cycles and time do not clear the locked state.
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(true)
        val manager = AppLockManager(context, userPrefs)

        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)  // cold start → locks
        repeat(2) {
            manager.onStateChanged(mockk(), Lifecycle.Event.ON_STOP)
            manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        }

        assertTrue("locked state must persist until unlock()", manager.isLocked.value)
    }

    @Test
    fun `ioDispatcher parameter can be injected and used`() = runTest {
        val userPrefs = mockk<UserPreferencesRepository>()
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(false)
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val manager = AppLockManager(context, userPrefs, testDispatcher)

        manager.onStateChanged(mockk(), Lifecycle.Event.ON_START)
        assertFalse("lock disabled should not lock", manager.isLocked.value)
    }
}
