package cn.vectory.syncplayer.settings

import android.content.Context
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.storage.volume.MediaVolume
import cn.vectory.syncplayer.storage.volume.MediaVolumeProvider
import cn.vectory.syncplayer.sync.SyncEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Verifies StorageManagementViewModel.resetStorage behavior, focusing on the
 * non-leak contract: the user-visible resetWarning MUST NOT carry e.message
 * (no path / volumeId / internal detail leakage).
 *
 * Asserts on state shape and the absence of leaked substrings — never on the
 * exact Chinese warning text (that lives in strings.xml).
 *
 * 使用 StandardTestDispatcher 通过 IoDispatcher 注入，使所有 IO 工作都在测试调度器上
 * 确定性执行，无需 Thread.sleep。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageManagementViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val mediaVolumeProvider = mockk<MediaVolumeProvider>()
    private val userPrefs = mockk<UserPreferencesRepository>()
    private val videoItemDao = mockk<VideoItemDao>()
    private val playbackRecordDao = mockk<PlaybackRecordDao>()
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val syncEngine = mockk<SyncEngine>()

    // The secret internal message that MUST NOT leak into the UI.
    private val secretInternalMessage = "volumeId=sdcard-XXXX path=/storage/emulated/0/Android/data/secret"

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // 设置 Main dispatcher 使 viewModelScope.launch 在 testDispatcher 上运行
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setUpMocks() {
        every { syncEngine.progress } returns MutableStateFlow(SyncProgress(isRunning = false))
        coEvery { videoItemDao.getTotalItemCount() } returns 1
        coEvery { videoItemDao.deleteAll() } returns Unit
        coEvery { pendingSyncActionDao.deleteAll() } returns Unit
        coEvery { playbackRecordDao.deleteAll() } returns Unit
        coEvery { userPrefs.getMediaStorageTreeUri() } returns null
        // Mutex: real Mutex so withLock runs the block synchronously
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()

        // Stub getString to return a non-empty placeholder (resource resolution is
        // not under test here; we only verify the non-leak contract).
        every { context.getString(any<Int>()) } answers { "error-${firstArg<Int>()}" }
    }

    /**
     * Creates a VM with a custom volume and the StandardTestDispatcher.
     * 直接构造 VM（绕过 Hilt），注入 testDispatcher 作为 ioDispatcher。
     */
    private fun createVmWith(volume: MediaVolume): StorageManagementViewModel {
        coEvery { mediaVolumeProvider.current() } returns volume
        return StorageManagementViewModel(
            context = context,
            mediaVolumeProvider = mediaVolumeProvider,
            userPrefs = userPrefs,
            videoItemDao = videoItemDao,
            playbackRecordDao = playbackRecordDao,
            pendingSyncActionDao = pendingSyncActionDao,
            syncEngine = syncEngine,
            ioDispatcher = testDispatcher,
        )
    }

    @Test
    fun `reset failure warning does not leak e message`() = runTest(testDispatcher) {
        setUpMocks()

        // Arrange: volume.list throws an exception carrying a secret internal message
        val crashingVolume = mockk<MediaVolume>()
        coEvery { crashingVolume.list(any()) } throws IOException(secretInternalMessage)
        coEvery { crashingVolume.exists(any()) } returns false
        coEvery { crashingVolume.delete(any()) } returns true
        every { crashingVolume.usableSpace() } returns 1_000_000L

        val testVm = createVmWith(crashingVolume)
        advanceUntilIdle()

        // Act: 触发 resetStorage 并使用 advanceUntilIdle 等待所有协程完成。
        // 所有 IO 工作都使用 testDispatcher，因此 advanceUntilIdle 能确定性完成所有工作。
        testVm.resetStorage()
        advanceUntilIdle()

        // Assert: warning is non-null (failure reported) but does NOT contain the
        // secret internal message — no path/volumeId leakage.
        val state = testVm.state.value
        assertFalse("isResetting should be false after completion", state.isResetting)
        assertNotNull("resetWarning should be set on failure", state.resetWarning)
        val warning = state.resetWarning!!
        assertFalse(
            "resetWarning must not leak internal e.message",
            warning.contains(secretInternalMessage)
        )
        assertFalse(
            "resetWarning must not contain raw path segments",
            warning.contains("/storage/")
        )
        assertFalse(
            "resetWarning must not contain volumeId",
            warning.contains("volumeId")
        )
    }

    @Test
    fun `reset partial failure warning is set without leaking internals`() = runTest(testDispatcher) {
        setUpMocks()

        // Arrange: volume with one file that fails to delete
        val partialVolume = mockk<MediaVolume>()
        // bucket "0" contains one media file "42.enc" (42/1000=0, valid for bucket 0) — but delete returns false
        coEvery { partialVolume.list("") } returns listOf("0")
        coEvery { partialVolume.list("0") } returns listOf("42.enc")
        coEvery { partialVolume.list("thumbs") } returns emptyList()
        coEvery { partialVolume.delete(any()) } returns false // all deletes fail
        coEvery { partialVolume.exists(any()) } returns true
        coEvery { partialVolume.openRead(any()) } returns mockk {
            every { size() } returns 100L
            every { read(any(), any(), any()) } returns 0
        }
        every { partialVolume.usableSpace() } returns 1_000_000L

        val testVm = createVmWith(partialVolume)
        advanceUntilIdle()

        // Act
        testVm.resetStorage()
        advanceUntilIdle()

        // Assert: warning is set (partial failure) — warning is non-null only
        // when failed > 0, so non-null proves the partial-failure path ran.
        val state = testVm.state.value
        assertFalse("isResetting should be false after completion", state.isResetting)
        assertNotNull("resetWarning should be set on partial failure", state.resetWarning)
        val warning = state.resetWarning!!
        // An empty warning leaks nothing; a non-empty one must not leak internals.
        assertFalse(
            "resetWarning must not contain volumeId",
            warning.contains("volumeId")
        )
        assertFalse(
            "resetWarning must not contain raw path segments",
            warning.contains("/storage/")
        )
    }

    @Test
    fun `reset success clears warning`() = runTest(testDispatcher) {
        setUpMocks()

        // Arrange: clean volume, no files
        val cleanVolume = mockk<MediaVolume>()
        coEvery { cleanVolume.list(any()) } returns emptyList()
        coEvery { cleanVolume.exists(any()) } returns false
        coEvery { cleanVolume.delete(any()) } returns true
        every { cleanVolume.usableSpace() } returns 1_000_000L

        val testVm = createVmWith(cleanVolume)
        advanceUntilIdle()

        // Act
        testVm.resetStorage()
        advanceUntilIdle()

        // Assert: no warning on clean success
        val state = testVm.state.value
        assertFalse("isResetting should be false after completion", state.isResetting)
        assertNull("resetWarning should be null on clean success", state.resetWarning)
    }

    /** Clean volume mock: no files, usable space readable. */
    private fun cleanVolume(): MediaVolume {
        val volume = mockk<MediaVolume>()
        coEvery { volume.list(any()) } returns emptyList()
        coEvery { volume.exists(any()) } returns false
        coEvery { volume.delete(any()) } returns true
        every { volume.usableSpace() } returns 1_000_000L
        return volume
    }

    @Test
    fun `refresh datastore read failure degrades state without crashing`() = runTest(testDispatcher) {
        // T4 (BUG-004): URI first-read failure must degrade to an unknown state that
        // forbids all storage mutations (fail-closed), not crash the process.
        setUpMocks()
        coEvery { userPrefs.getMediaStorageTreeUri() } throws IOException("disk")

        // Act: init triggers refresh; the URI read fails at the very first stage.
        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()

        // Assert: unknown state → all storage mutations disabled.
        val state = testVm.state.value
        assertFalse("canSwitch must be false when URI read failed (unknown state)", state.canSwitch)
        assertFalse("canReset must be false when URI read failed (unknown state)", state.canReset)
        // Degradation originates from the URI first read — DAO/space queries never ran.
        coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }
        coVerify(exactly = 0) { videoItemDao.getTotalItemCount() }
        coVerify(exactly = 0) { mediaVolumeProvider.current() }
    }

    @Test
    fun `stale successful refresh cannot overwrite newer refresh result`() = runTest(testDispatcher) {
        // T4a (ordering): an older in-flight refresh (parked at the DAO call) must
        // not publish its internal-storage result after a newer switch + trailing
        // refresh already published the external state.
        setUpMocks()
        mockkObject(StorageManagementViewModel.Companion) {
            every { StorageManagementViewModel.resolveTreeDisplayName(any(), any()) } returns "external"
            // 1st read = init refresh A (internal); 2nd read = switch trailing refresh C (external)
            coEvery { userPrefs.getMediaStorageTreeUri() } returnsMany listOf(null, "content://tree/external")
            // Success write stub — setUpMocks() does not stub the setter.
            coEvery { userPrefs.setMediaStorageTreeUri("content://tree/external") } returns Unit
            val gateA = CompletableDeferred<Unit>()
            var daoCalls = 0
            coEvery { videoItemDao.getTotalItemCount() } coAnswers {
                daoCalls++
                if (daoCalls == 1) gateA.await()  // A parks at the count read
                0
            }

            // Act₁: A parks; B switches and C publishes the external state.
            val testVm = createVmWith(cleanVolume())
            advanceUntilIdle()
            testVm.switchToExternal("content://tree/external")
            advanceUntilIdle()

            // Assert₁ (fence before releasing A): C published the success state,
            // and B actually completed its write call (stub + call count together).
            assertTrue("C must have published isExternal=true", testVm.state.value.isExternal)
            assertTrue("C must have published canSwitch=true", testVm.state.value.canSwitch)
            coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/external") }

            // Act₂: release A — it now wants to publish its stale internal result.
            gateA.complete(Unit)
            advanceUntilIdle()

            // Assert₂: the stale result must not overwrite the newer one.
            assertTrue("stale refresh must not overwrite newer external state", testVm.state.value.isExternal)
        }
    }

    @Test
    fun `stale failed refresh cannot re-disable after newer success`() = runTest(testDispatcher) {
        // T4b (ordering): an older refresh whose URI read fails late must not
        // re-disable operations after a newer switch + refresh published success.
        setUpMocks()
        mockkObject(StorageManagementViewModel.Companion) {
            every { StorageManagementViewModel.resolveTreeDisplayName(any(), any()) } returns "external"
            coEvery { userPrefs.setMediaStorageTreeUri("content://tree/external") } returns Unit
            coEvery { videoItemDao.getTotalItemCount() } returns 0
            val gateA = CompletableDeferred<Unit>()
            var uriCalls = 0
            coEvery { userPrefs.getMediaStorageTreeUri() } coAnswers {
                uriCalls++
                if (uriCalls == 1) {
                    gateA.await()
                    throw IOException("disk")  // A's first read fails late
                } else {
                    "content://tree/external"  // C reads external
                }
            }

            // Act₁: A parks at its first read; B succeeds and C publishes good state.
            val testVm = createVmWith(cleanVolume())
            advanceUntilIdle()
            testVm.switchToExternal("content://tree/external")
            advanceUntilIdle()

            // Assert₁ (fence before releasing A): C published success; B completed write.
            assertTrue("C must have published canSwitch=true", testVm.state.value.canSwitch)
            assertTrue("C must have published isExternal=true", testVm.state.value.isExternal)
            coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/external") }

            // Act₂: release A — its URI read now throws IOException → catch branch.
            gateA.complete(Unit)
            advanceUntilIdle()

            // Assert₂: the stale failure must not overwrite the newer success
            // nor re-disable operations.
            assertTrue("stale failure must not re-disable canSwitch", testVm.state.value.canSwitch)
            assertTrue("stale failure must not overwrite isExternal", testVm.state.value.isExternal)
        }
    }

    @Test
    fun `switchToExternal write failure does not crash and keeps state truthful`() = runTest(testDispatcher) {
        // T5 (BUG-005): DataStore edit failure in switchToExternal must not crash;
        // the trailing refresh re-reads the unchanged truth (still internal).
        setUpMocks()
        // count=0 so the write path is reachable (setUpMocks default is 1).
        coEvery { videoItemDao.getTotalItemCount() } returns 0
        coEvery { userPrefs.setMediaStorageTreeUri(any()) } throws IOException("disk")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToExternal("content://tree/primary%3ATest")
        advanceUntilIdle()

        // Assert: no crash (test green itself proves it); state reflects the truth.
        assertFalse("write failure must bounce back to internal truth", testVm.state.value.isExternal)
        coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/primary%3ATest") }
    }

    @Test
    fun `switchToInternal write failure does not crash and keeps state truthful`() = runTest(testDispatcher) {
        // T5a (BUG-005): internal-side write failure — same contract as T5.
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } returns 0
        coEvery { userPrefs.setMediaStorageTreeUri(any()) } throws IOException("disk")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToInternal()
        advanceUntilIdle()

        assertFalse("write failure must keep internal truth", testVm.state.value.isExternal)
        coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri(null) }
    }

    @Test
    fun `switchToExternal db count failure does not crash and degrades`() = runTest(testDispatcher) {
        // T6: DAO count failure inside the switch lock must not crash; the trailing
        // refresh's inner guard degrades count to MAX_VALUE (fail-closed).
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } throws RuntimeException("room closed")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToExternal("content://tree/x")
        advanceUntilIdle()

        assertFalse("count failure must keep internal truth", testVm.state.value.isExternal)
        assertFalse("unknown count must disable switching (fail-closed)", testVm.state.value.canSwitch)
    }

    @Test
    fun `switchToInternal db count failure does not crash and degrades`() = runTest(testDispatcher) {
        // T6a: internal-side DAO failure — same contract as T6.
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } throws RuntimeException("room closed")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToInternal()
        advanceUntilIdle()

        assertFalse("count failure must keep internal truth", testVm.state.value.isExternal)
        assertFalse("unknown count must disable switching (fail-closed)", testVm.state.value.canSwitch)
    }

    @Test
    fun `switch rethrows CancellationException and skips refresh`() = runTest(testDispatcher) {
        // T7 (CE guard): CE must be rethrown (red line 3) — the switch's trailing
        // refresh is skipped, so the URI is only read by the init refresh.
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } returns 0
        coEvery { userPrefs.setMediaStorageTreeUri(any()) } throws CancellationException("cancelled")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToInternal()
        advanceUntilIdle()

        // Exactly one URI read — the init refresh. If CE were swallowed and the
        // trailing refresh ran, this would be 2.
        coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }
    }

    @Test
    fun `switchToExternal rethrows CancellationException and skips refresh`() = runTest(testDispatcher) {
        // T7a (CE guard): external-side CE — same contract as T7.
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } returns 0
        coEvery { userPrefs.setMediaStorageTreeUri(any()) } throws CancellationException("cancelled")

        val testVm = createVmWith(cleanVolume())
        advanceUntilIdle()
        testVm.switchToExternal("content://tree/x")
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }
    }
}
