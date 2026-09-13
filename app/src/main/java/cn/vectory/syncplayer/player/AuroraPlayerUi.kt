package cn.vectory.syncplayer.player

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import java.math.BigDecimal

/** Player-private fixed art direction; it deliberately does not use dynamic theme colours. */
internal object AuroraTokens {
    val AuroraCanvas = Color(0xFF000000)
    val AuroraSurface = Color(0xFF101318)
    val AuroraSurfacePressed = Color(0xFF192330)
    val AuroraText = Color(0xFFF5F7FA)
    val AuroraTextSecondary = Color(0xFF9AA3AD)
    val AuroraBlue = Color(0xFF64B5F6)
    val AuroraPurple = Color(0xFF9575CD)
    val AuroraDestructive = Color(0xFFFF8A80)
    val AuroraPanelScrim = Color.Black.copy(alpha = 0.48f)
    val AuroraTrack = AuroraText.copy(alpha = 0.22f)
    val AuroraDisabled = AuroraTextSecondary.copy(alpha = 0.38f)
    val AuroraPanelFill = AuroraSurface.copy(alpha = 0.84f)
    val AuroraPillFill = AuroraSurface.copy(alpha = 0.88f)
    val AuroraControlFill = AuroraSurface.copy(alpha = 0.88f)
    val AuroraGlowBlue = AuroraBlue.copy(alpha = 0.10f)
    val AuroraGlowPurple = AuroraPurple.copy(alpha = 0.10f)
    val AuroraGlowClear = Color.Transparent
    val AuroraGradient = Brush.horizontalGradient(0f to AuroraBlue, 1f to AuroraPurple)
    val AuroraTopBandBrush = Brush.verticalGradient(listOf(AuroraGlowBlue, AuroraGlowClear))
    val AuroraBottomBandBrush = Brush.verticalGradient(listOf(AuroraGlowClear, AuroraGlowPurple))

    val AuroraEdgePadding = 16.dp
    val AuroraTopRowHeight = 48.dp
    val AuroraTopBarGap = 8.dp
    val AuroraChromeStackGap = 8.dp
    const val AuroraBandFraction = 0.10f
    val AuroraBottomInset = 16.dp
    val AuroraProgressTouchHeight = 44.dp
    val AuroraProgressHeight = 4.dp
    val AuroraMinProgressHeight = 1.dp
    val AuroraControlSize = 48.dp
    val AuroraPlaySize = 64.dp
    val AuroraControlGap = 16.dp
    val AuroraControlRowMaxWidth = 320.dp
    val AuroraIconSize = 24.dp
    val AuroraPlayIconSize = 30.dp
    val AuroraPillRadius = 24.dp
    val AuroraPillPaddingH = 16.dp
    val AuroraPillPaddingV = 10.dp
    val AuroraPanelRadius = 24.dp
    val AuroraPanelBorder = 1.5.dp
    val AuroraPanelMaxWidth = 560.dp
    const val AuroraPanelMaxHeightFraction = 0.80f
    val AuroraSettingsRowHeight = 48.dp
    val AuroraRecycleRowHeight = 52.dp
    val AuroraMagnifierHeight = 72.dp
    val AuroraPreviewWidth = 184.dp
    val AuroraPreviewMaxHeight = 104.dp
    val AuroraMagnifierNeedleWidth = 2.dp
    val AuroraTickMajorHeight = 24.dp
    val AuroraTickMinorHeight = 12.dp
    val AuroraPreviewGap = 8.dp
    val AuroraCompactHeightThreshold = 480.dp
    val AuroraDisplayTimeSize = 48.sp
    val AuroraFileNameSize = 15.sp
    val AuroraLabelSize = 12.sp
    val AuroraAuxSize = 13.sp
    const val FineScrubActivationDp = 12f
    const val FineScrubQuarterEnterDp = 40f
    const val FineScrubQuarterReturnDp = 32f
    const val FineScrubTenthEnterDp = 80f
    const val FineScrubTenthReturnDp = 72f
    const val FineScrubFactorFull = 1f
    const val FineScrubFactorQuarter = 0.25f
    const val FineScrubFactorTenth = 0.10f
    const val FineScrubTickRangeSeconds = 30
    val AuroraNumeralFont = FontFamily.SansSerif
    val AuroraNumeralWeight = FontWeight.SemiBold
    val AuroraNumeralStyle = TextStyle(
        fontFamily = AuroraNumeralFont,
        fontWeight = AuroraNumeralWeight,
        fontFeatureSettings = "tnum",
    )

    const val AuroraRevealStaggerMs = 40
    val AuroraControlSlideSpring: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.78f, stiffness = 600f)
    val AuroraControlSlideOut: FiniteAnimationSpec<IntOffset> = tween(durationMillis = 160, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraTopSlideIn: FiniteAnimationSpec<IntOffset> = tween(durationMillis = 260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val AuroraTopSlideOut: FiniteAnimationSpec<IntOffset> = tween(durationMillis = 160, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraFadeIn: FiniteAnimationSpec<Float> = tween(durationMillis = 160, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraFadeOut: FiniteAnimationSpec<Float> = tween(durationMillis = 120, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    val AuroraOverlayAlpha: FiniteAnimationSpec<Float> = tween(durationMillis = 90, easing = LinearEasing)
    val AuroraOverlayFadeOut: FiniteAnimationSpec<Float> = tween(durationMillis = 160, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val AuroraPanelAlpha: FiniteAnimationSpec<Float> = tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val AuroraPanelExpandSpring: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.82f, stiffness = 520f)
    val AuroraPanelScaleSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 520f)
    val AuroraPanelShrink: FiniteAnimationSpec<IntSize> = tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraMagnifierExpandSpring: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.82f, stiffness = 520f)
    val AuroraMagnifierScaleSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 520f)
    val AuroraMagnifierShrink: FiniteAnimationSpec<IntSize> = tween(durationMillis = 140, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraIconMorph: FiniteAnimationSpec<Float> = tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val AuroraIconExit: FiniteAnimationSpec<Float> = tween(durationMillis = 90, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraPillEnter: FiniteAnimationSpec<Float> = tween(durationMillis = 150, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    val AuroraPillExit: FiniteAnimationSpec<Float> = tween(durationMillis = 110, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    val AuroraRipple: FiniteAnimationSpec<Float> = tween(durationMillis = 360, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val AuroraGlowBreath = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 3_200, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    )
    val AuroraProgressFill: FiniteAnimationSpec<Float> = tween(durationMillis = 180, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    val AuroraPress: FiniteAnimationSpec<Float> = tween(durationMillis = 80, easing = androidx.compose.animation.core.FastOutSlowInEasing)
}

internal enum class SeekSource { NONE, PROGRESS_BAR, GESTURE }

internal data class AuroraChromeVisibility(
    val showTopRow: Boolean,
    val showProgressZone: Boolean,
    val showControlRow: Boolean,
    val showMinimizedProgress: Boolean,
)

internal fun resolveAuroraChromeVisibility(
    isFullscreen: Boolean,
    showControls: Boolean,
    seekSource: SeekSource,
): AuroraChromeVisibility {
    // FULLSCREEN no longer suppresses the control row: a single tap toggles
    // showControls, and the full bottom control area follows it in both modes.
    val showControlRow = showControls
    val showProgressZone = showControlRow || seekSource != SeekSource.NONE
    return AuroraChromeVisibility(
        showTopRow = isFullscreen || showControls,
        showProgressZone = showProgressZone,
        showControlRow = showControlRow,
        showMinimizedProgress = !showProgressZone,
    )
}

internal fun stabilizedFineScrubFactor(dragDownDp: Float, previous: Float): Float = when (previous) {
    AuroraTokens.FineScrubFactorTenth -> if (dragDownDp <= AuroraTokens.FineScrubTenthReturnDp) {
        AuroraTokens.FineScrubFactorQuarter
    } else {
        AuroraTokens.FineScrubFactorTenth
    }
    AuroraTokens.FineScrubFactorQuarter -> when {
        dragDownDp >= AuroraTokens.FineScrubTenthEnterDp -> AuroraTokens.FineScrubFactorTenth
        dragDownDp <= AuroraTokens.FineScrubQuarterReturnDp -> AuroraTokens.FineScrubFactorFull
        else -> AuroraTokens.FineScrubFactorQuarter
    }
    else -> when {
        dragDownDp >= AuroraTokens.FineScrubTenthEnterDp -> AuroraTokens.FineScrubFactorTenth
        dragDownDp >= AuroraTokens.FineScrubQuarterEnterDp -> AuroraTokens.FineScrubFactorQuarter
        else -> AuroraTokens.FineScrubFactorFull
    }
}

internal fun resolveSegmentTargetMs(
    segmentStartX: Float,
    currentX: Float,
    widthPx: Int,
    durationMs: Long,
    segmentStartTargetMs: Long,
    factor: Float,
): Long {
    val safeWidth = max(widthPx, 1).toDouble()
    val safeDuration = max(durationMs, 0L)
    val raw = segmentStartTargetMs.toDouble() +
        ((currentX - segmentStartX).toDouble() / safeWidth) * safeDuration.toDouble() * factor.toDouble()
    return raw.roundToLong().coerceIn(0L, safeDuration)
}

@Composable
internal fun AuroraBands() {
    val transition = rememberInfiniteTransition(label = "aurora_bands")
    val alpha by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.00f,
        animationSpec = AuroraTokens.AuroraGlowBreath,
        label = "aurora_breath",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(AuroraTokens.AuroraBandFraction)
                .background(AuroraTokens.AuroraTopBandBrush)
                .graphicsLayer(alpha = alpha),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(AuroraTokens.AuroraBandFraction)
                .background(AuroraTokens.AuroraBottomBandBrush)
                .graphicsLayer(alpha = alpha),
        )
    }
}

@Composable
internal fun AuroraTopRow(fileName: String, visible: Boolean, onExit: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = AuroraTokens.AuroraFadeIn) +
            slideInVertically(animationSpec = AuroraTokens.AuroraTopSlideIn, initialOffsetY = { -it / 3 }),
        exit = fadeOut(animationSpec = AuroraTokens.AuroraFadeOut) +
            slideOutVertically(animationSpec = AuroraTokens.AuroraTopSlideOut, targetOffsetY = { -it / 4 }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(AuroraTokens.AuroraTopRowHeight)
                .padding(horizontal = AuroraTokens.AuroraEdgePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(AuroraTokens.AuroraControlSize),
                shape = CircleShape,
                color = AuroraTokens.AuroraControlFill,
            ) {
                IconButton(onClick = onExit, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.player_exit),
                        tint = AuroraTokens.AuroraText,
                        modifier = Modifier.size(AuroraTokens.AuroraIconSize),
                    )
                }
            }
            Spacer(Modifier.width(AuroraTokens.AuroraTopBarGap))
            Text(
                text = fileName,
                color = AuroraTokens.AuroraText,
                fontSize = AuroraTokens.AuroraFileNameSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun AuroraBottomArea(
    visibility: AuroraChromeVisibility,
    viewModel: PlayerViewModel,
    exoPlayer: ExoPlayer,
    seekSource: SeekSource,
    durationMs: Long,
    gesturePositionMs: Long,
    isPlaying: Boolean,
    onOpenSettings: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onRotate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = AuroraTokens.AuroraEdgePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (visibility.showProgressZone) {
            AuroraProgressZone(viewModel, exoPlayer, seekSource, durationMs, gesturePositionMs)
        }
        if (visibility.showProgressZone && visibility.showControlRow) {
            Spacer(Modifier.height(AuroraTokens.AuroraChromeStackGap))
        }
        if (visibility.showControlRow) {
            AuroraControlRow(
                isPlaying = isPlaying,
                onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onOpenSettings = onOpenSettings,
                onBack10 = onBack10,
                onForward10 = onForward10,
                onRotate = onRotate,
            )
        }
        if (visibility.showProgressZone || visibility.showControlRow) {
            Spacer(Modifier.height(AuroraTokens.AuroraBottomInset))
        }
    }
}

@Composable
internal fun AuroraControlRow(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onRotate: () -> Unit,
) {
    var revealPlay by remember { mutableStateOf(false) }
    var revealBack by remember { mutableStateOf(false) }
    var revealForward by remember { mutableStateOf(false) }
    var revealSettings by remember { mutableStateOf(false) }
    var revealRotate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        revealPlay = true
        delay(AuroraTokens.AuroraRevealStaggerMs.toLong())
        revealBack = true
        delay(AuroraTokens.AuroraRevealStaggerMs.toLong())
        revealForward = true
        delay(AuroraTokens.AuroraRevealStaggerMs.toLong())
        revealSettings = true
        delay(AuroraTokens.AuroraRevealStaggerMs.toLong())
        revealRotate = true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = AuroraTokens.AuroraControlRowMaxWidth),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraControlSlot(AuroraTokens.AuroraControlSize, revealSettings) {
            AuroraControlButton(AuroraTokens.AuroraControlSize, Icons.Filled.Settings, stringResource(R.string.player_a11y_open_settings), onOpenSettings)
        }
        AuroraControlSlot(AuroraTokens.AuroraControlSize, revealBack) {
            AuroraControlButton(AuroraTokens.AuroraControlSize, Icons.Filled.SkipPrevious, stringResource(R.string.player_a11y_seek_backward_10s), onBack10)
        }
        AuroraControlSlot(AuroraTokens.AuroraPlaySize, revealPlay) {
            AuroraPlayButton(isPlaying, onTogglePlay)
        }
        AuroraControlSlot(AuroraTokens.AuroraControlSize, revealForward) {
            AuroraControlButton(AuroraTokens.AuroraControlSize, Icons.Filled.SkipNext, stringResource(R.string.player_a11y_seek_forward_10s), onForward10)
        }
        AuroraControlSlot(AuroraTokens.AuroraControlSize, revealRotate) {
            AuroraControlButton(AuroraTokens.AuroraControlSize, Icons.Filled.ScreenRotation, stringResource(R.string.player_a11y_rotation), onRotate)
        }
    }
}

@Composable
private fun AuroraControlSlot(size: Dp, visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = AuroraTokens.AuroraFadeIn) +
                slideInVertically(animationSpec = AuroraTokens.AuroraControlSlideSpring, initialOffsetY = { it / 3 }),
            exit = fadeOut(animationSpec = AuroraTokens.AuroraFadeOut) +
                slideOutVertically(animationSpec = AuroraTokens.AuroraControlSlideOut, targetOffsetY = { it / 4 }),
        ) {
            content()
        }
    }
}

@Composable
private fun AuroraControlButton(size: androidx.compose.ui.unit.Dp, icon: ImageVector, description: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = AuroraTokens.AuroraPress,
        label = "aurora_control_press",
    )
    Surface(
        modifier = Modifier.size(size).graphicsLayer(scaleX = pressedScale, scaleY = pressedScale),
        shape = CircleShape,
        color = AuroraTokens.AuroraControlFill,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize(), interactionSource = interactionSource) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = AuroraTokens.AuroraText,
                modifier = Modifier.size(AuroraTokens.AuroraIconSize),
            )
        }
    }
}

@Composable
private fun AuroraPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = AuroraTokens.AuroraPress,
        label = "aurora_play_press",
    )
    Surface(
        modifier = Modifier
            .size(AuroraTokens.AuroraPlaySize)
            .graphicsLayer(scaleX = pressedScale, scaleY = pressedScale),
        shape = CircleShape,
        color = AuroraTokens.AuroraControlFill,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize(), interactionSource = interactionSource) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    ((fadeIn(animationSpec = AuroraTokens.AuroraIconMorph) +
                        scaleIn(animationSpec = AuroraTokens.AuroraIconMorph, initialScale = 0.92f)) togetherWith
                        (fadeOut(animationSpec = AuroraTokens.AuroraIconExit) +
                            scaleOut(animationSpec = AuroraTokens.AuroraIconExit, targetScale = 0.82f)))
                        .using(SizeTransform(clip = false))
                },
                label = "aurora_play_icon",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                    tint = AuroraTokens.AuroraText,
                    modifier = Modifier.size(AuroraTokens.AuroraPlayIconSize),
                )
            }
        }
    }
}

@Composable
internal fun AuroraProgressZone(
    viewModel: PlayerViewModel,
    exoPlayer: ExoPlayer,
    seekSource: SeekSource,
    durationMs: Long,
    gesturePositionMs: Long,
) {
    val scrubState by viewModel.progressScrubState.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val safeDuration = durationMs.coerceAtLeast(0L)
    val progressDescription = stringResource(R.string.player_a11y_fine_scrub_progress)
    val displayedPosition = when (seekSource) {
        SeekSource.PROGRESS_BAR -> scrubState.targetPositionMs
        SeekSource.GESTURE -> gesturePositionMs.coerceIn(0L, safeDuration)
        SeekSource.NONE -> currentPosition.coerceIn(0L, safeDuration)
    }
    val progress = if (safeDuration > 0) displayedPosition.toFloat() / safeDuration.toFloat() else 0f
    val progressStateDescription = "${formatDuration(displayedPosition)} / ${formatDuration(safeDuration)}"
    fun commitBy(delta: Long) {
        val target = (currentPosition + delta).coerceIn(0L, safeDuration)
        viewModel.onProgressScrubStarted(target)
        viewModel.onProgressScrubFinished(target)
        exoPlayer.seekTo(target)
    }
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.player_a11y_seek_backward_10s)) { commitBy(-10_000L); true },
        CustomAccessibilityAction(stringResource(R.string.player_a11y_seek_forward_10s)) { commitBy(10_000L); true },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AuroraTokens.AuroraProgressTouchHeight)
            .testTag("player_aurora_progress_zone")
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(displayedPosition.toFloat(), 0f..safeDuration.toFloat())
                contentDescription = progressDescription
                stateDescription = progressStateDescription
                customActions = actions
            }
            .pointerInput(safeDuration) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val safeWidth = size.width.coerceAtLeast(1)
                        val anchor = ((down.position.x / safeWidth.toFloat()) * safeDuration.toDouble())
                            .roundToLong()
                            .coerceIn(0L, safeDuration)
                        var segmentStartX = down.position.x
                        var segmentStartTarget = anchor
                        var lastTarget = anchor
                        var factor = AuroraTokens.FineScrubFactorFull
                        var isFineMode = false
                        viewModel.onProgressScrubStarted(anchor)
                        val finished = drag(down.id) { change ->
                            val dragDownDp = ((change.position.y - down.position.y).coerceAtLeast(0f)) / density.density
                            if (dragDownDp >= AuroraTokens.FineScrubActivationDp) isFineMode = true
                            val nextFactor = if (isFineMode) {
                                stabilizedFineScrubFactor(dragDownDp, factor)
                            } else {
                                AuroraTokens.FineScrubFactorFull
                            }
                            if (nextFactor != factor) {
                                factor = nextFactor
                                segmentStartX = change.position.x
                                segmentStartTarget = lastTarget
                            } else {
                                lastTarget = resolveSegmentTargetMs(
                                    segmentStartX = segmentStartX,
                                    currentX = change.position.x,
                                    widthPx = safeWidth,
                                    durationMs = safeDuration,
                                    segmentStartTargetMs = segmentStartTarget,
                                    factor = factor,
                                )
                            }
                            viewModel.onProgressScrubChanged(lastTarget, factor, isFineMode)
                            change.consume()
                        }
                        if (finished) {
                            viewModel.onProgressScrubFinished(lastTarget)
                            exoPlayer.seekTo(lastTarget)
                        } else {
                            viewModel.onProgressScrubCancelled()
                        }
                    }
                } catch (e: CancellationException) {
                    viewModel.onProgressScrubCancelled()
                    throw e
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(AuroraTokens.AuroraProgressHeight).clip(RoundedCornerShape(AuroraTokens.AuroraPillRadius)).background(AuroraTokens.AuroraTrack)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(AuroraTokens.AuroraGradient))
        }
    }
}

@Composable
internal fun AuroraMinimizedProgressBar(viewModel: PlayerViewModel, durationMs: Long, onSeekBy: (Long) -> Unit) {
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val safeDuration = durationMs.coerceAtLeast(0L)
    val clampedPosition = currentPosition.coerceIn(0L, safeDuration)
    val rawProgress = if (safeDuration > 0) clampedPosition.toFloat() / safeDuration.toFloat() else 0f
    val progress by animateFloatAsState(rawProgress, animationSpec = AuroraTokens.AuroraProgressFill, label = "aurora_min_progress")
    val progressDescription = stringResource(R.string.player_a11y_fine_scrub_progress)
    val progressStateDescription = "${formatDuration(clampedPosition)} / ${formatDuration(safeDuration)}"
    val back10Description = stringResource(R.string.player_a11y_seek_backward_10s)
    val forward10Description = stringResource(R.string.player_a11y_seek_forward_10s)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AuroraTokens.AuroraProgressTouchHeight)
            .testTag("player_aurora_minimized_progress")
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clampedPosition.toFloat(), 0f..safeDuration.toFloat())
                contentDescription = progressDescription
                stateDescription = progressStateDescription
                customActions = listOf(
                    CustomAccessibilityAction(back10Description) { onSeekBy(-10_000L); true },
                    CustomAccessibilityAction(forward10Description) { onSeekBy(10_000L); true },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().height(AuroraTokens.AuroraMinProgressHeight).background(AuroraTokens.AuroraTrack)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(AuroraTokens.AuroraGradient))
        }
    }
}

@Composable
internal fun AuroraSettingsModal(
    visible: Boolean,
    isFullscreen: Boolean,
    showControls: Boolean,
    videoId: Long?,
    playbackSpeed: Float,
    isTrashed: Boolean,
    errorSequence: Long?,
    onDismiss: () -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleTrash: ((Long) -> Unit) -> Unit,
    onTrashSucceeded: (Long) -> Unit,
) {
    var trashTogglePending by remember { mutableStateOf(false) }
    var errorSequenceAtStart by remember { mutableStateOf<Long?>(null) }
    val openingVideoId = remember(visible) { videoId }
    LaunchedEffect(visible, showControls, videoId) {
        if (!visible) {
            trashTogglePending = false
        } else if (!showControls || videoId == null || videoId != openingVideoId) {
            trashTogglePending = false
            onDismiss()
        }
    }
    LaunchedEffect(errorSequence, trashTogglePending) {
        if (trashTogglePending && errorSequence != null && errorSequence != errorSequenceAtStart) {
            trashTogglePending = false
        }
    }
    DisposableEffect(Unit) { onDispose { trashTogglePending = false } }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = AuroraTokens.AuroraFadeIn),
        exit = fadeOut(animationSpec = AuroraTokens.AuroraFadeOut),
    ) {
        BackHandler(enabled = visible) { onDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AuroraTokens.AuroraPanelScrim)
                .navigationBarsPadding()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier,
                color = Color.Transparent,
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = AuroraTokens.AuroraPanelExpandSpring,
                    ) + scaleIn(
                        animationSpec = AuroraTokens.AuroraPanelScaleSpring,
                        initialScale = 0.96f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
                    ) + fadeIn(animationSpec = AuroraTokens.AuroraPanelAlpha),
                    exit = shrinkVertically(animationSpec = AuroraTokens.AuroraPanelShrink) +
                        fadeOut(animationSpec = AuroraTokens.AuroraFadeOut),
                ) {
                    Surface(
                        modifier = Modifier
                            // Content-adaptive width: the panel is as wide as its widest
                            // row (max intrinsic width of the radio rows / trash row /
                            // dividers), NOT min(screen, 560dp). Order matters:
                            // width(IntrinsicSize.Max) is outside widthIn(max), so the
                            // intrinsic query itself is capped at AuroraPanelMaxWidth
                            // (and by incoming screen constraints at measure time).
                            // Rows/dividers keep fillMaxWidth — under this parent they
                            // fill the FINAL panel width instead of inflating it.
                            // verticalScroll only unbounds height, so horizontal
                            // intrinsics pass through untouched.
                            .width(IntrinsicSize.Max)
                            .widthIn(max = AuroraTokens.AuroraPanelMaxWidth)
                            .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * AuroraTokens.AuroraPanelMaxHeightFraction)
                            .padding(horizontal = AuroraTokens.AuroraEdgePadding)
                            .clip(RoundedCornerShape(AuroraTokens.AuroraPanelRadius))
                            .border(AuroraTokens.AuroraPanelBorder, AuroraTokens.AuroraGradient, RoundedCornerShape(AuroraTokens.AuroraPanelRadius))
                            .testTag("player_aurora_settings_panel")
                            .clickable(onClick = {}),
                        color = AuroraTokens.AuroraPanelFill,
                        shape = RoundedCornerShape(AuroraTokens.AuroraPanelRadius),
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(AuroraTokens.AuroraEdgePadding),
                            verticalArrangement = Arrangement.spacedBy(AuroraTokens.AuroraTopBarGap),
                        ) {
                    Text(stringResource(R.string.player_settings_group_direction), color = AuroraTokens.AuroraTextSecondary, style = MaterialTheme.typography.labelMedium)
                    AuroraRadioItem(!isFullscreen, stringResource(R.string.player_a11y_rotation)) { onDirectionChange(false) }
                    AuroraRadioItem(isFullscreen, stringResource(R.string.player_a11y_fullscreen)) { onDirectionChange(true) }
                    HorizontalDivider(color = AuroraTokens.AuroraTrack)
                    Text(stringResource(R.string.player_settings_group_speed), color = AuroraTokens.AuroraTextSecondary, style = MaterialTheme.typography.labelMedium)
                    AuroraRadioItem(playbackSpeed == 1f, stringResource(R.string.player_speed_value_1x)) { onSpeedChange(1f) }
                    AuroraRadioItem(playbackSpeed == 1.5f, stringResource(R.string.player_speed_value_1_5x)) { onSpeedChange(1.5f) }
                    AuroraRadioItem(playbackSpeed == 2f, stringResource(R.string.player_speed_value_2x)) { onSpeedChange(2f) }
                    HorizontalDivider(color = AuroraTokens.AuroraTrack)
                    val restore = isTrashed
                    val label = if (restore) stringResource(R.string.player_settings_restore) else stringResource(R.string.player_settings_recycle)
                    val description = if (restore) stringResource(R.string.player_a11y_restore_action) else stringResource(R.string.player_a11y_recycle_action)
                    val color = if (restore) AuroraTokens.AuroraBlue else AuroraTokens.AuroraDestructive
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AuroraTokens.AuroraRecycleRowHeight)
                            .testTag("player_aurora_recycle_action")
                            .semantics { role = Role.Button; contentDescription = description }
                            .clickable(enabled = videoId != null && !trashTogglePending) {
                                if (trashTogglePending || videoId == null) return@clickable
                                trashTogglePending = true
                                errorSequenceAtStart = errorSequence
                                onToggleTrash { completedId ->
                                    trashTogglePending = false
                                    onTrashSucceeded(completedId)
                                    onDismiss()
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (restore) Icons.Filled.RestoreFromTrash else Icons.Filled.Delete,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(AuroraTokens.AuroraIconSize),
                        )
                        Spacer(Modifier.width(AuroraTokens.AuroraTopBarGap))
                        Column {
                            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
                            if (!restore) Text(stringResource(R.string.player_settings_recycle_hint), color = AuroraTokens.AuroraTextSecondary, fontSize = AuroraTokens.AuroraLabelSize)
                        }
                    }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuroraRadioItem(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AuroraTokens.AuroraSettingsRowHeight)
            .testTag("player_aurora_radio")
            .semantics { this.selected = selected; role = Role.RadioButton }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.MoreHoriz else Icons.Filled.Block,
            contentDescription = null,
            tint = if (selected) AuroraTokens.AuroraBlue else AuroraTokens.AuroraTextSecondary,
            modifier = Modifier.size(AuroraTokens.AuroraIconSize),
        )
        Spacer(Modifier.width(AuroraTokens.AuroraTopBarGap))
        Text(label, color = if (selected) AuroraTokens.AuroraText else AuroraTokens.AuroraTextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AuroraSeekPreviewHost(
    seekSource: SeekSource,
    progressScrubState: StateFlow<ProgressScrubUiState>,
    gesturePositionMs: Long,
    durationMs: Long,
    bottomChromeHeightPx: Int,
    videoUri: String?,
    previewExtractor: SeekPreviewExtractor,
) {
    val scrubState by progressScrubState.collectAsStateWithLifecycle()
    val hostView = LocalView.current
    var lastHapticFactor by remember { mutableStateOf(AuroraTokens.FineScrubFactorFull) }
    LaunchedEffect(seekSource, scrubState.isActive, scrubState.factor) {
        if (
            seekSource == SeekSource.PROGRESS_BAR &&
            scrubState.isActive &&
            scrubState.factor != lastHapticFactor
        ) {
            hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastHapticFactor = scrubState.factor
    }
    val latestTarget by rememberUpdatedState(
        when (seekSource) {
            SeekSource.PROGRESS_BAR -> scrubState.targetPositionMs
            SeekSource.GESTURE -> gesturePositionMs
            SeekSource.NONE -> 0L
        },
    )
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewPosition by remember { mutableStateOf(0L) }
    var lastExtractedPosition by remember { mutableStateOf(Long.MIN_VALUE) }
    LaunchedEffect(seekSource, videoUri) {
        if (seekSource == SeekSource.NONE || videoUri == null) {
            delay(100L)
            bitmap = null
            lastExtractedPosition = Long.MIN_VALUE
            return@LaunchedEffect
        }
        try {
            previewExtractor.beginSession(android.net.Uri.parse(videoUri))
            while (isActive) {
                val target = latestTarget.coerceIn(0L, durationMs.coerceAtLeast(0L))
                previewPosition = target
                if (target != lastExtractedPosition) {
                    val cached = previewExtractor.getCachedFrame(videoUri, target)
                    bitmap = cached ?: previewExtractor.extractFrameAtPosition(videoUri, target)
                    lastExtractedPosition = target
                }
                delay(if (scrubState.isFineMode) 50L else 100L)
            }
        } finally {
            withContext(NonCancellable) { previewExtractor.endSession() }
        }
    }
    // Horizontal follow uses the IMMEDIATE seek target (latestTarget), not the
    // throttled previewPosition, so the coarse preview never lags the thumb.
    // duration <= 0 collapses the fraction to 0.
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val seekFraction = if (safeDurationMs > 0L) {
        latestTarget.coerceIn(0L, safeDurationMs).toFloat() / safeDurationMs.toFloat()
    } else {
        0f
    }
    when (seekSource) {
        SeekSource.PROGRESS_BAR -> if (scrubState.isFineMode) {
            FineScrubMagnifier(scrubState, bitmap, durationMs)
        } else {
            AuroraCoarsePreview(bitmap, previewPosition, durationMs, seekFraction, bottomChromeHeightPx)
        }
        SeekSource.GESTURE -> AuroraCoarsePreview(bitmap, previewPosition, durationMs, seekFraction, bottomChromeHeightPx)
        SeekSource.NONE -> Unit
    }
}

/**
 * Resolved geometry for the coarse seek preview: the effective (possibly shrunk)
 * width AND the horizontal offset, both derived from the same safe-viewport math
 * so the UI's `.width()` and `.offset()` can never drift apart.
 */
internal data class PreviewGeometry(
    val effectiveWidthPx: Float,
    val offsetXPx: Float,
)

/**
 * Pure geometry for the coarse seek preview, in the preview container's coordinate
 * space (the player root box).
 *
 * - Safe viewport (clamping range): [navigationLeftPx] .. rootWidthPx - [navigationRightPx]
 *   — the nav-inset-safe area, matching where the bottom progress area lives.
 * - Track span (mirrors AuroraBottomArea: navigationBarsPadding() then
 *   AuroraEdgePadding): trackLeft = navLeft + [edgePaddingPx],
 *   trackRight = root - navRight - [edgePaddingPx]; a degenerate track
 *   (trackRight < trackLeft) collapses to zero track width.
 * - Non-finite [fraction] (NaN/±Infinity) is treated as 0, then clamped to 0..1.
 * - effectiveWidthPx = min(nominal, safeRight - safeLeft) floored at 0, so the
 *   preview never overflows the safe viewport on narrow screens.
 * - offsetXPx keeps the preview centered over the thumb, clamped so that
 *   offsetXPx >= safeLeft AND offsetXPx + effectiveWidthPx <= safeRight.
 */
internal fun resolvePreviewGeometry(
    rootWidthPx: Float,
    navigationLeftPx: Float,
    navigationRightPx: Float,
    edgePaddingPx: Float,
    fraction: Float,
    nominalPreviewWidthPx: Float,
): PreviewGeometry {
    val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val safeLeft = navigationLeftPx
    val safeRight = rootWidthPx - navigationRightPx
    val effectiveWidth = min(nominalPreviewWidthPx, (safeRight - safeLeft).coerceAtLeast(0f))
    val trackLeft = navigationLeftPx + edgePaddingPx
    val trackRight = max(safeRight - edgePaddingPx, trackLeft)
    val thumbX = trackLeft + (trackRight - trackLeft) * safeFraction
    val maxLeft = max(safeLeft, safeRight - effectiveWidth)
    val offset = (thumbX - effectiveWidth / 2f).coerceIn(safeLeft, maxLeft)
    return PreviewGeometry(effectiveWidthPx = effectiveWidth, offsetXPx = offset)
}

/**
 * Vertical clearance between the preview's bottom edge and the container bottom,
 * in px: measured chrome height + [gapPx] ONLY.
 *
 * The chrome height is MEASURED (PlayerScreen's onSizeChanged around the bottom
 * area) and therefore ALREADY contains AuroraBottomArea's navigationBarsPadding().
 * The nav-bar inset must NOT be added on top of it — the signature deliberately
 * omits an inset parameter to make that contract explicit; actual regression
 * protection comes from the non-zero navigation-inset layout test.
 */
internal fun resolvePreviewBottomClearancePx(
    bottomChromeHeightPx: Int,
    gapPx: Float,
): Float = bottomChromeHeightPx + gapPx

@Composable
private fun AuroraCoarsePreview(
    bitmap: Bitmap?,
    positionMs: Long,
    durationMs: Long,
    seekFraction: Float,
    bottomChromeHeightPx: Int,
) {
    // Horizontal follow: the preview tracks the progress thumb instead of staying
    // pinned to the horizontal center. The preview host fills the same player root
    // box as the bottom progress area, so this container's coordinate space matches
    // the real track's window coordinates. resolvePreviewGeometry derives BOTH the
    // clamping viewport (nav-inset-safe area) and the track span from the same
    // insets, and returns the effective width + offset that the Surface consumes
    // directly — width and offset can never disagree.
    //
    // Vertical anchor: the preview's bottom edge sits ABOVE the bottom chrome
    // (progress zone + optional control row) with AuroraPreviewGap clearance,
    // instead of flush against the container bottom. [bottomChromeHeightPx] is the
    // chrome's MEASURED height (PlayerScreen: onSizeChanged on the bottom-area
    // wrapper), never a guessed constant — and that measurement already includes
    // AuroraBottomArea's navigationBarsPadding(), so the clearance below adds the
    // gap only. Both visibility cases (gesture seek with the control row hidden,
    // progress-bar seek with full chrome) and both orientations resolve correctly.
    // GESTURE and coarse PROGRESS_BAR paths share this composable.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val navigationBars = WindowInsets.navigationBars
        // RTL is a known residual risk: absolute LTR X coordinates, consistent with
        // the progress fill/click math which is also LTR-only (no half-RTL support).
        val geometry = resolvePreviewGeometry(
            rootWidthPx = with(density) { maxWidth.toPx() },
            navigationLeftPx = navigationBars.getLeft(density, LayoutDirection.Ltr).toFloat(),
            navigationRightPx = navigationBars.getRight(density, LayoutDirection.Ltr).toFloat(),
            edgePaddingPx = with(density) { AuroraTokens.AuroraEdgePadding.toPx() },
            fraction = seekFraction,
            nominalPreviewWidthPx = with(density) { AuroraTokens.AuroraPreviewWidth.toPx() },
        )
        // Bottom clearance: measured chrome height + gap ONLY. The chrome's measured
        // height already includes AuroraBottomArea's navigationBarsPadding()
        // (PlayerScreen's onSizeChanged measures the wrapper around the padded
        // Column), so adding the nav-bar inset again here would double-count it on
        // gesture-nav devices and lift the preview one nav bar too high. Applied as
        // a negative offset on a bottom-aligned child, so the preview's bottom edge
        // lands exactly (chrome top - gap) in root coordinates.
        val bottomClearancePx = resolvePreviewBottomClearancePx(
            bottomChromeHeightPx = bottomChromeHeightPx,
            gapPx = with(density) { AuroraTokens.AuroraPreviewGap.toPx() },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .absoluteOffset { IntOffset(geometry.offsetXPx.roundToInt(), -bottomClearancePx.roundToInt()) }
                .testTag("player_aurora_coarse_preview")
                .width(with(density) { geometry.effectiveWidthPx.toDp() })
                .heightIn(max = AuroraTokens.AuroraPreviewMaxHeight),
            color = AuroraTokens.AuroraPanelFill,
            shape = RoundedCornerShape(AuroraTokens.AuroraPanelRadius),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().weight(1f, fill = false))
                } else {
                    Box(Modifier.fillMaxWidth().height(AuroraTokens.AuroraPreviewMaxHeight))
                }
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    color = AuroraTokens.AuroraText,
                    style = AuroraTokens.AuroraNumeralStyle.copy(fontSize = AuroraTokens.AuroraAuxSize),
                    modifier = Modifier.padding(vertical = AuroraTokens.AuroraPreviewGap),
                )
            }
        }
    }
}

@Composable
internal fun FineScrubMagnifier(state: ProgressScrubUiState, bitmap: Bitmap?, durationMs: Long) {
    val deltaLabel = stringResource(R.string.player_fine_scrub_delta, state.deltaSeconds)
    val speedLabel = when (state.factor) {
        AuroraTokens.FineScrubFactorTenth -> stringResource(R.string.player_a11y_fine_scrub_speed_tenth)
        AuroraTokens.FineScrubFactorQuarter -> stringResource(R.string.player_a11y_fine_scrub_speed_quarter)
        else -> stringResource(R.string.player_a11y_fine_scrub_speed_full)
    }
    val magnifierDescription = stringResource(
        R.string.player_a11y_fine_scrub_magnifier,
        formatDuration(state.targetPositionMs),
        deltaLabel,
        speedLabel,
    )
    AnimatedVisibility(
        visible = state.isFineMode,
        enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = AuroraTokens.AuroraMagnifierExpandSpring) +
            scaleIn(animationSpec = AuroraTokens.AuroraMagnifierScaleSpring, initialScale = 0.96f) + fadeIn(animationSpec = AuroraTokens.AuroraFadeIn),
        exit = shrinkVertically(animationSpec = AuroraTokens.AuroraMagnifierShrink) + fadeOut(animationSpec = AuroraTokens.AuroraFadeOut),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val compact = maxHeight < AuroraTokens.AuroraCompactHeightThreshold
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuroraTokens.AuroraEdgePadding)
                    .semantics(mergeDescendants = true) {
                        contentDescription = magnifierDescription
                    }
                    .testTag("player_aurora_fine_magnifier"),
                shape = RoundedCornerShape(AuroraTokens.AuroraPanelRadius),
                color = AuroraTokens.AuroraPanelFill,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(AuroraTokens.AuroraEdgePadding)) {
                    Text(
                        formatDuration(state.targetPositionMs),
                        color = AuroraTokens.AuroraText,
                        style = AuroraTokens.AuroraNumeralStyle.copy(fontSize = AuroraTokens.AuroraDisplayTimeSize),
                    )
                    Text(deltaLabel, color = AuroraTokens.AuroraBlue, fontSize = AuroraTokens.AuroraAuxSize)
                    if (!compact && bitmap != null) {
                        Spacer(Modifier.height(AuroraTokens.AuroraPreviewGap))
                        Image(bitmap.asImageBitmap(), null, Modifier.width(AuroraTokens.AuroraPreviewWidth).heightIn(max = AuroraTokens.AuroraPreviewMaxHeight))
                    }
                    Spacer(Modifier.height(AuroraTokens.AuroraPreviewGap))
                    AuroraFineRuler(state.deltaSeconds)
                }
            }
        }
    }
}

@Composable
private fun AuroraFineRuler(deltaSeconds: Int) {
    Canvas(Modifier.fillMaxWidth().height(AuroraTokens.AuroraMagnifierHeight)) {
        val centerY = size.height / 2f
        for (seconds in -AuroraTokens.FineScrubTickRangeSeconds..AuroraTokens.FineScrubTickRangeSeconds step 5) {
            val x = ((seconds + AuroraTokens.FineScrubTickRangeSeconds).toFloat() /
                (AuroraTokens.FineScrubTickRangeSeconds * 2).toFloat()) * size.width
            val tickHeight = if (seconds % 10 == 0) AuroraTokens.AuroraTickMajorHeight.toPx() else AuroraTokens.AuroraTickMinorHeight.toPx()
            drawLine(AuroraTokens.AuroraTextSecondary, Offset(x, centerY - tickHeight / 2f), Offset(x, centerY + tickHeight / 2f), cap = StrokeCap.Round)
        }
        val needleX = ((deltaSeconds + AuroraTokens.FineScrubTickRangeSeconds).toFloat() /
            (AuroraTokens.FineScrubTickRangeSeconds * 2).toFloat() * size.width).coerceIn(0f, size.width)
        drawLine(AuroraTokens.AuroraBlue, Offset(needleX, 0f), Offset(needleX, size.height), strokeWidth = AuroraTokens.AuroraMagnifierNeedleWidth.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
internal fun AuroraPill(overlay: PlayerOverlay?) {
    AnimatedContent(
        targetState = overlay,
        transitionSpec = {
            (fadeIn(animationSpec = AuroraTokens.AuroraOverlayAlpha) + scaleIn(animationSpec = AuroraTokens.AuroraPillEnter, initialScale = 0.86f)) togetherWith
                (fadeOut(animationSpec = AuroraTokens.AuroraOverlayFadeOut) + scaleOut(animationSpec = AuroraTokens.AuroraPillExit, targetScale = 0.96f))
        },
        contentKey = { it?.let { value -> value::class } },
        label = "aurora_pill",
    ) { current ->
        if (current == null) return@AnimatedContent Box(Modifier)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.border(AuroraTokens.AuroraPanelBorder, AuroraTokens.AuroraGradient, RoundedCornerShape(AuroraTokens.AuroraPillRadius)),
                shape = RoundedCornerShape(AuroraTokens.AuroraPillRadius),
                color = AuroraTokens.AuroraPillFill,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuroraTokens.AuroraPillPaddingH, vertical = AuroraTokens.AuroraPillPaddingV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraTokens.AuroraTopBarGap),
                ) {
                    Icon(
                        imageVector = auroraOverlayIcon(current),
                        contentDescription = null,
                        tint = AuroraTokens.AuroraText,
                        modifier = Modifier.size(AuroraTokens.AuroraIconSize),
                    )
                    Text(auroraOverlayText(current), color = AuroraTokens.AuroraText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal fun formatSpeedMultiplier(multiplier: Float): String =
    runCatching {
        BigDecimal(multiplier.toString()).stripTrailingZeros().toPlainString()
    }.getOrDefault(multiplier.toString())

@Composable
internal fun AuroraSpeedPill(multiplier: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.border(
                AuroraTokens.AuroraPanelBorder,
                AuroraTokens.AuroraGradient,
                RoundedCornerShape(AuroraTokens.AuroraPillRadius),
            ),
            shape = RoundedCornerShape(AuroraTokens.AuroraPillRadius),
            color = AuroraTokens.AuroraPillFill,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = AuroraTokens.AuroraPillPaddingH,
                    vertical = AuroraTokens.AuroraPillPaddingV,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuroraTokens.AuroraTopBarGap),
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = AuroraTokens.AuroraText,
                    modifier = Modifier.size(AuroraTokens.AuroraIconSize),
                )
                Text(
                    stringResource(R.string.player_speed_multiplier, formatSpeedMultiplier(multiplier)),
                    color = AuroraTokens.AuroraText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun auroraOverlayText(overlay: PlayerOverlay): String = when (overlay) {
    is PlayerOverlay.Seek -> "${formatDuration(overlay.positionMs)} / ${formatDuration(overlay.durationMs)}"
    is PlayerOverlay.Volume -> "${overlay.percent}%"
    is PlayerOverlay.Brightness -> "${overlay.percent}%"
    is PlayerOverlay.SpeedUp -> stringResource(R.string.player_speed_up)
    is PlayerOverlay.Previous -> stringResource(R.string.player_overlay_previous)
    is PlayerOverlay.Next -> stringResource(R.string.player_overlay_next)
    is PlayerOverlay.NoPrevious -> stringResource(R.string.player_overlay_no_previous)
    is PlayerOverlay.NoNext -> stringResource(R.string.player_overlay_no_next)
    is PlayerOverlay.TrashAction -> if (overlay.isIn) stringResource(R.string.player_overlay_trash_in) else stringResource(R.string.player_overlay_trash_out)
    is PlayerOverlay.Orientation -> if (overlay.mode == OrientationMode.FULLSCREEN) stringResource(R.string.player_overlay_orientation_fullscreen) else stringResource(R.string.player_overlay_orientation_rotation)
}

private fun auroraOverlayIcon(overlay: PlayerOverlay): ImageVector = when (overlay) {
    is PlayerOverlay.Seek -> Icons.Filled.Schedule
    is PlayerOverlay.Volume -> Icons.AutoMirrored.Filled.VolumeUp
    is PlayerOverlay.Brightness -> Icons.Filled.LightMode
    is PlayerOverlay.SpeedUp -> Icons.Filled.FastForward
    is PlayerOverlay.Previous -> Icons.Filled.SkipPrevious
    is PlayerOverlay.Next -> Icons.Filled.SkipNext
    is PlayerOverlay.NoPrevious, is PlayerOverlay.NoNext -> Icons.Filled.Block
    is PlayerOverlay.TrashAction -> if (overlay.isIn) Icons.Filled.Delete else Icons.Outlined.DeleteOutline
    is PlayerOverlay.Orientation -> if (overlay.mode == OrientationMode.FULLSCREEN) Icons.Filled.Fullscreen else Icons.Filled.ScreenRotation
}

internal data class RippleEvent(val sequence: Long, val xFraction: Float, val yFraction: Float)

@Composable
internal fun DoubleTapRipple(event: RippleEvent?) {
    if (event == null) return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(event.sequence) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = AuroraTokens.AuroraRipple)
    }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * event.xFraction, size.height * event.yFraction)
        drawCircle(
            brush = AuroraTokens.AuroraGradient,
            radius = min(size.width, size.height) * 0.32f * progress.value,
            center = center,
            alpha = 0.52f * (1f - progress.value),
            style = Stroke(width = AuroraTokens.AuroraPanelBorder.toPx()),
        )
    }
}
