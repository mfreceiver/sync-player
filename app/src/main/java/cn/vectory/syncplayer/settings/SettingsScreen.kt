package cn.vectory.syncplayer.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.BuildConfig
import cn.vectory.syncplayer.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMetadataRebuild: () -> Unit = {},
    onNavigateToGainAnalysis: () -> Unit = {},
    onNavigateToSyncSourceList: () -> Unit = {},
    onNavigateToSyncRelationList: () -> Unit = {}
) {
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    val currentContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isNavigatingBack by remember { mutableStateOf(false) }
    var showVolumeSwitchWarning by remember { mutableStateOf(false) }
    var pendingVolumeAction by remember { mutableStateOf<VolumeAction?>(null) }

    val openDocumentTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val treeUri = uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            currentContext.contentResolver.takePersistableUriPermission(treeUri, flags)
            // Only persist URI after durable grant succeeds — otherwise restart would lose access.
            viewModel.setMediaStorageTreeUri(treeUri.toString())
        } catch (e: SecurityException) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    currentContext.getString(R.string.settings_media_storage_persist_failed)
                )
            }
        }
    }

    fun launchOpenDocumentTree() {
        openDocumentTree.launch(null)
    }

    fun requestOpenDocumentTree(hasExistingVolume: Boolean) {
        if (hasExistingVolume) {
            pendingVolumeAction = VolumeAction.PickExternal
            showVolumeSwitchWarning = true
        } else {
            launchOpenDocumentTree()
        }
    }

    fun requestResetInternal(hasExistingVolume: Boolean) {
        if (!hasExistingVolume) return
        pendingVolumeAction = VolumeAction.ResetInternal
        showVolumeSwitchWarning = true
    }

    if (showVolumeSwitchWarning) {
        AlertDialog(
            onDismissRequest = {
                showVolumeSwitchWarning = false
                pendingVolumeAction = null
            },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.volume_switch_warning_title)) },
            text = { Text(stringResource(R.string.volume_switch_warning_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pendingVolumeAction
                        showVolumeSwitchWarning = false
                        pendingVolumeAction = null
                        when (action) {
                            VolumeAction.PickExternal -> launchOpenDocumentTree()
                            VolumeAction.ResetInternal -> viewModel.clearMediaStorageTreeUri()
                            null -> Unit
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.volume_switch_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showVolumeSwitchWarning = false
                        pendingVolumeAction = null
                    }
                ) {
                    Text(stringResource(R.string.volume_switch_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        val hasExternalVolume = state.mediaStorageTreeUri != null
        val locationLabel = if (hasExternalVolume) {
            val displayName = resolveTreeDisplayName(currentContext, state.mediaStorageTreeUri!!)
            stringResource(R.string.settings_media_storage_external, displayName)
        } else {
            stringResource(R.string.settings_media_storage_internal)
        }
        val spaceLabel = stringResource(R.string.settings_media_storage_space, state.usableSpaceLabel)
        val storageSummary = stringResource(
            R.string.settings_media_storage_summary,
            locationLabel,
            spaceLabel,
        )

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
                    Switch(
                        checked = state.volumeBalance,
                        onCheckedChange = { viewModel.setVolumeBalance(it) }
                    )
                },
                modifier = Modifier.clickable { onNavigateToGainAnalysis() }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(R.string.settings_section_system),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_media_storage_location)) },
                supportingContent = { Text(storageSummary) },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                modifier = Modifier.clickable {
                    requestOpenDocumentTree(hasExistingVolume = hasExternalVolume)
                }
            )

            if (hasExternalVolume) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_media_storage_reset_internal)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_media_storage_internal))
                    },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    modifier = Modifier.clickable {
                        requestResetInternal(hasExistingVolume = true)
                    }
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_app_lock)) },
                supportingContent = {
                    val cap = viewModel.authCapabilityInfo
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
                        enabled = viewModel.authCapabilityInfo.canAuthenticate
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
                headlineContent = { Text(stringResource(R.string.settings_app_version)) },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

        }
    }
}

private enum class VolumeAction {
    PickExternal,
    ResetInternal,
}

/** 尽量解析 SAF tree 目录显示名；失败则回退到 URI 末段。 */
private fun resolveTreeDisplayName(context: android.content.Context, treeUriStr: String): String {
    return try {
        val treeUri = Uri.parse(treeUriStr)
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } ?: fallbackUriLabel(treeUriStr)
    } catch (_: Exception) {
        fallbackUriLabel(treeUriStr)
    }
}

private fun fallbackUriLabel(treeUriStr: String): String {
    val decoded = Uri.decode(treeUriStr)
    val last = decoded.substringAfterLast(':').substringAfterLast('/')
    return last.ifBlank { treeUriStr }
}
