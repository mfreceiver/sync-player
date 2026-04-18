package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors

@Composable
internal fun StarRatingInput(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 32.dp,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    val starGold = LocalExtendedColors.current.starGold

    Row(modifier = modifier) {
        for (i in 0..4) {
            val isFilled = i < rating
            Icon(
                imageVector = if (isFilled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (isFilled) "已评分" else "未评分",
                tint = if (isFilled) starGold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .alpha(contentAlpha)
                    .then(
                        if (enabled) {
                            Modifier
                                .clickable { onRatingChange(if (i + 1 == rating) 0 else i + 1) }
                                .padding(4.dp)
                        } else {
                            Modifier
                        }
                    )
                    .size(starSize)
            )
        }
    }
}

@Composable
internal fun StarRatingDisplay(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Dp = 12.dp,
    filled: Boolean = true
) {
    if (rating <= 0) return
    val starGold = LocalExtendedColors.current.starGold

    Row(modifier = modifier) {
        repeat(rating) {
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (filled) starGold else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(starSize)
            )
        }
    }
}
