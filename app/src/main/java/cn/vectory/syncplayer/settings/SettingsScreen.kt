package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.BuildConfig
import cn.vectory.syncplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMetadataRebuild: () -> Unit = {},
    onNavigateToGainAnalysis: () -> Unit = {},
    onNavigateToSyncSourceList: () -> Unit = {},
    onNavigateToSyncRelationList: () -> Unit = {},
    onNavigateToStorageManagement: () -> Unit = {},
    onNavigateToDebugLog: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
) {
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    val authCapabilityInfo by viewModel.authCapabilityInfo.collectAsStateWithLifecycle()
    val currentContext = LocalContext.current
    var isNavigatingBack by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshAuthCapability()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                onNavigateBack()
                            }
                        },
                        enabled = !isNavigatingBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        if (settingsState == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val state = settingsState!!
        val locationLabel = stringResource(R.string.storage_current_internal)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.settings_section_sync),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_configure_source)) },
                leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToSyncSourceList() }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_configure_sync)) },
                leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToSyncRelationList() }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_management)) },
                supportingContent = { Text(stringResource(R.string.storage_management_desc, locationLabel)) },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToStorageManagement() }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_show_onboarding)) },
                supportingContent = { Text(stringResource(R.string.settings_show_onboarding_desc)) },
                leadingContent = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
                modifier = Modifier.clickable { viewModel.resetOnboardingDismissed() }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(R.string.settings_section_playback),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_quick_recycle)) },
                supportingContent = { Text(stringResource(R.string.settings_quick_recycle_desc)) },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = state.quickRecycle,
                        onCheckedChange = { viewModel.setQuickRecycle(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_volume_balance)) },
                supportingContent = { Text(stringResource(R.string.settings_volume_balance_desc)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                trailingContent = {
                    // AND-076: two unrelated actions on one row was ambiguous —
                    // the row now toggles the setting (matching switch-row
                    // convention), and the chevron is the navigation affordance.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.volumeBalance,
                            onCheckedChange = { viewModel.setVolumeBalance(it) }
                        )
                        IconButton(onClick = onNavigateToGainAnalysis) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.gain_title)
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { viewModel.setVolumeBalance(!state.volumeBalance) }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_orientation_follow_system)) },
                supportingContent = { Text(stringResource(R.string.settings_orientation_follow_system_desc)) },
                leadingContent = { Icon(Icons.Default.ScreenRotation, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = state.followSystemOrientation,
                        onCheckedChange = { viewModel.setFollowSystemOrientation(it) }
                    )
                },
                modifier = Modifier.clickable { viewModel.setFollowSystemOrientation(!state.followSystemOrientation) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(R.string.settings_section_system),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_app_lock)) },
                supportingContent = {
                    val cap = authCapabilityInfo
                    if (!cap.canAuthenticate) {
                        Text(stringResource(R.string.settings_no_auth), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.settings_auth_support, cap.description))
                    }
                },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = state.secureLockEnabled,
                        onCheckedChange = { checked ->
                            val activity = currentContext as? FragmentActivity
                            if (activity != null) {
                                viewModel.authenticateAndToggle(activity, checked)
                            }
                        },
                        enabled = authCapabilityInfo.canAuthenticate
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(R.string.settings_section_maintenance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_rebuild_metadata)) },
                supportingContent = { Text(stringResource(R.string.settings_rebuild_metadata_desc)) },
                leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToMetadataRebuild() }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.debug_log_title)) },
                leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToDebugLog() }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_app_version)) },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                // AND-079: AboutScreen (version + sync-log viewer) was unreachable —
                // the version row is its natural entry point.
                modifier = Modifier.clickable { onNavigateToAbout() }
            )

        }
    }
}
