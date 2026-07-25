package cn.vectory.syncplayer.crypto

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import javax.crypto.SecretKey

class DecryptingFdCallback(
    private val handle: SeekableReadHandle,
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) : ProxyFileDescriptorCallback() {
    private val decryptor = RangeDecryptor(key, iv, size)
    override fun onGetSize(): Long = size
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        val out = decryptor.readRange(handle, offset, size)
        System.arraycopy(out, 0, data, 0, out.size)
        return out.size
    }
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
        throw ErrnoException("DecryptingFd", android.system.OsConstants.EBADF)
    override fun onRelease() { handle.close() }
}