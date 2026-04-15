package cn.vectory.syncplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.VideoMetadataExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class FileDiscovery(
    val relativePath: String,
    val fileName: String,
    val fileSize: Long,
    val absolutePath: String
)

data class DiscoveryResult(
    val newFiles: List<FileDiscovery>,
    val orphanedRecords: List<VideoItemEntity>,
    val incompleteRecords: List<VideoItemEntity>
)

@HiltViewModel
class MetadataRebuildViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val storagePaths: StoragePaths
) : ViewModel() {

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "ts", "m4v", "3gp", "mpeg", "mpg"
    )

    private fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

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

    private var rebuildJob: Job? = null

    fun discover() {
        viewModelScope.launch {
            _isDiscovering.value = true
            _error.value = null
            _isComplete.value = false
            _discoveryResult.value = null
            try {
                withContext(Dispatchers.IO) {
                    val dbRecords = videoRepository.getAll()
                    val dbPathSet = dbRecords.map { it.relativePath }.toSet()

                    val newFiles = mutableListOf<FileDiscovery>()
                    val orphanedRecords = mutableListOf<VideoItemEntity>()
                    val incompleteRecords = mutableListOf<VideoItemEntity>()

                    val userdataRoot = storagePaths.userdataRoot
                    if (userdataRoot.exists()) {
                        userdataRoot.walkTopDown()
                            .filter { it.isFile }
                            .filter { !it.name.startsWith(".") && !it.name.endsWith(".sync_tmp") }
                            .forEach { file ->
                                val relPath = file.relativeTo(userdataRoot).path
                                if (relPath !in dbPathSet) {
                                    newFiles.add(FileDiscovery(
                                        relativePath = relPath,
                                        fileName = file.name,
                                        fileSize = file.length(),
                                        absolutePath = file.absolutePath
                                    ))
                                }
                            }
                    }

                    // 只将视频文件纳入重建范围，非视频文件不需要元数据提取
                    val videoNewFiles = newFiles.filter { isVideoFile(it.fileName) }
                    val nonVideoNewFiles = newFiles.filter { !isVideoFile(it.fileName) }

                    for (record in dbRecords) {
                        val file = File(record.localAbsolutePath)
                        if (!file.exists()) {
                            orphanedRecords.add(record)
                        } else if (isVideoFile(record.fileName) && (record.duration == null || record.thumbnailPath == null)) {
                            incompleteRecords.add(record)
                        }
                    }

                    _discoveryResult.value = DiscoveryResult(videoNewFiles, orphanedRecords, incompleteRecords)
                    _totalCount.value = videoNewFiles.size + orphanedRecords.size + incompleteRecords.size

                    // 非视频文件仍需创建 DB 记录以供浏览，但不计入重建进度
                    if (nonVideoNewFiles.isNotEmpty()) {
                        for (file in nonVideoNewFiles) {
                            val entity = VideoItemEntity(
                                relativePath = file.relativePath,
                                fileName = file.fileName,
                                localAbsolutePath = file.absolutePath,
                                fileSize = file.fileSize,
                                syncRelationId = 0
                            )
                            videoRepository.upsert(entity)
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = "扫描失败: ${e.message}"
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    fun startRebuild() {
        val result = _discoveryResult.value ?: return
        rebuildJob = viewModelScope.launch {
            _isRebuilding.value = true
            _completedCount.value = 0
            _error.value = null

            try {
                withContext(Dispatchers.IO) {
                    for (file in result.newFiles) {
                        _currentFile.value = file.fileName
                        val entity = VideoItemEntity(
                            relativePath = file.relativePath,
                            fileName = file.fileName,
                            localAbsolutePath = file.absolutePath,
                            fileSize = file.fileSize,
                            syncRelationId = 0
                        )
                        val id = videoRepository.upsert(entity)
                        val saved = videoRepository.getById(id)
                        if (saved != null) {
                            videoMetadataExtractor.extractAndSave(saved)
                        }
                        _completedCount.value++
                    }

                    for (record in result.orphanedRecords) {
                        _currentFile.value = record.fileName
                        playbackRepository.deleteByVideoId(record.id)
                        videoRepository.deleteById(record.id)
                        _completedCount.value++
                    }

                    for (record in result.incompleteRecords) {
                        _currentFile.value = record.fileName
                        val fresh = videoRepository.getById(record.id) ?: continue
                        videoMetadataExtractor.extractAndSave(fresh)
                        _completedCount.value++
                    }
                }

                _discoveryResult.value = null
                _isComplete.value = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "重建失败: ${e.message}"
            } finally {
                _isRebuilding.value = false
                _currentFile.value = null
            }
        }
    }

    fun cancelRebuild() {
        rebuildJob?.cancel()
        rebuildJob = null
        _isRebuilding.value = false
        _currentFile.value = null
    }
}
