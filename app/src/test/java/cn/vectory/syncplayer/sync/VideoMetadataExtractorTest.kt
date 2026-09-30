package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A1 列所有权：[VideoMetadataExtractor] 经 [VideoRepository.updateVideoMetadata] 按列提交，
 * 绝不整行 upsert（不覆盖 star/audioGainDb、不复活被删行）。
 *
 * null 语义（rev-gpt 补充验收）：提取失败 ⇒ 早退不触 DB（既有列值保留）；
 * 提取成功但字段缺失 ⇒ 显式 null 提交（清除陈旧值）。
 *
 * JVM 环境限制：android.jar stub（isReturnDefaultValues）下 MediaMetadataRetriever 全部
 * 返回 null ⇒ 「成功但字段缺失」即本 harness 的可观测形态；提取失败路径以 mockkObject
 * 注入（MediaUris 抛异常）——注入点在生产受保护区内（uri 构造位于 try 内，A1-04），
 * 结果经显式接收 extractAndSave 返回值取得，不依赖 shadow。
 */
class VideoMetadataExtractorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>>()
    private val context = mockk<android.content.Context>(relaxed = true)

    @Before
    fun setUp() {
        every { storeLazy.get() } returns store
    }

    private fun entity() = VideoItemEntity(
        id = 5L,
        syncRelationId = 1L,
        relativePath = "rel/v.mp4",
        parentPath = "rel",
        fileName = "v.mp4",
        fileSize = 100L,
        // 下载前已有陈旧提取值——列提交不得动它们之外，也验证 null 覆盖语义
        duration = 55L,
        star = 4,
        audioGainDb = 3.5f,
    )

    @Test
    fun `successful extraction commits five video columns and never upserts`() = runTest {
        coEvery { videoRepository.updateVideoMetadata(any(), any(), any(), any(), any(), any()) } returns 1
        val sut = VideoMetadataExtractor(videoRepository, storeLazy, context)

        val returned = sut.extractAndSave(entity())

        // stub 环境提取「成功但字段全缺失」⇒ 五列全部显式 null（清除陈旧 55L）
        coVerify(exactly = 1) {
            videoRepository.updateVideoMetadata(5L, null, null, null, null, null)
        }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        // 返回实体携带提取结果（调用方展示用），不再作为整行写回依据
        assertNull(returned.duration)
        assertNull(returned.width)
        assertEquals(100L, returned.fileSize)
    }

    @Test
    fun `zero-row column update returns input entity without resurrection`() = runTest {
        coEvery { videoRepository.updateVideoMetadata(any(), any(), any(), any(), any(), any()) } returns 0
        val input = entity()
        val sut = VideoMetadataExtractor(videoRepository, storeLazy, context)

        val returned = sut.extractAndSave(input)

        // 行已消失：不复活、不 upsert，原实体原样返回
        assertSame(input, returned)
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    @Test
    fun `extraction failure keeps existing row untouched`() = runTest {
        val sut = VideoMetadataExtractor(videoRepository, storeLazy, context)
        val input = entity()

        // A3（rev-gpt）：作用域内显式接收返回值并断言，verify 在作用域外
        // （mockkObject 块形式退出即自动还原，返回值不经块返回值转递）。
        mockkObject(cn.vectory.syncplayer.provider.MediaUris) {
            every { cn.vectory.syncplayer.provider.MediaUris.mediaUri(any()) } throws RuntimeException("uri boom")
            val returned = sut.extractAndSave(input)
            assertSame(input, returned)
        }

        // 提取失败 ≠ 成功但字段缺失：绝不做任何 DB 写（陈旧 duration=55 保留在库中）
        coVerify(exactly = 0) { videoRepository.updateVideoMetadata(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    // ------------------------------------------------- 受控 Retriever（A3，rev-gpt）
    // mockkConstructor 拦截生产代码内 `MediaMetadataRetriever().use{...}` 的构造：
    // 创建成功（构造即 mock 实例）、setDataSource 失败、release(=close，use 语义) 恰一次。

    @Test
    fun `setDataSource failure is extraction failure with release exactly once`() = runTest {
        val sut = VideoMetadataExtractor(videoRepository, storeLazy, context)
        val input = entity()
        // A4（rev-gpt）：mediaUri 返回类型为 Kotlin 非空 Uri——mock 实例而非 null
        val uri = mockk<Uri>()
        mockkConstructor(MediaMetadataRetriever::class)
        try {
            mockkObject(cn.vectory.syncplayer.provider.MediaUris) {
                every { cn.vectory.syncplayer.provider.MediaUris.mediaUri(any()) } returns uri
                every {
                    anyConstructed<MediaMetadataRetriever>()
                        .setDataSource(any<android.content.Context>(), uri)
                } throws IOException("setDataSource failed")

                val returned = sut.extractAndSave(input)

                assertSame(input, returned)
            }
            // 提取失败：零 DB 写
            coVerify(exactly = 0) { videoRepository.updateVideoMetadata(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { videoRepository.upsert(any()) }
            // 构造成功（到达 setDataSource 恰一次）+ use 语义下 release 恰一次
            verify(exactly = 1) {
                anyConstructed<MediaMetadataRetriever>()
                    .setDataSource(any<android.content.Context>(), uri)
            }
            verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().close() }
        } finally {
            unmockkConstructor(MediaMetadataRetriever::class)
        }
    }

    @Test
    fun `cancellation from setDataSource propagates original CE with release exactly once`() = runTest {
        val sut = VideoMetadataExtractor(videoRepository, storeLazy, context)
        val input = entity()
        val ce = CancellationException("retriever-ce")
        // A4（rev-gpt）：同上——非空 Uri 用 mock 实例
        val uri = mockk<Uri>()
        mockkConstructor(MediaMetadataRetriever::class)
        try {
            mockkObject(cn.vectory.syncplayer.provider.MediaUris) {
                every { cn.vectory.syncplayer.provider.MediaUris.mediaUri(any()) } returns uri
                every {
                    anyConstructed<MediaMetadataRetriever>()
                        .setDataSource(any<android.content.Context>(), uri)
                } throws ce

                val thrown = runCatching { sut.extractAndSave(input) }.exceptionOrNull()

                // CE-first：原异常对象原样传播（不被提取失败分支转换/包裹）
                assertSame(ce, thrown)
            }
            coVerify(exactly = 0) { videoRepository.updateVideoMetadata(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { videoRepository.upsert(any()) }
            // use 在异常路径同样 close 恰一次
            verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().close() }
        } finally {
            unmockkConstructor(MediaMetadataRetriever::class)
        }
    }
}
