package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R

/**
 * Inline form result text — used after connection tests, save operations, etc.
 * @param message The result message to display
 * @param isSuccess Whether the operation was successful (determines color)
 */
@Composable
fun FormResultText(
    message: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = message,
        color = if (isSuccess) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}

/**
 * Full-screen centered error state — used when error occurs and no content is available.
 * @param message Error message
 * @param onRetry Optional retry callback (shows retry button if provided)
 * @param onDismiss Optional dismiss callback (shows dismiss button if provided)
 */
@Composable
fun FullScreenError(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (onRetry != null || onDismiss != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onDismiss != null) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                    if (onRetry != null) {
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline error banner Card — used when error occurs alongside existing content.
 * @param message Error message
 * @param onDismiss Optional dismiss callback (shows dismiss button if provided)
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
