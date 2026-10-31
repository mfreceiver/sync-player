package cn.vectory.syncplayer.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AND-011 两遍解码；A1 按列提交；A2（rev-gpt）失败/缺失语义：
 * null 流、非法 bounds、EXIF 读取失败 = **提取失败**（不触 DB，既有列保留）；
 * 解析成功但无标签 = 成功缺失（按列提交 null 清除陈旧值）。
 * EXIF 经 [ExifDateReader] 薄层注入（真 ExifInterface 在 JVM 不可控）。
 * A3（rev-gpt #6）：null EXIF 流语义、真实 [AndroidExifDateReader]（手工字节）、
 * 缩略图 finally recycle 资源语义。
 */
class ImageMetadataExtractorTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>>()
    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()

    /** 解析成功但无拍摄时间（合法缺失）⇒ 提交 null。 */
    private object NullDateReader : ExifDateReader {
        override fun readDateTaken(stream: InputStream): Long? = null
    }

    /** 解析成功、携带拍摄时间。 */
    private class ConstDateReader(private val date: Long) : ExifDateReader {
        override fun readDateTaken(stream: InputStream): Long? = date
    }

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
        coEvery { videoRepository.updateImageMetadata(any(), any(), any(), any()) } returns 1
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, NullDateReader)

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
        // A1: 元数据按列提交（无 EXIF 标签 ⇒ dateTaken=null），绝不整行 upsert
        coVerify(exactly = 1) { videoRepository.updateImageMetadata(5L, 8000, 6000, null) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        // 缩略图落盘一次
        coVerify(exactly = 1) { store.writeThumb(5L, any()) }
    }

    @Test
    fun `bounds dimensions still saved when sampled decode fails`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000, sampledResult = null)
        coEvery { videoRepository.updateImageMetadata(any(), any(), any(), any()) } returns 1
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, NullDateReader)

        sut.extractAndSave(entity())

        coVerify(exactly = 1) { videoRepository.updateImageMetadata(5L, 4000, 3000, null) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        coVerify(exactly = 0) { store.writeThumb(any<Long>(), any()) }
        // EXIF 流照常重开
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
    }

    @Test
    fun `exif date taken is committed when present`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        coEvery { videoRepository.updateImageMetadata(any(), any(), any(), any()) } returns 1
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, decoder, ConstDateReader(1234567890L)
        )

        sut.extractAndSave(entity())

        coVerify(exactly = 1) { videoRepository.updateImageMetadata(5L, 4000, 3000, 1234567890L) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    @Test
    fun `zero-row column update returns input entity without resurrection`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        coEvery { videoRepository.updateImageMetadata(any(), any(), any(), any()) } returns 0
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, NullDateReader)
        val input = entity()

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    @Test
    fun `extraction failure keeps existing row untouched`() = runTest {
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, RecordingDecoder(1, 1), NullDateReader
        )
        val input = entity()
        every { contentResolver.openInputStream(any()) } throws RuntimeException("stream boom")

        val returned = sut.extractAndSave(input)

        // 提取失败 ⇒ 不触 DB（既有列值保留）
        assertSame(input, returned)
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    @Test
    fun `cancellation from stream propagates without being swallowed`() = runTest {
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, RecordingDecoder(1, 1), NullDateReader
        )
        every { contentResolver.openInputStream(any()) } throws CancellationException("ce-boom")

        val thrown = try {
            sut.extractAndSave(entity())
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("expected CancellationException but was ${thrown?.javaClass}", thrown is CancellationException)
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    // ------------------------------------------------- A1-05 失败 vs 成功缺失语义

    /**
     * A1-05（红）：bounds 流打不开（openInputStream 返回 null）= 提取失败——
     * 绝不当「成功但缺失」提交 (null,null,null) 清空既有元数据。
     * 红证据：旧代码 null 流静默跳过 bounds/缩略/EXIF ⇒ 走到底部按列提交
     * (5L, null, null, null)（exactly=0 断言失败、返回实体列被清空）。
     */
    @Test
    fun `null bounds stream is extraction failure not success-with-missing-fields`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, decoder, ConstDateReader(1234567890L)
        )
        val input = entity()
        every { contentResolver.openInputStream(any()) } returns null

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        // 既有列值保留：绝无任何 DB 写
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        coVerify(exactly = 0) { store.writeThumb(any<Long>(), any()) }
    }

    /**
     * A1-05（红）：bounds 非法（宽高 ≤0，如损坏文件）= 提取失败。
     * 红证据：旧代码 `if (w > 0 && h > 0)` 不成立直接跳过 ⇒ 提交
     * (5L, null, null, null) 清空既有宽高/dateTaken（exactly=0 断言失败）。
     */
    @Test
    fun `invalid bounds are extraction failure not success-with-missing-fields`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 0, boundsHeight = 6000)
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, decoder, ConstDateReader(1234567890L)
        )
        val input = entity()

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        assertEquals(1, decoder.boundsCalls) // 确已到达 bounds 解码
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
        coVerify(exactly = 0) { store.writeThumb(any<Long>(), any()) }
    }

    /**
     * A1-05（红）：EXIF 读取抛异常 = 提取失败——绝不当成功缺失提交 null 清空既有
     * dateTaken。注入点在 EXIF 读取层内部（第 3 遍流已打开、进入 use 之后）。
     * 红证据：旧代码内层 `catch (_: Exception) {}` 吞掉 ⇒ dateTaken 保持 null ⇒
     * 提交 (5L, 4000, 3000, null)（exactly=0 断言失败）。
     */
    @Test
    fun `exif read failure is extraction failure not success-with-missing-fields`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        val throwingReader = object : ExifDateReader {
            override fun readDateTaken(stream: InputStream): Long? =
                throw IOException("exif corrupt")
        }
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, throwingReader)
        val input = entity()

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        assertEquals(1, decoder.boundsCalls) // bounds/缩略阶段已通过，失败精确发生在 EXIF
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    /**
     * A1-03（红）：EXIF 读取**内部**抛 CE（第三次流打开后、读取中）必须原样传播——
     * 不得被内层 catch(Exception) 吞成提取失败/成功缺失。
     * 红证据：旧代码内层 catch 吞掉 CE ⇒ extractAndSave 正常返回且提交
     * (5L, 4000, 3000, null)（CE 断言与 exactly=0 断言均失败）。
     */
    @Test
    fun `cancellation inside exif read propagates without being swallowed`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        val ceReader = object : ExifDateReader {
            override fun readDateTaken(stream: InputStream): Long? =
                throw CancellationException("exif-ce")
        }
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, ceReader)

        val thrown = try {
            sut.extractAndSave(entity())
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("expected CancellationException but was ${thrown?.javaClass}", thrown is CancellationException)
        assertEquals(1, decoder.boundsCalls) // 注入点精确在第 3 遍流内部，非 openInputStream 入口
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    // ------------------------------------------------- A3（rev-gpt #6）补充

    /**
     * A3（红）：第 3 遍（EXIF）流 openInputStream 返回 null = 提取失败——
     * 绝不当成功缺失提交 null 清空既有 dateTaken（bounds/缩略已成功不等于全成功）。
     * 红证据（旧代码）：exif 流 null 静默跳过 ⇒ dateTaken 保持 null 走到底部
     * 提交 (5L, 4000, 3000, null)（exactly=0 断言失败）。
     */
    @Test
    fun `null exif stream is extraction failure not success-with-missing-fields`() = runTest {
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000)
        val sut = ImageMetadataExtractor(
            videoRepository, storeLazy, context, decoder, ConstDateReader(1234567890L)
        )
        val input = entity()
        val opens = AtomicInteger(0)
        every { contentResolver.openInputStream(any()) } answers {
            // bounds / 缩略两遍正常；第 3 遍（EXIF）打不开
            if (opens.incrementAndGet() <= 2) ByteArrayInputStream(ByteArray(0)) else null
        }

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        assertEquals(1, decoder.boundsCalls) // 前两阶段确已通过，失败精确发生在 EXIF 流
        verify(exactly = 3) { contentResolver.openInputStream(any()) }
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    /**
     * A3（rev-gpt #6）：真实 [AndroidExifDateReader] + 手工构造的最小 JPEG/EXIF 字节
     * （非 fake）——TIFF(II) + IFD0 单条目 tag 0x0132(DateTime, ASCII×20)。
     * 期望值用同格式 SimpleDateFormat 计算（与生产同一 JVM 时区 ⇒ 确定性相等）。
     */
    @Test
    fun `AndroidExifDateReader parses real EXIF DateTime from crafted JPEG`() {
        val reader = AndroidExifDateReader()
        val expected = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .parse("2023:05:01 12:00:00")!!.time

        val actual = reader.readDateTaken(ByteArrayInputStream(jpegWithExifDateTime()))

        assertEquals(expected, actual)
    }

    /**
     * A4（rev-gpt #2）：APP1 段长**含长度字段自身 2 字节** = 2 + 6("Exif\0\0") +
     * TIFF 载荷——此前缺陷漏算自身 2 字节。字节级锁定 SOI/APP1 定界与段长自洽
     * （段长 + 6 == 全文件长：SOI 2 + marker 2 + EOI 2；长度字段已包含在段长内）。
     * TIFF 内部（IFD0 偏移 8 / 值偏移 26 / count 20）维持正确，未改动。
     */
    @Test
    fun `crafted EXIF JPEG APP1 framing carries full segment length`() {
        val jpeg = jpegWithExifDateTime()

        // SOI
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        // APP1 marker @2
        assertEquals(0xFF, jpeg[2].toInt() and 0xFF)
        assertEquals(0xE1, jpeg[3].toInt() and 0xFF)
        // 段长（大端）= 长度字段自身 2 + "Exif\0\0" 6 + TIFF 载荷
        val lengthField = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
        assertEquals(jpeg.size - 6, lengthField)
        val segmentEnd = 4 + lengthField
        assertEquals(jpeg.size - 2, segmentEnd)
        assertEquals(0xFF, jpeg[segmentEnd].toInt() and 0xFF)
        assertEquals(0xD9, jpeg[segmentEnd + 1].toInt() and 0xFF)
        // "Exif\0\0" @6..11
        assertEquals("Exif", String(jpeg, 6, 4, Charsets.US_ASCII))
        assertEquals(0, jpeg[10].toInt())
        assertEquals(0, jpeg[11].toInt())
        // TIFF 小端头 @12："II" + 42；IFD0 偏移字段 @16..19 == 8
        assertEquals(0x49, jpeg[12].toInt() and 0xFF)
        assertEquals(0x49, jpeg[13].toInt() and 0xFF)
        val ifd0Offset = (jpeg[16].toInt() and 0xFF) or
            ((jpeg[17].toInt() and 0xFF) shl 8) or
            ((jpeg[18].toInt() and 0xFF) shl 16) or
            ((jpeg[19].toInt() and 0xFF) shl 24)
        assertEquals(8, ifd0Offset)
    }

    /**
     * A4（rev-gpt #2）：无 EXIF 而言的成功 null 必须用**真正可解码的 JPEG**证明
     * ——旧 4 字节 SOI+EOI 并非合法 JPEG。fixture 由 JVM `javax.imageio.ImageIO`
     * 编码生成（真实 JPEG 流、JFIF APP0、无 EXIF APP1；非 Android Bitmap/shadow），
     * 并在测试内用 ImageIO 回读断言可解码性。
     */
    @Test
    fun `AndroidExifDateReader returns null for real decodable JPEG without EXIF tags`() {
        val jpeg = minimalJpegWithoutExif()
        // fixture 自检：JVM 标准解码器可解码（真实 JPEG，非 4 字节假流）
        assertNotNull(javax.imageio.ImageIO.read(ByteArrayInputStream(jpeg)))

        val actual = AndroidExifDateReader()
            .readDateTaken(ByteArrayInputStream(jpeg))

        assertNull("no EXIF tags in a valid JPEG must parse to null (成功缺失)", actual)
    }

    /** JVM ImageIO 生成的 8×8 真实 JPEG（无 EXIF 依赖；无 Android API）。 */
    private fun minimalJpegWithoutExif(): ByteArray {
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    /** 最小 JPEG：SOI + APP1("Exif\0\0" + TIFF II + IFD0[DateTime]) + EOI。 */
    private fun jpegWithExifDateTime(value: String = "2023:05:01 12:00:00"): ByteArray {
        val tiff = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))        // "II" + 42（小端）
            write(byteArrayOf(0x08, 0x00, 0x00, 0x00))        // IFD0 偏移 = 8
            write(byteArrayOf(0x01, 0x00))                    // 1 个目录项
            write(byteArrayOf(0x32, 0x01))                    // tag 0x0132 DateTime
            write(byteArrayOf(0x02, 0x00))                    // type ASCII
            write(byteArrayOf(0x14, 0x00, 0x00, 0x00))        // count 20（含 NUL）
            write(byteArrayOf(0x1A, 0x00, 0x00, 0x00))        // 值偏移 26（小端）
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00))        // next IFD = 0
            write(value.toByteArray(Charsets.US_ASCII))       // 19 字节 @26
            write(0)                                          // NUL ⇒ 恰 20 字节
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0xFF); write(0xD8)                          // SOI
            write(0xFF); write(0xE1)                          // APP1
            // A4（rev-gpt #2）：段长含长度字段自身 2 字节（旧版漏算 ⇒ APP1 帧错位）
            val size = 2 + 6 + tiff.size
            write((size shr 8) and 0xFF); write(size and 0xFF)
            write("Exif".toByteArray(Charsets.US_ASCII)); write(0); write(0)
            write(tiff)
            write(0xFF); write(0xD9)                          // EOI
        }.toByteArray()
    }

    /**
     * A3（资源语义）：缩略图写盘抛 IOException ⇒ 提取失败（0 DB），bitmap 在
     * finally 中恰 recycle 一次（泄漏锁）。既有语义注记：缩略失败会中止整个提取
     * （bounds/dateTaken 一并不提交）——缺口不在本测试掩盖，见报告「剩余项」。
     */
    @Test
    fun `thumbnail write failure recycles bitmap in finally and is extraction failure`() = runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000, sampledResult = bitmap)
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, NullDateReader)
        val input = entity()
        coEvery { store.writeThumb(any<Long>(), any()) } throws IOException("thumb io")

        val returned = sut.extractAndSave(input)

        assertSame(input, returned)
        verify(exactly = 1) { bitmap.recycle() }
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }

    /** A3（资源语义）：缩略图写盘抛 CE ⇒ CE 原样传播（CE-first），finally 仍 recycle 恰一次。 */
    @Test
    fun `thumbnail write cancellation propagates and still recycles bitmap`() = runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val decoder = RecordingDecoder(boundsWidth = 4000, boundsHeight = 3000, sampledResult = bitmap)
        val sut = ImageMetadataExtractor(videoRepository, storeLazy, context, decoder, ConstDateReader(1L))
        val ce = CancellationException("thumb-ce")
        coEvery { store.writeThumb(any<Long>(), any()) } throws ce

        val thrown = try {
            sut.extractAndSave(entity())
            null
        } catch (e: Throwable) {
            e
        }

        assertSame(ce, thrown)
        verify(exactly = 1) { bitmap.recycle() }
        coVerify(exactly = 0) { videoRepository.updateImageMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 0) { videoRepository.upsert(any()) }
    }
}
