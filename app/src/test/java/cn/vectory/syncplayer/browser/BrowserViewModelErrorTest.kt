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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
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
}
