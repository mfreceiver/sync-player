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
