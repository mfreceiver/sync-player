package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.audio.AudioGainResult
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F08 — discover() re-entry guard tests (single-flight semantics).
 *
 * Semantics chosen: SINGLE-FLIGHT — a discover() call issued while a previous
 * discovery is still in flight is ignored. Rationale (also documented at the
 * implementation site): the discovery query domain is fixed for the screen's
 * lifetime (relationId from SavedStateHandle), so a repeat call carries no new
 * information; latest-wins would cancel the old job whose `finally` flips
 * `_isDiscovering=false` and whose reset sequence then interleaves with the new
 * job's reset sequence (state flicker + race window). Single-flight removes the
 * interleaving entirely and matches the existing `retryFailed()` guard style.
 *
 * Locked behaviors:
 *  1. an in-flight re-entry must NOT start a second scan (exactly ONE repository
 *     query) and the first scan's result must land intact (no old/new overwrite);
 *  2. after settle, a fresh discover() starts a new scan (the guard must not stick);
 *  3. discover() during an in-flight ANALYSIS must not cancel it — the analysis
 *     execution handle (analysisJob) is launchAnalysis's exclusive cancel handle and
 *     its behavior is unchanged (zero-diff regression lock).
 *
 * Conventions: asserts call counts / state flags only — never user text. Cleanup
 * follows common rule ② (five steps). The gated repository stub hops to
 * Dispatchers.IO first so the eager Unconfined Main never blocks the test thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GainAnalysisDiscoverReentryTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val audioGainAnalyzer = mockk<AudioGainAnalyzer>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private var vm: GainAnalysisViewModel? = null
    private var gateRef: CountDownLatch? = null

    private val gain = AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(MediaUris)
        every { MediaUris.mediaUri(any()) } returns mockk()
    }

    @After
    fun tearDown() {
        // Common rule ② — capture job references BEFORE cancelAnalysis (it nulls the seam).
        val analysis = vm?.analysisJob
        val discovery = vm?.discoverJob
        vm?.cancelAnalysis()                                   // step 1
        gateRef?.countDown()                                   // step 2 (finally semantics)
        runBlocking {                                          // step 3: bounded wait for jobs
            for (job in listOfNotNull(analysis, discovery)) {
                withTimeoutOrNull(5_000) { job.join() }
                    ?: fail("job did not finish in 5s")
            }
        }
        unmockkAll()                                           // step 4a: restore mockkObject(MediaUris)
        Dispatchers.resetMain()                                // step 4b
        // step 5: no in-memory DB in this class — skipped
    }

    private fun seedEntity(id: Long = 1L) = VideoItemEntity(
        id = id,
        syncRelationId = 1L,
        relativePath = "ep1.mp4",
        parentPath = "",
        fileName = "ep1.mp4",
        volumeId = "vol",
        fileSize = 1L,
        audioGainDb = null
    )

    private fun buildVm(): GainAnalysisViewModel = GainAnalysisViewModel(
        videoRepository = videoRepository,
        audioGainAnalyzer = audioGainAnalyzer,
        context = context,
        savedStateHandle = SavedStateHandle(mapOf("relationId" to 1L))
    ).also { vm = it }

    /** Gated discovery stub: reports entry, then blocks (bounded) until the main side releases. */
    private fun gateDiscoveryOnce() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        gateRef = gate
        coEvery { videoRepository.getBySyncRelationId(1L) } coAnswers {
            // Hop off the eager Unconfined Main thread so discover() returns promptly
            // and the test can issue the re-entrant call while the scan is in flight.
            withContext(Dispatchers.IO) {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            listOf(seedEntity())
        }
        gateEnteredRef = entered
    }

    private var gateEnteredRef: CountDownLatch? = null

    /** Common rule ① — dual-condition settle wait for discovery, real-time bounded. */
    private suspend fun awaitDiscovered(expectedIds: Set<Long>) {
        val vm = checkNotNull(vm)
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                combine(vm.discoveredFiles, vm.isDiscovering) { files, discovering ->
                    discovering || files.map { it.id }.toSet().intersect(expectedIds) != expectedIds
                }.first { !it }
            } ?: fail("discover did not settle in 5s")
        }
    }

    /** Common rule ① — dual-condition settle wait for analysis, real-time bounded. */
    private suspend fun awaitSettled(
        ids: StateFlow<Set<Long>>,
        analyzing: StateFlow<Boolean>,
        expectedIds: Set<Long>
    ) {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                combine(ids, analyzing) { done, active ->
                    active || !done.containsAll(expectedIds)
                }.first { !it }
            } ?: fail("analysis round did not settle in 5s")
        }
    }

    @Test
    fun `discover reentry while in flight is ignored until the first scan settles`() = runTest {
        gateDiscoveryOnce()
        val vm = buildVm()
        vm.discover()
        check(gateEnteredRef?.await(5, TimeUnit.SECONDS) == true) { "first discover never entered the query" }

        // Rapid consecutive call while the first scan is in flight: must be ignored.
        vm.discover()

        gateRef?.countDown()
        awaitDiscovered(setOf(1L))

        coVerify(exactly = 1) { videoRepository.getBySyncRelationId(1L) }
        assertTrue(vm.discoveredFiles.value.map { it.id }.contains(1L))
        assertFalse(vm.isDiscovering.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `discover after settle starts a fresh scan`() = runTest {
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(seedEntity())
        val vm = buildVm()
        vm.discover()
        awaitDiscovered(setOf(1L))

        vm.discover()
        awaitDiscovered(setOf(1L))

        coVerify(exactly = 2) { videoRepository.getBySyncRelationId(1L) }
        assertFalse(vm.isDiscovering.value)
    }

    @Test
    fun `discover during in-flight analysis does not cancel the analysis job`() = runTest {
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(seedEntity())
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        gateRef = gate
        every { audioGainAnalyzer.analyze(any()) } answers {
            entered.countDown()
            check(gate.await(5, TimeUnit.SECONDS)) { "gate timeout" }
            gain
        }
        val vm = buildVm()
        vm.discover()
        assertTrue(vm.discoveredFiles.value.map { it.id }.contains(1L))

        vm.startAnalysis()
        check(entered.await(5, TimeUnit.SECONDS)) { "analyzer not entered" }

        // discover() while analysis is gated: analysisJob is launchAnalysis's exclusive
        // cancel handle — discover must never touch it, so the round must still complete.
        vm.discover()

        gate.countDown()
        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        assertEquals(setOf(1L), vm.completedIds.value)
        assertTrue(vm.isComplete.value)
        assertFalse(vm.isAnalyzing.value)
        assertNull(vm.error.value)
    }
}
