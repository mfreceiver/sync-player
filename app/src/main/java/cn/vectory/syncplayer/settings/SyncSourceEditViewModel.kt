package cn.vectory.syncplayer.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.SyncSourceCredentialUncertainException
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * F09: typed connection-test outcome. The ViewModel only carries the type (+ structured
 * [SyncErrorCode]); display strings are mapped at the Screen via stringResource against
 * existing entries — the ViewModel never produces copy.
 */
sealed interface SourceTestResult {
    data object Connected : SourceTestResult
    data class TestFailed(val code: SyncErrorCode) : SourceTestResult
}

/** F09: typed save/delete outcome — 仅 Saved 触发返回导航；颜色由类型分支决定。 */
sealed interface SourceSaveResult {
    data object Saved : SourceSaveResult
    data object Deleted : SourceSaveResult

    /** 数据源仍被同步关系引用，拒绝删除（沿用旧行为渲染为警示色）。 */
    data class BlockedByRelations(val relationCount: Int) : SourceSaveResult
    data class SaveFailed(val code: SyncErrorCode) : SourceSaveResult
    data class DeleteFailed(val code: SyncErrorCode) : SourceSaveResult
}

enum class SourceEditLoadState { LOADING, READY, CREDENTIAL_RECOVERY, FAILED }

/**
 * F09: 失败码归一（file-private，仅本页类型化结果使用）。对 SyncException 直接取 code；
 * 对非 SyncException 的回退分类镜像 [cn.vectory.syncplayer.sync.syncErrorMessage]
 * （SocketTimeout/UnknownHost→超时族、ConnectException→CONNECTION_REFUSED、其余→UNKNOWN），
 * 以便 Screen 按 F02 模式用码重建既有资源文案。
 */
private fun resolveFailureCode(e: Throwable): SyncErrorCode =
    (e as? SyncException)?.code ?: when (e) {
        is SocketTimeoutException -> SyncErrorCode.TIMEOUT
        is UnknownHostException -> SyncErrorCode.UNKNOWN_HOST
        is ConnectException -> SyncErrorCode.CONNECTION_REFUSED
        else -> SyncErrorCode.UNKNOWN
    }

@HiltViewModel
class SyncSourceEditViewModel @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository,
    private val remoteFileClientFactory: RemoteFileClientFactory,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val sourceId: Long = savedStateHandle.get<Long>("sourceId") ?: 0L

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    fun setName(value: String) { if (canEdit()) _name.value = value }

    private val _protocol = MutableStateFlow(SyncProtocol.SMB)
    val protocol: StateFlow<SyncProtocol> = _protocol.asStateFlow()
    fun setProtocol(p: SyncProtocol) {
        if (!canEdit() || _protocol.value == p) return
        identityChanged()
        _protocol.value = p
        _port.value = if (p == SyncProtocol.SMB) "445" else "22"
    }

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()
    fun setHost(value: String) {
        if (!canEdit() || _host.value == value) return
        identityChanged()
        _host.value = value
    }

    private val _port = MutableStateFlow("445")
    val port: StateFlow<String> = _port.asStateFlow()
    fun setPort(value: String) {
        if (!canEdit() || _port.value == value) return
        identityChanged()
        _port.value = value
    }

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    fun setUsername(value: String) {
        if (!canEdit() || _username.value == value) return
        identityChanged()
        _username.value = value
    }

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    fun setPassword(value: String) {
        if (!canEdit()) return
        identityChanged()
        _password.value = value
        _emptyPasswordConfirmed.value = false
    }
    private val _emptyPasswordConfirmed = MutableStateFlow(false)
    val emptyPasswordConfirmed = _emptyPasswordConfirmed.asStateFlow()
    fun confirmEmptyPassword(confirmed: Boolean) {
        if (canEdit()) _emptyPasswordConfirmed.value = confirmed
    }
    private val _loadState = MutableStateFlow(
        if (sourceId > 0L) SourceEditLoadState.LOADING else SourceEditLoadState.READY
    )
    val loadState = _loadState.asStateFlow()

    private fun canUseForm(): Boolean = when (_loadState.value) {
        SourceEditLoadState.READY -> true
        SourceEditLoadState.CREDENTIAL_RECOVERY ->
            _password.value.isNotEmpty() || _emptyPasswordConfirmed.value
        else -> false
    }
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<SourceTestResult?>(null)
    val testResult: StateFlow<SourceTestResult?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<SourceSaveResult?>(null)
    val saveResult: StateFlow<SourceSaveResult?> = _saveResult.asStateFlow()
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var testGeneration = 0L
    private var testJob: Job? = null

    private fun canEdit() = !_isSaving.value &&
        (_loadState.value == SourceEditLoadState.READY ||
            _loadState.value == SourceEditLoadState.CREDENTIAL_RECOVERY)

    private fun identityChanged() {
        _emptyPasswordConfirmed.value = false
        invalidateTest()
    }

    private fun invalidateTest() {
        testGeneration++
        testJob?.cancel()
        _isTesting.value = false
        _testResult.value = null
    }

    init {
        if (sourceId > 0L) retryLoad()
    }

    fun retryLoad(): Job {
        // A retry is recovery, not a refresh of an editable form. Generation changes
        // before cancel so even non-cooperative old completions cannot publish.
        if (_isSaving.value || (_loadState.value != SourceEditLoadState.LOADING &&
                _loadState.value != SourceEditLoadState.FAILED)) return Job().apply { complete() }
        val generation = ++loadGeneration
        loadJob?.cancel()
        invalidateTest()
        _loadState.value = SourceEditLoadState.LOADING
        _password.value = ""
        _emptyPasswordConfirmed.value = false
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val existing = try {
                    syncSourceRepository.getById(sourceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SyncSourceCredentialUncertainException) {
                    if (generation != loadGeneration) return@launch
                    val sources = syncSourceRepository.getAll()
                    if (generation != loadGeneration) return@launch
                    val info = sources.firstOrNull { it.id == sourceId }
                        ?: error("Source metadata missing")
                    populate(info, includePassword = false)
                    _loadState.value = SourceEditLoadState.CREDENTIAL_RECOVERY
                    return@launch
                }
                if (generation != loadGeneration) return@launch
                checkNotNull(existing) { "Source missing" }
                populate(existing, includePassword = true)
                _loadState.value = SourceEditLoadState.READY
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == loadGeneration) _loadState.value = SourceEditLoadState.FAILED
            }
        }
        loadJob = job
        job.start()
        return job
    }

    private fun populate(source: SyncSource, includePassword: Boolean) {
        _name.value = source.name
        _protocol.value = source.protocol
        _host.value = source.host
        _port.value = source.port.toString()
        _username.value = source.username
        _password.value = if (includePassword) source.password else ""
    }

    fun testConnection() {
        if (!canUseForm() || _isTesting.value || _isSaving.value) return
        val generation = ++testGeneration
        val source = buildSource()
        _isTesting.value = true
        _testResult.value = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val client = remoteFileClientFactory.create(source.protocol)
                client.testConnection(source, "/")
                if (generation == testGeneration) _testResult.value = SourceTestResult.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == testGeneration) _testResult.value = SourceTestResult.TestFailed(resolveFailureCode(e))
            } finally {
                if (generation == testGeneration) _isTesting.value = false
            }
        }
        testJob = job
        job.start()
    }

    fun save() {
        if (!canUseForm() || _isSaving.value) return
        if (_loadState.value == SourceEditLoadState.CREDENTIAL_RECOVERY &&
            (_name.value.isBlank() || _host.value.isBlank())) return
        val source = buildSource()
        _isSaving.value = true
        _saveResult.value = null
        invalidateTest()
        viewModelScope.launch {
            try {
                syncSourceRepository.save(source)
                _loadState.value = SourceEditLoadState.READY
                _emptyPasswordConfirmed.value = false
                _saveResult.value = SourceSaveResult.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveResult.value = SourceSaveResult.SaveFailed(resolveFailureCode(e))
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteSource() {
        if (_isSaving.value) return
        _isSaving.value = true
        invalidateTest()
        viewModelScope.launch {
            _saveResult.value = null
            try {
                val count = syncSourceRepository.getRelationCount(sourceId)
                if (count > 0) {
                    _saveResult.value = SourceSaveResult.BlockedByRelations(count)
                } else {
                    syncSourceRepository.delete(sourceId)
                    _saveResult.value = SourceSaveResult.Deleted
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveResult.value = SourceSaveResult.DeleteFailed(resolveFailureCode(e))
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun buildSource() = SyncSource(
        id = if (sourceId > 0L) sourceId else 0L,
        name = _name.value.trim(),
        protocol = _protocol.value,
        host = _host.value.trim(),
        port = _port.value.toIntOrNull() ?: if (_protocol.value == SyncProtocol.SMB) 445 else 22,
        username = _username.value.trim(),
        password = _password.value
    )
}
