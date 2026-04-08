package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun FileStatusIcon(
    isCompleted: Boolean,
    isFavorite: Boolean,
    playbackPosition: Long,
    playbackDuration: Long,
    modifier: Modifier = Modifier
) {
    when {
        isCompleted -> {
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = 1f,
                    modifier = Modifier.size(32.dp)
                )
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "已预删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.Center)
                    )
                }
            }
        }
        playbackPosition > 0 && playbackDuration > 0 -> {
            Spacer(Modifier.width(8.dp))
            val progress = playbackPosition.toFloat() / playbackDuration.toFloat()
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = progress,
                    modifier = Modifier.size(32.dp)
                )
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "已预删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.Center)
                    )
                }
            }
        }
        isFavorite -> {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "已预删除",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
