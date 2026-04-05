package cn.vectory.syncplayer.debug

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    debugLogger: DebugLogger,
    onNavigateBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val currentLogLines by debugLogger.logLines.collectAsState()
    var previousLogLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingPrevious by remember { mutableStateOf(false) }
    val debugEnabled by debugLogger.enabled.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && previousLogLines.isEmpty()) {
            isLoadingPrevious = true
            previousLogLines = debugLogger.getPreviousLogContent()
            isLoadingPrevious = false
        }
    }

    val displayLines = if (selectedTab == 0) currentLogLines else previousLogLines
    val listState = rememberLazyListState()

    LaunchedEffect(currentLogLines.size, selectedTab) {
        if (selectedTab == 0 && currentLogLines.isNotEmpty()) {
            listState.animateScrollToItem(currentLogLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("调试日志") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (displayLines.isNotEmpty()) {
                            IconButton(onClick = {
                                val lines = displayLines
                                val cacheDir = context.cacheDir
                                val exportFile = java.io.File(cacheDir, "debug-export.log")
                                try {
                                    exportFile.writeText(lines.joinToString("\n"))
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        exportFile
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "导出日志"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "导出")
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(if (debugEnabled) "当前会话 (${currentLogLines.size})" else "当前会话")
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("上次会话") }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selectedTab == 0 && !debugEnabled -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "调试模式未启用，请先在播放设置中开启",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            isLoadingPrevious -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            displayLines.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (selectedTab == 0) "暂无日志记录" else "无上次调试记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (selectedTab == 0 && debugEnabled) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(
                            count = displayLines.size,
                            key = { index -> index }
                        ) { index ->
                            val line = displayLines[index]
                            val parts = line.split(" | ", limit = 4)
                            val time = parts.getOrElse(0) { "" }
                            val tag = parts.getOrElse(2) { "" }
                            val message = parts.getOrElse(3) { line }

                            val tagColor = when {
                                tag.contains("Error", ignoreCase = true) -> MaterialTheme.colorScheme.error
                                tag.contains("Nav") -> MaterialTheme.colorScheme.primary
                                tag.contains("Click") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = time.substring(11),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        ),
                                        color = tagColor
                                    )
                                }
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
