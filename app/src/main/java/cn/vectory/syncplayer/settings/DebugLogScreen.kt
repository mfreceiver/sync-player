package cn.vectory.syncplayer.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.debug.DebugLogger
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onNavigateBack: () -> Unit) {
    var selectedLevel by remember { mutableStateOf<DebugLogger.Level?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Poll the in-memory ring buffer every 500ms. No disk I/O.
    LaunchedEffect(Unit) {
        while (true) {
            refreshKey++
            delay(500)
        }
    }

    val allEntries = remember(refreshKey) { DebugLogger.snapshot() }
    val filteredEntries = remember(allEntries, selectedLevel) {
        if (selectedLevel != null) allEntries.filter { it.level == selectedLevel } else allEntries
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { DebugLogger.clear() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.debug_log_clear)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedLevel == null,
                    onClick = { selectedLevel = null },
                    label = { Text(stringResource(R.string.debug_log_level_all)) }
                )
                FilterChip(
                    selected = selectedLevel == DebugLogger.Level.DEBUG,
                    onClick = { selectedLevel = DebugLogger.Level.DEBUG },
                    label = { Text(stringResource(R.string.debug_log_level_debug)) }
                )
                FilterChip(
                    selected = selectedLevel == DebugLogger.Level.INFO,
                    onClick = { selectedLevel = DebugLogger.Level.INFO },
                    label = { Text(stringResource(R.string.debug_log_level_info)) }
                )
                FilterChip(
                    selected = selectedLevel == DebugLogger.Level.WARN,
                    onClick = { selectedLevel = DebugLogger.Level.WARN },
                    label = { Text(stringResource(R.string.debug_log_level_warn)) }
                )
                FilterChip(
                    selected = selectedLevel == DebugLogger.Level.ERROR,
                    onClick = { selectedLevel = DebugLogger.Level.ERROR },
                    label = { Text(stringResource(R.string.debug_log_level_error)) }
                )
            }

            // Log list / empty state
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.debug_log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(filteredEntries.size) {
                    // AND-078: follow the stream only while the user is already
                    // at the bottom — unconditional jumps fight anyone reading
                    // older entries.
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    if (lastVisible == null || lastVisible >= filteredEntries.lastIndex - 1) {
                        listState.animateScrollToItem(filteredEntries.lastIndex)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredEntries) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLogger.LogEntry) {
    val levelColor = when (entry.level) {
        DebugLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        DebugLogger.Level.INFO -> MaterialTheme.colorScheme.onSurface
        DebugLogger.Level.WARN -> Color(0xFFFF9800) // orange
        DebugLogger.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${entry.timestamp} ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "[${entry.level.label}] ",
            style = MaterialTheme.typography.bodySmall,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${entry.tag}: ",
            style = MaterialTheme.typography.bodySmall,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = levelColor,
            fontFamily = FontFamily.Monospace
        )
    }
}
