package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.BuildConfig
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMetadataRebuild: () -> Unit = {},
    onNavigateToGainAnalysis: () -> Unit = {}
) {
    val volumeBalance by viewModel.volumeBalance.collectAsStateWithLifecycle()
    val quickRecycle by viewModel.quickRecycle.collectAsStateWithLifecycle()
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
            ListItem(
                headlineContent = { Text("快捷回收") },
                supportingContent = { Text("启用时双击屏幕中部标记回收") },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = quickRecycle,
                        onCheckedChange = { viewModel.setQuickRecycle(it) }
                    )
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("音量均衡") },
                leadingContent = { Icon(Icons.Default.VolumeUp, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = volumeBalance,
                        onCheckedChange = { viewModel.setVolumeBalance(it) }
                    )
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("安卓存储框架") },
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = safEnabled,
                        onCheckedChange = { viewModel.setSafEnabled(it) }
                    )
                }
            )

            HorizontalDivider()

            val secureLockEnabledState by viewModel.secureLockEnabled.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text("应用安全锁") },
                supportingContent = {
                    if (!viewModel.canAuthenticateBio) {
                        Text("设备未设置生物识别信息", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("指纹/面部识别验证")
                    }
                },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingContent = {
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
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("增益分析") },
                leadingContent = { Icon(Icons.Default.Equalizer, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { onNavigateToGainAnalysis() }) {
                        Text("查看")
                    }
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("重建元数据") },
                supportingContent = { Text("扫描本地文件并重建数据库记录") },
                leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToMetadataRebuild() }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("应用版本") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            )

            HorizontalDivider()

            val syncLogs by viewModel.syncLogs.collectAsStateWithLifecycle()
            var logViewerExpanded by remember { mutableStateOf(false) }

            ListItem(
                headlineContent = { Text("同步日志") },
                leadingContent = { Icon(Icons.Default.Article, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { logViewerExpanded = !logViewerExpanded }) {
                        Text(if (logViewerExpanded) "收起" else "查看日志")
                    }
                }
            )

            if (logViewerExpanded) {
                Column(modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
                    if (syncLogs.isEmpty()) {
                        Text(
                            "暂无日志记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(syncLogs, key = { it.id }) { log ->
                                LogEntryItem(log = log)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::clearLogs,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清除日志")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(log: SyncLogEntity) {
    val extendedColors = LocalExtendedColors.current
    val statusColor = when (log.status) {
        "SUCCESS" -> extendedColors.successGreen
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatLogTimestamp(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = log.status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }

        Text(
            text = "${log.action} · ${log.relativePath}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        log.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

private fun formatLogTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
