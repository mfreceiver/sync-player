package cn.vectory.syncplayer.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.browser.DeleteMediaResult
import cn.vectory.syncplayer.browser.FileOperationsUseCase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.di.IoDispatcher
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.ImageMetadataExtractor
import cn.vectory.syncplayer.sync.VideoMetadataExtractor
import cn.vectory.syncplayer.sync.SyncEngine
import cn.vectory.syncplayer.sync.syncErrorMessage
import cn.vectory.syncplayer.ui.util.isImageFile
import cn.vectory.syncplayer.ui.util.isVideoFile
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DiscoveryResult(
    /** 本地文件缺失的记录（真 orphan，可安全清理）。 */
    val orphanedRecords: List<VideoItemEntity>,
    /** 元数据缺失（duration/width 等），可经 content:// 重新提取。 */
    val incompleteRecords: List<VideoItemEntity>,
)

data class CleanupSummary(
    val success: Int = 0,
    val failed: Int = 0,
    val failures: List<Pair<Long, String>> = emptyList(),
)

@HiltViewModel
class MetadataRebuildViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val imageMetadataExtractor: ImageMetadataExtractor,
    private val mediaFileStoreLazy: Lazy<MediaFileStore>,
    private val fileOps: FileOperationsUseCase,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val mediaStore: MediaFileStore get() = mediaFileStoreLazy.get()

    private val _discoveryResult = MutableStateFlow<DiscoveryResult?>(null)
    val discoveryResult = _discoveryResult.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _isRebuilding = MutableStateFlow(false)
    val isRebuilding = _isRebuilding.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete = _isComplete.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount = _completedCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount = _totalCount.asStateFlow()

    private val _currentFile = MutableStateFlow<String?>(null)
    val currentFile = _currentFile.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _cleanupSummary = MutableStateFlow<CleanupSummary?>(null)
    val cleanupSummary = _cleanupSummary.asStateFlow()

    private var rebuildJob: Job? = null
    private var discoverJob: Job? = null

    fun discover() {
        if (_isDiscovering.value) return
        discoverJob = viewModelScope.launch {
            _isDiscovering.value = true
            _error.value = null
            _isComplete.value = false
            _discoveryResult.value = null
            _cleanupSummary.value = null
            try {
                withContext(ioDispatcher) {
                    syncEngine.storageOperationMutex.withLock {
                        val dbRecords = videoRepository.getAll()
                        val orphanedRecords = mutableListOf<VideoItemEntity>()
                        val incompleteRecords = mutableListOf<VideoItemEntity>()

                        for (record in dbRecords) {
                        // M1: three-way existence — IO error aborts scan (do not fake orphan).
                        when (val existence = mediaStore.checkExistence(record.id)) {
                            is MediaExistence.EXISTS -> {
                                val incomplete = when {
                                    isVideoFile(record.fileName) -> record.duration == null
                                    isImageFile(record.fileName) -> record.width == null
                                    else -> false
                                }
                                if (incomplete) incompleteRecords.add(record)
                            }
                            is MediaExistence.NOT_FOUND -> orphanedRecords.add(record)
                            is MediaExistence.IO_ERROR -> {
                                _error.value = context.getString(R.string.metadata_scan_io_error, existence.reason)
                                return@withContext
                            }
                        }
                    }

                        if (_error.value != null) {
                        // Aborted mid-scan due to IO; leave discoveryResult null.
                        return@withContext
                    }

                        _discoveryResult.value = DiscoveryResult(
                        orphanedRecords = orphanedRecords,
                        incompleteRecords = incompleteRecords,
                    )
                    // Rebuild total covers orphan + incomplete.
                        _totalCount.value = orphanedRecords.size + incompleteRecords.size
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            } finally {
                _isDiscovering.value = false
                discoverJob = null
            }
        }
    }

    /** Rebuild orphans + incomplete. */
    fun startRebuild() {
        val result = _discoveryResult.value ?: return
        rebuildJob = viewModelScope.launch {
            _isRebuilding.value = true
            _completedCount.value = 0
            _error.value = null
            _cleanupSummary.value = null

            try {
                withContext(ioDispatcher) {
                    syncEngine.storageOperationMutex.withLock {
                        var fail = 0
                        val failures = mutableListOf<Pair<Long, String>>()
                        for (record in result.orphanedRecords) {
                            _currentFile.value = record.fileName
                            when (val r = fileOps.deleteMediaByIdLocked(record.id)) {
                                is DeleteMediaResult.Success, is DeleteMediaResult.SkippedNotFound -> Unit
                                is DeleteMediaResult.Failed -> {
                                    fail++
                                    failures.add(record.id to syncErrorMessage(r.error, context))
                                }
                            }
                            _completedCount.value++
                        }

                        for (record in result.incompleteRecords) {
                            _currentFile.value = record.fileName
                            val fresh = videoRepository.getById(record.id) ?: continue
                            try {
                                if (isImageFile(fresh.fileName)) imageMetadataExtractor.extractAndSave(fresh)
                                else videoMetadataExtractor.extractAndSave(fresh)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to extract metadata for ${record.fileName}", e)
                                fail++
                                failures.add(record.id to syncErrorMessage(e, context))
                            }
                            _completedCount.value++
                        }

                        if (failures.isNotEmpty()) {
                            _cleanupSummary.value = CleanupSummary(
                                success = _completedCount.value - fail,
                                failed = fail,
                                failures = failures,
                            )
                        }
                    }
                }

                _discoveryResult.value = null
                _isComplete.value = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = syncErrorMessage(e, context)
            } finally {
                _isRebuilding.value = false
                _currentFile.value = null
            }
        }
    }

    fun cancelRebuild() {
        rebuildJob?.cancel()
        rebuildJob = null
        discoverJob?.cancel()
        discoverJob = null
        _isRebuilding.value = false
        _isDiscovering.value = false
        _currentFile.value = null
    }

    companion object {
        private const val TAG = "MetadataRebuildViewModel"
    }
}
