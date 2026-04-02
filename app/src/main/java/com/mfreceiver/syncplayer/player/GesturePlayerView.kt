package com.mfreceiver.syncplayer.player

import android.content.Context
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.mfreceiver.syncplayer.R
import kotlinx.coroutines.*

class GesturePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val playerView = PlayerView(context)
    private val overlayText = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        setBackgroundColor(0x88000000.toInt())
        setPadding(24, 12, 24, 12)
        visibility = GONE
    }

    var gestureListener: GestureListener? = null
    var speedMultiplier: Float = 2.0f

    private var isLongPressing = false
    private var isSeeking = false
    private var seekAccumulatedMs = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isInLeftHalf = false
    private var initialBrightness = 0f
    private var overlayHideJob: Job? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    interface GestureListener {
        fun onSpeedUpChanged(speedUp: Boolean)
        fun onSeekTo(offsetMs: Long)
        fun onVolumeChanged(delta: Float)
        fun onBrightnessChanged(delta: Float)
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlayText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER
        })
    }

    fun getPlayerView(): PlayerView = playerView

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            isLongPressing = true
            showOverlay("${speedMultiplier}x 倍速播放")
            gestureListener?.onSpeedUpChanged(true)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 == null) return false

            val absDx = kotlin.math.abs(distanceX)
            val absDy = kotlin.math.abs(distanceY)

            if (!isSeeking && !isAdjustingVertical) {
                if (absDx > absDy && absDx > 10) {
                    isSeeking = true
                    seekAccumulatedMs = 0L
                } else if (absDy > absDx && absDy > 10) {
                    isAdjustingVertical = true
                    isInLeftHalf = e1.x < width / 2f
                    if (isInLeftHalf) {
                        val window = (context as? android.app.Activity)?.window
                        initialBrightness = window?.attributes?.screenBrightness ?: 0.5f
                        if (initialBrightness < 0) initialBrightness = 0.5f
                    }
                }
            }

            if (isSeeking) {
                seekAccumulatedMs += (-distanceX * 100).toLong()
                val sign = if (seekAccumulatedMs >= 0) "▶" else "◀"
                showOverlay("$sign ${formatSeekOffset(seekAccumulatedMs)}")
                return true
            }

            if (isAdjustingVertical) {
                val delta = -distanceY / height.toFloat()
                if (isInLeftHalf) {
                    gestureListener?.onBrightnessChanged(delta)
                } else {
                    gestureListener?.onVolumeChanged(delta)
                }
                return true
            }

            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (playerView.isControllerFullyVisible) {
                playerView.hideController()
            } else {
                playerView.showController()
            }
            return true
        }
    })

    private var isAdjustingVertical = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isAdjustingVertical = false
                isSeeking = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    isLongPressing = false
                    gestureListener?.onSpeedUpChanged(false)
                    hideOverlay()
                }
                if (isSeeking) {
                    gestureListener?.onSeekTo(seekAccumulatedMs)
                    isSeeking = false
                    seekAccumulatedMs = 0L
                    hideOverlay()
                }
                isAdjustingVertical = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }

        if (isSeeking || isAdjustingVertical || isLongPressing) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        return true
    }

    fun showVolumeOverlay(volumePercent: Int) {
        showOverlay("音量 $volumePercent%")
    }

    fun showBrightnessOverlay(brightnessPercent: Int) {
        showOverlay("亮度 $brightnessPercent%")
    }

    private fun showOverlay(text: String) {
        overlayHideJob?.cancel()
        overlayText.text = text
        overlayText.visibility = VISIBLE
        overlayHideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(1500)
            overlayText.visibility = GONE
        }
    }

    private fun hideOverlay() {
        overlayHideJob?.cancel()
        overlayText.visibility = GONE
    }

    private fun formatSeekOffset(ms: Long): String {
        val totalSeconds = kotlin.math.abs(ms) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
