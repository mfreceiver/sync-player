package cn.vectory.syncplayer.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.BrowseItem
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.ServerConfigRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val serverConfigRepository: ServerConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortOrder {
        ASCENDING,
        DESCENDING
    }

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ASCENDING)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val hasConfig: StateFlow<Boolean> = serverConfigRepository.getConfigFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val playbackRecords = playbackRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val browseItems: StateFlow<List<BrowseItem>> = combine(
        _currentFolder,
        _sortOrder,
        playbackRecords
    ) { folder, order, records ->
        buildBrowseItems(folder, order, records)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun buildBrowseItems(
        folder: String,
        order: SortOrder,
        records: List<PlaybackRecordEntity>
    ): List<BrowseItem> {
        val folders = videoRepository.getSubFolders(folder).first()
            .map { folderName ->
                BrowseItem(
                    videoItemId = null,
                    name = folderName,
                    relativePath = if (folder.isEmpty()) folderName else "$folder/$folderName",
                    isDirectory = true
                )
            }

        val videos = videoRepository.getItemsInFolder(folder).first()
            .map { video ->
                val record = records.find { it.videoItemId == video.id }
                BrowseItem(
                    videoItemId = video.id,
                    name = video.fileName,
                    relativePath = video.relativePath,
                    isDirectory = false,
                    fileSize = video.fileSize,
                    duration = video.duration,
                    width = video.width,
                    height = video.height,
                    bitrate = video.bitrate,
                    thumbnailPath = video.thumbnailPath,
                    playbackPosition = record?.position ?: 0,
                    playbackDuration = record?.duration ?: 0,
                    isCompleted = record?.isCompleted ?: false,
                    isFavorite = record?.isFavorite ?: false
                )
            }

        val allItems = folders + videos
        return when (order) {
            SortOrder.ASCENDING -> allItems.sortedBy { it.name.lowercase() }
            SortOrder.DESCENDING -> allItems.sortedByDescending { it.name.lowercase() }
        }
    }

    fun setFolder(folder: String) {
        _currentFolder.value = folder
    }

    fun navigateUp(): Boolean {
        val current = _currentFolder.value
        if (current.isEmpty()) {
            return false
        }
        val lastSlash = current.lastIndexOf('/')
        _currentFolder.value = if (lastSlash > 0) {
            current.substring(0, lastSlash)
        } else {
            ""
        }
        return true
    }

    fun toggleSortOrder() {
        _sortOrder.value = when (_sortOrder.value) {
            SortOrder.ASCENDING -> SortOrder.DESCENDING
            SortOrder.DESCENDING -> SortOrder.ASCENDING
        }
    }

    fun toggleFavorite(videoItemId: Long) {
        viewModelScope.launch {
            playbackRepository.toggleFavorite(videoItemId)
        }
    }

    fun shareVideo(videoItemId: Long) {
        viewModelScope.launch {
            val video = videoRepository.getById(videoItemId) ?: return@launch
            val file = File(video.localAbsolutePath)
            if (!file.exists()) return@launch

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(intent, "分享视频")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        }
    }
}
