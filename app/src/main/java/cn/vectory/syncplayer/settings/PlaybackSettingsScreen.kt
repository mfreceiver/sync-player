package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.browser.BrowserViewModel
import cn.vectory.syncplayer.data.model.OrientationPreference

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val orientationPreference by viewModel.orientationPreference.collectAsStateWithLifecycle()
    val defaultSortMode by viewModel.defaultSortMode.collectAsStateWithLifecycle()
    val defaultFilter by viewModel.defaultFilter.collectAsStateWithLifecycle()
    val maxConcurrentDownloads by viewModel.maxConcurrentDownloads.collectAsStateWithLifecycle()
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    Text("播放方向", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = orientationPreference == OrientationPreference.FOLLOW_VIDEO,
                            onClick = { viewModel.setOrientationPreference(OrientationPreference.FOLLOW_VIDEO) },
                            label = { Text("按照视频方向", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                        FilterChip(
                            selected = orientationPreference == OrientationPreference.FOLLOW_SYSTEM,
                            onClick = { viewModel.setOrientationPreference(OrientationPreference.FOLLOW_SYSTEM) },
                            label = { Text("按照系统方向", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
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
                    Text("并发下载数", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "同时下载的文件数量，增大可提升同步速度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { count ->
                            FilterChip(
                                selected = maxConcurrentDownloads == count,
                                onClick = { viewModel.setMaxConcurrentDownloads(count) },
                                label = { Text("$count 个", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
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
    BrowserViewModel.BrowseFilter.TRASHED_ONLY -> "仅回收站"
}
