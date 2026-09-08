package cn.vectory.syncplayer.browser

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.SearchViewModel.SearchScope
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.TestScope

/**
 * F03 regression locks for [SearchViewModel]'s single-pipeline search flow.
 *
 * The pre-fix implementation combined `_query.debounce(300)` with a SEPARATE
 * `_query.flatMapLatest { repo.search }` — query A's state could pair with query B's
 * results (raw path queries per keystroke, debounced text lags behind).
 *
 * These tests pin the target semantics:
 * - one debounced query value is captured into the whole downstream; flatMapLatest
 *   cancels the previous query's pipeline;
 * - results are retained (not refreshed) until the debounced switch fires — no
 *   pre-debounce repo call, no cross-paired emission;
 * - blank query short-circuits with NO repo query;
 * - scope switches re-filter using the SAME captured query without re-querying the repo.
 *
 * Assertions are on counts / ids / enum flags / call sequences — never user-facing text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val videoRepository = mockk<VideoRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val fileOps = mockk<FileOperationsUseCase>(relaxed = true)

    /** Every repo search invocation, in order — pins the query-churn behavior. */
    private val searchCalls = mutableListOf<String>()

    /** Cold-source flows per query, for tests that drive result emission manually. */
    private val searchFlows = mutableMapOf<String, MutableStateFlow<List<VideoItemWithPlayback>>>()

    @Before
    fun setUp() {
        searchCalls.clear()
        searchFlows.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubCommon() {
        every { syncRelationRepository.getAllWithSourceFlow() } returns MutableStateFlow(emptyList())
        every { videoRepository.getParentPathsInFolder(any()) } returns MutableStateFlow(emptyList())
    }

    /** Default stub: one conflated StateFlow per query string, emits its value on collect. */
    private fun stubSearchByQueryMap() {
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            searchFlows.getOrPut(q) { MutableStateFlow(emptyList()) }
        }
    }

    private fun createVm(): SearchViewModel = SearchViewModel(
        savedStateHandle = SavedStateHandle(mapOf("folder" to "")),
        videoRepository = videoRepository,
        syncRelationRepository = syncRelationRepository,
        fileOps = fileOps,
    )

    /**
     * WhileSubscribed(5000) stateIn needs a live collector for the pipeline to run.
     * Unconfined on the shared test scheduler: collection starts immediately and
     * virtual time still drives the 300ms debounce.
     */
    private fun TestScope.activateCollector(vm: SearchViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchResults.collect { }
        }
    }

    private fun videoItem(id: Long, fileName: String) = VideoItemWithPlayback(
        id = id,
        relativePath = "media/$fileName",
        fileName = fileName,
        fileSize = 1L,
        duration = null,
        width = null,
        height = null,
        bitrate = null,
        frameRate = null,
        audioGainDb = null,
        star = 0,
        mediaType = "VIDEO",
        dateTaken = null,
        playbackPosition = 0L,
        playbackDuration = 0L,
        isCompleted = false,
        isTrashed = false,
    )

    // --- Scenario 1: stable "a" then rapid switch to "b" ---

    @Test
    fun rapidInput_resultsRetainedUntilDebounceSwitch_finalResultsOnlyFromLatestQuery() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        // Stable query "a": debounce fires, repo queried, result delivered.
        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a"), searchCalls)
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        runCurrent()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        // Rapid input "b": before the debounce fires, the new query must NOT be
        // issued (no per-keystroke churn) and the old a-results must be retained —
        // neither refreshed nor mixed with b-data.
        vm.setQuery("b")
        runCurrent()
        assertEquals("repo must not be queried before debounce fires", listOf("a"), searchCalls)
        assertEquals(
            "old a-results must be retained while waiting for debounce",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )

        // Debounce fires: whole pipeline switches to "b"; b's empty baseline arrives.
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a", "b"), searchCalls)
        assertEquals(listOf<Long>(), vm.searchResults.value.map { it.videoItemId })

        // b's real result: only b's item, alpha never resurfaces.
        searchFlows.getValue("b").value = listOf(videoItem(2L, "banana.mp4"))
        runCurrent()
        assertEquals(listOf(2L), vm.searchResults.value.map { it.videoItemId })
    }

    // --- Scenario 2: old query's slow/in-flight emission must not pollute new state ---

    @Test
    fun slowOldQueryEmission_cancelledByFlatMapLatest_doesNotPolluteNewQueryState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        // Query "a" emits an empty baseline, then holds a second emission behind a gate —
        // a genuinely slow emission still in flight when the pipeline switches to "b".
        val gateA = CompletableDeferred<Unit>()
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            if (q == "a") {
                flow {
                    emit(emptyList<VideoItemWithPlayback>())
                    gateA.await()
                    emit(listOf(videoItem(1L, "alpha.mp4")))
                }
            } else {
                searchFlows.getOrPut(q) { MutableStateFlow(emptyList()) }
            }
        }
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a"), searchCalls)
        assertEquals(0, vm.searchResults.value.size)

        // Switch to "b": flatMapLatest must cancel the still-suspended "a" pipeline.
        vm.setQuery("b")
        runCurrent()
        assertEquals("repo must not be queried before debounce fires", listOf("a"), searchCalls)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a", "b"), searchCalls)
        assertEquals(0, vm.searchResults.value.size)

        // Release the old query's in-flight emission after cancellation: it must be
        // dropped entirely — no alpha pollution, no cross-paired state.
        gateA.complete(Unit)
        advanceUntilIdle()
        assertTrue(
            "late a-emission must not reach the b-pipeline",
            vm.searchResults.value.none { it.videoItemId == 1L },
        )
        assertEquals(0, vm.searchResults.value.size)

        // b's real result arrives intact.
        searchFlows.getValue("b").value = listOf(videoItem(2L, "banana.mp4"))
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.searchResults.value.map { it.videoItemId })
    }

    // --- Scenario 3: blank query short-circuits without touching the repo ---

    @Test
    fun blankQuery_shortCircuitsWithoutRepoSearch_andClearsResults() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        // Blank input from the start: debounce fires but no repo query may happen.
        vm.setQuery("   ")
        advanceUntilIdle()
        assertEquals("blank query must not trigger a repo search", 0, searchCalls.size)
        assertEquals(0, vm.searchResults.value.size)

        // Non-blank query works normally.
        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        // Back to blank: results cleared, still no additional repo search.
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals("blank query must not trigger a repo search", listOf("a"), searchCalls)
        assertEquals(0, vm.searchResults.value.size)
    }

    // --- Scope switch: same captured query re-filters without re-querying the repo ---

    @Test
    fun scopeSwitch_refiltersWithSameCapturedQuery_noRepoRequery() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        // Folder whose leaf name matches query "alpha" (ignore-case contains).
        every { videoRepository.getParentPathsInFolder(any()) } returns
            MutableStateFlow(listOf("media/AlphaDir"))
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("alpha")
        advanceUntilIdle()
        searchFlows.getValue("alpha").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals(listOf("alpha"), searchCalls)

        // ALL: one matching folder entry + one file entry.
        val all = vm.searchResults.value
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.isDirectory })
        assertEquals(1, all.count { !it.isDirectory && it.videoItemId == 1L })

        // FILES_ONLY: folder entry dropped, same captured query's file kept, no repo re-query.
        vm.setSearchScope(SearchScope.FILES_ONLY)
        advanceUntilIdle()
        val filesOnly = vm.searchResults.value
        assertEquals(1, filesOnly.size)
        assertEquals(0, filesOnly.count { it.isDirectory })
        assertEquals(listOf(1L), filesOnly.map { it.videoItemId })
        assertEquals("scope switch must not re-query the repo", listOf("alpha"), searchCalls)

        // FOLDERS_ONLY: inverse.
        vm.setSearchScope(SearchScope.FOLDERS_ONLY)
        advanceUntilIdle()
        val foldersOnly = vm.searchResults.value
        assertEquals(1, foldersOnly.size)
        assertEquals(1, foldersOnly.count { it.isDirectory })
        assertEquals("scope switch must not re-query the repo", listOf("alpha"), searchCalls)
    }
}
