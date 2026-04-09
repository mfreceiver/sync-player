package cn.vectory.syncplayer.player

import android.content.pm.ActivityInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val userPrefs: UserPreferencesRepository
) : ViewModel() {

    private val _videoItem = MutableStateFlow<VideoItemEntity?>(null)
    val videoItem: StateFlow<VideoItemEntity?> = _videoItem.asStateFlow()

    private val _playbackRecord = MutableStateFlow<PlaybackRecordEntity?>(null)
    val playbackRecord: StateFlow<PlaybackRecordEntity?> = _playbackRecord.asStateFlow()
    private val _speedMultiplier = MutableStateFlow(2.0f)
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()
    private val _allVideoIds = MutableStateFlow<List<Long>>(emptyList())
    val allVideoIds: StateFlow<List<Long>> = _allVideoIds.asStateFlow()

    private val _progressAlwaysVisible = MutableStateFlow(false)
    val progressAlwaysVisible: StateFlow<Boolean> = _progressAlwaysVisible.asStateFlow()

    private val _volumeBalance = MutableStateFlow(false)
    val volumeBalance: StateFlow<Boolean> = _volumeBalance.asStateFlow()

    private val _currentGainDb = MutableStateFlow(0f)
    val currentGainDb: StateFlow<Float> = _currentGainDb.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val savedVideoIds: String? = savedStateHandle.get<String>("videoIds")

    init {
        loadFolderVideoIds()
        viewModelScope.launch {
            _speedMultiplier.value = userPrefs.getLongPressSpeed()
            _progressAlwaysVisible.value = userPrefs.getProgressAlwaysVisible()
            _volumeBalance.value = userPrefs.getVolumeBalance()
        }
    }

    private fun loadFolderVideoIds() {
        val idsStr = savedVideoIds ?: ""
        val ids = if (idsStr.isNotBlank()) {
            idsStr.split(",").mapNotNull { it.trim().toLongOrNull() }
        } else {
            emptyList()
        }
        if (ids.isNotEmpty()) {
            _allVideoIds.value = ids
        } else {
            viewModelScope.launch {
                _allVideoIds.value = videoRepository.getAllIdsSorted()
            }
        }
    }

    fun loadVideo(videoItemId: Long) {
        viewModelScope.launch {
            val video = videoRepository.getById(videoItemId)
            _videoItem.value = video
            if (video != null) {
                _playbackRecord.value = playbackRepository.getByVideoId(video.id)
                val gain = video.audioGainDb ?: 0f
                _currentGainDb.value = if (_volumeBalance.value && video.audioGainDb != null) gain else 0f
            }
        }
    }

    fun saveProgress(position: Long, duration: Long) {
        val videoId = _videoItem.value?.id ?: return
        viewModelScope.launch {
            playbackRepository.saveProgress(videoId, position, duration)
        }
    }

    fun toggleTrashed() {
        val videoId = _videoItem.value?.id ?: return
        viewModelScope.launch {
            playbackRepository.toggleTrashed(videoId)
            _playbackRecord.value = playbackRepository.getByVideoId(videoId)
        }
    }

    fun getSavedPosition(): Long = _playbackRecord.value?.position ?: 0L

    fun getVideoFilePath(): String? = _videoItem.value?.localAbsolutePath

    fun toggleFullscreen() {
        _isFullscreen.value = !_isFullscreen.value
    }

    fun resolveOrientation(videoWidth: Int?, videoHeight: Int?): Int {
        if (!_isFullscreen.value) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0) {
            return if (videoWidth >= videoHeight) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun getPrevVideoId(): Long? {
        val ids = _allVideoIds.value
        val currentId = _videoItem.value?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx > 0) ids[idx - 1] else null
    }

    fun getNextVideoId(): Long? {
        val ids = _allVideoIds.value
        val currentId = _videoItem.value?.id ?: return null
        val idx = ids.indexOf(currentId)
        return if (idx >= 0 && idx < ids.size - 1) ids[idx + 1] else null
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    override fun onCleared() {
        super.onCleared()
        _playbackSpeed.value = 1.0f
        _isFullscreen.value = false
    }
}
