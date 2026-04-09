package cn.vectory.syncplayer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Locale

@HiltViewModel
class SyncRelationListViewModel @Inject constructor(
    private val syncRelationRepository: SyncRelationRepository,
    private val videoRepository: VideoRepository,
    private val audioGainAnalyzer: AudioGainAnalyzer
) : ViewModel() {
    val relationsWithSource = syncRelationRepository.getAllWithSourceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _analysisProgress = MutableStateFlow<VolumeAnalysisProgress?>(null)
    val analysisProgress = _analysisProgress.asStateFlow()

    fun analyzeRelation(relationId: Long) {
        viewModelScope.launch {
            val videos = videoRepository.getBySyncRelationId(relationId)
                .filter { it.audioGainDb == null }
            if (videos.isEmpty()) {
                _analysisProgress.value = VolumeAnalysisProgress(0, 0, true)
                _analysisProgress.value = null
                return@launch
            }
            _analysisProgress.value = VolumeAnalysisProgress(0, videos.size, false)
            val semaphore = Semaphore(3)
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            withContext(Dispatchers.IO) {
                videos.map { video ->
                    async {
                        semaphore.withPermit {
                            val result = audioGainAnalyzer.analyze(video.localAbsolutePath)
                            if (result != null) {
                                videoRepository.upsert(video.copy(audioGainDb = result.gainDb))
                            }
                            val done = completed.incrementAndGet()
                            _analysisProgress.value = VolumeAnalysisProgress(done, videos.size, false)
                        }
                    }
                }.awaitAll()
            }
            _analysisProgress.value = VolumeAnalysisProgress(videos.size, videos.size, true)
            _analysisProgress.value = null
        }
    }
}

data class VolumeAnalysisProgress(val completed: Int, val total: Int, val isDone: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRelationListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (relationId: Long) -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToSyncPreview: (relationId: Long) -> Unit,
    viewModel: SyncRelationListViewModel = hiltViewModel()
) {
    val relations by viewModel.relationsWithSource.collectAsStateWithLifecycle()
    val analysisProgress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    var isNavigatingBack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步关系管理") },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        if (relations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("尚未配置同步关系，点击右下角 + 新增同步关系")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(relations, key = { it.relation.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(item.relation.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.sourceName} → ${item.relation.remotePath}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "本地: ${item.relation.localBasePath}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            val lastSync = item.relation.lastSyncTime
                            Text(
                                "上次同步: ${lastSync?.let {
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it)
                                } ?: "从未同步"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { onNavigateToSyncPreview(item.relation.id) }) {
                                    Text("同步", style = MaterialTheme.typography.labelSmall)
                                }
                                val progress = analysisProgress
                                OutlinedButton(
                                    onClick = { viewModel.analyzeRelation(item.relation.id) },
                                    enabled = progress == null
                                ) {
                                    if (progress != null && !progress.isDone) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("${progress.completed}/${progress.total}", style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Icon(
                                            Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "分析音量",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("音量", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { onNavigateToEdit(item.relation.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "编辑",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
