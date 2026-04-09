package cn.vectory.syncplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = Color.White.copy(alpha = 0.4f)
): Modifier {
    val isScrollable by remember {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.size < state.layoutInfo.totalItemsCount
        }
    }

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(isScrollable) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling && isScrollable) {
                alpha.snapTo(1f)
            } else {
                delay(800)
                if (!state.isScrollInProgress) {
                    alpha.animateTo(0f, animationSpec = tween(300))
                }
            }
        }
    }

    val thumbInfo by remember {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            if (layoutInfo.totalItemsCount == 0 || layoutInfo.viewportSize.height == 0) {
                ThumbInfo(0f, 0f)
            } else {
                val first = layoutInfo.visibleItemsInfo.firstOrNull()
                val progress = if (first != null && layoutInfo.totalItemsCount > 1) {
                    (first.index.toFloat() + first.offset.toFloat() / -first.size.toFloat()
                        .coerceAtLeast(1f)) / (layoutInfo.totalItemsCount - 1).toFloat()
                } else 0f
                val ratio = layoutInfo.visibleItemsInfo.size.toFloat() / layoutInfo.totalItemsCount.toFloat()
                val viewport = layoutInfo.viewportSize.height.toFloat()
                val tSize = (ratio * viewport).coerceIn(40f, viewport)
                val tOffset = (progress * (viewport - tSize)).coerceAtLeast(0f)
                ThumbInfo(tOffset, tSize)
            }
        }
    }

    return this.drawWithContent {
        drawContent()
        val a = alpha.value
        if (a > 0.01f && thumbInfo.size > 0f) {
            val w = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(this.size.width - w - 2f, thumbInfo.offset),
                size = Size(w, thumbInfo.size),
                cornerRadius = CornerRadius(w / 2f),
                alpha = a * 0.5f
            )
        }
    }
}

private data class ThumbInfo(val offset: Float, val size: Float)
