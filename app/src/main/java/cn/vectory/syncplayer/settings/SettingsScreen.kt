package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// BrowserViewModel import removed as related cards are removed

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMetadataRebuild: () -> Unit = {}
) {
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val volumeBalance by viewModel.volumeBalance.collectAsStateWithLifecycle()
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()
    val safEnabled by viewModel.safEnabled.collectAsStateWithLifecycle()
    val currentContext = LocalContext.current
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("长按加速倍速", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                            FilterChip(
                                selected = longPressSpeed == speed,
                                onClick = { viewModel.setLongPressSpeed(speed) },
                                label = { Text("${speed}x", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

            // Removed: 默认排序 Card (Sort settings)

            // Removed: 默认筛选 Card (Filter settings)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("双击功能", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsViewModel.DoubleTapAction.entries.forEach { action ->
                            FilterChip(
                                selected = doubleTapAction == action,
                                onClick = { viewModel.setDoubleTapAction(action) },
                                label = { Text(doubleTapActionLabel(action), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("音量均衡", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "启用后播放时自动调节音量，使不同视频音量趋于一致。需先分析音量增益数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = volumeBalance,
                            onCheckedChange = { viewModel.setVolumeBalance(it) }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SAF 文件共享", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "允许第三方播放器通过系统文件选择器访问已同步的视频文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = safEnabled,
                            onCheckedChange = { viewModel.setSafEnabled(it) }
                        )
                    }
                }
            }

            // App lock card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("应用安全锁", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "从后台切回应用时需要验证身份（指纹/面部识别）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    val secureLockEnabledState by viewModel.secureLockEnabled.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = secureLockEnabledState,
                        onCheckedChange = { checked ->
                                val activity = currentContext as? FragmentActivity
                                if (activity != null) {
                                    viewModel.authenticateAndToggle(activity, checked)
                                }
                            },
                            enabled = viewModel.canAuthenticateBio
                        )
                    }
                    if (!viewModel.canAuthenticateBio) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "设备未设置生物识别信息（指纹/面部）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Removed: 默认视图 Card (View mode and grid customization)

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("重建元数据") },
                    supportingContent = { Text("扫描本地文件并重建数据库记录") },
                    leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                    modifier = Modifier.clickable { onNavigateToMetadataRebuild() }
                )
            }
        }
    }
}

private fun doubleTapActionLabel(action: SettingsViewModel.DoubleTapAction): String = when (action) {
    SettingsViewModel.DoubleTapAction.TRASH -> "回收"
    SettingsViewModel.DoubleTapAction.PLAY_PAUSE -> "播放"
}

// Helper label for double-tap actions is kept elsewhere as it's still in use
