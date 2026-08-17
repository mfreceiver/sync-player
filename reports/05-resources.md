# Phase 5：资源管理
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - `grep -rnE 'contentResolver\.query|\.openInputStream|\.openOutputStream|Cursor|FileChannel|RandomAccessFile' --include='*.kt' app/src/main` | scan_total=17 | adjudicated=17（10 实资源点全 PASS；7 import/注释/内存 MatrixCursor FP） | excluded_scope_or_FP=7 | unknown=0
  - `grep -rn "createTempFile\|\.tmp\|cacheDir" --include='*.kt' app/src/main` | scan_total=17 | adjudicated=17（16 PASS/注释；1 失败路径缺口→F-5-1） | excluded_scope_or_FP=0 | unknown=0
- 唯一发现: P0=0, P1=0, P2=1（CONFIRMED=0, CANDIDATE=1）；引用他 Phase 的 REF: 0
- U 条目: 0

## 5.1 流/句柄/Cursor 逐处核对（17 命中，10 实点）

| 位置 | 资源 | 处置 | 判定 |
|---|---|---|---|
| SafVolume.kt:40 | contentResolver.query | `?.use { cursor -> … }` | PASS |
| SafVolume.kt:87 | openOutputStream | 返回调用方（openWrite 契约）：EncryptedMediaStore.openEncryptSink 包 ChunkedEncryptSink（close 链完备）/ writeThumb `.use{}`(:144) | PASS |
| SafVolume.kt:118/:190 | query ×2 | `?.use { }` | PASS |
| FileVolume.kt:22 | RandomAccessFile | 句柄封装 SeekableReadHandle；全部消费者关闭：decryptWholeTo finally close（FOUC:335-337）、DecryptingFdCallback.onRelease、verifyReadable finally（EMS:118-119） | PASS |
| SyncRelationEditViewModel.kt:129 | query | `?.use { cursor -> }`（:135） | PASS |
| StorageManagementViewModel.kt:189 | query | `?.use { cursor -> }`（:191） | PASS |
| ImageMetadataExtractor.kt:42/:55 | openInputStream ×2 | `?.use { input -> }` | PASS |
| MediaContentProvider.kt（7 命中） | MatrixCursor/import/注释 | 内存对象无 close 义务 | PASS/FP |

## 5.2 AudioGainAnalyzer（完整读 152 行）

- `analyze`：extractor 创建 :33，finally :60 `extractor.release()`（guarded）✓
- `decodePcmAndMeasure`：codec 创建 :80（MIME null 时 elvis 在实参求值阶段返回，codec 未创建、无泄漏）；finally :148-149 `codec.stop()`+`codec.release()` 双 guarded ✓；输出缓冲全部 `releaseOutputBuffer`（:113/:138）✓
- **判定 PASS**：codec/extractor 异常路径 release 完整。

## 5.3 ExoPlayer / MediaSession / Notification 配对（player/ + sync/）

- **ExoPlayer**：PlayerScreen.kt:105 `remember { ExoPlayer.Builder(...).build() }` → DisposableEffect onDispose :140 `exoPlayer.release()` ✓；listener :226 onDispose remove ✓；lifecycle observer :265 remove ✓。
- **MediaSession**：全库 0 处使用（`grep -rn 'MediaSession'` exit=1 零命中）——无配对义务。
- **NotificationChannel**：SyncService.kt:35 onCreate 创建一次（幂等）✓。
- **前台通知**：startForeground（:36/:52）与 `stopForeground(STOP_FOREGROUND_REMOVE)`（:102 延迟停止、:117 onDestroy）配对 ✓；complete/pending 通知切换 cancel/notify 配对（SyncNotificationHelper:100-106）✓。

## 5.4 临时文件与失败路径

- **share 临时目录**（cacheDir/share）：ShareTempFiles 按龄清理（1h，每次分享前 cleanupOldShareTempFiles）；注释明示增长风险与 handoff 安全窗口——设计文档化 ✓。
- **媒体 .enc.tmp**：EncryptedMediaStore.cancel :73 `withContext(NonCancellable) { vol.delete(tmpMedia) }` ✓；purge 有意跳过活跃 .tmp（:194-198 竞态理由文档化）✓；legacy 双后缀 .enc.enc.tmp 在 purgeBucket :260 与 resetStorage :165 处理 ✓。
- **known_hosts 原子写**：rename 失败路径有清理（:90-92）；**store 写抛异常路径无清理** → F-5-1。

### F-5-1 [P2] [CANDIDATE] [P5-临时文件]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:88
- 代码: `tmpFile.outputStream().use { props.store(it, "SyncPlayer SFTP Known Hosts") }`
- 问题: `props.store` 抛异常（IO 失败/磁盘满）时 `use` 仅关流，残留 `<name>.<uuid>.tmp` 孤儿文件（rename 失败分支 :90-92 有 delete，此路径无）。文件小、触发罕见，故 CANDIDATE。
- 复现: sed -n '85,93p' app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt （exit=0）
- 建议: 写入包 try-catch（或 finally 探测性 delete）。

## 5.5 结论

资源管理整体严格：全部 Cursor/流经 use{}，句柄属主与关闭链清晰，播放器/服务生命周期配对完整，临时文件策略文档化。唯一缺口为已知罕见路径 tmp 残留（CANDIDATE）。
