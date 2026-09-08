package cn.vectory.syncplayer.settings

import android.util.Log
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.security.BiometricAuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * F04 regression lock: the settings page's storage-location display name must be
 * resolved asynchronously (off composition) by the VM's URI-keyed state source.
 *
 * Contract asserted here (state shape / call counts only — never display text):
 * - URI emission triggers exactly one resolution on the injected IO dispatcher.
 * - Resolution failure (SecurityException etc.) publishes the null sentinel —
 *   the Screen maps it to a fixed resource string; the URI-derived fallback
 *   label must never leak into this state.
 * - A slow stale resolution must not overwrite a newer URI's state
 *   (latest-wins via epoch), and the new request publishes the placeholder
 *   (null) immediately.
 * - Same-URI re-emissions do not re-resolve (no read amplification); a null
 *   URI clears the label without calling the resolver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStorageLocationLabelTest {

    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val userPrefs = mockk<UserPreferencesRepository>()
    private val biometricAuthManager = mockk<BiometricAuthManager>(relaxed = true)

    // Shared scheduler: Main is unconfined on it (collector runs eagerly),
    // IO work parks on testDispatcher until advanceUntilIdle.
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testDispatcher.scheduler))
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        mockkObject(StorageManagementViewModel.Companion)
    }

    @After
    fun tearDown() {
        unmockkObject(StorageManagementViewModel.Companion)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun createVm(
        volumeBalance: MutableStateFlow<Boolean> = MutableStateFlow(false),
        mediaStorageTreeUri: MutableStateFlow<String?>,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
    ): SettingsViewModel {
        every { userPrefs.volumeBalance } returns volumeBalance
        every { userPrefs.quickRecycle } returns MutableStateFlow(true)
        every { userPrefs.secureLockEnabled } returns MutableStateFlow(false)
        every { userPrefs.mediaStorageTreeUri } returns mediaStorageTreeUri
        return SettingsViewModel(
            context = mockk(relaxed = true),
            syncLogRepository = syncLogRepository,
            userPrefs = userPrefs,
            biometricAuthManager = biometricAuthManager,
            ioDispatcher = ioDispatcher,
        )
    }

    private fun treeUriFlow(value: String?): MutableStateFlow<String?> =
        MutableStateFlow(value)

    @Test
    fun `uri emission resolves display name on io dispatcher and publishes it`() =
        runTest(testDispatcher) {
            val uriFlow = treeUriFlow("content://tree/primary%3AMovies")
            every {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            } returns "Movies"

            val vm = createVm(mediaStorageTreeUri = uriFlow)
            advanceUntilIdle()

            assertEquals("Movies", vm.storageLocationLabel.value)
            verify(exactly = 1) {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            }
        }

    @Test
    fun `resolver security failure publishes null sentinel without leaking uri`() =
        runTest(testDispatcher) {
            val secretUri = "content://tree/primary%3ASecretDir"
            val uriFlow = treeUriFlow(secretUri)
            every {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            } throws SecurityException("denied: $secretUri")

            val vm = createVm(mediaStorageTreeUri = uriFlow)
            advanceUntilIdle()

            // null sentinel = Screen falls back to the fixed generic resource.
            assertNull(vm.storageLocationLabel.value)
            // The null sentinel carries no URI-derived text (nothing to leak —
            // state shape is String? and the failure path publishes null, not
            // a label derived from the URI).
            verify(exactly = 1) {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            }
        }

    @Test
    fun `stale slow resolution cannot overwrite newer uri label and placeholder shows meanwhile`() =
        runTest(testDispatcher) {
            val uriFlow = treeUriFlow("content://tree/A")
            var calls = 0
            every {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            } answers {
                calls++
                if (calls == 1) "OldA" else "NewB"
            }

            val vm = createVm(mediaStorageTreeUri = uriFlow)

            // A's resolution is parked on the IO dispatcher (not yet run).
            // Request a new URI: collector (unconfined Main) runs eagerly.
            uriFlow.value = "content://tree/B"

            // Placeholder for the new URI — stale result must not be shown.
            assertNull("new URI request must immediately show placeholder", vm.storageLocationLabel.value)

            advanceUntilIdle()
            // A resolves "OldA" first but is discarded by epoch; B publishes.
            assertEquals("NewB", vm.storageLocationLabel.value)
            verify(exactly = 2) {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            }
        }

    @Test
    fun `same uri re-emission does not re-resolve and null uri clears label`() =
        runTest(testDispatcher) {
            val uriFlow = treeUriFlow("content://tree/A")
            val volumeBalanceFlow = MutableStateFlow(false)
            every {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            } returns "A"

            val vm = createVm(
                volumeBalance = volumeBalanceFlow,
                mediaStorageTreeUri = uriFlow,
            )
            advanceUntilIdle()
            assertEquals("A", vm.storageLocationLabel.value)

            // Unrelated preference change re-emits the same URI → no re-resolve.
            volumeBalanceFlow.value = true
            advanceUntilIdle()
            verify(exactly = 1) {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            }

            // null URI clears the label without calling the resolver again.
            uriFlow.value = null
            advanceUntilIdle()
            assertNull(vm.storageLocationLabel.value)
            verify(exactly = 1) {
                StorageManagementViewModel.resolveTreeDisplayNameOrNull(any(), any())
            }
        }
}
