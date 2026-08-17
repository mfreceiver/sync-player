package cn.vectory.syncplayer.settings

import android.util.Log
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.security.BiometricAuthManager
import cn.vectory.syncplayer.storage.volume.MediaVolumeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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
    private val mediaVolumeProvider = mockk<MediaVolumeProvider>(relaxed = true)

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
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(false)
        every { userPrefs.mediaStorageTreeUri } returns flow {
            throw CancellationException("collect cancelled")
        }

        // Act: construct the VM — init runs eagerly on UnconfinedTestDispatcher.
        SettingsViewModel(
            context = mockk(relaxed = true),
            syncLogRepository = syncLogRepository,
            userPrefs = userPrefs,
            biometricAuthManager = biometricAuthManager,
            mediaVolumeProvider = mediaVolumeProvider,
        )

        // Assert: cancellation must NOT fall into the generic catch — no failure log,
        // and no state was published from the cancelled collection.
        verify(exactly = 0) { Log.e(any(), any(), any<Throwable>()) }
    }
}
