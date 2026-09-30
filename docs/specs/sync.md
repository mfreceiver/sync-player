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
    空间预检(SyncSpaceChecker) → 并发下载（通道级 lane 复用）→ 元数据/缩略图 → 删除 → 结果记录
```

## 组件拆分（SyncEngine 渐进拆分）

- `SyncEngine`：编排（dryRun/executeSync/executeDelete/consumeAndExecute/setPendingTask/consumePendingTask），持有 `storageOperationMutex`。
- `SyncProgressPublisher`：进度 `StateFlow<SyncProgress>` + 500ms 节流 + activeDownloadsMap/completedPaths。
- `SyncSpaceChecker`：plain-size 空间预检，求和溢出饱和为 Long.MAX_VALUE，可用空间读取失败（`usableSpace() <= 0`）与空间不足均判定为 `Result.Insufficient`，返回 sealed `Result`（`Ok` / `Insufficient`）。

## SyncException（结构化错误）

所有 sync/data-layer 失败抛：

```
SyncException(code, operation, path?, host?, expectedBytes?/actualBytes?/availableBytes?, cause)
```

- `message` **刻意为 null**（不携带显示文本）。
- `SyncErrorCode`（17）: NETWORK_UNAVAILABLE, UNKNOWN_HOST, CONNECTION_REFUSED, TIMEOUT, AUTHENTICATION_FAILED, ACCESS_DENIED, PATH_NOT_FOUND, NOT_CONNECTED, SCAN_INCOMPLETE, DOWNLOAD_FAILED, REMOTE_DELETE_FAILED, INSUFFICIENT_SPACE, FINALIZE_FAILED, STORAGE_ERROR, THUMBNAIL_ERROR, DATABASE_ERROR, UNKNOWN。
- `ErrorMessageMapper.classifyRemoteError(e)` 读 **code**（非 message 子串）→ `RemoteBrowseError`。
- `syncErrorMessage(e, context)` 解析 code → `R.string.*`（UNKNOWN 永不泄漏 `e.message`）。

## 不可违背的契约（均有测试锁定）

### E8 扫描失败保护
`RemoteScanIncompleteException`（或任何扫描异常）→ `dryRun` 清空 `toDownload` **和** `toDelete`。部分远端快照**绝不**推导 `DELETE_LOCAL`（删除真实本地文件 + DB 行，不可恢复）。`SyncEngineDryRunTest` 锁定。

### 本地删除一致性
本地只有单一存储卷（`filesDir/userdata`，`MediaFileStore`）。删除语义：
- DELETE_LOCAL / DELETE_BOTH：本地文件 + 缩略图 + DB 行 + 远端（remote-first）。
- 浏览器手动删除（`FileOperationsUseCase.deleteMediaById`）：文件缺失按成功处理（SkippedNotFound），真实 IO 错误保留 DB 行返回 FAILED——绝不误删不可恢复的数据。
- **下载两阶段提交**：先写 `<id>.tmp`，close 校验长度，`finalize` 原子 rename；`probePendingTmp` 三态探测收束 finalize 不确定窗口（BUG-007 机制，与加密无关，保留）。

### 取消优先
`CancellationException` **始终**先于普通 Exception 传播。清理在 `NonCancellable` 中完成（pending task markCancelled + deleteByRelationId）。

### 重试白名单
仅瞬态码重试：`{TIMEOUT, CONNECTION_REFUSED, UNKNOWN_HOST, NETWORK_UNAVAILABLE, DOWNLOAD_FAILED, UNKNOWN}`。终态码（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/…）立即抛出。

## DryRunResult

`DryRunResult(toDownload, toDelete, error: SyncException?)`：`SyncEngine.dryRun()` 返回结构化错误，使 `SyncPreviewViewModel` 渲染真实原因（AUTH/PATH_NOT_FOUND/SCAN_INCOMPLETE）而非泛化"连接失败"。

## Known Residual Risks（accepted, personal-use）

1. `isNotFound()` 仅按类型（`FileNotFoundException`）。provider 用普通 `IOException` 文本信号 not-found 时会被当真实失败（DB 行保留）——保守，绝不误删。
2. `classifySmbException` 以 `ntStatus` 数值分类为主、jcifs 英文状态短语为兜底。未知变体安全降级为 `UNKNOWN`→`DOWNLOAD_FAILED`。
3. 无两阶段提交（Room + EncryptedSharedPreferences + 文件系统）；append-only 写不做崩溃后完整性检查（best-effort purge marker 幂等 → 无害）。
