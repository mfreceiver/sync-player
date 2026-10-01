package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout tests for the coarse seek preview's VERTICAL anchor: the preview's bottom
 * edge must sit exactly one AuroraPreviewGap (8dp) ABOVE the bottom chrome's top
 * edge — not flush against the container bottom.
 *
 * The test replicates PlayerScreen's wiring: a bottom chrome box whose height is
 * MEASURED via onSizeChanged and fed into [AuroraSeekPreviewHost] as
 * bottomChromeHeightPx. Chrome heights cover both real visibility cases: a short
 * chrome (progress zone only — gesture seek with the control row hidden) and a
 * tall chrome (progress + control row).
 *
 * The nav-inset test guards against DOUBLE-COUNTING the navigation bar: the
 * measured chrome height already contains AuroraBottomArea's
 * navigationBarsPadding(), so the preview's clearance must add the gap ONLY. A real
 * non-zero bottom system-bar inset is dispatched into the hierarchy so the old
 * double-counting implementation (clearance = nav inset + chrome height + gap)
 * fails this suite measurably.
 *
 * videoUri = null keeps the host on its placeholder path (no frame extraction,
 * extractor never invoked), and SeekSource.GESTURE exercises the same
 * AuroraCoarsePreview composable as the coarse PROGRESS_BAR path. Asserts pixel
 * geometry only — never user-facing text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = NoOpTestApp::class,
    qualifiers = "w800dp-h1280dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuroraCoarsePreviewPositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Last chrome height reported by the replica's onSizeChanged. */
    private val measuredChromeHeight = arrayOf(0)

    private fun setContentWithChrome(
        chromeHeightDp: () -> Int,
        chromeHasNavPadding: Boolean = false,
        assertDisplayed: Boolean = true,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    // Fake bottom chrome, replicating PlayerScreen's bottom-area
                    // wrapper (measured height) around AuroraBottomArea's padded
                    // Column (navigationBarsPadding + content height).
                    var chromeHeightPx by remember { mutableIntStateOf(0) }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .testTag("test_bottom_chrome")
                            .onSizeChanged {
                                chromeHeightPx = it.height
                                measuredChromeHeight[0] = it.height
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (chromeHasNavPadding) Modifier.navigationBarsPadding() else Modifier)
                                .height(chromeHeightDp().dp),
                        )
                    }
                    AuroraSeekPreviewHost(
                        seekSource = SeekSource.GESTURE,
                        progressScrubState = remember { MutableStateFlow(ProgressScrubUiState()) },
                        gesturePositionMs = 60_000L,
                        durationMs = 120_000L,
                        bottomChromeHeightPx = chromeHeightPx,
                        videoUri = null,
                        previewExtractor = SeekPreviewExtractor(ApplicationProvider.getApplicationContext()),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        if (assertDisplayed) {
            composeRule.onNodeWithTag("player_aurora_coarse_preview").assertIsDisplayed()
        }
    }

    private fun dpToPx(dp: Int): Float = dp * composeRule.density.density

    private fun assertPreviewBottomSitsAboveChromeTop() {
        val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
        val chrome = composeRule.onNodeWithTag("test_bottom_chrome").fetchSemanticsNode()

        val previewBottom = preview.positionInRoot.y + preview.size.height
        val chromeTop = chrome.positionInRoot.y

        // The contract: preview bottom == chrome top - AuroraPreviewGap (8dp),
        // i.e. exactly one gap of clearance above the progress zone.
        assertEquals(chromeTop - dpToPx(8), previewBottom, 1.5f)
        assertTrue("preview bottom must be strictly above chrome top", previewBottom < chromeTop)
    }

    /**
     * Dispatches a REAL platform [android.view.WindowInsets] with a non-zero bottom
     * system-bar into the compose hierarchy (the same path production insets flow
     * through), so WindowInsets.navigationBarsPadding()/navigationBars resolve
     * non-zero under Robolectric too.
     */
    private fun dispatchBottomSystemBarInset(bottomPx: Int) {
        composeRule.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomPx))
                .build()
                .toWindowInsets()
                ?: error("platform WindowInsets unavailable under this SDK")
            val content = (composeRule as AndroidComposeTestRule<*, *>)
                .activity
                .findViewById<android.view.View>(android.R.id.content)
            content.dispatchApplyWindowInsets(insets)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `preview bottom sits one gap above a progress-only chrome`() {
        // Gesture seek with the control row hidden: chrome = progress touch zone.
        setContentWithChrome(chromeHeightDp = { 48 })
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `preview bottom sits one gap above a full chrome with control row`() {
        // Progress-bar seek with full chrome: progress + stack gap + control row.
        setContentWithChrome(chromeHeightDp = { 140 })
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `clearance does not double-count the nav bar inset`() {
        // Faithful replica: the chrome's measured height INCLUDES its
        // navigationBarsPadding(), exactly like AuroraBottomArea under PlayerScreen's
        // measuring wrapper. The preview must clear by the gap ONLY — adding the nav
        // inset on top (old implementation) lifted the preview one nav bar too high.
        setContentWithChrome(chromeHeightDp = { 48 }, chromeHasNavPadding = true)
        val chromeHeightBeforeInset = measuredChromeHeight[0]

        dispatchBottomSystemBarInset(bottomPx = 63)

        // Non-vacuity guard: the dispatched inset must be LIVE — the padded chrome
        // must have grown by exactly the injected 63px. If this fails, inset
        // dispatch is inert under the current Robolectric/Compose stack and this
        // test can no longer detect double-counting.
        assertEquals(
            chromeHeightBeforeInset + 63f,
            measuredChromeHeight[0].toFloat(),
            1.5f,
        )

        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `initial zero to measured chrome height keeps preview in viewport`() {
        // Initial-composition transient (0 -> measured): freeze the clock BEFORE
        // composing so the earliest frames render while bottomChromeHeightPx is
        // still 0 (chrome not yet measured). The preview must stay inside the
        // viewport during those frames, then land exactly one gap above the chrome.
        composeRule.mainClock.autoAdvance = false
        setContentWithChrome(chromeHeightDp = { 48 }, assertDisplayed = false)

        val rootHeightPx = dpToPx(1280)
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
            assertTrue("preview top must stay >= 0", preview.positionInRoot.y >= 0f)
            assertTrue(
                "preview bottom must stay within the viewport",
                preview.positionInRoot.y + preview.size.height <= rootHeightPx + 1f,
            )
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPreviewBottomSitsAboveChromeTop()
    }

    @Test
    fun `chrome height transition keeps preview in viewport and lands gap-above`() {
        // Transition transient (140dp -> 48dp): sampled frame by frame with a frozen
        // clock, the preview must never leave the viewport; once idle it must land
        // exactly one gap above the new chrome top.
        val chromeHeightDp = mutableStateOf(140)
        composeRule.mainClock.autoAdvance = false
        setContentWithChrome(chromeHeightDp = { chromeHeightDp.value })

        composeRule.runOnUiThread { chromeHeightDp.value = 48 }
        val rootHeightPx = dpToPx(1280)
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            val preview = composeRule.onNodeWithTag("player_aurora_coarse_preview").fetchSemanticsNode()
            assertTrue("preview top must stay >= 0", preview.positionInRoot.y >= 0f)
            assertTrue(
                "preview bottom must stay within the viewport",
                preview.positionInRoot.y + preview.size.height <= rootHeightPx + 1f,
            )
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPreviewBottomSitsAboveChromeTop()
    }

    // ---------- B1: speed-adaptive submission gate (pure, virtual clock) ----------

    @Test
    fun `the first target of a session always submits`() {
        assertTrue(
            shouldSubmitPreviewFrame(
                targetBucket = 120L,
                nowMs = 1_000L,
                lastSubmittedBucket = null,
                lastBucketChangeElapsedMs = null,
            ),
        )
    }

    @Test
    fun `a fast sweep skips intermediate buckets and submits the stable stop`() {
        // The thumb sweeps 4 buckets per pointer emission (~10 ms apart —
        // never stable): buckets 124, 128, 132, 136 are all mid-sweep, each
        // more than 3 buckets past the submitted one — none may reach the
        // engine.
        var lastSubmitted: Long? = 120L
        var lastChange = 0L
        for (step in 1..4) {
            val bucket = 120L + step * 4
            val now = step * 10L
            lastChange = now
            val submit = shouldSubmitPreviewFrame(bucket, now, lastSubmitted, lastChange)
            assertFalse("mid-sweep bucket $bucket must be skipped", submit)
            // A skipped bucket must NOT count as submitted.
            assertEquals(120L, lastSubmitted)
        }
        // Settling on bucket 148: once the target has been stable for at
        // least the threshold, the very next emission submits immediately —
        // no fixed delay is ever added on top of stability.
        assertTrue(
            shouldSubmitPreviewFrame(
                targetBucket = 148L,
                nowMs = 200L,
                lastSubmittedBucket = lastSubmitted,
                lastBucketChangeElapsedMs = lastChange,
            ),
        )
    }

    @Test
    fun `slow per-bucket dragging still submits every bucket`() {
        var lastSubmitted: Long? = 120L
        for (step in 1..5) {
            val bucket = 120L + step
            val now = step * 10L
            assertTrue(
                "bucket $bucket must submit during a slow per-bucket drag",
                shouldSubmitPreviewFrame(bucket, now, lastSubmitted, now),
            )
            lastSubmitted = bucket
        }
    }

    @Test
    fun `stability boundary submits at exactly the threshold`() {
        // 120 ms without a bucket change → submit regardless of sweep distance.
        assertTrue(
            shouldSubmitPreviewFrame(
                targetBucket = 150L,
                nowMs = 1_000L,
                lastSubmittedBucket = 120L,
                lastBucketChangeElapsedMs = 880L,
            ),
        )
        // 119 ms, far sweep → still skipped.
        assertFalse(
            shouldSubmitPreviewFrame(
                targetBucket = 150L,
                nowMs = 1_000L,
                lastSubmittedBucket = 120L,
                lastBucketChangeElapsedMs = 881L,
            ),
        )
        // A target that never changed bucket (single-bucket session) counts
        // as maximally stable.
        assertTrue(
            shouldSubmitPreviewFrame(
                targetBucket = 7L,
                nowMs = 5_000L,
                lastSubmittedBucket = null,
                lastBucketChangeElapsedMs = null,
            ),
        )
    }

    // ---------- B1: host wiring (composed host + fake extractor) ----------

    /**
     * The composed wiring must mirror the pure gate: during a fast bucket
     * sweep NO new native decode may be submitted (the initial settled frame
     * stays on screen), and once the thumb settles the stable stop submits
     * exactly one more decode. The UI retriever counts ONLY the UI decodes —
     * prefetch uses its own retriever.
     */
    /**
     * Shared harness for the B2 sweep/wakeup tests: a counting, parkable UI
     * retriever (each submission is recorded as the native timeUs), a silent
     * prefetch retriever, and the extractor built on them.
     */
    private class SweepHarness {
        val askedUs = CopyOnWriteArrayList<Long>()
        val park = AtomicBoolean(false)
        val settleGate = CountDownLatch(1)

        private val uiRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getScaledFrameAtTime(any(), any(), any(), any()) } answers {
                // Record the submission BEFORE parking so gate assertions
                // observe it even while the decode is parked.
                askedUs.add(firstArg<Long>())
                if (park.get()) settleGate.await()
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }
        private val prefetchRetriever = mockk<MediaMetadataRetriever> {
            every { setDataSource(any<Context>(), any<Uri>()) } returns Unit
            every { getScaledFrameAtTime(any(), any(), any(), any()) } answers {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            every { release() } returns Unit
        }

        val extractor = SeekPreviewExtractor(
            context = ApplicationProvider.getApplicationContext(),
            retrieverFactory = { uiRetriever },
            prefetchRetrieverFactory = { prefetchRetriever },
        )

        fun release() {
            park.set(false)
            settleGate.countDown()
            extractor.release()
        }
    }

    private fun setContentForSweep(
        harness: SweepHarness,
        gesture: MutableState<Long>,
        source: MutableState<SeekSource> = mutableStateOf(SeekSource.GESTURE),
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AuroraSeekPreviewHost(
                        seekSource = source.value,
                        progressScrubState = remember { MutableStateFlow(ProgressScrubUiState()) },
                        gesturePositionMs = gesture.value,
                        durationMs = 120_000L,
                        bottomChromeHeightPx = 0,
                        videoUri = "content://test/video",
                        previewExtractor = harness.extractor,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a stepped bucket sweep submits nothing until the thumb truly stops`() {
        val harness = SweepHarness()
        val gesture = mutableStateOf(60_000L)
        try {
            setContentForSweep(harness, gesture)
            composeRule.waitUntil("initial settled decode must happen", 5_000) {
                harness.askedUs.size >= 1
            }

            // Fast sweep, one drain per emission: no intermediate bucket may
            // submit, and each emission cancels the previous stop-point wait.
            harness.park.set(true)
            for (position in 62_000L..70_000L step 2_000L) {
                gesture.value = position
                composeRule.waitForIdle()
                assertEquals(
                    "mid-sweep position $position must not submit",
                    1,
                    harness.askedUs.size,
                )
            }

            // TRUE STOP: no further emission at all — the stop-point wakeup
            // must fire on its own after the remaining stable time and submit
            // exactly the final swept bucket (B2 regression net: the old
            // skip-branch simply returned, so this stop never submitted).
            SystemClock.sleep(400)
            composeRule.waitUntil("the true stop must submit the final bucket", 5_000) {
                harness.askedUs.size >= 2
            }
            assertEquals(
                "the stop point must be the last swept bucket",
                listOf(60_000_000L, 70_000_000L),
                harness.askedUs.toList(),
            )
        } finally {
            harness.release()
        }
    }

    @Test
    fun `a coalesced sweep burst still submits only the final stop`() {
        val harness = SweepHarness()
        val gesture = mutableStateOf(60_000L)
        try {
            setContentForSweep(harness, gesture)
            composeRule.waitUntil("initial settled decode must happen", 5_000) {
                harness.askedUs.size >= 1
            }

            harness.park.set(true)
            gesture.value = 62_000
            gesture.value = 64_000
            gesture.value = 66_000
            gesture.value = 68_000
            gesture.value = 70_000
            composeRule.waitForIdle()
            assertEquals(
                "the coalesced burst must not submit any intermediate decode",
                1,
                harness.askedUs.size,
            )

            // True stop without any further emission: intermediate lambdas
            // are superseded by collectLatest, only the final lambda's
            // wakeup survives and submits.
            SystemClock.sleep(400)
            composeRule.waitUntil("the final stop must submit", 5_000) {
                harness.askedUs.size >= 2
            }
            assertEquals(
                listOf(60_000_000L, 70_000_000L),
                harness.askedUs.toList(),
            )
        } finally {
            harness.release()
        }
    }

    @Test
    fun `a stop-point wait is superseded when the thumb keeps moving`() {
        val harness = SweepHarness()
        val gesture = mutableStateOf(60_000L)
        try {
            setContentForSweep(harness, gesture)
            composeRule.waitUntil("initial settled decode must happen", 5_000) {
                harness.askedUs.size >= 1
            }
            harness.park.set(true)

            // Sweep to a far bucket: its stop-point wait is scheduled.
            gesture.value = 74_000
            composeRule.waitForIdle()
            assertEquals(1, harness.askedUs.size)

            // Keep moving in sub-stable intervals: every move cancels the
            // previous wait. The FIRST wait's deadline passes long before
            // the last emission — its bucket must never submit.
            SystemClock.sleep(60)
            gesture.value = 80_000
            composeRule.waitForIdle()
            SystemClock.sleep(50)
            gesture.value = 86_000
            composeRule.waitForIdle()
            SystemClock.sleep(50)
            gesture.value = 92_000
            composeRule.waitForIdle()
            SystemClock.sleep(50)
            assertEquals(
                "the superseded stop points must never submit (first deadline long passed)",
                1,
                harness.askedUs.size,
            )

            // The final stop settles and submits its own bucket.
            SystemClock.sleep(400)
            composeRule.waitUntil("the final stop must submit", 5_000) {
                harness.askedUs.size >= 2
            }
            assertEquals(
                listOf(60_000_000L, 92_000_000L),
                harness.askedUs.toList(),
            )
        } finally {
            harness.release()
        }
    }

    @Test
    fun `session end cancels the stop-point wait with zero submissions`() {
        val harness = SweepHarness()
        val gesture = mutableStateOf(60_000L)
        val source = mutableStateOf(SeekSource.GESTURE)
        try {
            setContentForSweep(harness, gesture, source)
            composeRule.waitUntil("initial settled decode must happen", 5_000) {
                harness.askedUs.size >= 1
            }
            harness.park.set(true)

            gesture.value = 74_000
            composeRule.waitForIdle()
            assertEquals(1, harness.askedUs.size)

            // Ending the scrub session tears down the effect (demand gate)
            // and must cancel the scheduled wait with zero submissions.
            source.value = SeekSource.NONE
            composeRule.waitForIdle()
            SystemClock.sleep(400)
            assertEquals(
                "a cancelled stop-point wait must never submit",
                1,
                harness.askedUs.size,
            )
        } finally {
            harness.release()
        }
    }

    @Test
    fun `a replaced display frame keeps its bucket cached for a back-scrub hit`() {
        val harness = SweepHarness()
        val gesture = mutableStateOf(60_000L)
        try {
            setContentForSweep(harness, gesture)
            composeRule.waitUntil("initial settled decode must happen", 5_000) {
                harness.askedUs.size >= 1
            }

            // Step to the next bucket: beyond the 3-bucket skip window, so
            // it submits via the stop-point wakeup. The acceptance REPLACES
            // the display reference; the replaced frame must stay cached —
            // B2 separated giving up the UI reference from evicting the
            // cache.
            gesture.value = 62_000
            composeRule.waitForIdle()
            composeRule.waitUntil("the near bucket must submit", 5_000) {
                harness.askedUs.size >= 2
            }
            assertEquals(
                listOf(60_000_000L, 62_000_000L),
                harness.askedUs.toList(),
            )

            // Back-scrub INTO the replaced frame's bucket (60_200 → bucket
            // 60_000): served from the cache — no third decode. (The pre-B2
            // host evicted the replaced instance on replacement and
            // re-decoded here.)
            gesture.value = 60_200
            composeRule.waitForIdle()
            assertEquals(
                "the back-scrub must be served from the retained cache entry (askedUs=${harness.askedUs.toList()})",
                2,
                harness.askedUs.size,
            )
            val fromCache = harness.extractor.getCachedFrame("content://test/video", 60_200L)
            assertNotNull(fromCache)
            assertSame(
                fromCache,
                harness.extractor.getCachedFrame("content://test/video", 60_000L),
            )
        } finally {
            harness.release()
        }
    }
}
