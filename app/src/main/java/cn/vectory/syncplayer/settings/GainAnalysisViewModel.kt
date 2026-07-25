package cn.vectory.syncplayer.settings

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val _completedIds = MutableStateFlow<Set<Long>>(emptySet())
    val completedIds = _completedIds.asStateFlow()

    private val _failedIds = MutableStateFlow<Set<Long>>(emptySet())
    val failedIds = _failedIds.asStateFlow()

    private var analysisJob: kotlinx.coroutines.Job? = null

    fun discover() {
        viewModelScope.launch {
            _isDiscovering.value = true
            _error.value = null
            _isComplete.value = false
            _completedIds.value = emptySet()
            _failedIds.value = emptySet()
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
        launchAnalysis(files, resetAll = true)
    }

    fun retryFailed() {
        if (_isAnalyzing.value) return

        val failed = _discoveredFiles.value.filter { it.id in _failedIds.value }
        if (failed.isEmpty()) return

        launchAnalysis(failed, resetAll = false)
    }

    private fun launchAnalysis(
        files: List<VideoItemEntity>,
        resetAll: Boolean
    ) {
        analysisJob?.cancel()

        analysisJob = viewModelScope.launch {
            _isAnalyzing.value = true
            _error.value = null
            _isComplete.value = false

            if (resetAll) {
                _completedCount.value = 0
                _completedIds.value = emptySet()
                _failedIds.value = emptySet()
            } else {
                _completedCount.value = 0
                _totalCount.value = files.size
                _failedIds.value = _failedIds.value - files.map { it.id }.toSet()
            }

            val semaphore = Semaphore(3)
            val completed = AtomicInteger(0)
            val activeMutex = Mutex()
            val completedMutex = Mutex()
            val activeFiles = mutableMapOf<Long, String>()
            val failed = mutableSetOf<Long>()
            val failedMutex = Mutex()

            try {
                withContext(Dispatchers.IO) {
                    files.map { video ->
                        async {
                            semaphore.withPermit {
                                activeMutex.withLock {
                                    activeFiles[video.id] = video.fileName
                                    _activeAnalysis.value = activeFiles.values.toList()
                                }

                                try {
                                    val result = audioGainAnalyzer.analyze(MediaUris.mediaUri(video.id))
                                    if (result != null) {
                                        videoRepository.upsert(
                                            video.copy(audioGainDb = result.gainDb)
                                        )
                                        completedMutex.withLock {
                                            _completedIds.value = _completedIds.value + video.id
                                        }
                                    } else {
                                        failedMutex.withLock {
                                            failed.add(video.id)
                                            _failedIds.value = failed.toSet()
                                        }
                                        Log.w(
                                            TAG,
                                            "Gain analysis returned null for id=${video.id}"
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG,
                                        "Gain analysis failed for id=${video.id}",
                                        e
                                    )
                                    failedMutex.withLock {
                                        failed.add(video.id)
                                        _failedIds.value = failed.toSet()
                                    }
                                } finally {
                                    activeMutex.withLock {
                                        activeFiles.remove(video.id)
                                        _activeAnalysis.value = activeFiles.values.toList()
                                    }
                                    _completedCount.value = completed.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                }

                _isComplete.value = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "分析失败: ${e.message}"
            } finally {
                _activeAnalysis.value = emptyList()
                _isAnalyzing.value = false
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _isAnalyzing.value = false
        _activeAnalysis.value = emptyList()
    }

    companion object {
        private const val TAG = "GainAnalysisViewModel"
    }
}
