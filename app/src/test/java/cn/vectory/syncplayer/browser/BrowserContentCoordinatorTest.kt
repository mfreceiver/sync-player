package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.browser.BrowserViewModel.BrowseFilter
import cn.vectory.syncplayer.browser.BrowserViewModel.BrowseMode
import cn.vectory.syncplayer.browser.BrowserViewModel.SortMode
import cn.vectory.syncplayer.browser.BrowserViewModel.ViewMode
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Unit tests for [BrowserContentCoordinator], the content-state owner extracted from
 * [BrowserViewModel] (remediation plan Task 8, TDD: this file was written first and
 * failed with `Unresolved reference: BrowserContentCoordinator` before the class existed).
 *
 * Coverage per plan T8-C1:
 * - navigateUp semantics (root -> false, subfolder -> parent + true)
 * - FILE-mode setters (setBrowseMode/setSortMode) flip the StateFlow, persist via
 *   UserPreferencesRepository, and after the 300ms debounce the new sort reaches
 *   VideoRepository.getFlatItemsFiltered (hydrated baselines are the opposite values)
 * - currentRelationId prefix mapping (folder under a relation's localBasePath -> relation id)
 * - refresh() re-queries the current folder
 * - init hydration restores the five persisted preferences
 *
 * The coordinator's public flows are all `stateIn(scope, Lazily, ...)`, so the flow-chain
 * tests attach eager collectors via [collectEagerly] (backgroundScope auto-cancels at test end,
 * UnconfinedTestDispatcher starts collection immediately, virtual time still applies to debounce).
 *
 * Per project testing convention: assertions are on enums / invocation counts, never user text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserContentCoordinatorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)

    private fun stubQueries() {
        every { syncRelationRepository.getAllWithSourceFlow() } returns
            MutableStateFlow(listOf(relationWithSource()))
        every { playbackRepository.getByFolderFlow(any()) } returns MutableStateFlow(emptyList())
        every { videoRepository.getSubFolders(any()) } returns flowOf(emptyList())
        every { videoRepository.getItemsInFolder(any()) } returns flowOf(emptyList())
        coEvery { videoRepository.getChildVideoStats(any()) } returns emptyList()
        coEvery { videoRepository.getDescendantVideoStats(any()) } returns emptyList()
        coEvery { videoRepository.getDescendantFilterStats(any()) } returns emptyList()
        coEvery { videoRepository.getDirectChildFolderHasSubFolders(any(), any()) } returns emptyMap()
        coEvery { videoRepository.getFlatItemsFiltered(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { videoRepository.getTotalItemCount() } returns 0
        coEvery { userPrefs.getDefaultSortMode() } returns "DURATION_DESC"
        coEvery { userPrefs.getDefaultFilter() } returns "MEDIA_ONLY"
        coEvery { userPrefs.getViewMode() } returns "GRID"
        coEvery { userPrefs.getGridColumns() } returns 4
        coEvery { userPrefs.getBrowseMode() } returns "FILE"
    }

    // SyncRelationWithSource actual fields: (relation, sourceName, sourceProtocol).
    private fun relationWithSource() = SyncRelationWithSource(
        relation = SyncRelation(
            id = 7L,
            name = "r",
            syncSourceId = 1L,
            remotePath = "/remote",
            localBasePath = "videos"
        ),
        sourceName = "s",
        sourceProtocol = SyncProtocol.SMB
    )

    private fun TestScope.sut(): BrowserContentCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // The SUT scope is derived from backgroundScope (keeping its Job, swapping in the
        // unconfined test dispatcher): runTest cancels backgroundScope after the test body,
        // so the coordinator's Lazily-shared stateIn jobs are deterministically cancelled
        // and never outlive the test as orphaned sharing coroutines on the scheduler.
        val sutScope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        return BrowserContentCoordinator(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            userPrefs = userPrefs,
            scope = sutScope,
            ioDispatcher = dispatcher // keep DB gathers in virtual time
        )
    }

    /** Warm the Lazily-shared chains the assertions below depend on. */
    private fun TestScope.collectEagerly(sut: BrowserContentCoordinator) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { sut.browseItems.collect {} }
        backgroundScope.launch(dispatcher) { sut.currentRelationId.collect {} }
    }

    @Test
    fun navigateUp_fromRootReturnsFalse_fromSubfolderReturnsParent() = runTest {
        stubQueries()
        val sut = sut()
        assertFalse(sut.navigateUp())
        sut.setFolder("videos/a")
        assertTrue(sut.navigateUp())
        assertEquals("videos", sut.currentFolder.value)
    }

    @Test
    fun fileModeSetters_changeStateQueryParamsAndPersistedPreference() = runTest {
        stubQueries()
        // Hydrate to the OPPOSITE of the values under test so neither setter can no-op
        // against an already-equal hydrated state (false green).
        coEvery { userPrefs.getDefaultSortMode() } returns "FILENAME_ASC"
        coEvery { userPrefs.getBrowseMode() } returns "DIRECTORY"
        val sut = sut()
        collectEagerly(sut)
        // Baseline: hydration landed on the opposite values.
        assertEquals(SortMode.FILENAME_ASC, sut.sortMode.value)
        assertEquals(BrowseMode.DIRECTORY, sut.browseMode.value)

        // setBrowseMode: StateFlow flips, persistence is invoked.
        sut.setBrowseMode(BrowseMode.FILE)
        assertEquals(BrowseMode.FILE, sut.browseMode.value)
        coVerify(atLeast = 1) { userPrefs.setBrowseMode("FILE") }

        // setSortMode: StateFlow flips, and after the 300ms debounce the new sort reaches
        // the DAO query's sort column (FILE mode only queries getFlatItemsFiltered).
        sut.setSortMode(SortMode.DURATION_DESC)
        assertEquals(SortMode.DURATION_DESC, sut.sortMode.value)
        advanceTimeBy(301)
        coVerify(atLeast = 1) {
            videoRepository.getFlatItemsFiltered(any(), any(), any(), any(), "duration_desc")
        }
        coVerify(atLeast = 1) { userPrefs.setDefaultSortMode("DURATION_DESC") }
    }

    @Test
    fun currentRelationId_mapsFolderUnderBasePath() = runTest {
        stubQueries()
        val sut = sut()
        collectEagerly(sut)
        sut.setFolder("videos/sub")
        advanceTimeBy(301)
        assertEquals(7L, sut.currentRelationId.value)
    }

    @Test
    fun refresh_requeriesCurrentFolder() = runTest {
        stubQueries()
        // The refresh path under test is the DIRECTORY-mode rebuild (getSubFolders);
        // stubQueries' FILE default would route the build to getFlatItemsFiltered instead.
        coEvery { userPrefs.getBrowseMode() } returns "DIRECTORY"
        val sut = sut()
        collectEagerly(sut)
        sut.setFolder("videos")
        advanceTimeBy(301)
        verify(atLeast = 1) { videoRepository.getSubFolders("videos") }
        sut.refresh()
        advanceTimeBy(50)
        verify(atLeast = 2) { videoRepository.getSubFolders("videos") }
    }

    /**
     * U2c: DIRECTORY builds fetch the two descendant scans — the all-depth mosaic scan and the
     * candidate-row scan for the "(N)" counts — both scoped to the current folder. Since U2c
     * the count scan carries NO filter/star parameters (mediaType is unreliable; semantics
     * live in the builder), so only the folder scoping is asserted here.
     */
    @Test
    fun directoryBuild_fetchesDescendantScopesForCurrentFolder() = runTest {
        stubQueries()
        coEvery { userPrefs.getBrowseMode() } returns "DIRECTORY"
        val sut = sut()
        collectEagerly(sut)
        sut.setFolder("videos")
        advanceTimeBy(301)
        coVerify(atLeast = 1) { videoRepository.getDescendantVideoStats("videos") }
        coVerify(atLeast = 1) { videoRepository.getDescendantFilterStats("videos") }
    }

    /** U2-6: FILE mode is a flat list — the folder-oriented descendant scans must not run. */
    @Test
    fun fileBuild_doesNotFetchDirectoryDescendantStats() = runTest {
        stubQueries() // browseMode hydrated to FILE
        val sut = sut()
        collectEagerly(sut)
        advanceTimeBy(301)
        coVerify(exactly = 0) { videoRepository.getDescendantVideoStats(any()) }
        coVerify(exactly = 0) { videoRepository.getDescendantFilterStats(any()) }
    }

    /**
     * U2b: a rebuild must issue EACH descendant scan exactly ONCE per build, regardless of the
     * number of direct-child subfolders (single descendant scan, never per-folder N+1). The
     * counters stub over the warm-up phase; the delta across one refresh() is asserted.
     */
    @Test
    fun directoryBuild_issuesSingleDescendantScanPerRebuild() = runTest {
        stubQueries()
        coEvery { userPrefs.getBrowseMode() } returns "DIRECTORY"
        coEvery { videoRepository.getSubFolders("videos") } returns flowOf(listOf("a", "b", "c"))
        val mosaicCalls = AtomicInteger(0)
        val filterCalls = AtomicInteger(0)
        coEvery { videoRepository.getDescendantVideoStats(any()) } coAnswers {
            mosaicCalls.incrementAndGet()
            emptyList()
        }
        coEvery { videoRepository.getDescendantFilterStats(any()) } coAnswers {
            filterCalls.incrementAndGet()
            emptyList()
        }
        val sut = sut()
        collectEagerly(sut)
        sut.setFolder("videos")
        advanceTimeBy(301) // warm-up rebuild with multiple direct-child subfolders
        val mosaicBefore = mosaicCalls.get()
        val filterBefore = filterCalls.get()
        assertTrue(mosaicBefore >= 1)
        sut.refresh()
        advanceTimeBy(50)
        assertEquals(mosaicBefore + 1, mosaicCalls.get())
        assertEquals(filterBefore + 1, filterCalls.get())
    }

    @Test
    fun init_hydratesPersistedPreferences() = runTest {
        stubQueries()
        val sut = sut()
        advanceTimeBy(10)
        assertEquals(SortMode.DURATION_DESC, sut.sortMode.value)
        assertEquals(BrowseFilter.MEDIA_ONLY, sut.filter.value)
        assertEquals(ViewMode.GRID, sut.viewMode.value)
        assertEquals(4, sut.gridColumns.value)
        assertEquals(BrowseMode.FILE, sut.browseMode.value)
    }

    /**
     * F07 (调度一致性): loadSubFolders performs Room I/O (getSubFoldersOnce) and must
     * run it on the INJECTED ioDispatcher — not a hardwired Dispatchers.Default. The
     * recording dispatcher counts every dispatch and runs blocks inline, so the
     * assertion is deterministic and thread-identity independent. Count-based assertion
     * only, per project convention.
     */
    private class RecordingDispatcher : CoroutineDispatcher() {
        val dispatchCount = AtomicInteger(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            block.run()
        }
    }

    @Test
    fun loadSubFolders_runsQueriesOnInjectedIoDispatcher() = runTest {
        stubQueries()
        coEvery { videoRepository.getSubFoldersOnce("videos") } returns listOf("a", "b")
        val ioDispatcher = RecordingDispatcher()
        val sut = BrowserContentCoordinator(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            userPrefs = userPrefs,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            ioDispatcher = ioDispatcher
        )
        val nodes = sut.loadSubFolders("videos")
        assertTrue(
            "loadSubFolders I/O must dispatch through the injected ioDispatcher",
            ioDispatcher.dispatchCount.get() >= 1
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.fullPath == "videos/${it.name}" })
    }
}
