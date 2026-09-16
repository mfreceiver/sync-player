package cn.vectory.syncplayer.settings

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.audio.AudioGainAnalyzer
import cn.vectory.syncplayer.browser.safeErrorDetail
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.provider.MediaUris
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class GainAnalysisViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val audioGainAnalyzer: AudioGainAnalyzer,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    internal var analysisJob: kotlinx.coroutines.Job? = null
        private set

    // F08: discover 专属执行句柄（与 analysisJob 生命周期完全独立，语义见 discover()）。
    internal var discoverJob: kotlinx.coroutines.Job? = null
        private set

    /**
     * F08 重入防护 — 采用 **single-flight** 语义：上一次 discovery 仍在进行时，重复调用
     * 直接忽略（返回 no-op）。理由：
     * 1. discovery 的查询域在整屏生命周期内固定（relationId 来自 SavedStateHandle），
     *    进行中的重复调用不携带任何新信息；
     * 2. latest-wins（取消旧 job 再启动新 job）会让旧 job 的 finally 把
     *    `_isDiscovering` 置 false，与新 job 的 reset 序列交错（状态闪烁 + 竞态窗口）；
     *    single-flight 完全消除并发 discover 的交错，且与 retryFailed() 已有的
     *    `_isAnalyzing` 守卫风格一致；
     * 3. 红线：`analysisJob` 是 launchAnalysis 的分析执行取消句柄，二者生命周期不同，
     *    绝不复用——discover 不得取消、重启或观察 analysisJob。
     */
    fun discover() {
        if (discoverJob?.isActive == true) return
        discoverJob = viewModelScope.launch {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
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
                withContext(ioDispatcher) {
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
                                        // Single-column update: a whole-row upsert of the discover
                                        // snapshot would roll back concurrent column changes (e.g.
                                        // star) and could revive rows deleted during analysis.
                                        videoRepository.updateAudioGain(video.id, result.gainDb)
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
                                    Log.w(TAG, "Gain analysis failed id=${video.id}: ${safeErrorDetail(e)}")
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
                _error.value = syncErrorMessage(e, context)
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
