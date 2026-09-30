package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.model.RemoteFile
import cn.vectory.syncplayer.data.model.SyncSource
import java.io.OutputStream

interface RemoteFileClient {
    suspend fun connect(source: SyncSource, remotePath: String)
    suspend fun disconnect()
    suspend fun close()
    suspend fun testConnection(source: SyncSource, remotePath: String): Boolean
    /**
     * 递归列出 [path] 下的所有文件（含子目录内）。
     *
     * 完整性契约（E8）：本方法**要么返回完整快照，要么抛异常**——绝不返回残缺列表。
     * - 递归列目录异常（权限/网络/目录不可读）→ 直接上抛；
     * - 远端文件数超过内部上限 → 抛 [RemoteScanIncompleteException]，消息含上限值；
     * - 单条目属性读取异常 → 上抛。
     *
     * 调用方（`SyncEngine.dryRun`）依赖此契约来判断哪些本地文件「远端已删除」；
     * 残缺快照会导致本地有效视频被误删，故必须 fail-loud。
     */
    suspend fun listFiles(path: String): List<RemoteFile>
    /**
     * 列出 [path] 下的直接子目录（浏览用途，非递归）。
     *
     * 契约：
     * - 目录确实存在但为空 → 返回空列表；
     * - 枚举失败（权限/IO/传输错误）→ 抛 [SyncException]——不得降级为空列表，
     *   否则「枚举被拒」会被浏览 UI 与 `testRemotePath` 误报为「空目录/目录可访问」；
     * - `CancellationException` 始终先传播，不得包装或降级。
     */
    suspend fun listDirectories(path: String): List<RemoteFile>
    /**
     * Download remote file content into [sink]. Does NOT close the sink; caller controls lifecycle.
     * [remotePath] is relative to the connected base path.
     * Returns true on successful full download, throws on failure.
     */
    suspend fun downloadTo(
        remotePath: String,
        sink: OutputStream,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean
    suspend fun deleteFile(remotePath: String): Boolean
}
