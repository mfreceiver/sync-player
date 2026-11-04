package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.StorageDatasetDao
import cn.vectory.syncplayer.data.local.entity.StorageDatasetEntity
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.StorageAccessFailure
import cn.vectory.syncplayer.storage.TmpProbeState
import cn.vectory.syncplayer.storage.volume.MediaVolumeRegistry
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部 SD v2（任务包 B）· 端口层单测：
 *  - StorageAccessFailure → 新 SyncErrorCode 映射（缺卡/瞬态 IO/密钥/非法引用）。
 *  - 卷不可用 → probeFinalized IO_ERROR（E8 守卫：清空 toDownload/toDelete）。
 *  - Legacy 端口按 id 1:1 委托单卷 store（既有测试基座兼容性）。
 */
class SyncStoragePortTest {

    // ------------------------------------------------------------------ 错误码映射

    @Test
    fun `StorageAccessFailure reasons map to structured sync error codes`() {
        assertEquals(
            SyncErrorCode.STORAGE_UNAVAILABLE,
            StorageAccessFailure(StorageAccessFailure.Reason.VOLUME_UNAVAILABLE).toSyncErrorCode()
        )
        assertEquals(
            SyncErrorCode.STORAGE_IO_TRANSIENT,
            StorageAccessFailure(StorageAccessFailure.Reason.IO_TRANSIENT).toSyncErrorCode()
        )
        assertEquals(
            SyncErrorCode.KEY_UNAVAILABLE,
            StorageAccessFailure(StorageAccessFailure.Reason.KEY_UNAVAILABLE).toSyncErrorCode()
        )
        assertEquals(
            SyncErrorCode.STORAGE_ERROR,
            StorageAccessFailure(StorageAccessFailure.Reason.INVALID_REF).toSyncErrorCode()
        )
    }

    // ------------------------------------------------------------------ E8：缺卡 → IO_ERROR

    @Test
    fun `probeFinalized returns IO_ERROR when row volume is unavailable`() = runTest {
        val registry = mockk<MediaVolumeRegistry>()
        val videoRepository = mockk<VideoRepository>()
        val datasetDao = mockk<StorageDatasetDao>()
        val storeLazy = mockk<Lazy<MediaFileStore>>()
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        val port = DualVolumeSyncStoragePort(
            storeLazy, registry, videoRepository, datasetDao, prefs, StandardTestDispatcher()
        )
        val row = VideoItemEntity(
            id = 1L, syncRelationId = 1L, relativePath = "v/f.mp4", fileName = "f.mp4",
            fileSize = 10L, volumeId = "external:abc", storageFormat = "CTR_V2",
            iv = ByteArray(8), generation = "gen1",
        )
        coEvery { videoRepository.getById(1L) } returns row
        coEvery { datasetDao.get() } returns mockk {
            every { datasetId } returns "ds"
        }
        every { registry.isAvailable("external:abc") } returns false

        assertEquals(MediaProbeState.IO_ERROR, port.probeFinalized(1L, 10L))
    }

    // ------------------------------------------------------------------ Legacy 端口委托

    @Test
    fun `legacy port delegates by-id primitives to single-volume store`() = runTest {
        val store = mockk<MediaFileStore>(relaxed = true)
        val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
        val videoRepository = mockk<VideoRepository>(relaxed = true)
        val port = LegacySyncStoragePort(storeLazy, videoRepository)

        coEvery { store.probeFinalized(5L, 100L) } returns MediaProbeState.SIZE_MISMATCH
        coEvery { store.probePendingTmp(any<Long>()) } returns TmpProbeState.ABSENT
        coEvery { store.deleteAllMedia() } returns Pair(3, 1)
        coEvery { videoRepository.updateSyncMetadata(5L, 100L, 1L, 2L) } returns 1

        assertEquals(MediaProbeState.SIZE_MISMATCH, port.probeFinalized(5L, 100L))

        val attempt = port.newAttempt(5L, SyncVolumeTarget.INTERNAL, 100L)
        assertEquals(TmpProbeState.ABSENT, port.probePendingTmp(attempt))
        assertEquals(3 to 1, port.deleteAllOwned(VOLUME_ID_INTERNAL))
        assertEquals(1, port.updateSyncStorageMetadata(attempt, null, 100L, 1L, 2L))
        // legacy 端口：登记/注销外部卷为 no-op。
        assertEquals(null, port.registerExternalVolume())
        assertEquals(false, port.unregisterExternalVolume("external:x"))
        assertTrue(!port.isExternalVolume(VOLUME_ID_INTERNAL))
    }
}
