# Sync Player 全面静态分析报告（2026-09-08）

> **推荐先读**：[主分析者优化版：判断、取舍与执行顺序](2026-09-08-recommended-direction.md)。本文作为事实与风险证据库保留，不意味着每项发现都应立即实施。

- **基线 commit**: `93c5f47ae0f4d6a7452e7192f94510bdea7f0850`（分析开始时工作树干净）
- **分析方式**: 纯静态代码分析（多 explorer 探查 + 父 agent 交叉复核 + 本 lane 逐条行号核对）
- **配套改进路线**: [docs/ocmar/plans/2026-09-08-project-improvement-roadmap.md](../ocmar/plans/2026-09-08-project-improvement-roadmap.md)

## 0. 分析边界声明（必读）

1. **本报告所有结论仅来自源码阅读与调用链推演**。
2. **曾启动验证任务，后按用户要求停止；该验证的过程与产物不采纳为本文证据**，文中不引用其任何中间产物。
3. **本报告不声称任何测试通过，也不声称已复现任何运行时漏洞**。标注「确认」仅指代码路径与机制在静态层面成立，不代表运行时必然触发。
4. 测试规模仅作静态计数陈述：`app/src/main` 137 个 kt 文件、`app/src/test` 73 个、`app/src/androidTest` 5 个；`@Test` 注解方法约 659 个（JVM）/47 个（androidTest）。此为文本扫描结果，不代表通过率。
5. **本轮未读取任何截图/录屏**，不对 UI 布局、视觉与交互体验下任何确定结论。
6. CI 环境（Gitea runner 实际行为）未现场验证，涉及 CI 的发现仅基于 workflow 文件文本。

---

## 1. 架构地图

单 app（`cn.vectory.syncplayer`）、Single Activity + Jetpack Compose、MVVM + Repository（Player 模块为 MVI）、Hilt DI、Room v2 + DataStore、Media3/ExoPlayer。minSdk 34 / target+compile 35，个人侧载应用。

### 1.1 分层与模块流

```
MainActivity (Single Activity)
 └─ AppNavHost (navigation/) ── 各 Screen（Compose，纯展示倾向）
     ├─ browser/    Browse/BrowserContentCoordinator/SearchViewModel/UndoDelete/FileOperations
     ├─ player/     PlayerScreen + PlayerViewModel(MVI) + AuroraPlayerUi + GestureView
     ├─ settings/   Settings/StorageManagement/SyncSource/SyncRelation/GainAnalysis/DebugLog…
     └─ sync/       SyncPreviewScreen/ViewModel
          │
          ▼
ViewModel 层 ──(Hilt)──► Repository 层
      ├─ sync/SyncEngine ─► SyncRemoteScanner ─► RemoteFileClient(sftp/smb)
      │        └─► SyncDownloadPipeline ─► EncryptedMediaStore(storage/volume) ─► MediaVolume(FileVolume/SafVolume)
      │        └─► SyncDeleteExecutor / SyncSpaceChecker / SyncProgressPublisher / SyncPendingTaskManager
      ├─ crypto/  CryptoScheme(AEAD v2) / ChunkedEncryptSink / RangeDecryptor / DecryptingFdCallback
      ├─ provider/MediaContentProvider ─► DecryptingFdCallback(StorageManager openProxyFileDescriptor)
      ├─ security/ AppLockManager / LockScreenOverlay / 凭据(EncryptedSharedPreferences) / SSH TOFU
      └─ data/    Room(VideoItemDao/SyncRelationDao/SyncLogDao…) + DataStore(UserPreferencesRepository)
```

### 1.2 关键目录

| 目录 | 职责 |
|---|---|
| `app/src/main/java/cn/vectory/syncplayer/sync/` | 同步引擎、扫描、下载管线、错误映射、SFTP/SMB 客户端 |
| `app/src/main/java/cn/vectory/syncplayer/crypto/` | AEAD v2 分块加密（`SPM2` 格式）、随机读解密（6 个文件：CryptoScheme/ChunkedEncryptSink/RangeDecryptor/DecryptingFdCallback/MediaCryptoMaster/LegacyCiphertextException） |
| `app/src/main/java/cn/vectory/syncplayer/storage/volume/` | `MediaVolume` 抽象（`FileVolume`/`SafVolume` 实现）+ `EncryptedMediaStore`（密文读写、verifyReadable） |
| `app/src/main/java/cn/vectory/syncplayer/provider/` | `MediaContentProvider`：对外只读解密 FD，异常边界收敛 |
| `app/src/main/java/cn/vectory/syncplayer/security/` | App Lock（生物识别 + FLAG_SECURE）、凭据存储 |
| `app/src/main/java/cn/vectory/syncplayer/player/` | MVI 播放器：progress 独立 Flow、load generation、保存 channel |
| `app/src/main/java/cn/vectory/syncplayer/browser/` | 浏览、搜索、回收站/撤销 |
| `app/src/main/java/cn/vectory/syncplayer/settings/` | 设置、存储管理、同步源/关系编辑、增益分析、调试日志 |
| `app/src/main/java/cn/vectory/syncplayer/debug/` | `DebugLogger` 内存环形缓冲（无磁盘 I/O） |
| `app/src/main/java/cn/vectory/syncplayer/data/local/` | Room 实体/DAO（VideoItemEntity/SyncRelationEntity/SyncLogEntity…） |
| `scripts/` + `.gitea/workflows/` | 发版脚本、CI（branch-check / integration-check / release） |

### 1.3 核心数据流（播放视角）

远端文件 →（SyncEngine 扫描比对）→ SyncDownloadPipeline 下载 → ChunkedEncryptSink 加密落盘（AEAD v2）→ Room 索引（`VideoItemEntity` 记录 iv/fileSize/媒体元数据；**密钥本体不入 Room**，Master Key 由 `app/src/main/java/cn/vectory/syncplayer/crypto/MediaCryptoMaster.kt` 管理）→ 播放时 MediaContentProvider 经 RangeDecryptor 提供 `openProxyFileDescriptor` 解密句柄 → ExoPlayer 按明文坐标随机读。

### 1.4 关键调用序列（静态核对）

**同步 finalize 链**（下载完成后）：
1. `ChunkedEncryptSink.close()` 校验明文长度（declared≠actual 抛 IOException，拒封尾）。
2. `SyncDownloadPipeline`（:393-432）close → finalize → 调 `EncryptedMediaStore.verifyReadable`（:129-143）读取 magic 探针（`ByteArray(CryptoScheme.MAGIC.size)`，即 4B `"SPM2"`）。
3. 探针失败 → 抛异常 → SyncEngine 按 `finalize_failed` 错误码对该**单条目**做失败补偿。该边界仅限单文件 finalize；**与 E8 扫描失败保护（扫描异常清空整个 toDownload/toDelete 计划）是两个不同机制、不同边界**，不得混写。
4. 元数据提取在 upsert 之后（:468-484），Extractor 失败仅忽略（不阻塞索引）。

**播放随机读链**：
1. ExoPlayer 请求 `MediaContentProvider`（`openProxyFileDescriptor`，底层 `StorageManager.openProxyFileDescriptor` + `DecryptingFdCallback`）。
2. 每次随机读 → `RangeDecryptor` 按明文坐标定位 chunk → GCM 认证解密；认证失败抛异常，**绝不返回脏明文**。
3. Provider 边界将 volumeId 不匹配、iv 长度异常等统一收敛为 `FileNotFoundException`（不泄漏内部标识）。

**删除双路径**：
- 同步路径：SyncEngine 判定 DELETE_LOCAL → 卷所有权校验 → 不匹配计 FAILED（不删密文）。
- 浏览器/手动路径：卷不匹配走 index-only delete（仅删 Room 索引，密文保留）——防误删孤儿密文的 fail-closed 语义。

**生命周期保存**：ON_STOP → 立即 pause + saveProgress（channel 串行化）——**尽力保存**，不保证进程被杀前该持久化一定完成；恢复播放为用户显式操作（产品取舍，见 R4）。

---

## 2. 各子系统优点与已有防护

以下为静态核实到的正面机制，**改进时必须保留**：

1. **E8 扫描失败保护**：`app/src/main/java/cn/vectory/syncplayer/sync/SyncRemoteScanner.kt:170-184`——扫描异常清空 toDownload/toDelete 后才返回；`app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:298-311`、`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:200-208` 目录列取失败/超上限一律抛 `RemoteScanIncompleteException`，绝不基于残缺快照推导 `DELETE_LOCAL`。
2. **卷所有权 fail-closed**：`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt`（同步路径）卷不匹配计 FAILED；浏览器/手动删除走 index-only delete（仅删索引不动密文）——防误删产生不可恢复的孤儿密文。`sync_error_volume_mismatch`（`app/src/main/res/values/strings.xml:347`）文案亦按不可区分语义设计。
3. **CancellationException 始终优先传播**：抽查 `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:666-676`、`app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:83-84,188-189`、`app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:94-95`、`app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt:118-120` 均先 `throw e` 再处理其他异常。
4. **AEAD v2 存储契约稳定**：`app/src/main/java/cn/vectory/syncplayer/crypto/CryptoScheme.kt:36-61`——`SPM2` magic、header 16B、iv 8B、chunk 64KiB、GCM tag 16B、nonce/AAD 构成不变；`CHUNK_STRIDE` 65552 用防溢出整除计算（注释记录了历史溢出修复）。
5. **写入侧完整性**：`app/src/main/java/cn/vectory/syncplayer/crypto/ChunkedEncryptSink.kt:92-105`——`close()` 校验明文长度，declared≠actual 时抛 `IOException` 拒绝封尾，宁可留破损文件也不留「可解密但长度错误」文件。
6. **随机读认证**：`app/src/main/java/cn/vectory/syncplayer/crypto/RangeDecryptor.kt` 在播放路径做 GCM 认证，认证失败抛异常，不返回脏明文。
7. **finalize 探针**：`app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:129-143` `verifyReadable` 打开密文读取 4B magic 探针，失败抛异常让 SyncEngine 对该条目做 finalize 失败补偿，防「下载成功但永远打不开」的幽灵文件。
8. **Provider 安全边界与资源管理**：`app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt:95-124`——volumeId 不匹配/iv 长度异常统一抛不可区分的 `FileNotFoundException`（不泄漏卷标识/路径）；`runBlocking` 打开的句柄在构造/打开失败路径显式 `handle.close()`，CE 分支也先关闭再传播。
9. **App Lock fail-closed**：`app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt:70-82`——偏好读取故障视为「启用锁定」（安全侧）并指数退避重订阅（BUG-014）；`app/src/main/java/cn/vectory/syncplayer/security/LockScreenOverlay.kt:30-54` 连续失败计数 + 冷却。FLAG_SECURE 防截屏。凭据存 EncryptedSharedPreferences；SSH host key TOFU 持久化且指纹不匹配时阻断（`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:64-67`）。
10. **Room 索引设计**：`app/src/main/java/cn/vectory/syncplayer/data/local/entity/VideoItemEntity.kt:12` `Index(value=["parentPath"])` 支撑 `app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:9` 按目录查询；同步日志/媒体元数据走事务写入。
11. **Player 竞态防护**：`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:242-284`——load request generation（BUG-015）使旧代次请求迟到即丢弃；progress 为独立 StateFlow（重组隔离）；保存走 channel 串行化；回收站 undo 与删除竞态有防护（UndoDeleteUseCase）。
12. **生命周期持久化（尽力保存）**：`app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:242-262`——ON_STOP 时立即 pause + saveProgress（AND-001），在 recents 划掉/熄屏等 composition dispose 不保证的路径上尽力落盘。
13. **发版凭据卫生**：（历史记录：CI 发版 workflow 已于 2026-10-31 移除，发版改本地流程。）
14. **无障碍已有基础**：`app/src/main/java/cn/vectory/syncplayer/player/AuroraPlayerUi.kt` 已有播放/暂停 contentDescription（:544）、±10s customActions（:579-582 全屏进度、:663-677 迷你进度条）、进度条 ProgressBarRangeInfo + stateDescription（:588-590,670-672）、设置按钮 Role.Button（:795）、速度单选 Role.RadioButton + selected（:835）、精细擦除放大镜 contentDescription（:1089-1090，FineScrubMagnifier）等语义。**后续工作不得重复添加这些语义**。
15. **重试退避可测性**：`app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:206-208` 指数退避收敛函数独立可测；DataStore 读故障自动重订阅（BUG-003）。

---

## 3. 统一发现（F01–F14）

优先级：P1=应尽快处理；P2=计划内处理。确认度：确认=静态机制完整成立；P1/P2=影响面取决于使用模式；维护=正确性暂无问题、维护性欠佳。

### 3.0 发现汇总

| ID | 优先级 | 确认度 | 子系统 | 一句话概述 |
|---|---|---|---|---|
| F01 | P1 | 确认 | sync/debug | 扫描失败日志经 DebugLogger 携带底层异常细节到用户可达 UI |
| F02 | P1 | 确认 | sync/settings | 同步终态持久化为拼接文案，UI 关键词反推状态导致失败显示成功 |
| F03 | P1 | 确认 | browser | 搜索 debounce 与 flatMapLatest 分叉，新旧 query 文本与结果可混合 |
| F04 | P1/P2 | 确认 | settings | 设置页组合期阻塞 ContentResolver.query |
| F05 | P2 | 确认 | settings | 可用空间读取的无效 IO 与过期写回风险 |
| F06 | P2 | 确认 | settings | SAF 授权失败静默无反馈 |
| F07 | P2 | 维护 | browser | loadSubFolders 用 Dispatchers.Default 包 Room 查询 |
| F08 | P2 | 待验证风险 | settings | 增益分析 discover 重入不受分析任务句柄管理（UI 可达未证） |
| F09 | P2 | 维护 | settings | 编辑页字符串 contains 驱动颜色/导航，文案一改即脆裂 |
| F10 | P2 | 确认 | CI/docs | 发布产物契约漂移（mapping 有意不上传但文档声称上传） |
| F11 | P1/P2 | 配置风险 | CI | 裸 workflow_dispatch + 发布端无 ref/tag-commit 校验 |
| F12 | P2 | 确认 | docs | AGENTS/conventions 与 wrapper/实际目录/门禁不符 |
| F13 | P2 | 确认 | CI/test | CI 无 connectedAndroidTest，Provider/Service 集成盲区 |
| F14 | P2 | 防御增强 | storage | 落盘后完整性检查依赖时机，可加 O(1) header/长度检查 |

### F01 [P1｜确认] 同步扫描失败日志可能携带底层异常细节

- **位置**: `app/src/main/java/cn/vectory/syncplayer/sync/SyncRemoteScanner.kt:182`（`DebugLogger.e(TAG, "dryRun 失败", scanError)`）→ `app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt:57-60`（`e()` 将 `t.stackTraceToString()` 拼入 entry.message 并 `Log.e` 到 logcat；`w()` 同理 :52-55）→ `app/src/main/java/cn/vectory/syncplayer/settings/DebugLogScreen.kt:51-61`（500ms 轮询 snapshot）→ `:154-187`（LogEntryRow 展示 entry.message）→ 入口 `app/src/main/java/cn/vectory/syncplayer/settings/SettingsScreen.kt:209-213`、路由 `app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt:315-318`。
- **机制**: `scanError = wrapSyncException(e)`（:172）本身是 SyncException（message=null、结构化），但 `DebugLogger.e` 传 `Throwable` 时会把**完整堆栈与 cause 链 message**写入环形缓冲与 logcat；cause 若为底层 SFTP/SMB 异常，其 message 可能包含远端路径、主机名等网络元数据。UI 链路（设置→调试日志）可达，用户可读原文。
- **影响**: 敏感载荷依具体异常而定，机制上可达（DebugLogScreen 显示完整 message 含堆栈文本）。未发现导出/持久化日志链路，**不推定远程可利用**。
- **已有防护**: `app/src/main/java/cn/vectory/syncplayer/sync/SyncSafeLog.kt` 已提供安全摘要 mapper（`failure()` 只产出操作+错误码+异常类别 token），`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:678-679` 已采用（注释明确「不传原始 Throwable」）。安全规范（`docs/specs/storage-security.md:49-52`）允许 logcat 输出 relativePath 等诊断信息、禁止用户 UI 展示底层原文——**因此不是「所有诊断路径均禁」，而是 UI 可达链路应对齐 SafeLog 语义**。
- **最小建议**: `SyncRemoteScanner.kt:182` 不直接传 Throwable，改用 `SyncSafeLog.failure` 安全摘要（与 Pipeline:679 同款）；logcat 中相应记录的即为同一安全摘要，与其余同步路径一致。

### F02 [P1｜确认] 同步终态持久化为拼接文案，UI 用关键词反推状态

- **位置与机制**:
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:642-663`：有失败时 phase 置 `PARTIAL_FAILED`，但持久化 `msg = getString(sync_result_success, 成功数, reconcilePart, failedPart, 删除数)`——**部分失败也以「成功：…」文案入库**；:677-687 整体失败路径写 `syncErrorMessage(e, context)`（`app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt:60-109`，按错误码映射资源文案）。
  - `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationListScreen.kt:80-99`：`failKeyword = sync_failed 文案按冒号截断`（「同步失败」），`isSuccess = lastResult != null && !contains(failKeyword)`；绿色=成功，仅 `!isSuccess` 时展示详情文本（:98-99）。
  - `app/src/main/res/values/strings.xml:297-299`（`sync_result_success`/`sync_result_failed`/`sync_failed`）与 `:331-347`（`sync_error_*` 系列）：`网络不可用`、`认证失败…`、`下载失败` 等大量失败文案**不含「同步失败」前缀**。
- **后果**（静态推演）: ① PARTIAL_FAILED 的 lastSyncResult 以「成功：」开头 → 列表绿色、失败详情被隐藏；② FAILED 写入多数 `sync_error_*` 文案不含关键词 → 同样被判为成功（绿色），真实失败信息不可见。判定依赖具体文案拼写，属脆弱协议而非文案小错。
- **限定**: 当前默认 locale 下已存在上述误判（见后果）；当前仓库仅 `values/`（默认）资源、无多语言，引入多语言后文案拼写随 locale 变化会**进一步加剧**该 contains 判定的脆弱性。
- **已有防护**: 无（这是结构性缺口）；但 `SyncPhase` 枚举与 `SyncProgress` 已含 phase/failedFiles，信息源存在。
- **最小建议**: 结构化持久化同步终态（phase/统计/错误码），UI 仅做展示映射；**不推荐**换关键词/补文案的补丁，**也不暗示仅改文案能彻底修复**。
- **⚠ 数据安全前置**: Room 当前 v2、破坏性迁移（`dropAllTables=true`）。为 `app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncRelationEntity.kt`（:29-30 `lastSyncTime/lastSyncResult: String?`）新增终态字段涉及 schema 变更，**必须单独决定如何保护媒体索引/播放历史，绝不能悄悄升 schema 造成数据丢失**——该决策是本项实施的前置条件（见路线图 T3/T6）。

### F03 [P1｜确认] 搜索流 debounce 与 flatMapLatest 分叉导致新旧 query 结果混合

- **位置**: `app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:64-76`。
- **机制**: `combine(_query.debounce(300), _searchScope, _query.flatMapLatest { DAO 查询 }, scopedFolderPaths)`。同一 `_query` 有两条下游订阅：debounce 后的文本 A 与 raw 流驱动的 DAO 结果。核心问题在于**两条路径的 query 值可以不同步**，组合出「文本是 A、结果是 B」的混合状态。具体例子：查询词稳定为 A，debounce 已发射 A、DAO 返回 A 结果；此时用户输入 B → raw 流立即为 B 的 DAO 查询发起（可在防抖前随输入启动查询；StateFlow 有合并调度，300ms 内不保证逐键都执行）；而 debounce 分支仍停留在 A——combine 随即产生「文本 A + B 的 DAO 结果」。反之 debounce 先更新为 B、B 的 DAO 尚未返回时，combine 也会用旧结果重组。scope 变化只是这类混合态的**触发器之一**，根因是同一 query 的两条订阅不同步，并非 scope 变化改变 query。
- **后果**: 中间态下结果集与输入框文本不一致；raw 流随输入发起查询造成查询抖动。
- **最小建议**: 收敛为单管道——`_query.debounce(300).flatMapLatest { query -> combine(repo.search(query…), scopedFolderPaths, _searchScope) { …build(query,…) } }`，同一 query 值被捕获进整个下游；**不能只给两条分支各加 debounce**（值不同步依旧）。保留既有语义：空 query 短路（:69,74）、scope 语义、旧查询取消（flatMapLatest）。

### F04 [P1/P2｜确认] 设置页组合期阻塞读 ContentResolver

- **位置**: `app/src/main/java/cn/vectory/syncplayer/settings/SettingsScreen.kt:78-87`（组合期直接调 `StorageManagementViewModel.resolveTreeDisplayName(currentContext, uri)`）→ `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:228-243`（companion 函数内 `contentResolver.query` 同步执行）。
- **机制**: SAF `ContentResolver.query` 是跨进程阻塞调用，在组合期于主线程执行；响应慢时设置页首帧被拖住。
- **限定**: 机制确认（阻塞调用在组合期）；未声称已测得 ANR，实际影响取决于 provider 响应速度。
- **最小建议**: 移入注入 IO 调度器的状态源（VM 内按 treeUri 刷新的 StateFlow），Compose 只做纯展示。查询失败/无权限时回退**固定资源通用文案**（如「已选择的文件夹」类通用 label，具体文案由 designer 定）——注意既有 `fallbackUriLabel`（`app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:244-249`）在解析失败时会回退为**完整 URI 字符串**，不能作为安全回退直接复用；合法 displayName 的展示策略另行确定。

### F05 [P2｜确认] 设置页可用空间读取的无效 IO 与过期写回风险

- **位置**: `app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:54-88`（combine 四个偏好流 → 每次 emit 都 `refreshUsableSpace()` :79-82）→ `:182-199`（`Dispatchers.IO` 读 `usableSpace`）。
- **机制**: ① `SettingsState.usableSpaceLabel` **未被 `SettingsScreen` 使用**（rg 核对零引用；存储管理页有自己的显示），每次偏好发射都产生一次无效 IO；② collect 与 `refreshUsableSpace` 异步更新同一 State，IO 慢时旧值可后写回（过期写回）。
- **最小建议**: 优先移除无用状态与 IO（优于引入新全局框架）；若保留显示则收敛触发时机并做 latest-wins。

### F06 [P2｜确认] SAF 持久化授权失败静默

- **位置**: `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementScreen.kt:35-46`——`takePersistableUriPermission` 抛 `SecurityException` 时 `catch` 后什么都不做（:43-45），无状态变更、无提示，用户只看到「没反应」。
- **最小建议**: 安全资源错误事件 + 重试入口；错误文案走资源、不泄漏 URI/message。

### F07 [P2｜维护] loadSubFolders 用错调度器

- **位置**: `app/src/main/java/cn/vectory/syncplayer/browser/BrowserContentCoordinator.kt:409-419`——`withContext(Dispatchers.Default)` 包裹 Room 查询 `getSubFoldersOnce`；同类 `:56` 已注入 `ioDispatcher` 且 `:239` 已使用。
- **限定**: 仅调度一致性与可测性问题（该 VM 已为 ioDispatcher 留了测试替换注释）；**不声称 suspend Room 必然阻塞主线程**。
- **最小建议**: :409 的 I/O 段改用注入的 `ioDispatcher`，与 :239 对齐（文件内其它纯计算保留 `Dispatchers.Default` 是合理的，不在本项范围）。

### F08 [P2｜重入机制/待验证风险] 增益分析 discover 重入不受分析任务句柄管理

- **位置**: `app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:73-102`——`discover()` 每次 `viewModelScope.launch`，不经任何 job 取消/epoch 门控；而 `analysisJob`（:70 字段）是 `launchAnalysis`（:119-127）的执行取消句柄（:126 `analysisJob?.cancel()`），**discover 与其生命周期互不感知**；`_discoveredFiles/_totalCount` 等多 flow 分散写。
- **机制**: 连续触发 discover 时两个协程并发，旧结果可能晚于新结果落地，旧覆盖新。
- **限定**: 重入机制在静态上成立，但 UI 是否可达连点**未证明**——故列为「待验证风险」而非已确认缺陷。先核实可达性，再明确语义，不建议大改。
- **最小建议**: 为 discover 引入**独立的** `discoverJob`/epoch 门控（single-flight 或 latest-wins 二选一，明确定义）；**不得复用 `analysisJob`**——那是分析执行的取消句柄，复用会混淆两个生命周期。

### F09 [P2｜维护] 编辑页用字符串 contains 驱动颜色/导航

- **位置**: `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt`——`:64-65` 取两个成功文案资源；`:245` `testResult` 用 `contains(dirAccessibleStr)` 定色；`:250-252` `saveResult` 硬编码绿色；`:256-259` `LaunchedEffect` 用 `contains(sourceSaveSuccessStr) || contains(relationSaveSuccessStr)` 触发导航。`app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt` 同模式：`:38` 仅是取资源（`sourceSaveSuccessStr`），**实际控制流在 :193-200**（`testResult`/`saveResult` 的 `isSuccess = contains(connectSuccessStr) || contains(sourceSaveSuccessStr)` 定色）与 **:226-229**（`LaunchedEffect` 以 `contains(sourceSaveSuccessStr)` 触发导航）。写入点：`app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:364`、`app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:111`。
- **限定**: 当前成功路径写入的恰是这两个文案（`source_save_success`/`relation_save_success`），导航行为正常，**不能声称已发生错误导航**；但 relation 编辑页同时匹配 `source_save_success`（:257）在其保存路径中不可达，属冗余匹配，文案一改即脆裂。
- **最小建议**: saveResult/testResult 改结构化结果（sealed/枚举），文案仅展示；**不要求为测试便利重写 ErrorMessageMapper**。

### F10 [P2｜确认] 发布产物契约漂移（mapping.txt）

- **位置**: `scripts/ci/release-check.sh:6-7`（注释明确「mapping.txt 不上传（个人自用，无线上崩溃符号化需求）」——**有意行为**）、`:16-19`（只归档 APK + SHA256SUMS）vs （CI 发版文件已于 2026-10-31 移除，发版改本地流程，产物 APK + SHA256SUMS 不含 mapping。）
- **性质**: 脚本实际行为（APK+SHA）与文档契约（APK+mapping+SHA）漂移；不上传是**有意选择，不是构建故障**。
- **最小建议**: 依现有个人侧载选择，将文档与 workflow 注释对齐为「APK + SHA256SUMS」；mapping 本地保留与否写明为可选。

### F11 [P1/P2｜配置风险] release workflow 允许裸手动触发且发布端不校验 tag

- **位置**: （历史记录：CI 发版 workflow 与配套脚本已于 2026-10-31 移除）——**全链路无 ref/tag-commit 一致性校验**。
- **机制推演**: 手动 dispatch 在 `release` 之类的分支名上触发时，checkout 的是该分支 HEAD（release-check 报 tag=分支名、版本退最近 tag），构建产物与「最近 tag」版本号组合，publish 仍按传入 TAG 名发布（或覆盖既有 release）。可能造成分支构建冒充 tag 版本、或覆盖正式 release。
- **限定**: 基于文件文本推演，Gitea runner 现场行为未验证；触发需人工误用 dispatch，故 P1/P2 之间。
- **最小建议**: 删除裸 `workflow_dispatch`，或 dispatch 必填 tag 输入并在发布前校验 `github.sha` 与 tag 所指 commit 一致；publish 步骤独立 fail-fast，测试不通过绝不发布。

### F12 [P2｜确认] 文档权威源漂移（AGENTS/conventions 与实际不符）

- **位置**: ① AGENTS.md 声称 Gradle 8.11.1，`gradle/wrapper/gradle-wrapper.properties` 实际 `gradle-9.3.1`；② `docs/specs/conventions.md:7` 单测路径写 `app/src/main/test`，实际为 `app/src/test`；③ `scripts/release.sh:66-67` 本地门禁实际含 `lintDebug`（AGENTS 速查只列 compile+test），`:48` 非 main 分支仅 warning 继续。
- **最小建议**: 以实际为准修订文档（权威源必须一致）；**本轮不擅改 AGENTS.md**，修订留待批准。

### F13 [P2｜确认] 测试执行覆盖缺口（CI 维度）

- **位置**: `.gitea/workflows/integration-check.yml:39` 与 `.gitea/workflows/branch-check.yml:48-51`——JVM `testDebugUnitTest` + lint + assembleDebug，有产物清单归档；**当前 workflow 未配置 connectedAndroidTest 执行、无测试报告归档**。
- **现状**: `app/src/androidTest` 静态存在 5 个文件（真实 in-memory Room、DAO/SAF/Player 相关），当前 workflow 未配置执行。**未发现专门的 Provider/Service 端到端 Android 测试**（ContentProvider 跨进程、SyncService 前台生命周期）；已有的外围/协调器层单测不替代真实组件生命周期验证。
- **限定**: 不是「已失败」，而是缺口；**不强推覆盖率指标**。真实 IO/墙钟测试为已接受成本（`docs/specs/conventions.md:13`）。
- **方向**: 先报告缺口，再按收益决定是否为可注入调度器的部分补 CI 执行；Provider/Service 集成测试属高成本项，单独评估。

### F14 [P2｜防御增强] 落盘后完整性检查依赖时机

- **位置**: `app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:129-143` `verifyReadable(id: Long)` 仅读取 4B magic 探针（:132 `ByteArray(CryptoScheme.MAGIC.size)`，`CryptoScheme.MAGIC` = `"SPM2"` 4 字节，**非 16B**）；完整性其余依赖：`app/src/main/java/cn/vectory/syncplayer/crypto/ChunkedEncryptSink.kt:97-105` close 时长度核对（declared≠actual 抛）、`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:393-432` close→finalize→magic 探针链、`:468-484` 元数据 upsert 后提取（`app/src/main/java/cn/vectory/syncplayer/settings/VideoMetadataExtractor.kt:46-50`/`ImageMetadataExtractor.kt:109-117` 失败忽略）。
- **机制**: 已有链路覆盖了「写入时长度」「finalize 时可读性（仅 magic 级）」；但探针只确认文件头 4 字节，**既不覆盖 header 其余 12B（version/chunkSizeKiB/plaintextLength），也不核对实际密文大小与预期长度的一致性**。检查时间窗口是 finalize 与提交前——它防的是该时刻的写入异常，**不声称能防止提交之后的外部改动/卷损坏**；对落盘后变化，未来读取时仍由 RangeDecryptor 的 GCM 认证兜底（认证失败抛异常，不返回脏明文）。**不宣称普通短下载都能漏过探针，也不宣称会返回脏明文**。
- **最小建议**: 增强 `verifyReadable` 为完整 16B header 解析（含 header 内声明的 plaintextLength）+ 实际密文大小 + **可信预期明文长度**三方匹配的 O(1) 检查。接口依赖需明确：当前签名无 fileSize 参数，且 DB 在 upsert 前不保证有最终 metadata——预期长度应由 pipeline 侧已有的 `action.fileSize`（远端可信来源）显式传入，或先定义该接口契约，**不能假设 DB 现有字段总是可信来源**。全文 GCM 校验昂贵，不默认要求。

---

## 4. 待确认风险（R 项，独立于 F，不定性为缺陷）

| ID | 位置 | 机制与现状 | 处置倾向 |
|---|---|---|---|
| R1 | `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:290-327` vs `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:190-203` | SFTP 递归每个目录入口 `ensureActive()`（CQ-001 注释 :197-199）；SMB 递归**无**对应检查且为非 suspend 阻塞递归。**注意：底层 jcifs 阻塞调用不因加 checkpoint 就可立即中断**，只能减少白做工 | 对齐检查点；预期收益有限需说明 |
| R2 | `app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt:95-117` | `safeOpenFile`:96 读 store、:97 读 entity、:99 校验 volumeId、:113 重新经 store 解析卷打开——两次解析间卷可被切换。父级复核的可达序列：`app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:156-188` reset 清 DB → `:121-145` switch 新卷 → Provider:113 解析到新卷 open；仍需新卷存在同 ID 文件才成立，**未证错误明文可读，GCM 认证保护仍在**。DB 非空禁切卷与 mutex 已缓解 | 维持待验证；如处理，倾向在 open 时捕获同一卷句柄，**不扩全局 mutex** |
| R3 | `app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt:52-58`（browser route 带 `folder` navArgument :54）与 `app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:77`（`currentFolder = coordinator.currentFolder`） | 导航 route 参数与 VM/coordinator 状态并存（双状态源）；Hilt 默认 VM scope 下每 entry 独立实例的复用行为**未证明可达**，不作为问题定性 | 观察项 |
| R4 | `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:242-262` | ON_STOP 暂停后**不自动恢复**播放是产品取舍（AND-001 只管保存） | 不擅自加 ON_START resume |
| R5 | `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:49,67` | TOFU 保存/不匹配时 Log 打印 host 标签与**公钥指纹**——属网络元数据，**非密钥泄漏、未明确违规** | 按隐私策略最小化（可截短 host 显示） |
| R6 | `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:234`、`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:160`（消费方 `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:227,267`） | listDirectories 对普通错误返回空列表是规范已接受的残余风险；**不影响 E8 扫描**（扫描路径另有 fail-fast，见 `RemoteFileClient.kt:24` 接口与扫描实现） | 维持现状，文档已有记载 |
| R7 | 签名脚本 umask（范围外建议，不计入风险清单编号语义） | 本机凭据文件的文件系统权限**不属基线代码证据，本报告不写结论**（用户仅要求代码分析） | 仅可建议签名脚本统一 umask，独立小项 |

---

## 5. 盲区汇总

1. **无运行时证据**：全部发现无测试/构建/真机佐证；标注「确认」均指静态机制成立。
2. **UI/视觉未评审**：未读取任何截图，不评判布局、亮度/音量手势等体验（路线图中相关项仅列为待设计/待设备验证）。
3. **CI 现场未验证**：Gitea runner 对 dispatch/tag 的实际处理、artifact 上传行为未实测。
4. **协议实现深度**：SFTP/SMB 库（sshj/jcifs）内部行为（超时、并发、断连恢复）未审计。
5. **性能画像缺失**：无启动、扫描吞吐、播放 seek 延迟的度量基线。
6. **locale 前瞻**：F02 的误判在当前默认 locale 下已存在；引入多语言后文案拼写随 locale 变化，contains 判定（F02/F09）会进一步加剧。
7. **androidTest 的真实价值**：5 个文件存在但当前 workflow 未配置执行，其断言是否仍与现行代码同步未知。
8. **脚本细节未全审计**：release.sh/release-check.sh 的 shell 边界（引号、set -e 语义、临时文件竞争）未逐行审计，仅核对了发现涉及的行为差异。
9. **单测断言质量**：JVM 测试 73 文件/@Test 659 个仅静态计数，未逐文件评审断言是否守约（不断言文案、CE 不吞）——属验证 owner 后续职责。

---

## 6. 处置路线

按优先级编排的改进建议（含阶段、验收标准、回滚风险、发现→任务矩阵）见：

**[docs/ocmar/plans/2026-09-08-project-improvement-roadmap.md](../ocmar/plans/2026-09-08-project-improvement-roadmap.md)**

> 再次强调：路线图为**建议方案，未经批准，不得直接实施**；其中所有任务在执行前需用户显式确认，涉及 schema/安全契约的任务另有前置决策项。
