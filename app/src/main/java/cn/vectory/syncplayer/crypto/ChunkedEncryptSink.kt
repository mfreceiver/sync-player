package cn.vectory.syncplayer.crypto

import java.io.IOException
import java.io.OutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException

/**
 * CTR 有界缓冲加密输出流（v2 新写；历史 ChunkedEncryptSink 是 GCM 分块 AEAD，不可复用）。
 *
 * 契约（设计文档「密钥与 crypto」）：
 * - 有界缓冲（[CryptoScheme.CTR_SINK_BUFFER]，256KiB）：写入明文先入缓冲，满块调用
 *   cipher.update 输出密文到底层 [sink]，天然 backpressure，不整文件驻内存。
 * - 每 attempt 一个 Cipher（[CryptoScheme.newCtrCipher]），分块 update；
 * - [close]：doFinal → 关闭底层 → 校验累计明文字节 == [expectedPlainLength]；
 *   短写/超写抛 [IOException]（不能 finalize）。任何异常本次 attempt 均失败；
 * - close 幂等；写后关闭再写抛 [IOException]；
 * - [CancellationException] 不包成 IOException（本类不 catch Exception，天然满足）；
 * - 纯 CTR 正文无头无 tag：ciphertextLength == plainLength（物理密文长度核验由
 *   store 的 finalize 路径对 tmp 文件 strictStat 完成）。
 *
 * [expectedPlainLength] 为负时由调用方（store）先行 require。
 */
class ChunkedEncryptSink(
    private val sink: OutputStream,
    private val key: SecretKey,
    private val iv: ByteArray,
    private val expectedPlainLength: Long,
) : OutputStream() {

    private val cipher = CryptoScheme.newCtrCipher(key, iv, 0L, encrypt = true)
    private val buffer = ByteArray(CryptoScheme.CTR_SINK_BUFFER)
    private var buffered = 0
    private var plainWritten = 0L
    private var closed = false
    private var closeFailed = false

    /** 成功 close（doFinal + 长度核验通过）后为 true。 */
    val finalized: Boolean get() = closed && !closeFailed

    val plainBytesWritten: Long get() = plainWritten

    override fun write(b: Int) {
        writeSingle(b.toByte())
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (closed) throw IOException("write after close")
        var pos = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, buffer.size - buffered)
            System.arraycopy(b, pos, buffer, buffered, n)
            buffered += n
            pos += n
            remaining -= n
            if (buffered == buffer.size) flushBlock()
        }
        plainWritten += len
    }

    private fun writeSingle(b: Byte) {
        if (closed) throw IOException("write after close")
        buffer[buffered++] = b
        plainWritten++
        if (buffered == buffer.size) flushBlock()
    }

    private fun flushBlock() {
        val out = cipher.update(buffer, 0, buffered)
        if (out != null && out.isNotEmpty()) sink.write(out)
        buffered = 0
    }

    override fun flush() {
        if (closed) throw IOException("flush after close")
        sink.flush()
    }

    override fun close() {
        if (closed) {
            // 幂等：首次 close 已失败时重复 close 保持失败可见。
            if (closeFailed) throw IOException("sink previously failed to close")
            return
        }
        closed = true
        var failure: IOException? = null
        try {
            if (buffered > 0) {
                val rest = cipher.update(buffer, 0, buffered)
                if (rest != null && rest.isNotEmpty()) sink.write(rest)
                buffered = 0
            }
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) sink.write(tail)
        } catch (e: IOException) {
            failure = e
        } catch (e: CancellationException) {
            // CE 先传播（不吞成 IOException），底层资源仍关闭。
            closeFailed = true
            try {
                sink.close()
            } catch (e2: IOException) {
                e.addSuppressed(e2)
            }
            throw e
        } catch (e: Exception) {
            failure = IOException("cipher finalization failed", e)
        }
        try {
            sink.close()
        } catch (e: IOException) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        if (plainWritten != expectedPlainLength) {
            val mismatch = IOException(
                "ciphertext sink length mismatch: wrote $plainWritten, expected $expectedPlainLength"
            )
            failure?.let { mismatch.addSuppressed(it) }
            failure = mismatch
        }
        if (failure != null) {
            closeFailed = true
            throw failure
        }
    }
}
