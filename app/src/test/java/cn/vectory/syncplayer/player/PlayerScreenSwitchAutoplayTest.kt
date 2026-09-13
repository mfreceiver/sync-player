package cn.vectory.syncplayer.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import cn.vectory.syncplayer.NoOpTestApp
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class PlayerScreenSwitchAutoplayTest {

    @Test
    fun `R2 - EOF switch unconditionally triggers autoplay`() {
        // Even if the player was paused when reaching EOF, continuous play must advance and play
        val autoplayWhenPlaying = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.EOF,
            currentPlayWhenReady = true,
        )
        assertTrue(autoplayWhenPlaying)

        val autoplayWhenPaused = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.EOF,
            currentPlayWhenReady = false,
        )
        assertTrue(autoplayWhenPaused)
    }

    @Test
    fun `R2 - Manual switch preserves previous playWhenReady intention`() {
        val autoplayWhenPlaying = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = true,
        )
        assertTrue(autoplayWhenPlaying)

        val autoplayWhenPaused = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = false,
        )
        assertFalse(autoplayWhenPaused)
    }

    @Test
    fun `R5 - Sequence A playing to manual pause to manual switch burst retains paused state`() {
        // 1. Initial state: playing, cold-start initialized pendingAutoplay = true
        var isPlaying = true
        var playWhenReady = true
        var pendingAutoplay = true

        // 2. User performs manual pause toggle
        val willPlay = !isPlaying
        pendingAutoplay = updateAutoplayIntent(willPlay)
        isPlaying = false
        playWhenReady = false
        assertFalse("Manual pause must sync pendingAutoplay to false", pendingAutoplay)

        // 3. User performs MANUAL switch (Click 1)
        val autoplayClick1 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        // Must evaluate to false: user explicitly paused earlier
        assertFalse("Paused before switch must not autoplay", autoplayClick1)
        pendingAutoplay = autoplayClick1
        // Entry calls exoPlayer.pause(), setting playWhenReady = false
        playWhenReady = false

        // 4. Burst second click (Click 2) while load is in-flight
        val autoplayClick2 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        // Must still evaluate to false
        assertFalse("Second burst click after pause must still not autoplay", autoplayClick2)
        pendingAutoplay = autoplayClick2
    }

    @Test
    fun `R5 - Sequence B playing to manual switch burst retains autoplay intention`() {
        // 1. Initial state: playing, cold-start initialized pendingAutoplay = true
        var isPlaying = true
        var playWhenReady = true
        var pendingAutoplay = true

        // 2. User performs MANUAL switch (Click 1) while playing
        val autoplayClick1 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        assertTrue("Switch while playing must autoplay", autoplayClick1)
        pendingAutoplay = autoplayClick1
        // Entry calls exoPlayer.pause(), setting playWhenReady = false
        playWhenReady = false

        // 3. Burst second click (Click 2) while load is in-flight
        val autoplayClick2 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        // Must remain true because pendingAutoplay preserved previous intention
        assertTrue("Burst click must preserve autoplay intention", autoplayClick2)
        pendingAutoplay = autoplayClick2
    }

    @Test
    fun `R5 - Sequence C control bar pause to manual switch retains paused state`() {
        // 1. Initial state: playing, cold-start initialized pendingAutoplay = true
        var isPlaying = true
        var playWhenReady = true
        var pendingAutoplay = true

        // 2. User taps play/pause button in AuroraControlRow (routed through lifted togglePlay)
        val willPlay = !isPlaying
        pendingAutoplay = updateAutoplayIntent(willPlay)
        isPlaying = false
        playWhenReady = false
        assertFalse("Control bar pause must sync pendingAutoplay to false", pendingAutoplay)

        // 3. User taps next video in control bar (MANUAL switch, Click 1)
        val autoplayClick1 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        assertFalse("Manual switch after control bar pause must remain paused", autoplayClick1)
        pendingAutoplay = autoplayClick1
        // Entry calls exoPlayer.pause(), setting playWhenReady = false
        playWhenReady = false

        // 4. Second burst click on next video while load is in flight
        val autoplayClick2 = determineAutoplayAfterSwitch(
            reason = MediaSwitchReason.MANUAL,
            currentPlayWhenReady = playWhenReady || pendingAutoplay,
        )
        assertFalse("Burst switch click after control bar pause must still remain paused", autoplayClick2)
        pendingAutoplay = autoplayClick2
    }

    @Test
    fun `R5 - System pause intent decision respects user request vs system pause reasons`() {
        // USER_REQUEST: user code controls intent, helper must return null (no-op)
        val userReqPlay = autoplayIntentAfterSystemPauseChange(
            playWhenReady = true,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        org.junit.Assert.assertNull("USER_REQUEST must not modify pendingAutoplay", userReqPlay)

        val userReqPause = autoplayIntentAfterSystemPauseChange(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        org.junit.Assert.assertNull("USER_REQUEST must not modify pendingAutoplay", userReqPause)

        // AUDIO_FOCUS_LOSS: system pause, should adopt false
        val focusLoss = autoplayIntentAfterSystemPauseChange(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
        )
        assertFalse("AUDIO_FOCUS_LOSS pause must update pendingAutoplay to false", focusLoss!!)

        // AUDIO_BECOMING_NOISY: headphone unplug, system pause, should adopt false
        val becomingNoisy = autoplayIntentAfterSystemPauseChange(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertFalse("AUDIO_BECOMING_NOISY pause must update pendingAutoplay to false", becomingNoisy!!)
    }

    @Test
    fun `R2 - preparePlayerForMedia calls play when shouldPlay is true`() {
        val mockPlayer = mockk<Player>(relaxed = true)
        val uri = "content://cn.vectory.syncplayer/media/42".toUri()

        preparePlayerForMedia(
            player = mockPlayer,
            mediaUri = uri,
            restorePosition = 15_000L,
            shouldPlay = true,
        )

        verify(exactly = 1) { mockPlayer.setMediaItem(any<MediaItem>()) }
        verify(exactly = 1) { mockPlayer.seekTo(15_000L) }
        verify(exactly = 1) { mockPlayer.prepare() }
        verify(exactly = 1) { mockPlayer.play() }
    }

    @Test
    fun `R2 - preparePlayerForMedia does not call play when shouldPlay is false`() {
        val mockPlayer = mockk<Player>(relaxed = true)
        val uri = "content://cn.vectory.syncplayer/media/42".toUri()

        preparePlayerForMedia(
            player = mockPlayer,
            mediaUri = uri,
            restorePosition = 0L,
            shouldPlay = false,
        )

        verify(exactly = 1) { mockPlayer.setMediaItem(any<MediaItem>()) }
        verify(exactly = 0) { mockPlayer.seekTo(any()) }
        verify(exactly = 1) { mockPlayer.prepare() }
        verify(exactly = 0) { mockPlayer.play() }
    }
}
