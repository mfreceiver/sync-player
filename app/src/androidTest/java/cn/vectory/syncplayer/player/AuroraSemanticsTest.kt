package cn.vectory.syncplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
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
        every { queue.consumeIds() } returns emptyList()
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
        composeRule.onAllNodesWithTag("player_aurora_fine_magnifier").assertCountEquals(1)
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
}

private enum class ProgressVariant {
    ACTIVE,
    MINIMIZED,
    FINE_MAGNIFIER,
}
