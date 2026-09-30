package cn.vectory.syncplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.exoplayer.ExoPlayer
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.VideoQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuroraSemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun createViewModel(): PlayerViewModel {
        val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
        coEvery { userPrefs.getVolumeBalance() } returns false
        coEvery { userPrefs.getQuickRecycle() } returns true
        val queue = mockk<VideoQueue>()
        every { queue.consumeIds(any()) } returns emptyList()
        every { queue.getIds() } returns emptyList()
        return PlayerViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            videoRepository = mockk<VideoRepository>(relaxed = true),
            playbackRepository = mockk<PlaybackRepository>(relaxed = true),
            userPrefs = userPrefs,
            videoQueue = queue,
            undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `settings panel exposes structural tags radios and synchronous recycle guard`() {
        var recycleCalls = 0
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                AuroraSettingsModal(
                    visible = visible,
                    isFullscreen = false,
                    showControls = true,
                    videoId = 1L,
                    playbackSpeed = 1f,
                    isTrashed = false,
                    errorSequence = null,
                    onDismiss = { visible = false },
                    onDirectionChange = {},
                    onSpeedChange = {},
                    onToggleTrash = { recycleCalls += 1 },
                    onTrashSucceeded = {},
                )
            }
        }

        composeRule.onNodeWithTag("player_aurora_settings_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("player_aurora_recycle_action").performClick()
        composeRule.onNodeWithTag("player_aurora_recycle_action").performClick()
        composeRule.onAllNodesWithTag("player_aurora_radio").assertCountEquals(5)
        assertEquals(1, recycleCalls)
    }

    @Test
    fun `back closes the settings panel`() {
        var dismissCalls = 0
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                AuroraSettingsModal(
                    visible = visible,
                    isFullscreen = false,
                    showControls = true,
                    videoId = 1L,
                    playbackSpeed = 1f,
                    isTrashed = false,
                    errorSequence = null,
                    onDismiss = {
                        dismissCalls += 1
                        visible = false
                    },
                    onDirectionChange = {},
                    onSpeedChange = {},
                    onToggleTrash = {},
                    onTrashSucceeded = {},
                )
            }
        }

        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        // The handler is enabled only while the panel is visible and dismisses
        // the panel rather than delegating Back to the hosting activity.
        assertEquals(1, dismissCalls)
    }

    @Test
    fun `progress variants expose tagged two-action semantics and magnifier is singular`() {
        val viewModel = createViewModel()
        viewModel.updatePlaybackProgress(position = 12_000L, duration = 60_000L)
        var variant by mutableStateOf(ProgressVariant.ACTIVE)
        composeRule.setContent {
            MaterialTheme {
                when (variant) {
                    ProgressVariant.ACTIVE -> AuroraProgressZone(
                        viewModel = viewModel,
                        exoPlayer = mockk(relaxed = true),
                        seekSource = SeekSource.NONE,
                        durationMs = 60_000L,
                        gesturePositionMs = 0L,
                    )

                    ProgressVariant.MINIMIZED -> AuroraMinimizedProgressBar(
                        viewModel = viewModel,
                        durationMs = 60_000L,
                        onSeekBy = {},
                    )

                    ProgressVariant.FINE_MAGNIFIER -> FineScrubMagnifier(
                        state = ProgressScrubUiState(
                            isActive = true,
                            anchorPositionMs = 12_000L,
                            targetPositionMs = 18_000L,
                            isFineMode = true,
                            factor = AuroraTokens.FineScrubFactorQuarter,
                        ),
                        bitmap = null,
                        durationMs = 60_000L,
                    )
                }
            }
        }
        val activeActions = composeRule
            .onNodeWithTag("player_aurora_progress_zone")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(2, activeActions.size)

        composeRule.runOnIdle {
            variant = ProgressVariant.MINIMIZED
        }
        val minimizedActions = composeRule
            .onNodeWithTag("player_aurora_minimized_progress")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(2, minimizedActions.size)

        composeRule.runOnIdle {
            variant = ProgressVariant.FINE_MAGNIFIER
        }
        composeRule.onAllNodesWithTag("player_aurora_coarse_preview").assertCountEquals(1)
    }

    @Test
    fun `progress pointer cancel rolls back without committing an ExoPlayer seek`() {
        val viewModel = createViewModel()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        viewModel.updatePlaybackProgress(position = 8_000L, duration = 60_000L)
        composeRule.setContent {
            MaterialTheme {
                AuroraProgressZone(
                    viewModel = viewModel,
                    exoPlayer = exoPlayer,
                    seekSource = SeekSource.NONE,
                    durationMs = 60_000L,
                    gesturePositionMs = 0L,
                )
            }
        }

        composeRule.onNodeWithTag("player_aurora_progress_zone").performTouchInput {
            down(center)
            moveBy(Offset(8f, 16f))
            cancel()
        }

        assertEquals(8_000L, viewModel.currentPosition.value)
        assertEquals(-1L, viewModel.uiState.value.seekTarget)
        assertEquals(false, viewModel.progressScrubState.value.isActive)
        verify(exactly = 0) { exoPlayer.seekTo(any<Long>()) }
    }

    @Test
    fun `progress pointer up commits exactly one ExoPlayer seek`() {
        val viewModel = createViewModel()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        viewModel.updatePlaybackProgress(position = 8_000L, duration = 60_000L)
        composeRule.setContent {
            MaterialTheme {
                AuroraProgressZone(
                    viewModel = viewModel,
                    exoPlayer = exoPlayer,
                    seekSource = SeekSource.NONE,
                    durationMs = 60_000L,
                    gesturePositionMs = 0L,
                )
            }
        }

        composeRule.onNodeWithTag("player_aurora_progress_zone").performTouchInput {
            down(center)
            moveBy(Offset(8f, 16f))
            up()
        }

        assertEquals(false, viewModel.progressScrubState.value.isActive)
        verify(exactly = 1) { exoPlayer.seekTo(any<Long>()) }
    }

    @Test
    fun `precision control rebases via composable click`() {
        val vm = createViewModel()
        vm.updatePlaybackProgress(10_000L, 60_000L)
        val session = vm.onProgressScrubStarted(10_000L)
        val resolver = TrackSeekResolver(10f, 10_000L, 100, 60_000L)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(300.dp)) {
                    AuroraSeekPreviewHost(SeekSource.PROGRESS_BAR, vm.progressScrubState,
                        0L, 60_000L, 48, null, mockk(relaxed = true), viewModel = vm)
                }
            }
        }
        assertEquals(12_000L, resolver.move(20f, SeekPrecision.Full, relativeRequested = false))
        vm.onProgressScrubChanged(12_000L, 1f, false, session)
        composeRule.onNodeWithTag("player_seek_precision_quarter").performClick()
        assertEquals(SeekPrecision.Quarter, vm.seekPresentation.value.precision)
        assertEquals(18_000L, resolver.move(30f, vm.seekPresentation.value.precision, relativeRequested = true))
        assertEquals(19_500L, resolver.move(40f, vm.seekPresentation.value.precision, relativeRequested = true))
    }

    @Test
    fun `compact transition cancels live decode and late bitmap cannot reappear`() {
        val extractor = mockk<SeekPreviewExtractor>(relaxed = true)
        val decode = CompletableDeferred<android.graphics.Bitmap?>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val ended = java.util.concurrent.CountDownLatch(1)
        coEvery { extractor.beginSession(any()) } returns Unit
        coEvery { extractor.endSession() } coAnswers { ended.countDown(); Unit }
        every { extractor.getCachedFrame(any(), any()) } returns null
        // Deliberately non-cooperative: a native decode can finish after UI demand
        // was cancelled. A cancellable Deferred.await alone would not test late return.
        coEvery { extractor.extractFrameAtPosition(any(), any()) } coAnswers {
            entered.countDown()
            withContext(NonCancellable) { decode.await() }
        }
        val height = mutableStateOf(300)
        composeRule.setContent {
            val chromePx = with(LocalDensity.current) { 48.dp.roundToPx() }
            Box(Modifier.fillMaxWidth().height(height.value.dp)) {
                AuroraSeekPreviewHost(SeekSource.GESTURE, MutableStateFlow(ProgressScrubUiState()),
                    10_000L, 60_000L, chromePx, "content://preview/test", extractor)
            }
        }
        composeRule.waitUntil(5_000) { entered.count == 0L }
        composeRule.runOnIdle { height.value = 100 }
        composeRule.onNodeWithTag("player_seek_compact_step_back").assertExists()
        decode.complete(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))
        composeRule.waitUntil(5_000) { ended.count == 0L }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("player_seek_preview_bitmap").assertCountEquals(0)
        coVerify(exactly = 1) { extractor.beginSession(any()) }
        coVerify(exactly = 1) { extractor.endSession() }
    }

    @Test
    fun `constrained card keeps its top above the safe margin`() {
        composeRule.setContent {
            val chromePx = with(LocalDensity.current) { 48.dp.roundToPx() }
            Box(Modifier.fillMaxWidth().height(210.dp)) {
                AuroraSeekPreviewHost(SeekSource.GESTURE, MutableStateFlow(ProgressScrubUiState()),
                    10_000L, 60_000L, chromePx, null, mockk(relaxed = true))
            }
        }
        val top = composeRule.onNodeWithTag("player_aurora_coarse_preview")
            .fetchSemanticsNode().positionInRoot.y
        val marginPx = 16f * composeRule.density.density
        org.junit.Assert.assertTrue(top >= marginPx - 1.5f)
    }
}

private enum class ProgressVariant {
    ACTIVE,
    MINIMIZED,
    FINE_MAGNIFIER,
}
