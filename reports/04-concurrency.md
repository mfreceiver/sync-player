# Phase 4：并发与协程审计
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - `grep -rn "GlobalScope\|runBlocking" --include='*.kt' app/src/main` | scan_total=11 | adjudicated=4（真实 runBlocking 调用：3 PASS + 1 CONFIRMED→F-4-1） | excluded_scope_or_FP=7（2 import + 5 注释行） | unknown=0
  - `grep -rnE 'Dispatchers\.(IO|Default|Main)' --include='*.kt' app/src/main` | scan_total=51 | adjudicated=51（DI 注入 5 合规 + withContext 硬编码 36[业务 14→F-4-2 / 基础设施 22 PASS] + launch(IO) 1 PASS + 其余 import/引用 9 PASS） | excluded_scope_or_FP=0 | unknown=0
  - `grep -rnE '\.length\(\)|File\(|InputStream|MessageDigest|Cipher' --include='*.kt' <crypto+storage>` | scan_total=30 | adjudicated=15（铁律 8 样本：11 PASS/FP + 2→F-4-4/F-4-5 + 2 关联 F-4-4） | excluded_scope_or_FP=0 | unknown=15（未入样本）
  - `grep -rnE 'Mutable(State|Shared)Flow' --include='*.kt' app/src/main` | scan_total=106（=B.2 锚点 ✓） | adjudicated=106（2 public 暴露→F-4-3，104 private/backing/类型引用 PASS） | excluded_scope_or_FP=0 | unknown=0
  - collect 方式 `collectAsStateWithLifecycle|\.collect \{|collectAsState\(` | scan_total=96 | adjudicated=96（92 lifecycle ✓；4 裸 collect 均为 VM/Service 作用域非 Composable，正确；0 裸 collectAsState） | excluded_scope_or_FP=0 | unknown=0
  - `grep -rn "CoroutineScope(" --include='*.kt' app/src/main` | scan_total=15 | adjudicated=15（5 手动 scope 全部有 owner/cancel 语义；8 为 rememberCoroutineScope 子串误命中→FP；2 类内手动 scope 见 4.6） | excluded_scope_or_FP=8 | unknown=0
  - `grep -rn "debounce\|distinctUntilChanged" --include='*.kt' app/src/main` | scan_total=5 | adjudicated=5 | excluded_scope_or_FP=0 | unknown=0
- 唯一发现: P0=0, P1=2, P2=3（其中 CONFIRMED=5, CANDIDATE=0）；引用他 Phase 的 REF: 0
- U 条目: 0

## 4.1 GlobalScope / runBlocking

- **GlobalScope：0 处使用**（仅 UndoDeleteUseCase.kt:103/:318 两行注释明确声明 safetyScope "explicitly NOT GlobalScope"）。
- runBlocking 真实调用 4 处：
  1. EncryptedMediaStore.kt:40 `volumeId` getter —— storage-security.md:30 **spec 明示**"volumeId 每次访问经 runBlocking 解析（不缓存）"，Provider/引擎路径合规。
  2. EncryptedMediaStore.kt:140 `usableSpace()` —— 全部调用方包裹在 IO：SyncSpaceChecker.kt:66 `withContext(Dispatchers.IO)`（E2 注释）、SettingsViewModel.kt:160 与 StorageManagementViewModel.kt:73 `withContext(ioDispatcher)`、SyncEngine.kt:499（worker launch(IO) 内）。合规。
  3. MediaContentProvider.kt:113/:127 `runBlocking(Dispatchers.IO)` —— ContentProvider 无协程作用域，spec:30 认可，且显式指派 IO。合规。
  4. **违规路径（F-4-1）**：volumeId getter 经分享链路在 **Main 线程**执行。

### F-4-1 [P1] [CONFIRMED] [C-CV-性能/IO Safety]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:185
- 代码: `scope.launch {` / `val intent = actions.buildShareIntent(itemId)`
- 问题: `scope` 为 rememberCoroutineScope（Main 调度器）。buildShareIntent 内 FileOperationsUseCase.kt:241 `mediaStore.volumeId`（→ EncryptedMediaStore.kt:40 `runBlocking { volume() }`，含 DataStore 磁盘读）与 :257 `ShareTempFiles.cleanupOldShareTempFiles`（文件遍历删除）均在 **Main 线程**阻塞执行，违反 conventions.md:32"所有阻塞 I/O 必须在 Dispatchers.IO"（jank/ANR 风险，SAF 卷时更甚）。
- 复现: grep -n 'runBlocking' app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt （exit=0，命中 2 行[含 import]）
- 建议: buildShareIntent 主体（或至少 volumeId/清理段）包 `withContext(Dispatchers.IO)`。

## 4.2 Dispatchers 使用分布

| 类别 | 量 | 判定 |
|---|---|---|
| `@IoDispatcher` 注入链（DispatchersModule 提供 + 注入点） | 5 | 合规（conventions:32"注入可测试"） |
| `withContext(Dispatchers.*)` 硬编码——基础设施（SafVolume 7 / SmbFileClient 6 / SftpFileClient 5 / SeekPreviewExtractor 4） | 22 | PASS（叶子原语自包含 IO） |
| `withContext(Dispatchers.*)` 硬编码——业务层 | 14 | F-4-2 |
| `launch(Dispatchers.IO)`（SyncEngine.kt:450 下载 worker） | 1 | PASS（引擎并发结构） |
| 其余（import、字段引用等） | 9 | PASS |

### F-4-2 [P2] [CONFIRMED] [C-CV-性能]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:166
- 代码: `withContext(Dispatchers.IO) {`
- 问题: 业务层 14 处硬编码 Dispatchers.* 而非注入 @IoDispatcher：MetadataRebuildViewModel 3 处（:166 等）、FileOperationsUseCase 3（:197/:306/:492 上下文）、UndoDeleteUseCase 2、BrowserViewModel 2、SyncSpaceChecker.kt:66、SyncService.kt:76、SettingsViewModel 1、GainAnalysisViewModel 1。功能正确（阻塞确在 IO），但违背 conventions.md:32 的可测试注入约定（Unconfined/TestDispatcher 无法替换）。
- 复现: grep -rnE 'withContext\(Dispatchers\.(IO|Default|Main)\)' --include='*.kt' app/src/main | grep -vE 'SafVolume|SmbFileClient|SftpFileClient|SeekPreviewExtractor' | wc -l （exit=0，输出 14）
- 建议: 业务层统一改注入 `@IoDispatcher ioDispatcher`。

## 4.3 阻塞调用嫌疑（crypto/ + storage/，铁律 8 采样）

清单 30 行（LC_ALL=C 排序），L=30，effectiveN=15，offset=231 mod 30=21，step=2；样本 idx={1,2,4,6,8,10,12,14,16,18,20,22,24,26,28}。裁定：11 处 PASS（Cipher 原语由 IO 线程调用方承载：provider HandlerThread/下载 worker/withContext(IO) 链；File 构造非 IO）；2 处异常类/注释 FP；2 处发现问题（下）。

### F-4-4 [P2] [CONFIRMED] [C-CV-性能]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt:22
- 代码: `val raf = RandomAccessFile(resolve(relPath), "r")`
- 问题: FileVolume 全部 IO 原语（openWrite/openRead/list/delete/exists/mkdirs/rename，:17-38）为 suspend 但**不自带 withContext(IO)**，直接在调用方调度器执行阻塞文件 IO——与 SafVolume（每个原语 withContext(Dispatchers.IO)）不一致。当前所有可达调用链（引擎 worker/VM ioDispatcher/Provider IO）恰都在 IO 上，无现行 Main 阻塞，但契约仅靠调用方纪律维持（脆弱，conventions:32 的防御性缺失）。
- 复现: grep -c 'withContext' app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt （exit=1 零命中；对照 SafVolume.kt 命中 10 行）
- 建议: FileVolume 原语对齐 SafVolume 自包 IO。

### F-4-5 [P2] [CONFIRMED] [C-CV-性能]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:261
- 代码: `storagePaths.normalizeAndValidateRelativePath(_localBasePath.value)`
- 问题: 该调用位于 viewModelScope.launch（Main）；normalize 内 StoragePaths.kt:88 `canonicalOrAbsolute(File(root, input))` → `file.canonicalFile` 执行磁盘 IO（stat/符号链接解析）于 Main 线程，违反 conventions:32。
- 复现: sed -n '86,90p' app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt （exit=0）
- 建议: 校验步骤包 IO 或将 canonicalFile 移至预计算路径。

## 4.4 可变流暴露与收集方式

- 锚点 106 ✓。**public 可变流 2 处**（命名带下划线的 backing 属性漏写 private）→ F-4-3。
- 收集方式：92 collectAsStateWithLifecycle / 4 裸 collect（AppLockManager:46 缓存预热、SyncService:42 进度收集、PlayerViewModel:127 SavedStateHandle 流、SettingsViewModel:67 init 合并——均为非 Composable 作用域，裸 collect 是正确形态）/ 0 裸 collectAsState。铁律 8 抽样 10 处 lifecycle 收集（L=92, offset=47, step=9, idx={0,9,18,27,36,47,56,65,74,83}）全部位于 Screen/Composable——PASS。

### F-4-3 [P1] [CONFIRMED] [P4-可变流暴露]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:83
- 代码: `val _playerErrorFlow = MutableStateFlow<String?>(null)`
- 问题: 公开可变 StateFlow 暴露（缺 `private`），任何收集方均可写入 `.value` 破坏单一数据源；同型 ImageViewerViewModel.kt:54 `val _imageErrorFlow = MutableStateFlow<String?>(null)`。
- 复现: grep -rnE '^[[:space:]]*(val|var)[[:space:]]+[A-Za-z0-9_]+[[:space:]]*(=|:)[[:space:]]*(Mutable(State|Shared)Flow|.*:[[:space:]]*Mutable(State|Shared)Flow)' --include='*.kt' app/src/main （exit=0，命中 2 行）
- 建议: 补 private 并暴露 asStateFlow() 只读视图。

## 4.5 Debounce / 节流

- SearchViewModel.kt:64 `_query.debounce(300)`、BrowserViewModel.kt:226 `debounce(300)`（过滤器组合流）——满足 conventions:31"≥300ms"。浏览主链 browseItems 的 folderData 路径未 debounce 但经 `mapLatest`（:255）取消过期构建（注释明示防 stale），低频导航无需 debounce。PASS。
- 进度节流：SyncProgressPublisher.kt:61 `PROGRESS_THROTTLE_MS = 500L`（tryLock 模式，:124）满足 conventions:34。PASS。

## 4.6 CoroutineScope 生命周期

15 处 grep 命中：8 处 `rememberCoroutineScope()`（Compose 管理，自动取消）；手动 scope 5 处全部有属主语义——
1. SyncService.kt:22 serviceScope（Default+SupervisorJob）→ onDestroy :126 `serviceScope.cancel()` ✓（:122-126 逐 job cancel）
2. SyncPlayerApp.kt:29 appScope / AppLockManager.kt:30 scope —— 进程级单例，无取消需求 ✓
3. UndoDeleteUseCase.kt:106 safetyScope（注释 :318 说明属主语义）✓
4. SeekPreviewExtractor.kt:55 prefetchScope —— **非 DI 单例**（PlayerScreen.kt:399 `remember { SeekPreviewExtractor(context) }` 每屏实例），release()（:121-130）随 onDispose 取消，实例生命周期自洽 ✓

无泄漏/失控 scope。PASS（无 Finding）。
