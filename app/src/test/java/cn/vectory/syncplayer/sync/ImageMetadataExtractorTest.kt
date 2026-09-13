package cn.vectory.syncplayer.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AND-011: 两遍解码 — bounds 先行、缩略按需、EXIF 流保留。 */
class ImageMetadataExtractorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>>()
    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()

    @Before
    fun setUp() {
        every { storeLazy.get() } returns store
        every { context.contentResolver } returns contentResolver
        // 加密流不可 reset：每次 openInputStream 都要给全新流（与 Provider 行为一致）。
        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(ByteArray(0))
        }
    }

    private fun entity() = VideoItemEntity(
        id = 5L,
        syncRelationId = 1L,
        relativePath = "rel/photo.jpg",
        parentPath = "rel",
        fileName = "photo.jpg",
        fileSize = 100L,
        mediaType = "IMAGE",
    )

    private class RecordingDecoder(
        private val boundsWidth: Int,
        private val boundsHeight: Int,
        private val sampledResult: Bitmap? = mockk(relaxed = true),
    ) : BitmapDecoder {
        var boundsCalls = 0
        val sampledSizes = mutableListOf<Int>()

        override fun decodeBounds(stream: InputStream): IntArray {
            boundsCalls++
            return intArrayOf(boundsWidth, boundsHeight)
        }

        override fun decodeSampled(stream: InputStream, sampleSize: Int): Bitmap? {
            sampledSizes += sampleSize
            return sampledResult
        }
    }

    @Test
    fun `48mp image decodes bounds first then sampled thumbnail`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 8000, boundsHeight = 6000)
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder)

        sut.extractAndSave(entity())

        // pass1: 仅 bounds 解码一次
        assertEquals(1, decoder.boundsCalls)
        // pass2: 恰一次缩略解码；8000px → 640 目标 ⇒ 2 幂向下取 ≥12（即 16）
        assertEquals(1, decoder.sampledSizes.size)
        val sampleSize = decoder.sampledSizes[0]
        assertTrue("sampleSize=$sampleSize", sampleSize >= 12)
        assertTrue("sampleSize=$sampleSize not power of two", sampleSize and (sampleSize - 1) == 0)
        // 3 遍流：bounds / sampled / EXIF
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
        // 元数据来自 bounds；缩略图落盘一次
        val saved = slot<VideoItemEntity>()
        coVerify { videoRepository.upsert(capture(saved)) }
        assertEquals(8000, saved.captured.width)
        assertEquals(6000, saved.captured.height)
        coVerify(exactly = 1) { store.writeThumb(5L, any()) }
    }

    @Test
    fun `bounds dimensions still saved when sampled decode fails`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000, sampledResult = null)
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder)

        sut.extractAndSave(entity())

        val saved = slot<VideoItemEntity>()
        coVerify { videoRepository.upsert(capture(saved)) }
        assertEquals(4000, saved.captured.width)
        assertEquals(3000, saved.captured.height)
        coVerify(exactly = 0) { store.writeThumb(any(), any()) }
        // EXIF 流照常重开
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
    }
}
