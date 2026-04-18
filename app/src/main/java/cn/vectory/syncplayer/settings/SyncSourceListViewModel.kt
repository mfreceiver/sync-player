package cn.vectory.syncplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyncSourceListViewModel @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository
) : ViewModel() {
    val sources: StateFlow<List<SyncSource>> = syncSourceRepository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
