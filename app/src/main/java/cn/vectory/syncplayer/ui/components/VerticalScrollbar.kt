package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay

private data class ThumbInfo(val offset: Float, val size: Float)

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = LocalExtendedColors.current.scrollbarColor
): Modifier {
    val isScrollable by remember {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.size < state.layoutInfo.totalItemsCount
        }
    }
    val isScrolling by remember { derivedStateOf { state.isScrollInProgress } }

    var scrollbarAlpha by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrolling) {
        if (isScrolling && isScrollable) {
            scrollbarAlpha = 1f
        } else {
            delay(800)
            if (!state.isScrollInProgress) {
                repeat(10) { i ->
                    scrollbarAlpha = 1f - (i + 1) / 10f
                    delay(30)
                }
                scrollbarAlpha = 0f
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
        if (scrollbarAlpha > 0.01f && thumbInfo.size > 0f) {
            val w = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(this.size.width - w - 2f, thumbInfo.offset),
                size = Size(w, thumbInfo.size),
                cornerRadius = CornerRadius(w / 2f),
                alpha = scrollbarAlpha * 0.7f
            )
        }
    }
}

@Composable
fun Modifier.gridVerticalScrollbar(
    state: LazyGridState,
    width: Dp = 4.dp,
    color: Color = LocalExtendedColors.current.scrollbarColor
): Modifier {
    val isScrollable by remember {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.size < state.layoutInfo.totalItemsCount
        }
    }
    val isScrolling by remember { derivedStateOf { state.isScrollInProgress } }

    var scrollbarAlpha by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrolling) {
        if (isScrolling && isScrollable) {
            scrollbarAlpha = 1f
        } else {
            delay(800)
            if (!state.isScrollInProgress) {
                repeat(10) { i ->
                    scrollbarAlpha = 1f - (i + 1) / 10f
                    delay(30)
                }
                scrollbarAlpha = 0f
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
                    (first.index.toFloat() + first.offset.y.toFloat() / -first.size.height.toFloat()
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
        if (scrollbarAlpha > 0.01f && thumbInfo.size > 0f) {
            val w = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(this.size.width - w - 2f, thumbInfo.offset),
                size = Size(w, thumbInfo.size),
                cornerRadius = CornerRadius(w / 2f),
                alpha = scrollbarAlpha * 0.7f
            )
        }
    }
}
