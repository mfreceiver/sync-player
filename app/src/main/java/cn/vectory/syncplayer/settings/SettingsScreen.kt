package cn.vectory.syncplayer.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.browser.BrowserViewModel
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToMetadataRebuild: () -> Unit = {}
) {
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val defaultSortMode by viewModel.defaultSortMode.collectAsStateWithLifecycle()
    val defaultFilter by viewModel.defaultFilter.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val progressAlwaysVisible by viewModel.progressAlwaysVisible.collectAsStateWithLifecycle()
    val volumeBalance by viewModel.volumeBalance.collectAsStateWithLifecycle()
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()
    val safEnabled by viewModel.safEnabled.collectAsStateWithLifecycle()
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认排序", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowserViewModel.SortMode.entries.forEach { mode ->
                            FilterChip(
                                selected = defaultSortMode == mode,
                                onClick = { viewModel.setDefaultSortMode(mode) },
                                leadingIcon = { Icon(sortModeIcon(mode), null, modifier = Modifier.size(18.dp)) },
                                label = { Text(sortModeLabel(mode), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认筛选", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowserViewModel.BrowseFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = defaultFilter == filter,
                                onClick = { viewModel.setDefaultFilter(filter) },
                                label = { Text(filterLabel(filter), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

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
                    Text("进度常显", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "启用后播放界面顶部固定显示进度条，隐藏控制条时仍可见",
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
                            checked = progressAlwaysVisible,
                            onCheckedChange = { viewModel.setProgressAlwaysVisible(it) }
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认视图", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowserViewModel.ViewMode.entries.forEach { mode ->
                            FilterChip(
                                selected = viewMode == mode,
                                onClick = { viewModel.setViewMode(mode) },
                                label = { Text(viewModeLabel(mode), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    if (viewMode == BrowserViewModel.ViewMode.GRID) {
                        Spacer(Modifier.height(12.dp))
                        Text("网格列数", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("$gridColumns", style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = gridColumns.toFloat(),
                                onValueChange = { viewModel.setGridColumns(it.toInt()) },
                                valueRange = 1f..5f,
                                steps = 3,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

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

private fun sortModeLabel(mode: BrowserViewModel.SortMode): String = when (mode) {
    BrowserViewModel.SortMode.FILENAME_ASC -> "A→Z"
    BrowserViewModel.SortMode.FILENAME_DESC -> "Z→A"
    BrowserViewModel.SortMode.DURATION_ASC -> "短→长"
    BrowserViewModel.SortMode.DURATION_DESC -> "长→短"
}

private fun sortModeIcon(mode: BrowserViewModel.SortMode) = when (mode) {
    BrowserViewModel.SortMode.FILENAME_ASC, BrowserViewModel.SortMode.FILENAME_DESC -> Icons.Default.SortByAlpha
    BrowserViewModel.SortMode.DURATION_ASC, BrowserViewModel.SortMode.DURATION_DESC -> Icons.Default.Schedule
}

private fun filterLabel(filter: BrowserViewModel.BrowseFilter): String = when (filter) {
    BrowserViewModel.BrowseFilter.ALL -> "全部"
    BrowserViewModel.BrowseFilter.VIDEO_ONLY -> "仅视频"
    BrowserViewModel.BrowseFilter.UNWATCHED -> "没看过"
}

private fun viewModeLabel(mode: BrowserViewModel.ViewMode): String = when (mode) {
    BrowserViewModel.ViewMode.LIST -> "列表"
    BrowserViewModel.ViewMode.GRID -> "网格"
}

private fun doubleTapActionLabel(action: SettingsViewModel.DoubleTapAction): String = when (action) {
    SettingsViewModel.DoubleTapAction.TRASH -> "回收"
    SettingsViewModel.DoubleTapAction.PLAY_PAUSE -> "播放"
}
