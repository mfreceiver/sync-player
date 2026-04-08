package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Typeface
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlin.math.abs


class GesturePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val playerView = PlayerView(context).apply {
        controllerAutoShow = false
        useController = false
    }
    private val overlayText = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        setBackgroundColor(0x80000000.toInt())
        setPadding(24, 12, 24, 12)
        visibility = GONE
    }
    private val iconOverlayLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0x80000000.toInt())
        setPadding(40, 24, 40, 24)
        visibility = GONE
    }
    private val iconOverlaySymbol = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val iconOverlayLabel = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, 8, 0, 0)
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
    private var overlayHideJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var singleTapCallback: (() -> Unit)? = null

    interface GestureListener {
        fun onSpeedUpChanged(speedUp: Boolean)
        fun onSeekTo(offsetMs: Long)
        fun onVolumeGestureStart()
        fun onVolumeChanged(totalDelta: Float)
        fun onBrightnessChanged(delta: Float)
        fun onDoubleTap()
        fun onTripleTap()
    }

    private var tapCount = 0
    private var tapJob: Job? = null

    fun setOnSingleTapCallback(callback: (() -> Unit)?) {
        singleTapCallback = callback
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlayText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        iconOverlayLayout.addView(iconOverlaySymbol)
        iconOverlayLayout.addView(iconOverlayLabel)
        addView(iconOverlayLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
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
                showOverlay(formatTimeMs(targetMs))
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

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tapCount++
            tapJob?.cancel()
            tapJob = CoroutineScope(Dispatchers.Main).launch {
                delay(280)
                when (tapCount) {
                    1 -> singleTapCallback?.invoke()
                    2 -> gestureListener?.onDoubleTap()
                    else -> gestureListener?.onTripleTap()
                }
                tapCount = 0
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
                    val clampedOffset = if (videoDuration > 0) {
                        seekOffsetMs.coerceIn(-currentPosition, videoDuration - currentPosition)
                    } else {
                        seekOffsetMs
                    }
                    gestureListener?.onSeekTo(clampedOffset)
                    isSeeking = false
                    seekOffsetMs = 0L
                    hideOverlay()
                }
                isAdjustingVertical = false
            }
        }
        if (isSeeking || isAdjustingVertical || isLongPressing) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }

    fun showVolumeOverlay(volumePercent: Int) {
        showOverlay("音量 $volumePercent%")
    }

    fun showBrightnessOverlay(brightnessPercent: Int) {
        showOverlay("亮度 $brightnessPercent%")
    }

    fun showGestureOverlay(text: String) {
        showOverlay(text)
    }

    fun showIconOverlay(symbol: String, label: String) {
        overlayHideJob?.cancel()
        hideOverlay()
        iconOverlaySymbol.text = symbol
        iconOverlayLabel.text = label
        iconOverlayLayout.visibility = VISIBLE
        overlayHideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(750)
            iconOverlayLayout.visibility = GONE
        }
    }

    private fun showOverlay(text: String) {
        overlayHideJob?.cancel()
        iconOverlayLayout.visibility = GONE
        overlayText.text = text
        overlayText.visibility = VISIBLE
        overlayHideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(750)
            overlayText.visibility = GONE
        }
    }

    private fun hideOverlay() {
        overlayHideJob?.cancel()
        overlayText.visibility = GONE
        iconOverlayLayout.visibility = GONE
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
