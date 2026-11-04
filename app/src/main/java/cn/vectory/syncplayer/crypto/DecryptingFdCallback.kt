package cn.vectory.syncplayer.crypto

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import java.io.IOException
import javax.crypto.SecretKey

/**
 * 外部卷 CTR 密文的 proxy FD callback。
 *
 * 回调发生在 openFile 返回后，不能依赖 openFile catch 消毒：onRead/onGetSize/onRelease
 * 自行把异常映射为安全 [ErrnoException]（EIO/ENODEV/EBADF），不携带原 cause/message/path。
 * handle 的 seek/read 用同一把锁串行（独占 handle，避免共享 seek 指针竞争）；
 * onRelease 幂等关闭（第二次 close 由 handle 实现保证或被吞掉）。
 * CancellationException 优先传播（本类不 catch Exception 全家桶）。
 */
class DecryptingFdCallback(
    private val handle: SeekableReadHandle,
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) : ProxyFileDescriptorCallback() {

    private val decryptor = RangeDecryptor(key, iv, size)
    private val lock = Object()

    override fun onGetSize(): Long = size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        if (offset < 0 || size < 0 || size > data.size) {
            throw ErrnoException("onRead", OsConstants.EINVAL)
        }
        val out = synchronized(lock) { decryptor.readRange(handle, offset, size) }
        if (out.isNotEmpty()) System.arraycopy(out, 0, data, 0, out.size)
        out.size
    } catch (e: ErrnoException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: IOException) {
        // 截断/IO：不携带底层 message/path
        throw ErrnoException("onRead", OsConstants.EIO)
    } catch (_: Exception) {
        throw ErrnoException("onRead", OsConstants.EIO)
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
        throw ErrnoException("onWrite", OsConstants.EBADF)

    override fun onRelease() {
        try {
            synchronized(lock) { handle.close() }
        } catch (_: Exception) {
            // 幂等：重复 release / 底层已关——无副作用可暴露
        }
    }
}
