package cn.vectory.syncplayer.player

import android.content.Context
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs


class GesturePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val playerView = PlayerView(context).apply {
        controllerAutoShow = false
        useController = false
    }

    var gestureListener: GestureListener? = null
    var speedMultiplier: Float = 2.0f
    var videoDuration: Long = 0L
    var currentPosition: Long = 0L
    private var isLongPressing = false
    private var isSeeking = false
    private var seekOffsetMs = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isInLeftHalf = false
    private var initialBrightness = 0f
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var singleTapCallback: (() -> Unit)? = null

    interface GestureListener {
        fun onSpeedUpChanged(speedUp: Boolean)
        fun onSeekTo(offsetMs: Long)
        fun onVolumeGestureStart()
        fun onVolumeChanged(totalDelta: Float)
        fun onBrightnessChanged(delta: Float)
        fun onDoubleTap(zone: DoubleTapZone)
        fun onSeekOverlay(positionText: String) {}
        fun onLongPressSpeedOverlay(multiplier: Float) {}
        fun onHideOverlay() {}
    }

    enum class DoubleTapZone { PREVIOUS, NEXT, CENTER }

    fun setOnSingleTapCallback(callback: (() -> Unit)?) {
        singleTapCallback = callback
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun getPlayerView(): PlayerView = playerView

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            isLongPressing = true
            gestureListener?.onLongPressSpeedOverlay(speedMultiplier)
            gestureListener?.onSpeedUpChanged(true)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 == null) return false
            if (isLongPressing) return false

            val dxFromStart = e2.x - touchStartX
            val dyFromStart = e2.y - touchStartY
            val absDxFromStart = abs(dxFromStart)
            val absDyFromStart = abs(dyFromStart)

            if (!isSeeking && !isAdjustingVertical) {
                if (absDyFromStart > absDxFromStart && absDyFromStart > 20) {
                    isAdjustingVertical = true
                    isInLeftHalf = e1.x < width / 2f
                    if (isInLeftHalf) {
                        val window = (context as? android.app.Activity)?.window
                        initialBrightness = window?.attributes?.screenBrightness ?: 0.5f
                        if (initialBrightness < 0) initialBrightness = 0.5f
                    } else {
                        gestureListener?.onVolumeGestureStart()
                    }
                } else if (absDxFromStart > absDyFromStart && absDxFromStart > 30) {
                    isSeeking = true
                    seekOffsetMs = 0L
                }
            }

            if (isSeeking) {
                seekOffsetMs = (dxFromStart * 100f).toLong()
                val targetMs = (currentPosition + seekOffsetMs)
                    .coerceIn(0, videoDuration.coerceAtLeast(0))
                gestureListener?.onSeekOverlay(formatTimeMs(targetMs))
                return true
            }

            if (isAdjustingVertical) {
                if (isInLeftHalf) {
                    val delta = distanceY / height.toFloat()
                    gestureListener?.onBrightnessChanged(delta)
                } else {
                    val totalDelta = (touchStartY - e2.y) / height.toFloat()
                    gestureListener?.onVolumeChanged(totalDelta)
                }
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            singleTapCallback?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val x = e.x
            val w = width.toFloat()
            val zone = when {
                x < w / 4f -> DoubleTapZone.PREVIOUS
                x > w * 3f / 4f -> DoubleTapZone.NEXT
                else -> DoubleTapZone.CENTER
            }
            gestureListener?.onDoubleTap(zone)
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
                    gestureListener?.onHideOverlay()
                }
                if (isSeeking) {
                    val clampedOffset = if (videoDuration > 0) {
                        seekOffsetMs.coerceIn(-currentPosition, videoDuration - currentPosition)
                    } else {
                        seekOffsetMs
                    }
                    gestureListener?.onSeekTo(clampedOffset)
                    isSeeking = false
                    seekOffsetMs = 0L
                    gestureListener?.onHideOverlay()
                }
                isAdjustingVertical = false
            }
        }
        if (isSeeking || isAdjustingVertical || isLongPressing) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
