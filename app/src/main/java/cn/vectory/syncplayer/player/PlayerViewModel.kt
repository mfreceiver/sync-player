package cn.vectory.syncplayer.player

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.OrientationPreference
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _videoItem = MutableStateFlow<VideoItemEntity?>(null)
    val videoItem: StateFlow<VideoItemEntity?> = _videoItem.asStateFlow()

    private val _playbackRecord = MutableStateFlow<PlaybackRecordEntity?>(null)
    val playbackRecord: StateFlow<PlaybackRecordEntity?> = _playbackRecord.asStateFlow()
    private val _speedMultiplier = MutableStateFlow(2.0f)
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()
    private val _orientationPreference = MutableStateFlow(OrientationPreference.FOLLOW_VIDEO)
    val orientationPreference: StateFlow<OrientationPreference> = _orientationPreference.asStateFlow()
    private val _forcedOrientation = MutableStateFlow<Int?>(null)
    val forcedOrientation: StateFlow<Int?> = _forcedOrientation.asStateFlow()
    private val _allVideoIds = MutableStateFlow<List<Long>>(emptyList())
    val allVideoIds: StateFlow<List<Long>> = _allVideoIds.asStateFlow()

    init {
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        _speedMultiplier.value = prefs.getFloat("long_press_speed", 2.0f)
        val prefStr = prefs.getString("orientation", null)
        _orientationPreference.value = try {
            OrientationPreference.valueOf(prefStr ?: OrientationPreference.FOLLOW_VIDEO.name)
        } catch (_: Exception) {
            OrientationPreference.FOLLOW_VIDEO
        }
        loadAllVideoIds()
    }

    private fun loadAllVideoIds() {
        viewModelScope.launch {
            _allVideoIds.value = videoRepository.getAllIdsSorted()
        }
    }

    fun loadVideo(videoItemId: Long) {
        viewModelScope.launch {
            val video = videoRepository.getById(videoItemId)
            _videoItem.value = video
            if (video != null) {
                _playbackRecord.value = playbackRepository.getByVideoId(video.id)
            }
        }
    }

    fun saveProgress(position: Long, duration: Long) {
        val videoId = _videoItem.value?.id ?: return
        viewModelScope.launch {
            playbackRepository.saveProgress(videoId, position, duration)
        }
    }

    fun toggleFavorite() {
        val videoId = _videoItem.value?.id ?: return
        viewModelScope.launch {
            playbackRepository.toggleFavorite(videoId)
            _playbackRecord.value = playbackRepository.getByVideoId(videoId)
        }
    }

    fun getSavedPosition(): Long = _playbackRecord.value?.position ?: 0L

    fun getVideoFilePath(): String? = _videoItem.value?.localAbsolutePath

    fun setForcedOrientation(orientation: Int) {
        _forcedOrientation.value = orientation
    }

    fun resetForcedOrientation() {
        _forcedOrientation.value = null
    }

    fun resolveOrientation(videoWidth: Int?, videoHeight: Int?): Int {
        val forced = _forcedOrientation.value
        if (forced != null) return forced
        val pref = _orientationPreference.value
        return if (pref == OrientationPreference.FOLLOW_VIDEO) {
            if (videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0) {
                if (videoWidth >= videoHeight) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
}
