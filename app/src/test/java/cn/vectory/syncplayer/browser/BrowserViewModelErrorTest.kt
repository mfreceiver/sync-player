package cn.vectory.syncplayer.browser

import android.content.Context
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelErrorTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val userPrefs = mockk<UserPreferencesRepository>()
    private val fileOps = mockk<FileOperationsUseCase>(relaxed = true)
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        every { syncRelationRepository.getAllWithSourceFlow() } returns MutableStateFlow(emptyList())
        every { syncSourceRepository.getAllFlow() } returns MutableStateFlow(emptyList())
        every { videoRepository.getAllItemsFlow() } returns MutableStateFlow(emptyList())
        every { videoRepository.hasAnyItemsFlow() } returns MutableStateFlow(false)
        every { userPrefs.onboardingDismissed } returns MutableStateFlow(false)
        coEvery { userPrefs.getDefaultSortMode() } returns "FILENAME_ASC"
        coEvery { userPrefs.getDefaultFilter() } returns "ALL"
        coEvery { userPrefs.getViewMode() } returns "LIST"
        coEvery { userPrefs.getGridColumns() } returns 3
        coEvery { userPrefs.getBrowseMode() } returns "DIRECTORY"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = BrowserViewModel(
        videoRepository = videoRepository,
        playbackRepository = playbackRepository,
        syncRelationRepository = syncRelationRepository,
        syncSourceRepository = syncSourceRepository,
        userPrefs = userPrefs,
        fileOps = fileOps,
        undoDeleteUseCase = undoDeleteUseCase,
        context = context,
    )

    @Test
    fun `identical consecutive errors get distinct increasing seq`() {
        val vm = createVm()

        vm.showError("error")
        val first = vm.error.value!!
        vm.showError("error")
        val second = vm.error.value!!

        assertTrue(second.seq > first.seq)
        assertEquals(first.seq + 1, second.seq)
    }

    /**
     * F1 复审缺口回归：错误态重试（[BrowserViewModel.refreshCurrentFolder]）对**同
     * folder** 也必须发生真实重查。旧接线 clearError + setFolder(同路径) 是 StateFlow
     * 赋值 no-op——同文件夹重试从不触发重查。断言仓库 DIRECTORY 模式查询的调用次数
     * （初始构建 1 次 → 重试后恰 2 次），并断言错误被清除。
     */
    @Test
    fun `refreshCurrentFolder on unchanged folder re-queries and clears error`() = runTest {
        // Bind viewModelScope (Main) to this runTest's scheduler so the 300ms filter
        // debounce and the refresh chain advance under virtual time.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // DIRECTORY-mode chain needs real Flow stubs (relaxed Flow mocks would never emit).
        every { playbackRepository.getByFolderFlow(any()) } returns MutableStateFlow(emptyList())
        every { videoRepository.getSubFolders(any()) } returns flowOf(emptyList())
        every { videoRepository.getItemsInFolder(any()) } returns flowOf(emptyList())
        coEvery { videoRepository.getChildVideoStats(any()) } returns emptyList()
        coEvery { videoRepository.getDirectChildFolderHasSubFolders(any(), any()) } returns emptyMap()

        val vm = createVm()
        vm.setFolder("videos")
        // Warm the Lazily-shared browseItems chain (production UI always collects it).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.browseItems.collect {} }
        advanceTimeBy(301)
        // Initial build completed (buildBrowseItems tail runs on the real IO pool: poll).
        verify(timeout = 5_000, exactly = 1) { videoRepository.getSubFolders("videos") }

        // Error state on the SAME folder: retry must still force a real re-query.
        vm.showError("boom")
        assertTrue(vm.error.value != null)
        vm.refreshCurrentFolder()
        verify(timeout = 5_000, exactly = 2) { videoRepository.getSubFolders("videos") }
        assertEquals(null, vm.error.value)
    }
}
