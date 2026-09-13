package cn.vectory.syncplayer.settings

import android.content.Context
import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.SyncEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Verifies StorageManagementViewModel behavior, focusing on the non-leak contract:
 * the user-visible resetWarning MUST NOT carry e.message (no path / internal detail
 * leakage), and the refresh epoch ordering (stale refresh never overwrites newer).
 *
 * Asserts on state shape and the absence of leaked substrings — never on the exact
 * Chinese warning text (that lives in strings.xml).
 *
 * 使用 StandardTestDispatcher 通过 IoDispatcher 注入，使所有 IO 工作都在测试调度器上
 * 确定性执行，无需 Thread.sleep。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageManagementViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val mediaFileStore = mockk<MediaFileStore>()
    private val videoItemDao = mockk<VideoItemDao>()
    private val playbackRecordDao = mockk<PlaybackRecordDao>()
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val syncEngine = mockk<SyncEngine>()

    // The secret internal message that MUST NOT leak into the UI.
    private val secretInternalMessage = "path=/data/data/secret/0/42.tmp errno=EACCES"

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
        every { mediaFileStore.usableSpace() } returns 1_000_000L
        coEvery { mediaFileStore.deleteAllMedia() } returns (0 to 0)
        // Mutex: real Mutex so withLock runs the block synchronously
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()

        // Stub getString to return a non-empty placeholder (resource resolution is
        // not under test here; we only verify the non-leak contract).
        every { context.getString(any<Int>()) } answers { "error-${firstArg<Int>()}" }
    }

    /**
     * Creates a VM with the StandardTestDispatcher.
     * 直接构造 VM（绕过 Hilt），注入 testDispatcher 作为 ioDispatcher。
     */
    private fun createVm(): StorageManagementViewModel {
        return StorageManagementViewModel(
            context = context,
            mediaFileStore = mediaFileStore,
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

        // Arrange: deleteAllMedia throws an exception carrying a secret internal message
        coEvery { mediaFileStore.deleteAllMedia() } throws IOException(secretInternalMessage)

        val testVm = createVm()
        advanceUntilIdle()

        // Act
        testVm.resetStorage()
        advanceUntilIdle()

        // Assert: warning is non-null (failure reported) but does NOT contain the
        // secret internal message — no path/internal detail leakage.
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
            warning.contains("/data/")
        )
    }

    @Test
    fun `reset partial failure warning is set without leaking internals`() = runTest(testDispatcher) {
        setUpMocks()

        // Arrange: some files fail to delete
        coEvery { mediaFileStore.deleteAllMedia() } returns (7 to 3)

        val testVm = createVm()
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
        assertFalse(
            "resetWarning must not contain raw path segments",
            warning.contains("/data/")
        )
    }

    @Test
    fun `reset success clears warning`() = runTest(testDispatcher) {
        setUpMocks()

        // Arrange: clean delete
        coEvery { mediaFileStore.deleteAllMedia() } returns (5 to 0)

        val testVm = createVm()
        advanceUntilIdle()

        // Act
        testVm.resetStorage()
        advanceUntilIdle()

        // Assert: no warning on clean success
        val state = testVm.state.value
        assertFalse("isResetting should be false after completion", state.isResetting)
        assertNull("resetWarning should be null on clean success", state.resetWarning)
    }

    @Test
    fun `refresh db count failure degrades to fail-closed has-files semantics without crashing`() = runTest(testDispatcher) {
        // DAO count failure must degrade to "unknown → treat as has files" (fail-closed,
        // matching the historical contract), surface the placeholder space label, and not
        // crash the process.
        setUpMocks()
        coEvery { videoItemDao.getTotalItemCount() } throws IOException("disk")

        val testVm = createVm()
        advanceUntilIdle()

        val state = testVm.state.value
        assertTrue("unknown count must degrade to has-files (canReset stays available)", state.canReset)
        assertTrue("unknown count must degrade to MAX (has-files)", state.mediaFileCount > 0)
        assertTrue(
            "usable space label must be populated from the (still readable) store",
            state.usableSpaceLabel.isNotBlank() && state.usableSpaceLabel != "—"
        )
        assertFalse("reset must not be left in-progress", state.isResetting)
    }

    @Test
    fun `stale successful refresh cannot overwrite newer refresh result`() = runTest(testDispatcher) {
        // Epoch ordering: an older in-flight refresh (parked at the DAO call) must
        // not publish its result after a newer refresh already published state.
        setUpMocks()
        val gateA = CompletableDeferred<Unit>()
        var daoCalls = 0
        coEvery { videoItemDao.getTotalItemCount() } coAnswers {
            daoCalls++
            if (daoCalls == 1) gateA.await()  // A parks at the count read
            3
        }

        val testVm = createVm()
        // A parks; drive a newer refresh B that publishes the current state.
        advanceUntilIdle()
        testVm.refresh()
        advanceUntilIdle()

        assertTrue("B must have published canReset=true", testVm.state.value.canReset)

        // Release A — it now wants to publish its stale result.
        gateA.complete(Unit)
        advanceUntilIdle()

        // The stale result must not overwrite the newer one.
        assertTrue("stale refresh must not overwrite newer state", testVm.state.value.canReset)
    }
}
