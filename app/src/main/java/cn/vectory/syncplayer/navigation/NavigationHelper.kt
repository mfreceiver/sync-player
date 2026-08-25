package cn.vectory.syncplayer.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

const val SAVED_VIDEO_IDS_KEY = "videoIds"
const val SAVED_IMAGE_IDS_KEY = "imageIds"

@HiltViewModel
class NavigationHelper @Inject constructor(
    val videoQueue: VideoQueue,
    val imageQueue: ImageQueue
) : ViewModel()
