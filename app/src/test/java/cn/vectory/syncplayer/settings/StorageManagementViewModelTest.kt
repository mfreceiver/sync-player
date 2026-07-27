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
import io.mockk.every
import io.mockk.mockk
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
}
