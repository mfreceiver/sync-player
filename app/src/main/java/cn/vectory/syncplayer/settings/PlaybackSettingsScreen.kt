package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.OrientationPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val longPressSpeed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
    val orientationPreference by viewModel.orientationPreference.collectAsStateWithLifecycle()

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
        }
    }
}
