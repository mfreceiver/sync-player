package cn.vectory.syncplayer.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavigationHelper @Inject constructor(
    val videoQueue: VideoQueue,
    val imageQueue: ImageQueue
) : ViewModel()
