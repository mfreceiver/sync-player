package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A1（rev-gpt 补充验收）：真实 Room 列所有权测试。
 *
 * 锁定三条按列 UPDATE 的语义（不 mock DAO——真 SQLite 执行计划）：
 *  - [VideoItemDao.updateSyncMetadata]：只动 fileSize/remoteModifiedTime/dateSynced，
 *    绝不触碰用户列（star/audioGainDb）与提取器列（duration/width/height/bitrate/
 *    frameRate/dateTaken/mediaType）；
 *  - [VideoItemDao.updateVideoMetadata] / [VideoItemDao.updateImageMetadata]：完整
 *    覆盖各自全部所有列（含显式 null 清除陈旧值——「成功但字段缺失」语义），
 *    不动同步列与用户列；
 *  - 行不存在（含已删除）⇒ 返回 0 且绝不插入（不复活）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class VideoItemDaoColumnOwnershipTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 预置一行：每列都带可区分的非默认值（star/audioGain=用户列；其余=提取/同步列）。 */
    private suspend fun seed(): VideoItemEntity {
        val seeded = VideoItemEntity(
            id = 0,
            syncRelationId = 1L,
            relativePath = "videos/a.mp4",
            parentPath = "videos",
            fileName = "a.mp4",
            fileSize = 111L,
            duration = 222L,
            width = 333,
            height = 444,
            bitrate = 555L,
            frameRate = 66.6f,
            audioGainDb = 7.7f,
            dateSynced = 888L,
            remoteModifiedTime = 999L,
            star = 3,
            mediaType = "IMAGE",
            dateTaken = 1234L,
        )
        val id = dao.upsert(seeded)
        return seeded.copy(id = id)
    }

    // ------------------------------------------------------------------ updateSyncMetadata

    @Test
    fun `updateSyncMetadata writes only its three sync columns`() = runBlocking {
        val seeded = seed()

        val rows = dao.updateSyncMetadata(
            id = seeded.id,
            fileSize = 1000L,
            remoteModifiedTime = 5000L,
            dateSynced = 6000L,
        )

        assertEquals(1, rows)
        val after = dao.getById(seeded.id)!!
        // 同步列：全部更新
        assertEquals(1000L, after.fileSize)
        assertEquals(5000L, after.remoteModifiedTime)
        assertEquals(6000L, after.dateSynced)
        // 用户列：原样保留
        assertEquals(3, after.star)
        assertEquals(7.7f, after.audioGainDb)
        // 提取器列：原样保留
        assertEquals(222L, after.duration)
        assertEquals(333, after.width)
        assertEquals(444, after.height)
        assertEquals(555L, after.bitrate)
        assertEquals(66.6f, after.frameRate)
        assertEquals(1234L, after.dateTaken)
        assertEquals("IMAGE", after.mediaType)
        // 身份列：原样保留
        assertEquals(seeded.relativePath, after.relativePath)
        assertEquals(seeded.fileName, after.fileName)
    }

    @Test
    fun `updateSyncMetadata accepts null remoteModifiedTime overwriting stale value`() = runBlocking {
        val seeded = seed()

        val rows = dao.updateSyncMetadata(
            id = seeded.id,
            fileSize = 1000L,
            remoteModifiedTime = null,
            dateSynced = 6000L,
        )

        assertEquals(1, rows)
        assertNull(dao.getById(seeded.id)!!.remoteModifiedTime)
    }

    // ------------------------------------------------------------------ updateVideoMetadata

    @Test
    fun `updateVideoMetadata writes all five video columns and clears stale with null`() = runBlocking {
        val seeded = seed()

        // 「成功但字段缺失」语义：显式 null 必须清掉陈旧非空值（不是保留、不是跳过）
        val rows = dao.updateVideoMetadata(
            id = seeded.id,
            duration = 90000L,
            width = 1920,
            height = 1080,
            bitrate = null,
            frameRate = 29.97f,
        )

        assertEquals(1, rows)
        val after = dao.getById(seeded.id)!!
        assertEquals(90000L, after.duration)
        assertEquals(1920, after.width)
        assertEquals(1080, after.height)
        assertNull(after.bitrate)
        assertEquals(29.97f, after.frameRate)
        // 用户列 + 同步列 + 图片列 + mediaType：原样保留
        assertEquals(3, after.star)
        assertEquals(7.7f, after.audioGainDb)
        assertEquals(111L, after.fileSize)
        assertEquals(999L, after.remoteModifiedTime)
        assertEquals(888L, after.dateSynced)
        assertEquals(1234L, after.dateTaken)
        assertEquals("IMAGE", after.mediaType)
    }

    // ------------------------------------------------------------------ updateImageMetadata

    @Test
    fun `updateImageMetadata writes its three image columns only`() = runBlocking {
        val seeded = seed()

        val rows = dao.updateImageMetadata(
            id = seeded.id,
            width = 8000,
            height = 6000,
            dateTaken = 4321L,
        )

        assertEquals(1, rows)
        val after = dao.getById(seeded.id)!!
        assertEquals(8000, after.width)
        assertEquals(6000, after.height)
        assertEquals(4321L, after.dateTaken)
        // 视频专属列/用户列/同步列：原样保留
        assertEquals(222L, after.duration)
        assertEquals(555L, after.bitrate)
        assertEquals(66.6f, after.frameRate)
        assertEquals(3, after.star)
        assertEquals(7.7f, after.audioGainDb)
        assertEquals(111L, after.fileSize)
        assertEquals("IMAGE", after.mediaType)
    }

    // ------------------------------------------------------------------ 不存在/已删行 ⇒ 0 且不复活

    @Test
    fun `column updates on missing id return zero and never insert`() = runBlocking {
        assertEquals(0, dao.updateSyncMetadata(999L, 1L, 1L, 1L))
        assertEquals(0, dao.updateVideoMetadata(999L, null, null, null, null, null))
        assertEquals(0, dao.updateImageMetadata(999L, null, null, null))
        assertNull(dao.getById(999L))
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `column updates never resurrect a deleted row`() = runBlocking {
        val seeded = seed()
        dao.deleteById(seeded.id)

        assertEquals(0, dao.updateSyncMetadata(seeded.id, 1000L, 5000L, 6000L))
        assertEquals(0, dao.updateVideoMetadata(seeded.id, 1L, 1, 1, 1L, 1f))
        assertEquals(0, dao.updateImageMetadata(seeded.id, 1, 1, 1L))

        assertNull(dao.getById(seeded.id))
        assertEquals(0, dao.getAll().size)
    }

    // ------------------------------------------------------------------ 提取期间用户列交错（真实 Room）

    /**
     * A3 修正（rev-gpt）：顺序**列更新**（非跨组件并发）——分阶段读取断言。
     * width/height 是视频/图片共享列：后写者胜出是既定语义；视频独有列
     * （duration/bitrate/frameRate）与用户/同步列不受图片列提交影响。
     * 旧断言在同一 after 上同时断言两组宽高，永不可能通过。
     */
    @Test
    fun `sequential column updates stage reads and preserve owner columns`() = runBlocking {
        val seeded = seed()

        dao.updateSyncMetadata(seeded.id, fileSize = 1000L, remoteModifiedTime = 5000L, dateSynced = 6000L)
        dao.updateStar(seeded.id, 5)
        dao.updateVideoMetadata(seeded.id, duration = 90000L, width = 1920, height = 1080, bitrate = 8_000_000L, frameRate = 29.97f)
        dao.updateAudioGain(seeded.id, -3.25f)

        // 阶段 1（视频列提交后）：视频尺寸 + 用户/同步列就位
        val afterVideo = dao.getById(seeded.id)!!
        assertEquals(5, afterVideo.star)
        assertEquals(-3.25f, afterVideo.audioGainDb)
        assertEquals(1000L, afterVideo.fileSize)
        assertEquals(5000L, afterVideo.remoteModifiedTime)
        assertEquals(6000L, afterVideo.dateSynced)
        assertEquals(90000L, afterVideo.duration)
        assertEquals(1920, afterVideo.width)
        assertEquals(1080, afterVideo.height)
        assertEquals(8_000_000L, afterVideo.bitrate)
        assertEquals(29.97f, afterVideo.frameRate)

        // 阶段 2（图片列提交后重读）：共享列 width/height 为图片值（后写者胜出），
        // 视频独有列与用户/同步列保留，图片独有列 dateTaken 写入。
        dao.updateImageMetadata(seeded.id, width = 8000, height = 6000, dateTaken = 4321L)
        val afterImage = dao.getById(seeded.id)!!
        assertEquals(8000, afterImage.width)
        assertEquals(6000, afterImage.height)
        assertEquals(4321L, afterImage.dateTaken)
        // 视频独有列不被图片提交触碰
        assertEquals(90000L, afterImage.duration)
        assertEquals(8_000_000L, afterImage.bitrate)
        assertEquals(29.97f, afterImage.frameRate)
        // 用户/同步列不被任何提取列提交触碰
        assertEquals(5, afterImage.star)
        assertEquals(-3.25f, afterImage.audioGainDb)
        assertEquals(1000L, afterImage.fileSize)
    }

    /**
     * A2 顺序列更新（顺序 B，A3 注释修正）：提取先、用户后——旧实体快照在整行
     * upsert 世界里会把用户修改打回；按列 UPDATE 下用户最终修改胜出，提取列
     * （含显式 null 清除）不受影响。同为顺序列更新语义，非跨组件并发。
     */
    @Test
    fun `user column edits after extraction commits win without clobbering extraction`() = runBlocking {
        val seeded = seed()

        dao.updateVideoMetadata(
            seeded.id, duration = 90000L, width = 1920, height = 1080, bitrate = null, frameRate = 29.97f
        )
        dao.updateImageMetadata(seeded.id, width = 8000, height = 6000, dateTaken = null)
        dao.updateStar(seeded.id, 1)
        dao.updateAudioGain(seeded.id, 0f)

        val after = dao.getById(seeded.id)!!
        // 用户列的修改是最终事实
        assertEquals(1, after.star)
        assertEquals(0f, after.audioGainDb)
        // 提取列不受其后用户修改影响（含显式 null 清除语义保持）；
        // 共享列 width/height 保持图片提交值（视频提交先被图片覆盖，既定语义）
        assertEquals(90000L, after.duration)
        assertEquals(8000, after.width)
        assertEquals(6000, after.height)
        assertEquals(29.97f, after.frameRate)
        assertNull(after.bitrate)
        assertNull(after.dateTaken)
    }
}
