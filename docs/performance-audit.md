# Sync Player 性能瓶颈 & 技术债审计报告

> 生成日期: 2026-04-15
> 状态说明: [ ] 待修复 | [x] 已完成

---

## HIGH 优先级

### H1. [x] Thumbnail.kt: 无降采样、无缓存的位图加载 ✅

**文件**: `ui/components/Thumbnail.kt:35-43`

**问题**: `BitmapFactory.decodeFile()` 将完整尺寸 JPEG 加载到内存，无 `inSampleSize`、无 LRU 缓存、无 `inBitmap` 复用。列表模式下每个 item 独立解码，导致内存峰值飙升、频繁 GC、滚动卡顿。

**对比**: `VideoGridItem.kt` 的 `GridThumbnail` 已实现 `inSampleSize` 降采样，但 `Thumbnail.kt` 完全缺失。

**方案**: 统一引入 Coil 图片加载库，替换手写的 `BitmapFactory` 调用。Coil 自带内存缓存、降采样、生命周期感知。

**实际**: 引入 Coil 2.7.0，Thumbnail.kt 和 VideoGridItem.kt 均改用 `AsyncImage` + `ImageRequest.Builder.size()` 降采样。

---

### H2. [x] BrowserViewModel: 全量加载播放记录到内存 ✅

**文件**: `browser/BrowserViewModel.kt:95-97`

**问题**: `playbackRepository.getAllFlow()` 将所有 `PlaybackRecordEntity` 一次性加载到内存，在 `buildBrowseItems` (line 134) 中 `associateBy` 构建映射。视频数量增长时内存线性增长，任何记录变化触发完整 Flow 重发射。

**方案**: 在 `PlaybackRecordDao` 添加按 folder 过滤的查询方法，BrowserViewModel 改为按需查询当前文件夹对应的播放记录，而非全量缓存。

**实际**: PlaybackRecordDao 新增 `getByFolderFlow`，BrowserViewModel 用 `flatMapLatest` 替换全量 `getAllFlow()`。

---

### H3. [x] BrowserViewModel: 每次浏览构建都触发缩略图修复 ✅

**文件**: `browser/BrowserViewModel.kt:206, 332-343`

**问题**: `repairMissingThumbnails(videoEntities)` 在每次 `buildBrowseItems` 末尾调用。排序切换、筛选变更等操作都会触发最多 5 个后台 MediaMetadataRetriever 任务，造成不必要的 CPU/IO 开销。

**方案**: 将修复逻辑从 `buildBrowseItems` 中移除，改为在 ViewModel 初始化时执行一次，并通过独立的 refresh 触发器控制。

**实际**: 从 `buildBrowseItems` 移除调用，添加独立 init 块通过 `effectiveFolder.collectLatest` 在文件夹切换时触发。

---

### H4. [x] Theme.kt: 动态颜色在每次重组时重新计算 ✅

**文件**: `ui/theme/Theme.kt:106-125`

**问题**: `colorScheme` 在 `@Composable` 函数体中无条件计算 `dynamicColor` 分支（`dynamicColor()` + `copy()` 链），每次重组都会重新执行。

**方案**: 用 `remember` 缓存 `colorScheme` 计算结果，仅在 `darkTheme` / `dynamicColor` 参数变化时重算。

**实际**: `colorScheme` 包裹在 `remember(darkTheme, dynamicColor)` 中，context 提取到 remember 外部。

---

### H5. [x] SMB/SFTP: 传输循环缺少协程取消支持 ✅

**文件**: `sync/smb/SmbFileClient.kt:264-275`, `sync/sftp/SftpFileClient.kt:164-176`

**问题**: `input.read(buffer)` 是阻塞调用，协程取消信号无法中断正在进行的 I/O 操作。用户取消同步后，当前文件会继续下载直到完成。

**方案**: 在 `onProgress` 回调中添加 `ensureActive()` 检查，将取消信号传播到传输循环内部。

**实际**: SmbFileClient 和 SftpFileClient 的 read 循环中添加 `currentCoroutineContext().ensureActive()`。

---

### H6. [x] ExoPlayer: 使用 Activity Context + 未清理 PlayerView 引用 ✅

**文件**: `player/PlayerScreen.kt:196-205, 291-297`

**问题**: (1) ExoPlayer 用 Activity Context 创建，release 延迟会泄漏 Activity。(2) `PlayerView.player` 引用在 release 后未清除，可能导致回调到已释放的 Player。

**方案**: (1) 改用 `context.applicationContext`。(2) 在 `onDispose` 中先 `gestureView.getPlayerView().player = null` 再 `exoPlayer.release()`。

**实际**: ExoPlayer.Builder 使用 `context.applicationContext`，onDispose 中先置 null 再 release。

---

## MEDIUM 优先级

### M1. [x] VideoItemDao: 添加缺失索引 ✅

**文件**: `data/local/entity/VideoItemEntity.kt`

**问题**: `relativePath` 单独查询无索引（仅有 `relativePath + syncRelationId` 复合索引）。`syncRelationId` 单独查询、`fileName` LIKE 查询也无索引。

**方案**: 在 Entity 上添加 `Index("syncRelationId")`。搜索性能通过 Room FTS 或应用层过滤优化（暂不加 FTS）。

**实际**: VideoItemEntity 添加 `Index(value = ["syncRelationId"])`，新增 MIGRATION_10_11，DB 版本升到 11。

---

### M2. [x] PlayerScreen: 200ms 轮询改为事件驱动 ✅

**文件**: `player/PlayerScreen.kt:266-285`

**问题**: `while(true) { delay(200L) }` 每 200ms 触发状态更新导致重组，即使视频暂停也持续运行。

**方案**: 改用 ExoPlayer 的 `Player.Listener` 事件回调 + 仅在播放时启动定时器的模式。

**实际**: 添加 `Player.Listener` + `DisposableEffect` 管理生命周期，轮询循环加 `if (exoPlayer.isPlaying)` 守卫。

---

### M3. [x] SyncService: 前台服务停止时机优化 ✅

**文件**: `sync/SyncService.kt:82-84, 94`

**问题**: (1) 固定 3 秒延迟后 `stopForeground`，而非等待 Engine 完成。(2) `START_NOT_STICKY` 系统杀死后不自动重启。

**方案**: (1) 将 `stopForeground` 绑定到 SyncEngine 的完成回调。(2) 改用 `START_REDELIVER_INTENT`。

**实际**: 所有 `START_NOT_STICKY` → `START_REDELIVER_INTENT`，stopForeground 改为 `if (!finalProgress.isRunning)` 条件触发 + 1.5s 延迟。

---

### M4. [x] SyncEngine: 元数据并行提取 ✅

**文件**: `sync/SyncEngine.kt:499-501`

**问题**: 下载完成后的元数据提取（时长、分辨率、缩略图）是串行的 `for` 循环，大同步任务长时间阻塞。

**方案**: 使用 `coroutineScope` + `Semaphore` 并行提取，与下载类似并发模型。

**实际**: 串行 for 循环替换为 `Semaphore(maxConcurrency.coerceAtMost(3))` + `coroutineScope` + `launch` 并行提取。

---

### M5. [x] VideoItemDao: 消除重复的同步/异步查询方法 ✅

**文件**: `data/local/dao/VideoItemDao.kt:86-119`

**问题**: `getItemsInFolder` / `getItemsInFolderSync`、`getSubFolders` / `getSubFoldersSync`、`searchByFileName` / `searchByFileNameSync` 六个方法中 SQL 完全重复。

**方案**: 删除 Sync 后缀方法，调用方改用 Flow 版本的 `.first()` 获取一次性结果。

**实际**: 删除 `getItemsInFolderSync`、`getSubFoldersSync`、`searchByFileNameSync`，SyncDocumentsProvider 改用 Flow + `.first()`。

---

### M6. [x] ProGuard 规则收紧 ✅

**文件**: `app/proguard-rules.pro:1-3`

**问题**: `-keep class jcifs.** { *; }` 和 `-keep class net.schmizz.** { *; }` 保留整个库所有类成员，严重削弱 R8 优化。

**方案**: 测试缩小 keep 范围。先用 `-keep class` 不带 `{ *; }` 观察是否正常，逐步缩小到实际被反射调用的类。

**实际**: 移除所有 `{ *; }` 通配符，R8 可对类内部进行优化和缩减。Release 构建验证通过。

---

### M7. ~~DB Migration 链不完整~~ (不处理)

> 用户确认跳过此项。

---

### M8. [x] PlayerScreen: 跑马灯动画缓存优化 ✅ (已验证无需修改)

**文件**: `player/PlayerScreen.kt:716-733`

**问题**: `rememberInfiniteTransition` + `textMeasurer.measure()` 在长文件名时触发，每次 fileName 变化都重新计算文本布局。

**方案**: `textLayoutResult` 已用 `remember(fileName)` 缓存，`rememberInfiniteTransition` 本身也是 `remember` 包裹。当前实现已基本合理，仅需确保不在重组热路径中创建额外对象。

**实际**: 验证 `rememberTextMeasurer()` + `remember(fileName)` + `rememberInfiniteTransition` 已正确缓存，无需修改。

---

## LOW 优先级 — 全部完成 ✅

### L1. [x] GesturePlayerView 触摸阈值魔数提取 ✅

**文件**: `player/GesturePlayerView.kt`

**问题**: onScroll 中垂直/水平手势判定阈值（20、30）和 seek 灵敏度（100f）为硬编码魔数。

**实际**: 提取为 `VERTICAL_GESTURE_THRESHOLD`、`HORIZONTAL_GESTURE_THRESHOLD`、`SEEK_SENSITIVITY` 命名常量。

---

### L2. [x] 导航参数用逗号拼接 ID 字符串 — 已验证不存在此问题 ✅

**文件**: `navigation/NavigationHelper.kt`

**分析**: videoIds 通过 `VideoQueue` 单例在 ViewModel 间传递（`setIds` → `consumeIds`），未通过路由参数拼接。路由仅传递单个 `videoItemId`（Long）和 `returnFolder`（String），无性能问题。

---

### L3. [x] VideoQueue @Singleton 全局状态 — 已验证为合理设计 ✅

**文件**: `navigation/VideoQueue.kt`

**分析**: `consumeIds()` 在读取后立即清空状态，是 ViewModel 间临时消息传递的标准模式。@Singleton 确保跨 ViewModel 共享同一实例，生命周期与 Application 一致，无泄漏风险。

---

### L4. [x] SMB listFilesRecursive maxFiles 硬编码 ✅

**文件**: `sync/smb/SmbFileClient.kt`

**问题**: `maxFiles: Int = 10000` 硬编码为字面量。

**实际**: 提取为 `MAX_REMOTE_FILES = 10000` 命名常量，便于后续配置化。

---

### L5. [x] UserPreferencesRepository 重复 Flow+suspend getter — 已验证为合理 API 设计 ✅

**文件**: `data/repository/UserPreferencesRepository.kt`

**分析**: `suspend fun getXxx() = xxx.first()` 是 Flow API 的标准便捷封装，让不需要响应式更新的调用方以更简洁的方式获取一次性值。13 个 suspend getter 各仅一行，维护成本可忽略。

---

### L6. [x] BrowserViewModel.ensureVideoRecord 调用上下文 — 已验证无问题 ✅

**文件**: `browser/BrowserScreen.kt:394, 477`

**分析**: 调用点均在 `scope.launch {}`（`rememberCoroutineScope()`）中执行，不会阻塞 UI 线程。`ensureVideoRecord` 本身是 suspend 函数，正确利用了协程异步机制。

---

## 修复路线图

```
Phase 1 (高收益·低风险) — 全部完成 ✅
  H1 → H4 → H6 → M1 → M5

Phase 2 (中收益·中风险) — 全部完成 ✅
  H2 → H3 → M2 → M4 → M8

Phase 3 (需测试验证) — 全部完成 ✅
  H5 → M3 → M6
```

## 变更文件汇总

| 文件 | 变更类型 |
|---|---|
| `gradle/libs.versions.toml` | 新增 Coil 依赖 |
| `app/build.gradle.kts` | 添加 Coil implementation |
| `app/proguard-rules.pro` | 收紧 keep 规则 |
| `ui/components/Thumbnail.kt` | BitmapFactory → Coil AsyncImage |
| `ui/components/VideoGridItem.kt` | BitmapFactory → Coil AsyncImage |
| `ui/theme/Theme.kt` | colorScheme remember 缓存 |
| `player/PlayerScreen.kt` | ExoPlayer appContext + Listener + 暂停时跳过更新 |
| `browser/BrowserViewModel.kt` | 按需查询播放记录 + 缩略图修复移至文件夹切换 |
| `data/local/dao/PlaybackRecordDao.kt` | 新增 getByFolderFlow |
| `data/local/dao/VideoItemDao.kt` | 删除 3 个重复 Sync 方法 |
| `data/local/entity/VideoItemEntity.kt` | 添加 syncRelationId 索引 |
| `data/local/AppDatabase.kt` | 版本升至 11 |
| `data/repository/PlaybackRepository.kt` | 新增 getByFolderFlow 透传 |
| `di/DatabaseModule.kt` | 新增 MIGRATION_10_11 |
| `sync/SyncEngine.kt` | 元数据并行提取 |
| `sync/SyncService.kt` | START_REDELIVER_INTENT + 条件停止 |
| `sync/smb/SmbFileClient.kt` | ensureActive 取消检查 + MAX_REMOTE_FILES 常量 |
| `sync/sftp/SftpFileClient.kt` | ensureActive 取消检查 |
| `provider/SyncDocumentsProvider.kt` | Sync 方法 → Flow.first() |
| `player/GesturePlayerView.kt` | 触摸阈值魔数提取为命名常量 |
