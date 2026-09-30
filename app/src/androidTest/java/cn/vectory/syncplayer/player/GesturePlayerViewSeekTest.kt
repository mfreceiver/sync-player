package cn.vectory.syncplayer.player

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GesturePlayerViewSeekTest {
    private class Capture : GesturePlayerView.GestureListener {
        var started = 0
        var canceled = 0
        var committed = 0
        var target = -1L
        var precision = 0.25f
        override fun onSeekStarted(anchorMs: Long) { started++; precision = 1f }
        override fun onSeekCancelled() { canceled++ }
        override fun onSeekOverlay(positionMs: Long, durationMs: Long) { target = positionMs }
        override fun onSeekTo(offsetMs: Long, basePositionMs: Long) { committed++; target = basePositionMs + offsetMs }
        override fun onSpeedUpChanged(speedUp: Boolean) {}
        override fun onVolumeGestureStart() {}
        override fun onVolumeChanged(totalDelta: Float) {}
        override fun onBrightnessChanged(delta: Float) {}
        override fun onDoubleTap(zone: GesturePlayerView.DoubleTapZone, xFraction: Float, yFraction: Float) {}
    }

    private fun makeView(capture: Capture): GesturePlayerView = GesturePlayerView(
        ApplicationProvider.getApplicationContext<Context>(),
    ).apply {
        layout(0, 0, 1_000, 500)
        videoDuration = 100_000L
        currentPosition = 10_000L
        gestureListener = capture
        seekPrecisionProvider = { capture.precision }
    }

    private fun touch(view: GesturePlayerView, time: Long, action: Int, x: Float) {
        MotionEvent.obtain(1L, time, action, x, 250f, 0).let {
            try { view.onTouchEvent(it) } finally { it.recycle() }
        }
    }

    @Test fun cancelNeverCommitsAndGeometryChangeCancels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val capture = Capture()
            val view = makeView(capture)
            touch(view, 1L, MotionEvent.ACTION_DOWN, 400f)
            touch(view, 20L, MotionEvent.ACTION_MOVE, 440f)
            touch(view, 40L, MotionEvent.ACTION_CANCEL, 460f)
            assertEquals(1, capture.canceled)
            assertEquals(0, capture.committed)
            touch(view, 60L, MotionEvent.ACTION_DOWN, 400f)
            touch(view, 80L, MotionEvent.ACTION_MOVE, 440f)
            view.layout(0, 0, 900, 500)
            touch(view, 100L, MotionEvent.ACTION_UP, 460f)
            assertEquals(2, capture.canceled)
            assertEquals(0, capture.committed)
        }
    }

    @Test fun newGestureReadsResetPrecisionAndPausedAcknowledgedPosition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val capture = Capture()
            val view = makeView(capture)
            var actualPosition = 10_000L
            view.positionProvider = { actualPosition }
            touch(view, 1L, MotionEvent.ACTION_DOWN, 400f)
            touch(view, 20L, MotionEvent.ACTION_MOVE, 440f)
            touch(view, 40L, MotionEvent.ACTION_MOVE, 460f)
            assertEquals(12_000L, capture.target)
            touch(view, 60L, MotionEvent.ACTION_UP, 460f)
            actualPosition = 30_000L // seek-acknowledged actual position, no 200ms position poll
            capture.precision = 0.1f // previous session's stale local choice
            touch(view, 80L, MotionEvent.ACTION_DOWN, 400f)
            touch(view, 100L, MotionEvent.ACTION_MOVE, 440f)
            touch(view, 120L, MotionEvent.ACTION_MOVE, 460f)
            assertEquals(32_000L, capture.target)
            assertEquals(2, capture.started)
        }
    }
}
