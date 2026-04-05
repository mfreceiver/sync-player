package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.OrientationPreference
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDebugLog: () -> Unit = {}
) {
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val orientationPreference by viewModel.orientationPreference.collectAsStateWithLifecycle()
    val debugMode by viewModel.debugMode.collectAsStateWithLifecycle()
    val previousSummary by viewModel.previousSessionSummary.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                            FilterChip(
                                selected = longPressSpeed == speed,
                                onClick = { viewModel.setLongPressSpeed(speed) },
                                label = { Text("${speed}x") }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("播放方向", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = orientationPreference == OrientationPreference.FOLLOW_VIDEO,
                            onClick = { viewModel.setOrientationPreference(OrientationPreference.FOLLOW_VIDEO) },
                            label = { Text("按照视频方向") }
                        )
                        FilterChip(
                            selected = orientationPreference == OrientationPreference.FOLLOW_SYSTEM,
                            onClick = { viewModel.setOrientationPreference(OrientationPreference.FOLLOW_SYSTEM) },
                            label = { Text("按照系统方向") }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("调试模式", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (debugMode) "调试模式已启用" else "调试模式已关闭",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!debugMode) {
                                Text(
                                    previousSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = debugMode,
                            onCheckedChange = { enabled ->
                                if (enabled) viewModel.enableDebugMode() else viewModel.disableDebugMode()
                            }
                        )
                    }
                    if (!debugMode && previousSummary != "无上次调试记录") {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNavigateToDebugLog) {
                                Text("查看日志")
                            }
                            OutlinedButton(onClick = {
                                val file = viewModel.exportPreviousLog()
                                if (file != null && file.exists()) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "导出调试日志"))
                                } else {
                                    Toast.makeText(context, "无日志可导出", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("导出日志")
                            }
                            TextButton(onClick = { viewModel.clearPreviousLog() }) {
                                Text("删除日志", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
