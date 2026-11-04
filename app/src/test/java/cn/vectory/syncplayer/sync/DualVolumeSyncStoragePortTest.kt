package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
import cn.vectory.syncplayer.data.local.entity.StorageDatasetEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.VolumeIds
import cn.vectory.syncplayer.storage.VolumeTarget
import cn.vectory.syncplayer.storage.volume.ExternalCandidate
import cn.vectory.syncplayer.storage.volume.MediaVolumeRegistry
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * P2-18 / P1-6（sol）· 缺卡重插与冷启动登记恢复：
 *  - 缺卡：持久化登记集合**不被裁剪**（拔卡≠注销），默认卷选择保留、绝不回落 internal。
 *  - 插回/冷启动：restoreVolumesAtStartup 按 persisted 集重登记，并恢复用户默认卷选择。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualVolumeSyncStoragePortTest {

    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>>()
    private val registry = mockk<MediaVolumeRegistry>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val datasetDao = mockk<StorageDatasetDao>()
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()

    private val candidate = ExternalCandidate(uuid = "u1", dir = File("/tmp/ext"))

    private lateinit var sut: DualVolumeSyncStoragePort

    @Before
    fun setUp() {
        every { storeLazy.get() } returns store
        coEvery { datasetDao.get() } returns StorageDatasetEntity(datasetId = "ds")
        coEvery { prefs.getRegisteredExternalVolumes() } returns setOf("u1")
        coEvery { prefs.getDefaultWriteVolume() } returns "external:u1"
        every { registry.enumerateExternalCandidates() } returns emptyList() // 缺卡
        every { registry.registerExternal(any(), any()) } returns "external:u1"
        every { registry.setDefaultWriteVolume(any()) } returns true
        every { registry.defaultWriteTarget() } returns VolumeTarget(VolumeIds.INTERNAL)
        every { registry.isAvailable(any()) } returns false
        every { registry.registeredVolumeIds() } returns listOf(VolumeIds.INTERNAL)
        sut = DualVolumeSyncStoragePort(
            storeLazy, registry, videoRepository, datasetDao, prefs, dispatcher,
        )
    }

    @Test
    fun `missing card keeps persisted registration and default volume choice`() = runTest(dispatcher) {
        sut.restoreVolumesAtStartup()

        // P1-6：缺卡不清登记——绝不把 persisted 集合改写为空。
        coVerify(exactly = 0) { prefs.setRegisteredExternalVolumes(any()) }
        // 默认卷选择保留（恢复尝试失败可接受），且绝不回落+持久化 internal。
        verify(atLeast = 1) { registry.setDefaultWriteVolume("external:u1") }
        coVerify(exactly = 0) { prefs.setDefaultWriteVolume(VolumeIds.INTERNAL) }

        // 快照仍返回用户选择的卷（后续按 STORAGE_UNAVAILABLE 结构化停派），不静默写内部。
        assertEquals("external:u1", sut.snapshotDefaultVolume().volumeId)
    }

    @Test
    fun `reinserted card is re-registered from persisted set at startup`() = runTest(dispatcher) {
        every { registry.enumerateExternalCandidates() } returns listOf(candidate)
        every { registry.isAvailable("external:u1") } returns true

        sut.restoreVolumesAtStartup()

        // 冷启动恢复：按 persisted uuid 重登记，并尝试恢复默认卷。
        verify(atLeast = 1) { registry.registerExternal(candidate, "ds") }
        verify(atLeast = 1) { registry.setDefaultWriteVolume("external:u1") }
    }
}
