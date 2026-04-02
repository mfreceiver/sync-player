package com.mfreceiver.syncplayer.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mfreceiver.syncplayer.data.local.entity.PlaybackRecordEntity
import com.mfreceiver.syncplayer.data.local.entity.VideoItemEntity
import com.mfreceiver.syncplayer.data.repository.PlaybackRepository
import com.mfreceiver.syncplayer.data.repository.VideoRepository
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

    init {
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        _speedMultiplier.value = prefs.getFloat("long_press_speed", 2.0f)
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
}
