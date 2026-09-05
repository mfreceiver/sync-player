package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Class A — mock call-contract tests for the gain write path (L4-2 / L4-3).
 *
 * The analysis round must persist the measured gain through the single-column
 * `VideoRepository.updateAudioGain(id, gain)` API — never through a whole-row
 * `upsert(video.copy(...))`, which rolls back concurrent column changes and can
 * revive deleted rows (BUG-013). This class locks the CALL CONTRACT on a stateless
 * mock repository; the data effect (no rollback / no revive) is proven by
 * [GainAnalysisConcurrencyTest] against a real in-memory Room DB.
 *
 * Both entries (`startAnalysis` and the `retryFailed` chain) converge on the single
 * call site in `launchAnalysis`, so each is exercised end-to-end.
 *
 * Conventions: asserts call counts only — never user-visible text. Cleanup follows
 * common rule ② (five steps; no gate here → step 2 is a no-op, no DB → step 5 skipped).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GainAnalysisViewModelUpdateTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val audioGainAnalyzer = mockk<AudioGainAnalyzer>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private var vm: GainAnalysisViewModel? = null

    private val gain = cn.vectory.syncplayer.audio.AudioGainResult(
        rmsDb = -20f, peakDb = -3f, gainDb = 6f
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(MediaUris)
        every { MediaUris.mediaUri(any()) } returns mockk()
    }

    @After
    fun tearDown() {
        // Common rule ② — capture the job reference BEFORE cancelAnalysis (it nulls the seam).
        val job = vm?.analysisJob
        vm?.cancelAnalysis()                                   // step 1
        // step 2 (gate release): no gate in this class — no-op
        if (job != null) {                                     // step 3: bounded wait for job completion
            runBlocking {
                withTimeoutOrNull(5_000) { job.join() }
                    ?: fail("analysis job did not finish in 5s")
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

    /**
     * Common rule ① — dual-condition settle wait on a real scheduler: the target IDs
     * have been published AND `isAnalyzing == false`. The hardcoded
     * `withContext(Dispatchers.IO)` inside `launchAnalysis` is not mocked away, so the
     * wait must run on real time (never the runTest virtual clock, never advanceUntilIdle).
     */
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
    fun `startAnalysis persists gain via single-column updateAudioGain`() = runTest {
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(seedEntity())
        every { audioGainAnalyzer.analyze(any()) } returns gain

        val vm = buildVm()
        vm.discover()
        assertTrue(vm.discoveredFiles.value.map { it.id }.contains(1L))

        vm.startAnalysis()
        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    @Test
    fun `retryFailed entry also persists via single-column updateAudioGain`() = runTest {
        coEvery { videoRepository.getBySyncRelationId(1L) } returns listOf(seedEntity())
        // First analysis round fails (analyzer yields null → failedIds), the retry succeeds.
        every { audioGainAnalyzer.analyze(any()) } returnsMany listOf(null, gain)

        val vm = buildVm()
        vm.discover()
        assertTrue(vm.discoveredFiles.value.map { it.id }.contains(1L))

        vm.startAnalysis()
        // Dual-condition wait MUST succeed before retryFailed() (retry is rejected while analyzing).
        awaitSettled(vm.failedIds, vm.isAnalyzing, setOf(1L))
        assertTrue(vm.failedIds.value.contains(1L))

        vm.retryFailed()
        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        assertEquals(emptySet<Long>(), vm.failedIds.value)
        coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }
}
