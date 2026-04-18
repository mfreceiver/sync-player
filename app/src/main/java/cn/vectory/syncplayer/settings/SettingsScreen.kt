package cn.vectory.syncplayer.settings

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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.BuildConfig

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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ListItem(
                headlineContent = { Text("配置数据源") },
                leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToSyncSourceList() }
            )

            ListItem(
                headlineContent = { Text("配置同步关系") },
                leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToSyncRelationList() }
            )

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

            ListItem(
                headlineContent = { Text("音量均衡") },
                supportingContent = { Text("点击进行增益分析") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = volumeBalance,
                        onCheckedChange = { viewModel.setVolumeBalance(it) }
                    )
                },
                modifier = Modifier.clickable { onNavigateToGainAnalysis() }
            )

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

            ListItem(
                headlineContent = { Text("重建元数据") },
                supportingContent = { Text("扫描本地文件并重建数据库记录") },
                leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToMetadataRebuild() }
            )

            ListItem(
                headlineContent = { Text("应用版本") },
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
