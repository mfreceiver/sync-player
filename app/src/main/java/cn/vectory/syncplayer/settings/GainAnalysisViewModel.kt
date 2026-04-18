package cn.vectory.syncplayer.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class GainAnalysisViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val audioGainAnalyzer: AudioGainAnalyzer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val relationId: Long = savedStateHandle.get<Long>("relationId") ?: 0L

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg"
    )

    private fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    private val _discoveredFiles = MutableStateFlow<List<VideoItemEntity>>(emptyList())
    val discoveredFiles = _discoveredFiles.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete = _isComplete.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount = _completedCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount = _totalCount.asStateFlow()

    private val _activeAnalysis = MutableStateFlow<List<String>>(emptyList())
    val activeAnalysis = _activeAnalysis.asStateFlow()

    private val _completedPaths = MutableStateFlow<Set<String>>(emptySet())
    val completedPaths = _completedPaths.asStateFlow()

    private var analysisJob: kotlinx.coroutines.Job? = null

    fun discover() {
        viewModelScope.launch {
            _isDiscovering.value = true
            _error.value = null
            _isComplete.value = false
            _completedPaths.value = emptySet()
            _completedCount.value = 0
            try {
                val videos = if (relationId == 0L) {
                    videoRepository.getAll()
                        .filter { it.audioGainDb == null && isVideoFile(it.fileName) }
                } else {
                    videoRepository.getBySyncRelationId(relationId)
                        .filter { it.audioGainDb == null && isVideoFile(it.fileName) }
                }
                _discoveredFiles.value = videos
                _totalCount.value = videos.size
                if (videos.isEmpty()) {
                    _isComplete.value = true
                }
            } catch (e: Exception) {
                _error.value = "发现文件失败: ${e.message}"
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    fun startAnalysis() {
        val files = _discoveredFiles.value
        if (files.isEmpty()) return

        analysisJob = viewModelScope.launch {
            _isAnalyzing.value = true
            _error.value = null
            _completedCount.value = 0
            _completedPaths.value = emptySet()

            val semaphore = Semaphore(3)
            val completed = AtomicInteger(0)
            val activeMutex = kotlinx.coroutines.sync.Mutex()
            val activeFiles = mutableMapOf<String, String>()

            withContext(Dispatchers.IO) {
                files.map { video ->
                    async {
                        semaphore.withPermit {
                            activeMutex.lock()
                            activeFiles[video.localAbsolutePath] = video.fileName
                            _activeAnalysis.value = activeFiles.values.toList()
                            activeMutex.unlock()

                            val result = audioGainAnalyzer.analyze(video.localAbsolutePath)
                            if (result != null) {
                                videoRepository.upsert(video.copy(audioGainDb = result.gainDb))
                            } else {
                                videoRepository.upsert(video.copy(audioGainDb = 0f))
                            }

                            activeMutex.lock()
                            activeFiles.remove(video.localAbsolutePath)
                            _activeAnalysis.value = activeFiles.values.toList()
                            activeMutex.unlock()

                            val done = completed.incrementAndGet()
                            _completedCount.value = done
                            _completedPaths.value = _completedPaths.value + video.localAbsolutePath
                        }
                    }
                }.awaitAll()
            }

            _isAnalyzing.value = false
            _isComplete.value = true
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _isAnalyzing.value = false
        _activeAnalysis.value = emptyList()
    }
}
