package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R
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
            val ratingState = stringResource(
                if (isFilled) R.string.star_rated else R.string.star_unrated
            )
            val starContentDescription = stringResource(
                R.string.filter_star_rating_a11y_n,
                i + 1,
                ratingState,
            )
            Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = starContentDescription
                        role = Role.Button
                    }
                    .clickable(enabled = enabled) {
                        onRatingChange(if (i + 1 == rating) 0 else i + 1)
                    }
            ) {
                Icon(
                    imageVector = if (isFilled) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = if (isFilled) starGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .alpha(contentAlpha)
                        .size(starSize)
                )
            }
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
