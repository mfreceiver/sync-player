package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A3（rev-gpt #3）：**生产存储组合**下的下载闸门回归——真实 [MediaFileStore]
 * （tmp→close 长度校验→finalize 原子 rename→verifyReadable）+ 临时目录。
 *
 * 边界声明：本文件不复制任何生产长度校验逻辑（旧 LengthCheckSink 已删）：
 * - store/StoragePaths = 生产实现（真文件、真 IOException、真原子 rename）；
 * - client = 受控 fake（真实字节写入真实 sink）；
 * - videoRepository = fake（LinkedHashMap）——**非真实 SQLite**；DAO 列所有权
 *   的真实 Room 验证在 VideoItemDaoColumnOwnershipTest；
 * - 本文件所有断言均为静态预期，未在本机执行（Gradle 由 verifier 统一执行）。
 *
 * 红证据（旧缺陷，A1-1）：downloadTo 返回即置 downloadSuccess ⇒ 真实
 * close 长度校验 IOException 之后循环带毒退出 ⇒（T1）finalize 0 次、
 * （T2）短写耗尽被误报成功、旧 final 语义未验证。
 */
class SyncDownloadPipelineRealStorageTest {

    private lateinit var rootDir: File
    private lateinit var store: MediaFileStore
    private lateinit var storagePaths: StoragePaths

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

    private lateinit var sut: SyncDownloadPipeline

    /** fake repository 行存储（非 SQLite；本测试只锁文件系统侧语义）。 */
    private val rows = LinkedHashMap<Long, VideoItemEntity>()
    /** updateSyncMetadata 到达时刻的 final 字节快照（证明元数据只在 final 就绪后提交）。 */
    private val commitTimeFinalBytes = CopyOnWriteArrayList<ByteArray>()

    private val oldBytes = ByteArray(500) { (it % 7).toByte() }
    private val newBytes = ByteArray(1000) { (it % 251).toByte() }
    private val truncatedBytes = newBytes.copyOf(999)

    @Before
    fun setUp() {
        rootDir = File(System.getProperty("java.io.tmpdir"), "pipeline-real-storage-${System.nanoTime()}")
        every { context.filesDir } returns File(rootDir, "files")
        storagePaths = StoragePaths(context)
        store = MediaFileStore(storagePaths, Dispatchers.Unconfined)
        val storeLazy = Lazy<MediaFileStore> { store }

        val deleteExecutor = SyncDeleteExecutor(
            syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
            syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
        )
        sut = SyncDownloadPipeline(
            syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
            syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
            clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
        )

        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        // fake repository：仅本测试所需最小面（existing 行查找 + 列提交 + 删除）
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
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
            commitTimeFinalBytes.add(runCatching { store.mediaFile(id).readBytes() }.getOrNull())
            1
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }
    }

    @After
    fun tearDown() {
        rootDir.setWritable(true)
        rootDir.deleteRecursively()
    }

    /** 现有行 42（磁盘上已有旧 final 字节）。 */
    private fun seedExistingRow(): VideoItemEntity {
        val row = VideoItemEntity(
            id = 42L, syncRelationId = 1L, relativePath = "videos/f.mp4", parentPath = "videos",
            fileName = "f.mp4", fileSize = 1L, star = 3, audioGainDb = 2.5f, mediaType = "VIDEO"
        )
        rows[42L] = row
        val final = store.mediaFile(42L)
        final.parentFile.mkdirs()
        final.writeBytes(oldBytes)
        return row
    }

    /** 受控 client：前 [truncatedFirstN] 次写 999 字节（真实短写），此后写足量 1000 字节。 */
    private fun stubClient(truncatedFirstN: Int): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        val calls = AtomicInteger(0)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            val sink = secondArg<OutputStream>()
            sink.write(if (calls.incrementAndGet() <= truncatedFirstN) truncatedBytes else newBytes)
            true
        }
        return client
    }

    private fun tmpLeftovers(): List<File> =
        store.mediaFile(42L).parentFile.listFiles { f -> f.name.endsWith(".tmp") }?.toList()
            ?: emptyList()

    private fun dlAction() = SyncAction(SyncActionType.DOWNLOAD, "f.mp4", 1000L, 5000L)

    /**
     * A1-1（红）· 真实存储：首传短写（999/1000）⇒ 真实 close 长度校验 IOException ⇒
     * 第 2 次传输足量 ⇒ 真实 finalize 原子替换旧 final。元数据只在成功后更新
     * （提交时刻 final 已是新字节）。
     */
    @Test
    fun `short write then full rewrite recovers and finalizes new bytes over old final`() = runTest {
        seedExistingRow()
        val client = stubClient(truncatedFirstN = 1)

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertTrue("second full attempt must complete the commit", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        coVerify(exactly = 2) { client.downloadTo(any(), any(), any()) }
        // 真实 final 字节 = 第二次足量内容（原子替换，非追加/残留）
        val final = store.mediaFile(42L)
        assertTrue(final.exists())
        assertArrayEquals(newBytes, final.readBytes())
        // 元数据只在成功后更新：提交时刻的 final 已是新字节（顺序锁）
        assertEquals(1, commitTimeFinalBytes.size)
        assertArrayEquals(newBytes, commitTimeFinalBytes.single())
        // 行内列提交（fake repo；真实 SQLite 列所有权见 DAO 测试）
        coVerify(exactly = 1) { videoRepository.updateSyncMetadata(42L, 1000L, 5000L, any()) }
        assertEquals(1000L, rows[42L]?.fileSize)
        assertEquals(3, rows[42L]?.star)
        // 真实 cancel 已清 attempt 1 的 tmp
        assertEquals("no .tmp leftovers after success", emptyList<File>(), tmpLeftovers())
    }

    /**
     * A1-1（红）· 真实存储：三次短写耗尽 ⇒ **旧 final 字节级不变**、行不更新、
     * 计一次失败。每次 attempt 的 tmp 被真实 cancel 清除（无 .tmp 残留）。
     */
    @Test
    fun `exhausted short writes keep old final bytes untouched and count failure`() = runTest {
        seedExistingRow()
        val client = stubClient(truncatedFirstN = 3)

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        assertFalse("exhausted length-verify failure must not report success", result)
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        coVerify(exactly = 3) { client.downloadTo(any(), any(), any()) }
        // 旧 final 字节级不变（红线 3：耗尽保留旧 final）
        val final = store.mediaFile(42L)
        assertTrue(final.exists())
        assertArrayEquals("old final must survive byte-for-byte", oldBytes, final.readBytes())
        // 行不动：fileSize 仍为旧值 1，无列提交
        assertEquals(1L, rows[42L]?.fileSize)
        coVerify(exactly = 0) { videoRepository.updateSyncMetadata(any(), any(), any(), any()) }
        assertEquals(0, commitTimeFinalBytes.size)
        // 每次 attempt 的 tmp 被真实 cancel 清理
        assertEquals("no .tmp leftovers after exhaustion", emptyList<File>(), tmpLeftovers())
        coVerify(exactly = 1) {
            syncLogRepository.log("DOWNLOAD", any(), "FAILED", any(), any(), any())
        }
    }
}
