package cn.vectory.syncplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GesturePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private companion object {
        const val VERTICAL_GESTURE_THRESHOLD = 20f
        const val EDGE_BACK_WIDTH_DP = 24f
        const val EDGE_BACK_THRESHOLD_DP = 48f
    }

    private val playerView = PlayerView(context).apply {
        controllerAutoShow = false
        useController = false
    }
    private val systemGestureExclusionRects = listOf(Rect(), Rect())

    var gestureListener: GestureListener? = null
    var speedMultiplier: Float = 2.0f
    var videoDuration: Long = 0L
    var currentPosition: Long = 0L
    var positionProvider: (() -> Long)? = null
    var durationProvider: (() -> Long)? = null
    var seekPrecisionProvider: (() -> Float)? = null
    private var isLongPressing = false
    private var isSeeking = false
    /** True while a horizontal seek gesture is active — the seek base is frozen. */
    val isGestureSeeking: Boolean get() = isSeeking
    private var seekOffsetMs = 0L
    private var seekBasePositionMs = 0L
    private var seekLastX = 0f
    private var seekTargetMs = 0L
    private var seekFactor = 1f
    private var seekCancelledUntilDown = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isInLeftHalf = false
    private var initialBrightness = 0f
    private var edgeBackStart: EdgeBackStart? = null
    private var edgeBackTriggered = false

    /**
     * The player UI is unconditionally immersive (system bars always hidden, video
     * fills the container), so edge-return is always enabled while playing.
     */
    var edgeBackEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateSystemGestureExclusionRects()
        }

    private var singleTapCallback: (() -> Unit)? = null

    interface GestureListener {
        fun onSpeedUpChanged(speedUp: Boolean)

        /**
         * Seek commit at gesture end. [basePositionMs] is the FROZEN position
         * captured when the gesture started; [offsetMs] is clamped relative to
         * that base (AND-014). The receiver must compute the final target as
         * `basePositionMs + offsetMs` — never from the live player position.
         */
        fun onSeekTo(offsetMs: Long, basePositionMs: Long)
        fun onSeekStarted(anchorMs: Long) {}
        fun onSeekCancelled() {}
        fun onVolumeGestureStart()
        fun onVolumeChanged(totalDelta: Float)
        fun onBrightnessChanged(delta: Float)
        /** Fractions preserve the existing zone mapping while locating the Aurora ripple. */
        fun onDoubleTap(zone: DoubleTapZone, xFraction: Float, yFraction: Float)
        fun onSeekOverlay(positionMs: Long, durationMs: Long) {}
        fun onLongPressSpeedOverlay(multiplier: Float) {}
        fun onHideOverlay() {}
        fun onEdgeBack() {}
    }

    enum class DoubleTapZone { PREVIOUS, NEXT, CENTER_TOP, CENTER_BOTTOM }

    fun setOnSingleTapCallback(callback: (() -> Unit)?) {
        singleTapCallback = callback
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) cancelSeekGesture()
        super.onLayout(changed, left, top, right, bottom)

        updateSystemGestureExclusionRects()
    }

    private fun updateSystemGestureExclusionRects() {
        if (!edgeBackEnabled || width == 0 || height == 0) {
            setSystemGestureExclusionRects(emptyList())
            return
        }
        val edgeWidth = minOf(edgeBackWidthPx, width)
        systemGestureExclusionRects[0].set(0, 0, edgeWidth, height)
        systemGestureExclusionRects[1].set(width - edgeWidth, 0, width, height)
        setSystemGestureExclusionRects(systemGestureExclusionRects)
    }

    private val edgeBackWidthPx: Int
        get() = (EDGE_BACK_WIDTH_DP * resources.displayMetrics.density).toInt()

    private val edgeBackThresholdPx: Float
        get() = EDGE_BACK_THRESHOLD_DP * resources.displayMetrics.density

    private enum class EdgeBackStart { LEFT, RIGHT }

    fun getPlayerView(): PlayerView = playerView

    fun cancelSeekGesture() {
        if (isSeeking) gestureListener?.onSeekCancelled()
        seekCancelledUntilDown = true
        isSeeking = false
        seekOffsetMs = 0L
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (edgeBackStart != null) return
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
            if (seekCancelledUntilDown) return false
            if (isLongPressing) return false

            val dxFromStart = e2.x - touchStartX
            val dyFromStart = e2.y - touchStartY
            val absDxFromStart = abs(dxFromStart)
            val absDyFromStart = abs(dyFromStart)

            // An edge-originating horizontal drag belongs to edge return before it can
            // become a seek. A clearly vertical drag releases the reservation so the
            // existing brightness/volume gestures keep their full behavior.
            edgeBackStart?.let { start ->
                if (edgeBackTriggered) return true
                if (absDyFromStart > absDxFromStart && absDyFromStart > VERTICAL_GESTURE_THRESHOLD) {
                    edgeBackStart = null
                } else {
                    val movesInward = when (start) {
                        EdgeBackStart.LEFT -> dxFromStart > 0f
                        EdgeBackStart.RIGHT -> dxFromStart < 0f
                    }
                    if (
                        movesInward &&
                        absDxFromStart >= edgeBackThresholdPx &&
                        absDxFromStart > absDyFromStart * 1.5f
                    ) {
                        edgeBackTriggered = true
                        gestureListener?.onEdgeBack()
                    }
                    return true
                }
            }

            if (!isSeeking && !isAdjustingVertical) {
                if (absDyFromStart > absDxFromStart && absDyFromStart > VERTICAL_GESTURE_THRESHOLD) {
                    isAdjustingVertical = true
                    isInLeftHalf = e1.x < width / 2f
                    if (isInLeftHalf) {
                        val window = (context as? android.app.Activity)?.window
                        initialBrightness = window?.attributes?.screenBrightness ?: 0.5f
                        if (initialBrightness < 0) initialBrightness = 0.5f
                    } else {
                        gestureListener?.onVolumeGestureStart()
                    }
                } else if (absDxFromStart > absDyFromStart && absDxFromStart > ViewConfiguration.get(context).scaledTouchSlop) {
                    videoDuration = durationProvider?.invoke()?.coerceAtLeast(0L) ?: videoDuration
                    isSeeking = true
                    seekOffsetMs = 0L
                    seekBasePositionMs = positionProvider?.invoke() ?: currentPosition
                    seekTargetMs = seekBasePositionMs
                    seekLastX = e2.x
                    gestureListener?.onSeekStarted(seekBasePositionMs)
                    seekFactor = 1f
                }
            }

            if (isSeeking) {
                // One viewport spans one duration, independent of pixel density or a 10-minute boundary.
                // Horizontal seek always uses the full-duration scale.
                seekTargetMs = resolveSegmentTargetMs(seekLastX, e2.x, width.coerceAtLeast(1), videoDuration, seekTargetMs, seekFactor)
                seekLastX = e2.x
                seekFactor = 1f
                seekOffsetMs = seekTargetMs - seekBasePositionMs
                gestureListener?.onSeekOverlay(seekTargetMs, videoDuration)
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
            return performClick()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            val w = width.toFloat()
            val h = height.toFloat()
            val zone = when {
                x < w / 4f -> DoubleTapZone.PREVIOUS
                x > w * 3f / 4f -> DoubleTapZone.NEXT
                y < h / 2f -> DoubleTapZone.CENTER_TOP
                else -> DoubleTapZone.CENTER_BOTTOM
            }
            gestureListener?.onDoubleTap(
                zone = zone,
                xFraction = if (w > 0f) (x / w).coerceIn(0f, 1f) else 0.5f,
                yFraction = if (h > 0f) (y / h).coerceIn(0f, 1f) else 0.5f,
            )
            return true
        }
    })

    private var isAdjustingVertical = false

    override fun performClick(): Boolean {
        val superHandled = super.performClick()
        val callback = singleTapCallback
        return if (callback != null) {
            callback()
            true
        } else {
            superHandled
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekCancelledUntilDown = false
                touchStartX = event.x
                touchStartY = event.y
                isAdjustingVertical = false
                isSeeking = false
                edgeBackTriggered = false
                edgeBackStart = if (edgeBackEnabled) {
                    when {
                        event.x <= edgeBackWidthPx -> EdgeBackStart.LEFT
                        event.x >= width - edgeBackWidthPx -> EdgeBackStart.RIGHT
                        else -> null
                    }
                } else {
                    null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    isLongPressing = false
                    gestureListener?.onSpeedUpChanged(false)
                    gestureListener?.onHideOverlay()
                }
                if (isSeeking) {
                    // AND-014: clamp relative to the FROZEN gesture-start base
                    // (seekBasePositionMs), not the live currentPosition — the
                    // commit target must match what the overlay previewed.
                    val clampedOffset = if (videoDuration > 0) {
                        seekOffsetMs.coerceIn(-seekBasePositionMs, videoDuration - seekBasePositionMs)
                    } else {
                        seekOffsetMs
                    }
                    if (event.action == MotionEvent.ACTION_UP) {
                        gestureListener?.onSeekTo(clampedOffset, seekBasePositionMs)
                    } else {
                        gestureListener?.onSeekCancelled()
                    }
                    isSeeking = false
                    seekOffsetMs = 0L
                    gestureListener?.onHideOverlay()
                }
                isAdjustingVertical = false
                edgeBackStart = null
            }
        }
        gestureDetector.onTouchEvent(event)
        if (isSeeking || isAdjustingVertical || isLongPressing) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}
