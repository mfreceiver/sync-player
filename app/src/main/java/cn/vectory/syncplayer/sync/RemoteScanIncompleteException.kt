package cn.vectory.syncplayer.sync

/**
 * 远端目录扫描无法保证返回完整快照时抛出。
 *
 * E8 修复的核心契约：`RemoteFileClient.listFiles` 要么返回**完整**的远端文件列表，
 * 要么抛出本异常（或让底层异常上抛）——**绝不返回残缺列表**。
 *
 * 残缺快照会导致 `SyncEngine.dryRun` 误判「本地记录在远端已不存在」并生成
 * `DELETE_LOCAL`，进而真删本地密文与 DB 行，造成不可恢复的视频丢失。
 *
 * 触发情形（任一即抛）：
 * 1. 递归列目录时 `listFiles()` / `sftp.ls()` 抛异常（权限、网络抖动、目录临时不可读）；
 * 2. 远端文件数达到 [maxFiles] 中声明的上限（避免静默截断）；
 * 3. 单条目属性读取异常（`length()` / `lastModified` 等）。
 *
 * 现在继承自 [SyncException]（code = [SyncErrorCode.SCAN_INCOMPLETE]），不再携带
 * 任何 display 文本——展示文案由 [ErrorMessageMapper.syncErrorMessage] 在资源中解析。
 */
class RemoteScanIncompleteException(
    val maxFiles: Int? = null,
    remotePath: String? = null,
    cause: Throwable? = null
) : SyncException(
    code = SyncErrorCode.SCAN_INCOMPLETE,
    operation = SyncOperation.SCAN,
    path = remotePath,
    cause = cause
)
