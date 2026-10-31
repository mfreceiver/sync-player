package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.TmpProbeState
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部 SD v2（任务包 B）· 卷路由行为组：
 *  - 快照默认卷只决定新文件落卷；existing 行按自身 volumeId 选目标（切换默认不影响旧文件）。
 *  - 新行占位 PENDING + volumeId/storageFormat 随行落地；CAS 提交携带 expectedOldGeneration。
 *  - 整卷 offline 停派：STORAGE_UNAVAILABLE 不进重试白名单，且同卷后续 action 不再派发。
 *  - CAS 0 行 → 回滚（deleteFinal），绝不复活行。
 */
class SyncDownloadPipelineVolumeTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)

    private val port = FakeSyncStoragePort().apply {
        // P2-18：existing 外部 READY 行沿用其引用与世代。
        refs[42L] = SyncStorageRef(
            id = 42L, volumeId = "external:abc", datasetId = "ds", generation = "gen-7",
            format = SyncStorageFormat.CTR_V2, iv = ByteArray(8), plainSize = 1L,
        )
    }

    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor,
        storagePortOverride = port
    )

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } returns true
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns null
    }

    private fun dlAction(name: String = "f.mp4") = SyncAction(SyncActionType.DOWNLOAD, name, 1000L, 0L)

    @Test
    fun `new file targets snapshotted default volume`() = runTest {
        stubBasics()
        port.defaultVolumeId = "external:abc"

        assertTrue(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        assertEquals(listOf("external:abc"), port.newAttemptTargetVolumeIds)
    }

    @Test
    fun `existing row keeps its own volume regardless of default`() = runTest {
        stubBasics()
        port.defaultVolumeId = "internal"
        val existing = VideoItemEntity(
            id = 42L, syncRelationId = 1L, relativePath = "videos/f.mp4", fileName = "f.mp4",
            fileSize = 1L, volumeId = "external:abc", storageFormat = "CTR_V2",
            iv = ByteArray(8), generation = "gen-7",
        )
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existing
        port.refs[42L] = SyncStorageRef(
            id = 42L, volumeId = "external:abc", datasetId = "ds", generation = "gen-7",
            format = SyncStorageFormat.CTR_V2, iv = ByteArray(8), plainSize = 1L,
        )

        assertTrue(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        assertEquals(listOf("external:abc"), port.newAttemptTargetVolumeIds)
        // CAS 提交携带 existing 世代（0 行即失败不复活）。
        assertEquals(listOf("gen-7"), port.commitExpectedOldGenerations)
    }

    @Test
    fun `placeholder row is PENDING on target volume with CTR_V2 for external`() = runTest {
        stubBasics()
        port.defaultVolumeId = "external:abc"
        coEvery { videoRepository.upsert(any()) } returns 7L

        assertTrue(sut.executeSyncLocked(1L, listOf(dlAction()), emptyList()))

        val inserted = slot<VideoItemEntity>()
        io.mockk.coVerify { videoRepository.upsert(capture(inserted)) }
        assertEquals("external:abc", inserted.captured.volumeId)
        assertEquals("CTR_V2", inserted.captured.storageFormat)
        assertEquals("PENDING", inserted.captured.storageState)
    }

    @Test
    fun `STORAGE_UNAVAILABLE is not retried and stops dispatching remaining actions on that volume`() = runTest {
        stubBasics()
        port.defaultVolumeId = "external:abc"
        port.sinkErrorCode = SyncErrorCode.STORAGE_UNAVAILABLE

        val result = sut.executeSyncLocked(
            1L, listOf(dlAction("a.mp4"), dlAction("b.mp4"), dlAction("c.mp4")), emptyList()
        )

        // 第一个 action 失败后整卷停派：后续同卷 action 不再 openWriteSink（也不进 3 次重试）。
        assertEquals(1, port.openSinkCount)
        assertEquals(0, port.commitCount)
        assertTrue(!result)
    }

    @Test
    fun `CAS zero rows on external volume keeps winner row and only reclaims own attempt file`() = runTest {
        stubBasics()
        port.defaultVolumeId = "external:abc"
        coEvery { videoRepository.upsert(any()) } returns 7L
        port.commitRows = 0

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(!result)
        // P0-4：CAS=0 可能是并发赢家已推进 generation——绝不 deleteById（保赢家行/旧 READY 引用）。
        io.mockk.coVerify(exactly = 0) { videoRepository.deleteById(any()) }
        // 外部卷世代隔离路径：只回收本失败 attempt 的文件。
        assertTrue(port.deleteFinalCount >= 1)
    }

    @Test
    fun `CAS zero rows on internal volume keeps row and never deletes shared final path`() = runTest {
        stubBasics()
        port.defaultVolumeId = "internal"
        coEvery { videoRepository.upsert(any()) } returns 7L
        port.commitRows = 0

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(!result)
        // P0-4：内部卷 final 同路径可能已被并发赢家重写——不删 final、不删行。
        io.mockk.coVerify(exactly = 0) { videoRepository.deleteById(any()) }
        assertEquals(0, port.deleteFinalCount)
    }

    /**
     * P2-18（sol）：外部 rename 替换（existing 外部 READY 行）在 finalize 后、
     * CAS DB 提交前崩溃（verifyReadable 抛 STORAGE_UNAVAILABLE——卷被拔走）：
     * 重启后旧 READY 行与引用必须保留——只回收新 attempt 的世代文件，绝不 deleteById。
     */
    @Test
    fun `crash after finalize on external rename keeps existing READY row`() = runTest {
        stubBasics()
        port.defaultVolumeId = "external:abc"
        val existing = VideoItemEntity(
            id = 42L, syncRelationId = 1L, relativePath = "videos/f.mp4", fileName = "f.mp4",
            fileSize = 1L, volumeId = "external:abc", storageFormat = "CTR_V2",
            iv = ByteArray(8), generation = "gen-7",
        )
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } returns existing
        port.verifyErrorCode = SyncErrorCode.STORAGE_UNAVAILABLE

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue(!result)
        // 旧 READY 行保留（重启后 probe 修复路径接管）；绝不因缺卡删行/删旧世代 final。
        io.mockk.coVerify(exactly = 0) { videoRepository.deleteById(any()) }
        // 只回收本次失败 attempt 的 final（新世代，路径隔离）。
        assertTrue(port.deleteFinalCount >= 1)
    }
}

/** 端口 fake：记录调用面，行为可注入（sink 失败码 / 提交行数 / 卷可用性）。 */
private class FakeSyncStoragePort(
    var defaultVolumeId: String = VOLUME_ID_INTERNAL,
    var sinkErrorCode: SyncErrorCode? = null,
    var verifyErrorCode: SyncErrorCode? = null,
    var commitRows: Int = 1,
) : SyncStoragePort {

    val refs = mutableMapOf<Long, SyncStorageRef>()
    val newAttemptTargetVolumeIds = mutableListOf<String>()
    val commitExpectedOldGenerations = mutableListOf<String?>()
    var openSinkCount = 0
    var commitCount = 0
    var deleteFinalCount = 0

    private var attemptCounter = 0

    override suspend fun snapshotDefaultVolume(): SyncVolumeTarget = SyncVolumeTarget(defaultVolumeId)

    override suspend fun resolveRef(id: Long): SyncStorageRef? = refs[id]

    override suspend fun defaultWriteVolumeId(): String = defaultVolumeId

    override suspend fun setDefaultWriteVolume(volumeId: String): Boolean = true

    override fun isExternalVolume(volumeId: String): Boolean = volumeId != VOLUME_ID_INTERNAL

    override fun isVolumeAvailable(volumeId: String): Boolean = true

    override suspend fun listVolumes(): List<SyncVolumeInfo> =
        listOf(
            SyncVolumeInfo(VOLUME_ID_INTERNAL, false, true, Long.MAX_VALUE),
            SyncVolumeInfo(defaultVolumeId, isExternalVolume(defaultVolumeId), true, Long.MAX_VALUE),
        )

    override suspend fun registerExternalVolume(): String? = null

    override suspend fun unregisterExternalVolume(volumeId: String): Boolean = false

    override suspend fun newAttempt(id: Long, target: SyncVolumeTarget, expectedLength: Long): SyncWriteAttempt {
        newAttemptTargetVolumeIds.add(target.volumeId)
        return SyncWriteAttempt(
            SyncStorageRef(id = id, volumeId = target.volumeId, plainSize = expectedLength),
            attemptId = "a${attemptCounter++}",
        )
    }

    override suspend fun openWriteSink(attempt: SyncWriteAttempt): OutputStream {
        openSinkCount++
        sinkErrorCode?.let { throw SyncException(it, SyncOperation.DOWNLOAD) }
        return mockk(relaxed = true)
    }

    override suspend fun finalize(attempt: SyncWriteAttempt): Boolean = true

    override suspend fun verifyReadable(attempt: SyncWriteAttempt) {
        verifyErrorCode?.let { throw SyncException(it, SyncOperation.DOWNLOAD) }
    }

    override suspend fun cancel(attempt: SyncWriteAttempt) {}

    override suspend fun probePendingTmp(attempt: SyncWriteAttempt): TmpProbeState = TmpProbeState.ABSENT

    override suspend fun deleteFinal(attempt: SyncWriteAttempt): Boolean {
        deleteFinalCount++
        return true
    }

    override suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState =
        MediaProbeState.VALID

    override suspend fun usableSpace(target: SyncVolumeTarget): SyncSpaceOutcome =
        SyncSpaceOutcome.Available(Long.MAX_VALUE)

    override suspend fun checkMediaExistence(id: Long): MediaExistence = MediaExistence.NOT_FOUND

    override suspend fun checkThumbExistence(id: Long): MediaExistence = MediaExistence.NOT_FOUND

    override suspend fun deleteMedia(id: Long): Boolean = true

    override suspend fun deleteThumb(id: Long): Boolean = true

    override suspend fun ownedVolumeIds(): Set<String> = setOf(defaultVolumeId)

    override suspend fun deleteAllOwned(volumeId: String, datasetId: String?): Pair<Int, Int> = 0 to 0

    override suspend fun updateSyncStorageMetadata(
        attempt: SyncWriteAttempt,
        expectedOldGeneration: String?,
        fileSize: Long,
        remoteModifiedTime: Long?,
        dateSynced: Long,
    ): Int {
        commitCount++
        commitExpectedOldGenerations.add(expectedOldGeneration)
        return commitRows
    }
}
