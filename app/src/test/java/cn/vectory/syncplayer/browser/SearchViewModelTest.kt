package cn.vectory.syncplayer.browser

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.vectory.syncplayer.browser.SearchViewModel.SearchDeleteOperation
import cn.vectory.syncplayer.browser.SearchViewModel.SearchPhase
import cn.vectory.syncplayer.browser.SearchViewModel.SearchScope
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F03 regression locks + P1/P1b search state-ownership locks for [SearchViewModel].
 *
 * F03 locks (retained verbatim in assertion semantics):
 * - one debounced query value is captured into the whole downstream; flatMapLatest
 *   cancels the previous query's pipeline;
 * - results are retained (attributed, not refreshed) until the debounced switch fires —
 *   no pre-debounce repo call, no cross-paired emission;
 * - blank query short-circuits with NO repo query;
 * - scope switches re-filter using the SAME captured query without re-querying the repo.
 *
 * P1 locks (search performance + state ownership):
 * - [SearchViewModel.SearchState] carries an inputKey and a displayedResult attributed to
 *   its own resultKey + generation; input changes flip the phase immediately (WAITING)
 *   while the old result stays attributed to the old resultKey (stale) until the new
 *   READY arrives;
 * - a late/old-generation completion must never overwrite the newer attribution — gated
 *   on the real Default dispatcher (queued mapping blocks), not just repo-flow awaits;
 * - mapping/expansion/sorting run off the collector thread;
 * - clearing the input flips to EMPTY_QUERY immediately without any repo query;
 * - VM-level deletes route through [UndoDeleteUseCase] (tokenized undo).
 *
 * P1b locks (rev-5 fixes):
 * - the Room data flow throttle is a single-subscription window state machine: leading
 *   value immediate, fixed window with latest-only trailing emission, continuous input
 *   never resets the window (anti-starvation); tests pin emission instants at the exact
 *   window boundary, publish counts/generations, and the upstream subscription count;
 * - the throttle sits BEFORE folder-path expansion;
 * - stale results are gated at every VM entry: the compat surface never exposes stale
 *   items, delete/toggle/folder-delete entry points no-op while stale (asserted via
 *   side-effect call counts, not just queue ids);
 * - hard-delete lifecycle: completion events ([SearchViewModel.deleteEvents], payload =
 *   operationId + operation + success), pending-id filtered from results until undo,
 *   and a verified onCleared fallback commit;
 * - folder-flow failures flow into the pipeline's FAILED/ErrorEvent handling (no
 *   unhandled coroutine exception); [SearchViewModel.retrySearch] refires the current
 *   inputKey and recovers;
 * - failure tests count ErrorEvents exactly-once.
 *
 * Assertions are on counts / ids / enum flags / call sequences — never user-facing text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val videoRepository = mockk<VideoRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val fileOps = mockk<FileOperationsUseCase>(relaxed = true)
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val appContext = mockk<Context>(relaxed = true)

    /** Every repo search invocation, in order — pins the query-churn behavior. */
    private val searchCalls = mutableListOf<String>()

    /** Cold-source flows per query, for tests that drive result emission manually. */
    private val searchFlows = mutableMapOf<String, MutableStateFlow<List<VideoItemWithPlayback>>>()

    /** Every non-null ErrorEvent observed on [SearchViewModel.error], in order. */
    private val errorEventsSeen = mutableListOf<ErrorEvent>()

    /** Every delete completion event observed on [SearchViewModel.deleteEvents], in order. */
    private val deleteEventsSeen = mutableListOf<SearchViewModel.SearchDeleteEvent>()

    @Before
    fun setUp() {
        searchCalls.clear()
        searchFlows.clear()
        errorEventsSeen.clear()
        deleteEventsSeen.clear()
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

    /**
     * P1: mapping / folder expansion / natural sort moved to an injected default
     * dispatcher. Tests share the runTest scheduler so virtual time controls both
     * the debounce/throttle windows and the mapping hops deterministically.
     */
    private fun TestScope.createVm(
        defaultDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    ): SearchViewModel = SearchViewModel(
        savedStateHandle = SavedStateHandle(mapOf("folder" to "")),
        videoRepository = videoRepository,
        syncRelationRepository = syncRelationRepository,
        fileOps = fileOps,
        undoDeleteUseCase = undoDeleteUseCase,
        appContext = appContext,
        defaultDispatcher = defaultDispatcher,
    )

    /**
     * P1b 🟠: a fully manual Default dispatcher — mapping blocks are QUEUED and run
     * only on [ManualDispatchQueue.runQueued]. Used to gate the mapping itself
     * (not just the repo flow) for the old-generation overwrite lock.
     */
    private class ManualDispatchQueue : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun runQueued() {
            while (true) {
                val block = synchronized(queue) { queue.removeFirstOrNull() } ?: return
                block.run()
            }
        }
    }

    /** Exposes [SearchViewModel.onCleared] via ViewModelStore.clear() for the fallback-commit lock. */
    @Suppress("UNCHECKED_CAST")
    private fun TestScope.putInStoreAndClear(vm: SearchViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        ).get(SearchViewModel::class.java)
        store.clear()
    }

    /**
     * WhileSubscribed(5000) stateIn needs a live collector for the state assembly to run.
     * Unconfined on the shared test scheduler: collection starts immediately and
     * virtual time still drives the debounce / throttle / mapping hops.
     */
    private fun TestScope.activateCollector(vm: SearchViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.searchResults.collect { }
        }
    }

    private fun TestScope.activateErrorCollector(vm: SearchViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.error.collect { event -> if (event != null) errorEventsSeen.add(event) }
        }
    }

    private fun TestScope.activateDeleteEventCollector(vm: SearchViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.deleteEvents.collect { deleteEventsSeen.add(it) }
        }
    }

    /** Cross the latest-only Room throttle window (300ms) for a manually set emission. */
    private fun TestScope.crossThrottleWindow() {
        advanceTimeBy(301)
        runCurrent()
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

    // --- F03 Scenario 1: stable "a" then rapid switch to "b" ---

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
        crossThrottleWindow()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        // Rapid input "b": before the debounce fires, the new query must NOT be
        // issued (no per-keystroke churn). P1b: the compat surface must not expose
        // stale items; the retained result stays attributed to the old resultKey.
        vm.setQuery("b")
        runCurrent()
        assertEquals("repo must not be queried before debounce fires", listOf("a"), searchCalls)
        assertEquals(
            "compat surface must not expose stale items",
            0,
            vm.searchResults.value.size,
        )
        val retained = vm.searchState.value
        assertEquals(
            "old a-results must be retained while waiting for debounce (attributed to old resultKey)",
            listOf(1L),
            retained.displayedResult?.items?.map { it.videoItemId },
        )
        val waiting = vm.searchState.value
        assertEquals(SearchPhase.WAITING, waiting.phase)
        assertEquals("b", waiting.inputKey.query)
        assertEquals("retained result must stay attributed to the old resultKey", "a", waiting.displayedResult?.resultKey?.query)
        assertTrue("stale must be true while the retained result is attributed to the old key", waiting.stale)

        // Debounce fires: whole pipeline switches to "b"; b's empty baseline arrives.
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a", "b"), searchCalls)
        assertEquals(listOf<Long>(), vm.searchResults.value.map { it.videoItemId })

        // b's real result: only b's item, alpha never resurfaces.
        searchFlows.getValue("b").value = listOf(videoItem(2L, "banana.mp4"))
        crossThrottleWindow()
        assertEquals(listOf(2L), vm.searchResults.value.map { it.videoItemId })
    }

    // --- F03 Scenario 2: old query's slow/in-flight emission must not pollute new state ---

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
        // dropped entirely — no alpha pollution, no cross-paired state, and the late
        // old-generation completion must NOT overwrite the new b-attribution.
        gateA.complete(Unit)
        advanceUntilIdle()
        assertTrue(
            "late a-emission must not reach the b-pipeline",
            vm.searchResults.value.none { it.videoItemId == 1L },
        )
        assertEquals(0, vm.searchResults.value.size)
        val ownership = vm.searchState.value
        assertEquals("ownership must stay with the newer query", "b", ownership.displayedResult?.resultKey?.query)
        assertEquals(SearchPhase.READY, ownership.phase)
        assertFalse(ownership.stale)
        assertNull("cancelling the old query is not an Error event", vm.error.value)

        // b's real result arrives intact.
        searchFlows.getValue("b").value = listOf(videoItem(2L, "banana.mp4"))
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.searchResults.value.map { it.videoItemId })
    }

    // --- F03 Scenario 3: blank query short-circuits without touching the repo ---

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

    // --- F03 Scenario 4: scope switch re-filters without re-querying the repo ---

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

    // --- P1: input change immediately flips phase + keeps old result attribution ---

    @Test
    fun inputChange_entersWaitingImmediately_oldResultStaysAttributedUntilNewReadySwitchesOwnership() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        crossThrottleWindow()

        // READY: result published and attributed to ("a", ALL).
        val ready = vm.searchState.value
        assertEquals(SearchPhase.READY, ready.phase)
        assertEquals("a", ready.displayedResult?.resultKey?.query)
        assertEquals(SearchScope.ALL, ready.displayedResult?.resultKey?.scope)
        assertFalse(ready.stale)

        // Input change (before debounce): phase flips immediately to WAITING, the old
        // result is retained but still attributed to the old resultKey → stale.
        vm.setQuery("ab")
        runCurrent()
        val waiting = vm.searchState.value
        assertEquals(SearchPhase.WAITING, waiting.phase)
        assertEquals("ab", waiting.inputKey.query)
        assertEquals("old result must remain attributed to the old resultKey", "a", waiting.displayedResult?.resultKey?.query)
        assertTrue(waiting.stale)
        assertEquals("no repo query before the debounce fires", listOf("a"), searchCalls)

        // New READY arrives: ownership switches to the new resultKey, stale clears.
        advanceTimeBy(301)
        runCurrent()
        val switched = vm.searchState.value
        assertEquals(SearchPhase.READY, switched.phase)
        assertEquals("ab", switched.displayedResult?.resultKey?.query)
        assertFalse(switched.stale)
    }

    // --- P1: scope switch immediately expires the old result and remaps within the
    // throttle window (scope-driven remap is NOT gated by the Room throttle) ---

    @Test
    fun scopeSwitch_immediatelyExpiresOldResult_andRemapsWithoutThrottleWait() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        every { videoRepository.getParentPathsInFolder(any()) } returns
            MutableStateFlow(listOf("media/AlphaDir"))
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("alpha")
        advanceUntilIdle()
        searchFlows.getValue("alpha").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals(SearchPhase.READY, vm.searchState.value.phase)
        assertFalse(vm.searchState.value.stale)

        // Scope change with NO virtual-time advance: inputKey switched immediately,
        // old result expired (stale, attributed to ALL), no repo re-query.
        vm.setSearchScope(SearchScope.FILES_ONLY)
        val mid = vm.searchState.value
        assertEquals(SearchScope.FILES_ONLY, mid.inputKey.scope)
        assertEquals("old result must still be attributed to the old scope", SearchScope.ALL, mid.displayedResult?.resultKey?.scope)
        assertTrue(mid.stale)
        assertEquals("remap for the new scope is active (pipeline alive)", SearchPhase.LOADING, mid.phase)
        assertEquals("scope switch must not re-query the repo", listOf("alpha"), searchCalls)
        assertEquals(
            "P1b: compat surface must not expose stale items",
            0,
            vm.searchResults.value.size,
        )

        // Remap completes well inside the 300ms throttle window — no throttle wait.
        advanceTimeBy(10)
        runCurrent()
        val remapped = vm.searchState.value
        assertEquals(SearchPhase.READY, remapped.phase)
        assertEquals(SearchScope.FILES_ONLY, remapped.displayedResult?.resultKey?.scope)
        assertFalse(remapped.stale)
        assertEquals(1, remapped.displayedResult?.items?.size)
        assertEquals("remap must not re-query the repo", listOf("alpha"), searchCalls)
    }

    // --- P1: clearing the input flips EMPTY_QUERY immediately, repo untouched ---

    @Test
    fun clearInput_entersEmptyQueryImmediately_noRepoCall() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        crossThrottleWindow()
        assertEquals(SearchPhase.READY, vm.searchState.value.phase)

        vm.setQuery("")
        val cleared = vm.searchState.value
        assertEquals("clearing the input must flip EMPTY_QUERY immediately", SearchPhase.EMPTY_QUERY, cleared.phase)
        assertEquals("no additional repo call", listOf("a"), searchCalls)

        advanceUntilIdle()
        val settled = vm.searchState.value
        assertEquals(SearchPhase.EMPTY_QUERY, settled.phase)
        assertNull("cleared input must not retain a displayed result", settled.displayedResult)
        assertEquals(0, vm.searchResults.value.size)
        assertEquals("blank query must not trigger a repo search", listOf("a"), searchCalls)
    }

    // --- P1: stale results disable queue-id construction at the VM layer ---

    @Test
    fun staleResults_disableQueueIdConstruction_untilNewReadySwitchesOwnership() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"), videoItem(2L, "beta.jpg"))
        crossThrottleWindow()
        assertEquals(listOf(1L), vm.getSearchResultVideoIds())
        assertEquals(listOf(2L), vm.getSearchResultImageIds())

        vm.setQuery("ab")
        runCurrent()
        assertTrue(vm.searchState.value.stale)
        assertEquals("stale results must not feed playlist construction", 0, vm.getSearchResultVideoIds().size)
        assertEquals(0, vm.getSearchResultImageIds().size)

        advanceTimeBy(301)
        runCurrent()
        assertFalse(vm.searchState.value.stale)
    }

    // --- P1b: throttle window state machine — boundary-exact, latest-only trailing,
    // single upstream subscription ---

    @Test
    fun throttleRoomFlow_inWindowValuesOnlyTrailingPublished_boundaryExact_andSubscribedOnce() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        var subscriptions = 0
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            flow {
                subscriptions++
                emit(listOf(videoItem(1L, "v0.mp4")))
                delay(50)
                emit(listOf(videoItem(2L, "v1.mp4")))
                delay(50)
                emit(listOf(videoItem(3L, "v2.mp4")))
            }
        }
        val vm = createVm()
        activateCollector(vm)

        // t=300: debounce fires; v0 is the leading value → published immediately.
        vm.setQuery("a")
        advanceTimeBy(300)
        runCurrent()
        runCurrent()
        assertEquals(listOf("a"), searchCalls)
        assertEquals("upstream must be subscribed exactly once", 1, subscriptions)
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })
        assertEquals(1L, vm.searchState.value.displayedResult?.generation)

        // t=350: v1 lands inside the open window → held, NOT published.
        advanceTimeBy(50)
        runCurrent()
        assertEquals(
            "in-window value must not publish",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )

        // t=400: v2 lands inside the window → held (latest replaces v1).
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        // t=599: fixed window boundary not yet reached — still no trailing publish.
        advanceTimeBy(199)
        runCurrent()
        assertEquals(
            "window must stay open until exactly 300ms after the leading publish",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )

        // t=600: fixed window closes → trailing latest (v2) published; v1 never observable.
        advanceTimeBy(1)
        runCurrent()
        runCurrent()
        assertEquals(
            "trailing latest must publish at the window boundary",
            listOf(3L),
            vm.searchResults.value.map { it.videoItemId },
        )
        assertEquals("exactly two publishes (leading + trailing)", 2L, vm.searchState.value.displayedResult?.generation)
        assertEquals("still exactly one upstream subscription", 1, subscriptions)
    }

    @Test
    fun throttleRoomFlow_continuousWrites_doNotStarve_finalLatestVisible() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        var subscriptions = 0
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            flow {
                subscriptions++
                // v0..v8 every 90ms — continuous Room writes spanning 720ms.
                repeat(9) { n ->
                    emit(listOf(videoItem(n + 1L, "v$n.mp4")))
                    delay(90)
                }
            }
        }
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        // v0@300 (leading, published) | window [300,600): v1@390 v2@480 v3@570 held.
        advanceTimeBy(585)
        runCurrent()
        assertEquals(
            "anti-starvation: only the leading value published mid-stream",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )
        assertEquals(1L, vm.searchState.value.displayedResult?.generation)

        advanceUntilIdle()
        runCurrent()
        assertEquals(
            "continuous writes must converge to the latest value",
            listOf(9L),
            vm.searchResults.value.map { it.videoItemId },
        )
        assertEquals(
            "publishes must be bounded (leading/trailing per window, not per write)",
            5L,
            vm.searchState.value.displayedResult?.generation,
        )
        assertEquals("single upstream subscription across the whole stream", 1, subscriptions)
    }

    @Test
    fun throttleRoomFlow_upstreamSubscribedOnce_acrossEmissionsAndScopeSwitch_reSubscribesOnlyOnQueryChange() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var subscriptions = 0
        every { syncRelationRepository.getAllWithSourceFlow() } returns MutableStateFlow(emptyList())
        every { videoRepository.getParentPathsInFolder(any()) } returns
            MutableStateFlow(listOf("media/AlphaDir"))
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            flow {
                subscriptions++
                emitAll(searchFlows.getOrPut(q) { MutableStateFlow(emptyList()) })
            }
        }
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("alpha")
        advanceUntilIdle()
        searchFlows.getValue("alpha").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals("exactly one upstream subscription for the query's pipeline", 1, subscriptions)

        // Result emissions must not re-subscribe the upstream.
        searchFlows.getValue("alpha").value =
            listOf(videoItem(1L, "alpha.mp4"), videoItem(2L, "beta.mp4"))
        advanceUntilIdle()
        assertEquals(1, subscriptions)

        // Scope switch must not re-subscribe the upstream.
        vm.setSearchScope(SearchScope.FILES_ONLY)
        advanceUntilIdle()
        assertEquals("scope switch must keep the single subscription", 1, subscriptions)
        assertEquals("scope switch must not re-query the repo", listOf("alpha"), searchCalls)

        // A new (debounced) query builds a new pipeline → exactly one more subscription.
        vm.setQuery("beta")
        advanceUntilIdle()
        assertEquals(2, subscriptions)
        assertEquals(listOf("alpha", "beta"), searchCalls)
    }

    // --- P1b: old-generation late completion gated on the REAL Default mapping ---

    @Test
    fun oldGeneration_queuedOnDefaultDispatcher_lateCompletion_doesNotOverwriteNewAttribution() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val manual = ManualDispatchQueue()
        val vm = createVm(defaultDispatcher = manual)
        activateCollector(vm)

        vm.setQuery("a")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("a"), searchCalls)
        assertNull(
            "mapping must be gated on Default: baseline [] is queued but not yet published",
            vm.searchState.value.displayedResult,
        )
        assertEquals(SearchPhase.LOADING, vm.searchState.value.phase)

        // The a-mapping block is now genuinely in flight (queued on the Default queue).
        // Switch input: pipeline-a cancelled; pipeline-b captures "ab" and queues its own block.
        vm.setQuery("ab")
        runCurrent()
        assertEquals(SearchPhase.WAITING, vm.searchState.value.phase)
        advanceTimeBy(301)
        runCurrent()

        // Drain the Default queue FIFO: the OLD a-mapping block runs first — its
        // generation guard must reject the stale publish; only b's block publishes.
        // Note: flatMapLatest may start pipeline-b only after pipeline-a's
        // cancellation fully completes — and that completion can wait for the
        // queued manual block — so drain twice and assert the re-query afterwards.
        manual.runQueued()
        runCurrent()
        manual.runQueued()
        runCurrent()
        assertEquals(listOf("a", "ab"), searchCalls)

        val state = vm.searchState.value
        assertEquals("only the new generation may own the state", "ab", state.displayedResult?.resultKey?.query)
        assertEquals(
            "exactly one publish — the stale queued a-mapping must not publish",
            1L,
            state.displayedResult?.generation,
        )
        assertEquals(SearchPhase.READY, state.phase)
        assertFalse(state.stale)
    }

    // --- P1b: stale entry guards block delete/toggle/folder-delete side effects ---

    @Test
    fun staleGuards_blockDeleteToggleFolderDelete_noEvents_orSideEffects_untilFresh() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        every { videoRepository.getParentPathsInFolder(any()) } returns
            MutableStateFlow(listOf("media/AlphaDir"))
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateDeleteEventCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value =
            listOf(videoItem(1L, "alpha.mp4"), videoItem(2L, "beta.mp4"))
        advanceUntilIdle()
        assertFalse(vm.searchState.value.stale)

        // While stale: all delete-family entries must no-op (no side effects, no events).
        vm.setQuery("ab")
        runCurrent()
        assertTrue(vm.searchState.value.stale)
        vm.deleteFile(1L)
        vm.toggleTrashed(1L)
        vm.deleteFolder("media/AlphaDir")
        advanceUntilIdle()
        verify(exactly = 0) {
            undoDeleteUseCase.performHardDelete(any(), any(), any<(String) -> Unit>(), any())
        }
        verify(exactly = 0) {
            undoDeleteUseCase.performTrashToggle(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any())
        }
        verify(exactly = 0) { fileOps.deleteFolder(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>()) }
        assertEquals("stale entry points must not emit completion events", 0, deleteEventsSeen.size)

        // Fresh again (ab READY): the guard lets the side effect through exactly once.
        searchFlows.getValue("ab").value = listOf(videoItem(3L, "cherry.mp4"))
        advanceUntilIdle()
        assertFalse(vm.searchState.value.stale)
        vm.deleteFile(1L)
        verify(exactly = 1) {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        }
    }

    // --- P1b: hard-delete lifecycle — events, pending filter, undo restore ---

    @Test
    fun deleteFile_emitsSuccessEvent_filtersPendingId_untilUndoRestores() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateDeleteEventCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value =
            listOf(videoItem(1L, "alpha.mp4"), videoItem(2L, "beta.jpg"))
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.getSearchResultVideoIds())

        every {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(3).invoke() }
        vm.deleteFile(1L)
        advanceUntilIdle()

        val event = deleteEventsSeen.single()
        assertEquals("event payload must carry the operationId (undo token)", 1L, event.operationId)
        assertEquals(SearchDeleteOperation.HARD_DELETE, event.operation)
        assertTrue(event.success)
        assertEquals(
            "pending hard delete must be filtered from the results",
            listOf(2L),
            vm.searchResults.value.map { it.videoItemId },
        )
        assertFalse("pending-id filtering is not staleness: same resultKey owns the state", vm.searchState.value.stale)

        // Undo restores the item and clears the pending filter.
        coEvery { undoDeleteUseCase.undo(1L) } returns UndoResult.Success
        val undone = vm.undoDelete(1L)
        assertTrue(undone == UndoResult.Success)
        advanceUntilIdle()
        assertEquals(
            "undo must clear the pending filter and restore the item",
            listOf(1L, 2L),
            vm.searchResults.value.map { it.videoItemId },
        )
    }

    @Test
    fun deleteFile_onError_emitsFailureEvent_keepsItemVisible() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateDeleteEventCollector(vm)
        activateErrorCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()

        every {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        } answers { arg<(String) -> Unit>(2).invoke("boom") }
        vm.deleteFile(1L)
        advanceUntilIdle()

        val event = deleteEventsSeen.single()
        assertEquals(1L, event.operationId)
        assertEquals(SearchDeleteOperation.HARD_DELETE, event.operation)
        assertFalse(event.success)
        assertEquals(
            "failed recording must not filter the item",
            listOf(1L),
            vm.getSearchResultVideoIds(),
        )
        assertEquals("failure path must raise exactly one error event", 1, errorEventsSeen.size)
    }

    @Test
    fun toggleTrashed_emitsCompletionEvent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateDeleteEventCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()

        every {
            undoDeleteUseCase.performTrashToggle(eq(7L), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(4).invoke() }
        vm.toggleTrashed(7L)
        advanceUntilIdle()

        val event = deleteEventsSeen.single()
        assertEquals(7L, event.operationId)
        assertEquals(SearchDeleteOperation.TRASH_TOGGLE, event.operation)
        assertTrue(event.success)
    }

    // --- P1b: onCleared fallback commit is verified ---

    @Test
    fun onCleared_commitsPendingDeleteAsLastResort() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        val vm = createVm()

        putInStoreAndClear(vm)

        // onCleared must fall back to commitPendingSafely.
        verify { undoDeleteUseCase.commitPendingSafely(null) }
    }

    // --- P1b: folder-flow failure ownership + retrySearch recovery ---

    @Test
    fun folderFlowFailure_flowsIntoFailedPhase_notUnhandled_andRetryRecovers() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        every { syncRelationRepository.getAllWithSourceFlow() } returns MutableStateFlow(emptyList())
        var folderSubs = 0
        every { videoRepository.getParentPathsInFolder(any()) } answers {
            flow {
                val n = ++folderSubs
                if (n == 1) {
                    throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.BROWSE)
                }
                emit(listOf("media/AlphaDir"))
            }
        }
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateErrorCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()

        assertEquals("first folder-flow failure must land in FAILED", SearchPhase.FAILED, vm.searchState.value.phase)
        assertNull(vm.searchState.value.displayedResult)
        assertEquals("failure must raise exactly one error event", 1, errorEventsSeen.size)

        // P1b 🟠5: retrySearch refires the pipeline for the same inputKey.
        vm.retrySearch()
        advanceUntilIdle()
        assertEquals("retry must re-issue the same query", listOf("a", "a"), searchCalls)
        assertEquals("retry must recover to READY", SearchPhase.READY, vm.searchState.value.phase)
        assertFalse(vm.searchState.value.stale)
        assertEquals("a", vm.searchState.value.displayedResult?.resultKey?.query)
        assertEquals("recovered failure must not add error events", 1, errorEventsSeen.size)
    }

    // --- P1: pipeline failure → FAILED phase + structured error event ---

    @Test
    fun pipelineFailure_marksFailedPhase_raisesSingleErrorEvent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            flow<List<VideoItemWithPlayback>> {
                throw SyncException(
                    SyncErrorCode.STORAGE_ERROR,
                    SyncOperation.DELETE,
                    cause = RuntimeException("db down"),
                )
            }
        }
        val vm = createVm()
        activateCollector(vm)
        activateErrorCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()

        val failed = vm.searchState.value
        assertEquals(SearchPhase.FAILED, failed.phase)
        assertNotNull("pipeline failure must raise an error event", failed.error)
        assertNotNull(vm.error.value)
        assertNull(failed.displayedResult)
        assertEquals(listOf("a"), searchCalls)
        assertEquals("failure must raise exactly one error event", 1, errorEventsSeen.size)
    }

    // --- P1: VM-level delete routes through UndoDeleteUseCase (tokenized undo) ---

    @Test
    fun deleteFile_routesThroughUndoDeleteUseCase_neverDirectFileOpsDelete() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(42L, "alpha.mp4"))
        advanceUntilIdle()

        vm.deleteFile(42L)

        verify { undoDeleteUseCase.performHardDelete(eq(42L), any(), any<(String) -> Unit>(), any()) }
        verify(exactly = 0) { fileOps.deleteFile(any(), any<CoroutineScope>(), any<(String) -> Unit>(), any()) }
    }

    @Test
    fun toggleTrashed_routesThroughUndoDeleteUseCase() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(7L, "alpha.mp4"))
        advanceUntilIdle()

        vm.toggleTrashed(7L)

        verify { undoDeleteUseCase.performTrashToggle(eq(7L), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any()) }
        verify(exactly = 0) { fileOps.toggleTrashed(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any()) }
    }

    @Test
    fun commitDeferredDelete_failureIsMappedToErrorEvent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        val failure = SyncException(
            SyncErrorCode.STORAGE_ERROR,
            SyncOperation.DELETE,
            cause = RuntimeException("disk full"),
        )
        coEvery { undoDeleteUseCase.commitDeferredDelete(null) } returns UndoResult.Failed(failure)
        val vm = createVm()

        val result = vm.commitDeferredDelete(null)

        assertTrue(result is UndoResult.Failed)
        assertNotNull("commit failure must surface as an error event", vm.error.value)
    }

    // ------------------------------------------------------------------
    // P1c rev-1：同步交互有效性守卫（🔴1）——守卫读输入源流 + publishedResult
    // 的同步身份，不读 combine+stateIn 派生 searchState（发布窗口）。
    // ------------------------------------------------------------------

    @Test
    fun syncGuard_inputChangeWithoutSchedulerRun_blocksDeleteToggleFolderAndQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)
        activateDeleteEventCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals(SearchPhase.READY, vm.searchState.value.phase)

        // 输入已变但调度器未运行：派生 searchState 仍是旧代次 fresh——这正是
        // 旧守卫的失效窗口；同步身份守卫必须在此刻立即拒绝。
        vm.setQuery("b")
        assertEquals(
            "regression premise: derived state must still look fresh before any scheduler run",
            false,
            vm.searchState.value.stale,
        )
        assertEquals("a", vm.searchState.value.displayedResult?.resultKey?.query)

        vm.deleteFile(1L)
        vm.toggleTrashed(1L)
        vm.deleteFolder("media/AlphaDir")
        assertEquals(emptyList<Long>(), vm.getSearchResultVideoIds())
        assertEquals(emptyList<Long>(), vm.getSearchResultImageIds())
        assertEquals(0, deleteEventsSeen.size)
        verify(exactly = 0) {
            undoDeleteUseCase.performHardDelete(any(), any(), any<(String) -> Unit>(), any())
        }
        verify(exactly = 0) {
            undoDeleteUseCase.performTrashToggle(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any())
        }
        verify(exactly = 0) { fileOps.deleteFolder(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>()) }

        // 新代次 READY 后同一操作放行恰一次。先跑调度器让 "b" 管道启动
        // （防抖触发 + stub 登记），再注入新列表。
        advanceUntilIdle()
        searchFlows.getValue("b").value = listOf(videoItem(3L, "cherry.mp4"))
        advanceUntilIdle()
        vm.deleteFile(3L)
        verify(exactly = 1) {
            undoDeleteUseCase.performHardDelete(eq(3L), any(), any<(String) -> Unit>(), any())
        }
    }

    @Test
    fun syncGuard_worksWithoutAnySearchStateSubscription() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        // 无任何 searchState/searchResults 订阅：派生态停留初始值（EMPTY_QUERY，
        // displayedResult=null，stale=false）——守卫不得依赖它。
        val vm = createVm()

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()

        // fresh：同步身份（a,ALL,folder）== 已发布身份 → 放行。
        assertEquals(listOf(1L), vm.getSearchResultVideoIds())
        vm.deleteFile(1L)
        verify(exactly = 1) {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        }

        // 输入变化（不 runCurrent）：立即过期 → 拒绝，队列构造同步失效。
        vm.setQuery("ab")
        assertEquals(emptyList<Long>(), vm.getSearchResultVideoIds())
        vm.deleteFile(2L)
        verify(
            // stale identity must be rejected even though derived state was never subscribed
            exactly = 1,
        ) {
            undoDeleteUseCase.performHardDelete(any(), any(), any<(String) -> Unit>(), any())
        }
    }

    @Test
    fun updateStarAndMarkFolderRead_gatedBySyncIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"))
        advanceUntilIdle()
        assertEquals(SearchPhase.READY, vm.searchState.value.phase)

        vm.setQuery("b")
        vm.updateStar(1L, 1)
        vm.markFolderRead("media/AlphaDir", true)
        verify(exactly = 0) {
            fileOps.updateStar(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any<() -> Unit>())
        }
        verify(exactly = 0) {
            fileOps.markFolderRead(any(), any(), any<CoroutineScope>(), any<(String) -> Unit>(), any<() -> Unit>())
        }

        advanceUntilIdle()
        vm.updateStar(1L, 1)
        vm.markFolderRead("media/AlphaDir", true)
        verify(exactly = 1) {
            fileOps.updateStar(eq(1L), eq(1), any<CoroutineScope>(), any<(String) -> Unit>(), any<() -> Unit>())
        }
        verify(exactly = 1) {
            fileOps.markFolderRead(eq("media/AlphaDir"), eq(true), any<CoroutineScope>(), any<(String) -> Unit>(), any<() -> Unit>())
        }
    }

    // ------------------------------------------------------------------
    // P1c rev-1（🟠3）：throttleLatest 有界化——下游（映射）被门控期间，
    // 上游连续快照合并为最新一份；处理次数有界（= 引导 + 最新），不逐值处理。
    // ------------------------------------------------------------------

    @Test
    fun throttleRoomFlow_conflatesWhileMappingBusy_boundedProcessing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubCommon()
        var repoSubscriptions = 0
        every { videoRepository.searchByFileNameInFolder(any(), any()) } answers {
            val q: String = firstArg()
            searchCalls.add(q)
            flow {
                repoSubscriptions++
                emitAll(searchFlows.getOrPut(q) { MutableStateFlow(emptyList()) })
            }
        }
        val manual = ManualDispatchQueue()
        val vm = createVm(defaultDispatcher = manual)
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        // 映射被 Manual 门控期间上游连发 10 个不同列表快照。
        repeat(10) { i ->
            searchFlows.getValue("a").value = listOf(videoItem(i + 1L, "v$i.mp4"))
        }
        manual.runQueued()
        runCurrent()
        // 首次发布 = 引导值（FIFO 头 v1）——引导语义，而非逐值扇出。
        assertEquals(1L, vm.searchState.value.displayedResult?.generation)

        // 固定窗口关闭：v2..v9 被 latest-only 窗口丢弃，仅补发最新 v10。
        advanceTimeBy(300)
        runCurrent()
        manual.runQueued()
        runCurrent()
        assertEquals(
            "exactly two mappings total: leading (v1) + trailing (latest v10), never per-value fan-out",
            2L,
            vm.searchState.value.displayedResult?.generation,
        )
        assertEquals(
            "final value must be the latest snapshot (v10)",
            listOf(10L),
            vm.searchResults.value.map { it.videoItemId },
        )
        assertEquals("single upstream subscription", 1, repoSubscriptions)
    }

    /**
     * 🟠3 有界槽的直接证明：下游在节流算子内部真实挂起（引导 emit 的背压点）
     * 期间，上游连发 100 个值——恢复后下游总共只被调用 2 次（引导 + 合并后的
     * 最新值），中间值零次触达下游（丢弃行为），非仅最终值正确。
     */
    @Test
    fun throttleLatest_mergesBacklogWhileCollectorSuspended_processingCountBounded() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var subscriptions = 0
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        val upstream = flow {
            subscriptions++
            repeat(100) { i ->
                emit(i)
                delay(1)
            }
        }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            upstream.throttleLatest(300).collect { v ->
                // 冻结点在首值 emit 内（握手 ack 在 emit 返回后才 complete），
                // 即冻结的是首次交付握手期；gate 释放后上游 1..99 快速流入，
                // 证明的是握手放行后的 conflated 合并（首值+最新值）。
                if (v == 0) gate.await()
                received += v
            }
        }

        advanceTimeBy(150)
        assertEquals("single upstream subscription while collector is suspended", 1, subscriptions)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "leading + conflated latest only: intermediate values never reach downstream",
            listOf(0, 99),
            received,
        )
        job.cancel()
    }

    // ------------------------------------------------------------------
    // P1d rev-1：首值交付握手——CONFLATED send 永不挂起，同步冷流（无 delay）
    // 会在生产协程首个调度槽内 send(1,2,3)+close，无握手时通道只留 3，
    // 外层首次 receiveCatching 拿到的不是引导值 1。握手后：首次交付恰为 1，
    // 后续值合并为最新 3，中间值 2 永不交付。
    // ------------------------------------------------------------------

    @Test
    fun throttleLatest_synchronousColdFlow_leadingValueDeliveredFirst_subsequentConflatesToLatest() = runTest {
        val received = mutableListOf<Int>()
        // 首值后冻结下游：窗口机停在 emit(leading) 内，时间推进不得产生新交付——
        // 使「首次交付恰为 1」与「后续合并为 3」两段断言各自确定。
        val collectorGate = CompletableDeferred<Unit>()
        // 用 runTest 直接子 launch（非 backgroundScope）：最小复现证明
        // backgroundScope 内 withTimeoutOrNull 的窗口定时器在虚拟时间下不触发
        // （advanceUntilIdle 后窗口永不收口）；regular launch 无此问题（同
        // mergesBacklog 用例）。收集器在 trailing 交付后随上游完结自然结束。
        // UNDISPATCHED + UnconfinedTestDispatcher：collect 内联执行——produce 子协程
        // 在 machine 挂入 receiveCatching 等待者之前就内联先跑（P1e 评审处方）。
        // 生产方同步发射 1,2,3：无握手时 CONFLATED 只剩 3 → 首交付 [3]，第一条
        // [1] 断言必败（锁死回归）；有握手时 await 暂停生产方 → 首交付恰为 [1]。
        // （默认 StandardTestDispatcher 构造无法区分：machine 先挂 receiveCatching，
        // send(1) 直接交付等待者，删握手首交付仍是 [1]。）
        // 不用 delay/yield 给生产方让权——那会绕开待测窗口。
        val job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            flowOf(1, 2, 3)
                .throttleLatest(periodMs = 300)
                .collect { v ->
                    received += v
                    if (received.size == 1) collectorGate.await()
                }
        }

        // 生产方已内联完成（或停在握手 await 上），无需 runCurrent 让权。
        assertEquals(listOf(1), received)

        advanceTimeBy(300)
        // 下游仍冻结：无 trailing、无新值——引导值交付不随时间推进而变化。
        assertEquals(listOf(1), received)

        collectorGate.complete(Unit)
        runCurrent()
        // 覆盖窗口 300ms 截止：trailing(3) 必达；2 被合并永不交付。
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "leading 1 delivered first, subsequent values conflate to latest 3; 2 never delivered",
            listOf(1, 3),
            received,
        )
        job.cancel()
    }

    // ------------------------------------------------------------------
    // P1c rev-1（🟠4）：pending 影子按 use case 权威状态回同步——
    // 旧 undo/commit 迟到完成不得误清并发登记的新 pending。
    // ------------------------------------------------------------------

    @Test
    fun pendingShadow_lateOldUndoCompletion_keepsNewPendingFiltered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        var authoritativePending: Long? = null
        every { undoDeleteUseCase.getPendingHardDeleteId() } answers { authoritativePending }
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"), videoItem(7L, "seven.mp4"))
        advanceUntilIdle()

        every {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(3).invoke() }
        vm.deleteFile(1L)
        authoritativePending = 1L
        advanceUntilIdle()
        assertEquals(listOf(7L), vm.searchResults.value.map { it.videoItemId })

        // 旧 undo(1) 挂起在门上；期间新删除(7) 登记影子。
        val undoGate = CompletableDeferred<Unit>()
        coEvery { undoDeleteUseCase.undo(1L) } coAnswers { undoGate.await(); UndoResult.Success }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.undoDelete(1L) }
        every {
            undoDeleteUseCase.performHardDelete(eq(7L), any(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(3).invoke() }
        vm.deleteFile(7L)
        authoritativePending = 7L
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        // 旧 undo 迟到完成：不得把新影子(7) 误清成 null。
        undoGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "late undo(1) completion must not resurface the newer pending(7)",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )
    }

    @Test
    fun pendingShadow_lateOldCommitCompletion_keepsNewPendingFiltered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubCommon()
        stubSearchByQueryMap()
        var authoritativePending: Long? = null
        every { undoDeleteUseCase.getPendingHardDeleteId() } answers { authoritativePending }
        val vm = createVm()
        activateCollector(vm)

        vm.setQuery("a")
        advanceUntilIdle()
        searchFlows.getValue("a").value = listOf(videoItem(1L, "alpha.mp4"), videoItem(7L, "seven.mp4"))
        advanceUntilIdle()

        every {
            undoDeleteUseCase.performHardDelete(eq(1L), any(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(3).invoke() }
        vm.deleteFile(1L)
        authoritativePending = 1L
        advanceUntilIdle()
        assertEquals(listOf(7L), vm.searchResults.value.map { it.videoItemId })

        val commitGate = CompletableDeferred<Unit>()
        coEvery { undoDeleteUseCase.commitDeferredDelete(1L) } coAnswers { commitGate.await(); UndoResult.Success }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.commitDeferredDelete(1L) }
        every {
            undoDeleteUseCase.performHardDelete(eq(7L), any(), any<(String) -> Unit>(), any())
        } answers { arg<() -> Unit>(3).invoke() }
        vm.deleteFile(7L)
        authoritativePending = 7L
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.searchResults.value.map { it.videoItemId })

        commitGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "late commit(1) completion must not resurface the newer pending(7)",
            listOf(1L),
            vm.searchResults.value.map { it.videoItemId },
        )
    }
}
