package cn.vectory.syncplayer.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R

/**
 * First-run guidance card shown on the browser root when the library is empty
 * and setup is incomplete ([FirstRunState] != READY).
 *
 * Visual: Material 3 [Card] — leading icon + title + description + primary
 * CTA + secondary "dismiss" action. Matches the card density of the settings
 * and sync-preview screens.
 *
 * The card replaces the plain [EmptyBrowseContent] only when all of these hold:
 * root folder, browse items empty, not dismissed, [FirstRunState] resolved and
 * != READY. Otherwise the normal empty state is used.
 */
@Composable
internal fun FirstRunOnboardingCard(
    state: FirstRunState,
    targetRelationId: Long?,
    onNavigateToCreateSource: () -> Unit,
    onNavigateToCreateRelation: () -> Unit,
    onNavigateToSyncPreview: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    require(state != FirstRunState.READY) {
        "FirstRunOnboardingCard should not be rendered in READY state"
    }

    val icon: ImageVector
    val title: String
    val description: String
    val primaryLabel: String
    val onPrimary: () -> Unit

    when (state) {
        FirstRunState.NEEDS_SOURCE -> {
            icon = Icons.Default.Cloud
            title = stringResource(R.string.onboarding_needs_source_title)
            description = stringResource(R.string.onboarding_needs_source_desc)
            primaryLabel = stringResource(R.string.onboarding_action_configure_source)
            onPrimary = onNavigateToCreateSource
        }
        FirstRunState.NEEDS_RELATION -> {
            icon = Icons.Default.Sync
            title = stringResource(R.string.onboarding_needs_relation_title)
            description = stringResource(R.string.onboarding_needs_relation_desc)
            primaryLabel = stringResource(R.string.onboarding_action_configure_relation)
            onPrimary = onNavigateToCreateRelation
        }
        FirstRunState.NEEDS_SYNC -> {
            icon = Icons.Default.Download
            title = stringResource(R.string.onboarding_needs_sync_title)
            description = stringResource(R.string.onboarding_needs_sync_desc)
            primaryLabel = stringResource(R.string.onboarding_action_sync_now)
            onPrimary = { targetRelationId?.let(onNavigateToSyncPreview) }
        }
        FirstRunState.READY -> {
            // Guarded by require() above; unreachable.
            error("unreachable")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .wrapContentHeight(Alignment.CenterVertically),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.onboarding_dismiss),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.onboarding_later))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onPrimary, enabled = state != FirstRunState.NEEDS_SYNC || targetRelationId != null) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}
