package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.storage.MediaFinalProbe
import cn.vectory.syncplayer.storage.StoragePaths
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream

/**
 * A9 scan→execute 修复闭环集成（lane A 专属集成测试）。
 *
 * 真实件/受控件边界：
 *  - **真实** MediaFileStore + StoragePaths（tmpdir 单内部卷）：真 sink 长度校验、
 *    真 finalize 原子 rename、真 verifyReadable、真 probeFinalized（NOFOLLOW）；
 *  - **受控** repository（mockk + LinkedHashMap 有状态 fake，非真实 SQLite——列所有权
 *    的真实 Room 验证在 VideoItemDaoColumnOwnershipTest）与 RemoteFileClient
 *    （写真实字节进真 sink）。
 *
 * 前提声明（准确表述）：[cn.vectory.syncplayer.data.model.SyncAction] 只携带
 * relativePath，**不携带行主键**——「修复落在原 id=42」这一断言依赖执行侧以
 * getByRelativePathAndRelationId 将 action 绑定回既有行；本测试实证该绑定结果，
 * 而非宣称 action 与 id 存在强绑定。
 *
 * 本文件所有断言为静态预期，未在本机执行（Gradle 由 verifier 统一执行）。
 */
class SyncRepairIntegrationTest {

    private val newBytes = ByteArray(1000) { (it % 251).toByte() }

    private lateinit var rootDir: File
    private lateinit var storagePaths: StoragePaths
    private lateinit var store: MediaFileStore

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()

    /** 有状态 fake 行表（受控 repo 的存储侧）。 */
    private val rows = LinkedHashMap<Long, VideoItemEntity>()

    private lateinit var storeLazy: Lazy<MediaFileStore>

    @Before
    fun setUp() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "sync-repair-it-${System.nanoTime()}")
        val filesContext = mockk<android.content.Context>()
        every { filesContext.filesDir } returns File(rootDir, "files")
        storagePaths = StoragePaths(filesContext)
        store = MediaFileStore(storagePaths, Dispatchers.Unconfined)
        storeLazy = Lazy { store }

        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SFTP, host = "h", port = 22,
            username = "u", password = "p"
        )

        // 既有行 id=42：final 字节缺失（真实 MISSING 前置），star=5 为用户列哨兵
        rows[42L] = VideoItemEntity(
            id = 42L, syncRelationId = 1L, relativePath = "videos/a.mp4", parentPath = "videos",
            fileName = "a.mp4", fileSize = 1000L, remoteModifiedTime = 5000L,
            star = 5, mediaType = "VIDEO"
        )

        coEvery { videoRepository.getBySyncRelationId(1L) } coAnswers { rows.values.toList() }
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
        // 修复路径不得有任何整行 upsert（占位/复活均违规）——fake 记录但不动行表
        coEvery { videoRepository.upsert(any()) } coAnswers { firstArg<VideoItemEntity>().id }
        // A1 列提交 fake：行存在 ⇒ 改 3 列返回 1；行消失 ⇒ 返回 0（不复活）
        coEvery {
            videoRepository.updateSyncMetadata(any(), any(), any(), any())
        } coAnswers {
            val id = firstArg<Long>()
            val row = rows[id] ?: return@coAnswers 0
            rows[id] = row.copy(
                fileSize = secondArg(),
                remoteModifiedTime = thirdArg(),
                dateSynced = arg(3),
            )
            1
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }

        // 受控 client：单次把完整 1000 字节写进真 sink
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SFTP) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            secondArg<OutputStream>().write(newBytes)
            true
        }
        // A10：relaxed client 的 listFiles 默认返回空表会让首扫误入 DELETE_LOCAL——
        // 必须显式给出远端快照（真实 RemoteFile 构造签名）。
        // 首扫与复扫使用同一不可变列表（快照不得人为变更）。
        coEvery { client.listFiles("/remote") } returns listOf(
            RemoteFile(path = "a.mp4", name = "a.mp4", isDirectory = false, size = 1000L, lastModified = 5000L)
        )
    }

    @After
    fun tearDown() {
        rootDir.setWritable(true)
        rootDir.deleteRecursively()
    }

    private fun scanner() = SyncRemoteScanner(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, context, publisher,
        MediaFinalProbe { id, size -> store.probeFinalized(id, size) }
    )

    private fun pipeline() = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher,
        SyncDeleteExecutor(
            syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
            syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
        )
    )

    @Test
    fun `scan then execute repairs missing final on the original row id without placeholder`() = runTest {
        // ---- 前置：真实 final 缺失（磁盘上无 0/42）
        val scan = scanner().dryRunLocked(1L)

        // ---- scan：MISSING → 同路径 DOWNLOAD 修复候选（预览只读：不改行/文件）
        assertEquals(1, scan.toDownload.size)
        assertEquals(SyncActionType.DOWNLOAD, scan.toDownload[0].type)
        assertEquals("a.mp4", scan.toDownload[0].relativePath)
        assertEquals(1000L, scan.toDownload[0].fileSize)
        assertEquals(5000L, scan.toDownload[0].remoteModifiedTime)
        assertEquals("scan is preview and must not fail on a repairable row", null, scan.error)
        assertEquals("no delete candidate while remote still has the file", 0, scan.toDelete.size)
        assertEquals(1000L, rows[42L]?.fileSize) // DB 行未被扫描触碰

        // ---- execute：真实 sink/finalize/verify + 列提交
        val result = pipeline().executeSyncLocked(1L, scan.toDownload, emptyList())

        assertTrue("repair download must succeed", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        // 列提交恰好一次且落在原行（fake 的副作用之外再以 coVerify 锁定实参）
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(42L, 1000L, 5000L, any()) }

        // 真实 final 落在原 id 的媒体路径，字节与传输一致
        assertArrayEquals(newBytes, store.mediaFile(42L).readBytes())
        // 无 .tmp 残留（两阶段提交收尾干净）
        assertTrue(
            "no tmp leftover in bucket",
            store.mediaFile(42L).parentFile.listFiles()!!.none { it.name.endsWith(".tmp") }
        )

        // 原行保留 + 仅列提交：id 不变、用户列 star=5 不被覆盖、同步列更新
        assertNotNull("original row must survive repair", rows[42L])
        val row = rows.getValue(42L)
        assertEquals(42L, row.id)
        assertEquals(5, row.star)
        assertEquals(1000L, row.fileSize)
        assertEquals(5000L, row.remoteModifiedTime)
        assertTrue("dateSynced must be stamped", (row.dateSynced ?: 0L) > 0L)

        // 绝无占位 upsert（新行/复活均违规）——修复全程只有既有行
        coVerify(exactly = 0) { videoRepository.upsert(any()) }

        // 提取分发：视频提取器恰一次且实体主键 = 原行 42（不以 best-effort 吞 fixture 错误）；
        // 图片提取器零调用（a.mp4 非图片扩展名）
        coVerify(exactly = 1) { videoMetadataExtractor.extractAndSave(match { it.id == 42L }) }
        coVerify(exactly = 0) { imageMetadataExtractor.extractAndSave(any()) }

        // ---- 闭环：复扫（同一远端快照；DB 元数据已提交 + final 真实 VALID）→ SKIP
        // P2 批量通道迁移：成功扫描行经 logAll 批量落库，不再逐条 log——
        // 捕获复扫的全部批次，flatten 后精确断言目标行（恰一次）。
        val rescanLogBatches = mutableListOf<List<SyncLogEntity>>()
        coEvery { syncLogRepository.logAll(capture(rescanLogBatches)) } returns Unit
        val rescan = scanner().dryRunLocked(1L)
        assertTrue("rescan must SKIP repaired row", rescan.toDownload.isEmpty())
        assertTrue("rescan must not plan deletes", rescan.toDelete.isEmpty())
        assertEquals("healthy rescan must not be a failed scan", null, rescan.error)
        // SKIP 行恰一次且经批量通道落库（首扫是 DOWNLOAD/PENDING，复扫才是 SKIP）——
        // 不能仅以空计划冒充成功：目标行全字段精确匹配（action/path/status/fileSize/dryRun）。
        val loggedScanRows = rescanLogBatches.flatten()
        assertEquals(
            "rescan must log exactly one SKIP row via the P2 batch channel",
            1,
            loggedScanRows.count {
                it.action == "SKIP" && it.relativePath == "videos/a.mp4" &&
                    it.status == "SKIPPED" && it.fileSize == 1000L && it.dryRun
            }
        )
        // 逐条通道零调用：P2 后成功扫描行不得回落逐条 log（执行阶段的 DOWNLOAD
        // SUCCESS 仍走逐条 log，故仅锁定 SKIP 行）
        coVerify(exactly = 0) { syncLogRepository.log("SKIP", any(), any(), any(), any(), any()) }
    }
}
