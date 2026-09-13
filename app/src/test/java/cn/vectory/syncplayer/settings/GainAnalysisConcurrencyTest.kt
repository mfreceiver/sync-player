package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.audio.AudioGainResult
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Class B — real in-memory Room interleave tests for the gain write path (L4-3).
 *
 * Proves the data effects that a stateless mock cannot (BUG-013 end-to-end):
 *  1. a single-column `updateAudioGain` must NOT roll back a concurrent `updateStar`
 *     column change made while the analyzer is blocked inside the analysis round;
 *  2. a row deleted concurrently must NOT be revived by the gain write
 *     (`updateAudioGain` on a missing id is a silent 0-row no-op, no exception escapes);
 *  3. the fail → `retryFailed()` → succeed chain still persists the gain and clears
 *     the failed id (regression lock for the retry entry).
 *
 * Deterministic interleaving uses bounded blocking latches inside the synchronous
 * analyzer mock (a plain function — coAnswers/CompletableDeferred cannot suspend it).
 * The main side FIRST waits for the `entered` latch (proof the gate is actually held —
 * observing `activeAnalysis` is NOT sufficient, it is published before the analyzer call)
 * before performing the concurrent write on Dispatchers.IO via the real repository.
 *
 * Cleanup follows common rule ② (all five steps, DB close last). Settle waits follow
 * common rule ① (dual condition, real-time bounded).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class GainAnalysisConcurrencyTest {

    private lateinit var db: AppDatabase
    private lateinit var videoRepository: VideoRepository
    private val audioGainAnalyzer = mockk<AudioGainAnalyzer>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private var vm: GainAnalysisViewModel? = null
    private var gateRef: CountDownLatch? = null

    private val gain = AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        videoRepository = VideoRepository(db.videoItemDao(), db)
        mockkObject(MediaUris)
        every { MediaUris.mediaUri(any()) } returns mockk()
    }

    @After
    fun tearDown() {
        // Common rule ② — the job reference is captured BEFORE cancelAnalysis (it nulls the seam).
        val job = vm?.analysisJob
        vm?.cancelAnalysis()                                   // step 1
        gateRef?.countDown()                                   // step 2 (finally semantics)
        if (job != null) {                                     // step 3: bounded wait for job completion
            runBlocking {
                withTimeoutOrNull(5_000) { job.join() }
                    ?: fail("analysis job did not finish in 5s")
            }
        }
        unmockkAll()                                           // step 4a: restore mockkObject(MediaUris)
        Dispatchers.resetMain()                                // step 4b
        if (::db.isInitialized) db.close()                     // step 5: close DB last
    }

    private fun seedRow(id: Long = 1L) = runBlocking {
        db.videoItemDao().upsert(
            VideoItemEntity(
                id = id,
                syncRelationId = 1L,
                relativePath = "ep1.mp4",
                parentPath = "",
                fileName = "ep1.mp4",
                fileSize = 1L,
                audioGainDb = null
            )
        )
    }

    private fun buildVm(): GainAnalysisViewModel = GainAnalysisViewModel(
        videoRepository = videoRepository,
        audioGainAnalyzer = audioGainAnalyzer,
        context = context,
        savedStateHandle = SavedStateHandle(mapOf("relationId" to 1L))
    ).also { vm = it }

    /** Gated analyzer stub: reports entry, then blocks (bounded) until the main side releases. */
    private fun gateAnalyzerOnce(): CountDownLatch {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        gateRef = gate
        every { audioGainAnalyzer.analyze(any()) } answers {
            entered.countDown() // proof the gate is actually held (before blocking)
            check(gate.await(5, TimeUnit.SECONDS)) { "gate timeout" }
            gain
        }
        return entered
    }

    /** Common rule ① — dual-condition settle wait, real-time bounded. */
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

    /** discover() runs a real Room query asynchronously — wait for it to settle first. */
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

    @Test
    fun `concurrent star column update is not rolled back by gain write`() = runBlocking {
        seedRow()
        val vm = buildVm()
        vm.discover()
        awaitDiscovered(setOf(1L))

        val entered = gateAnalyzerOnce()
        vm.startAnalysis()
        check(entered.await(5, TimeUnit.SECONDS)) { "analyzer not entered" }

        // Concurrent column write on a real IO thread, through the real repository (bypasses the VM).
        val writer = CoroutineScope(Dispatchers.IO).launch { videoRepository.updateStar(1L, 5) }
        writer.join()
        gateRef?.countDown()

        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        val row = videoRepository.getById(1L)
        assertEquals("single-column gain write must not roll back concurrent star", 5, row?.star)
        assertEquals(6f, row?.audioGainDb)
        assertNull(vm.error.value)
    }

    @Test
    fun `row deleted during analysis is not revived by gain write`() = runBlocking {
        seedRow()
        val vm = buildVm()
        vm.discover()
        awaitDiscovered(setOf(1L))

        val entered = gateAnalyzerOnce()
        vm.startAnalysis()
        check(entered.await(5, TimeUnit.SECONDS)) { "analyzer not entered" }

        // Concurrent delete of the row on a real IO thread, through the real repository.
        val writer = CoroutineScope(Dispatchers.IO).launch { videoRepository.deleteMediaRecord(1L) }
        writer.join()
        gateRef?.countDown()

        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        assertNull("deleted row must not be revived", videoRepository.getById(1L))
        // 0-row no-op must not surface as an error to the UI.
        assertNull(vm.error.value)
    }

    @Test
    fun `fail then retryFailed persists gain and clears failed id`() = runBlocking {
        seedRow()
        val vm = buildVm()
        var calls = 0
        every { audioGainAnalyzer.analyze(any()) } answers {
            if (calls++ == 0) null else gain
        }
        vm.discover()
        awaitDiscovered(setOf(1L))

        vm.startAnalysis()
        awaitSettled(vm.failedIds, vm.isAnalyzing, setOf(1L))
        check(vm.failedIds.value.contains(1L)) { "first round should mark id=1 failed" }

        vm.retryFailed()
        awaitSettled(vm.completedIds, vm.isAnalyzing, setOf(1L))

        assertEquals(6f, videoRepository.getById(1L)?.audioGainDb)
        assertFalse(vm.failedIds.value.contains(1L))
        assertNull(vm.error.value)
    }
}
