package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = Color.White.copy(alpha = 0.4f)
): Modifier {
    val targetAlpha by remember {
        derivedStateOf {
            if (state.layoutInfo.visibleItemsInfo.size < state.layoutInfo.totalItemsCount) 1f else 0f
        }
    }

    var thumbAlpha by remember { mutableFloatStateOf(0f) }
    var thumbOffset by remember { mutableFloatStateOf(0f) }
    var thumbSize by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(targetAlpha) {
        snapshotFlow {
            val layoutInfo = state.layoutInfo
            if (layoutInfo.totalItemsCount == 0 || layoutInfo.viewportSize.height == 0) {
                ThumbInfo(0f, 0f, 0f)
            } else {
                val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()
                val scrollProgress = if (firstVisibleItem != null && layoutInfo.totalItemsCount > 1) {
                    (firstVisibleItem.index.toFloat() + firstVisibleItem.offset.toFloat() / -firstVisibleItem.size.toFloat()
                        .coerceAtLeast(1f)) / (layoutInfo.totalItemsCount - 1).toFloat()
                } else 0f

                val viewportRatio = layoutInfo.visibleItemsInfo.size.toFloat() / layoutInfo.totalItemsCount.toFloat()
                val viewportHeight = layoutInfo.viewportSize.height.toFloat()
                val tSize = (viewportRatio * viewportHeight).coerceIn(40f, viewportHeight)
                val tOffset = scrollProgress * (viewportHeight - tSize)

                ThumbInfo(tOffset.coerceAtLeast(0f), tSize, if (viewportRatio < 1f) 1f else 0f)
            }
        }.collect { info ->
            thumbOffset = info.offset
            thumbSize = info.size
            thumbAlpha = info.alpha
        }
    }

    return this.drawWithContent {
        drawContent()
        if (thumbAlpha > 0f && thumbSize > 0f) {
            val scrollbarWidthPx = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(this.size.width - scrollbarWidthPx - 2f, thumbOffset),
                size = Size(scrollbarWidthPx, thumbSize),
                cornerRadius = CornerRadius(scrollbarWidthPx / 2f),
                alpha = thumbAlpha * 0.5f
            )
        }
    }
}

private data class ThumbInfo(val offset: Float, val size: Float, val alpha: Float)
