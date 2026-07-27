# Sync Engine & Error Model

> 本文档是 Sync Player 同步引擎与错误模型的权威来源。`AGENTS.md` 仅引用本文。

## Overview

- 镜像同步（remote → local），多源（SyncSource + SyncRelation）。
- **dry-run first**：先预览，用户确认后执行。
- 前台服务（`dataSync` 类型）。
- 并发上限 `Semaphore(3)`。

## 核心流程

```
dryRun(relationId)
  连接 → 完整远端扫描 → 与本地 DB 比较
  扫描异常 → 清空 toDownload 和 toDelete（E8 守卫）
  → DryRunResult(toDownload, toDelete, error: SyncException?)

用户确认 → PendingSyncActionDao 持久化

consumeAndExecute(relationId)
  executeSyncLocked():
    空间预检(SyncSpaceChecker) → 并发下载/加密(Semaphore 3) → 元数据/缩略图 → 删除 → 结果记录
```

## 组件拆分（SyncEngine 渐进拆分）

- `SyncEngine`：编排（dryRun/executeSync/executeDelete/consumeAndExecute/setPendingTask/consumePendingTask），持有 `storageOperationMutex`。
- `SyncProgressPublisher`：进度 `StateFlow<SyncProgress>` + 500ms 节流 + activeDownloadsMap/completedPaths。
- `SyncSpaceChecker`：`cipherLengthFor` 预检 + 溢出/unknown-space 降级，返回 sealed Result（UnknownAvailableSpace / CipherSizeComputationFailed / Insufficient / Ok）。

## SyncException（结构化错误）

所有 sync/data-layer 失败抛：

```
SyncException(code, operation, path?, host?, expectedBytes?/actualBytes?/availableBytes?, cause)
```

- `message` **刻意为 null**（不携带显示文本）。
- `SyncErrorCode`（18）: NETWORK_UNAVAILABLE, UNKNOWN_HOST, CONNECTION_REFUSED, TIMEOUT, AUTHENTICATION_FAILED, ACCESS_DENIED, PATH_NOT_FOUND, NOT_CONNECTED, SCAN_INCOMPLETE, DOWNLOAD_FAILED, REMOTE_DELETE_FAILED, INVALID_FILE_SIZE, INSUFFICIENT_SPACE, FINALIZE_FAILED, STORAGE_ERROR, THUMBNAIL_ERROR, DATABASE_ERROR, UNKNOWN。
- `ErrorMessageMapper.classifyRemoteError(e)` 读 **code**（非 message 子串）→ `RemoteBrowseError`。
- `syncErrorMessage(e, context)` 解析 code → `R.string.*`（UNKNOWN 永不泄漏 `e.message`）。

## 不可违背的契约（均有测试锁定）

### E8 扫描失败保护
`RemoteScanIncompleteException`（或任何扫描异常）→ `dryRun` 清空 `toDownload` **和** `toDelete`。部分远端快照**绝不**推导 `DELETE_LOCAL`（删除真实本地密文 + DB 行，不可恢复）。`SyncEngineDryRunTest` 锁定。

### 卷所有权 fail-closed
`entity.volumeId != store.volumeId` 时删除**跳过**（密文属于另一个卷——不删不属于自己的东西），但计为 **FAILED**（保证 `completedFiles+failedFiles == totalFiles`，relation 显示 PARTIAL_FAILED 而非假 SUCCESS）。状态日志 = `SKIPPED`。

### 取消优先
`CancellationException` **始终**先于普通 Exception 传播。清理在 `NonCancellable` 中完成（pending task markCancelled + deleteByRelationId）。

### 重试白名单
仅瞬态码重试：`{TIMEOUT, CONNECTION_REFUSED, UNKNOWN_HOST, NETWORK_UNAVAILABLE, DOWNLOAD_FAILED, UNKNOWN}`。终态码（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/…）立即抛出。

## DryRunResult

`DryRunResult(toDownload, toDelete, error: SyncException?)`：`SyncEngine.dryRun()` 返回结构化错误，使 `SyncPreviewViewModel` 渲染真实原因（AUTH/PATH_NOT_FOUND/SCAN_INCOMPLETE）而非泛化"连接失败"。

## Known Residual Risks（accepted, personal-use）

1. `listDirectories()` 吞异常 → 返回空列表（浏览 UI 可能显示"空目录"而非访问错误）。镜像扫描 `listFiles()` 仍抛（E8 成立）。Defer：转为结构化错误返回。
2. `isNotFound()` 仅按类型（`FileNotFoundException`）。SAF provider 用普通 `IOException` 文本信号 not-found 时会被当真实失败（DB 行保留）——保守，绝不误删。
3. `classifySmbException` 按 jcifs 英文状态短语。未知变体安全降级为 `UNKNOWN`→`DOWNLOAD_FAILED`。
4. 无两阶段提交（Room + EncryptedSharedPreferences + 文件系统）；append-only 写不做崩溃后完整性检查（best-effort purge marker 幂等 → 无害）。
