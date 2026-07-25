package cn.vectory.syncplayer.sync

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Contract test for [RemoteFileClient.downloadTo].
 *
 * Verifies:
 * 1. `downloadTo` writes data into the provided [sink].
 * 2. `downloadTo` does NOT close the [sink]; the caller retains lifecycle control.
 */
class RemoteFileClientContractTest {

    @Test
    fun `downloadTo writes to sink and does not close it`() = runTest {
        // Arrange
        val client = mockk<RemoteFileClient>()
        val sink = spyk(ByteArrayOutputStream())
        val testData = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f) // "Hello"

        // Simulate the real implementation: write testData to sink, then return true, never closing sink
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            val remotePath: String = firstArg()
            val outputStream: OutputStream = secondArg()
            val onProgress: ((Long, Long) -> Unit)? = thirdArg()

            outputStream.write(testData)
            onProgress?.invoke(testData.size.toLong(), testData.size.toLong())
            true
        }

        // Act
        val result = client.downloadTo("some/file.mp4", sink)

        // Assert: data was written
        assertTrue("downloadTo should return true on success", result)
        assertArrayEquals("Sink should contain the test data", "Hello".toByteArray(), sink.toByteArray())
        // Verify close was NOT called
        verify(exactly = 0) { sink.close() }
    }
}
