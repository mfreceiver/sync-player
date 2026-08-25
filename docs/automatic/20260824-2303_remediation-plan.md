# 20260824-2303 审计修复实施计划（Audit Remediation Plan）

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development（推荐）或 ocmar-executing-plans 按任务逐项实施本计划。步骤用 checkbox（`- [ ]`）跟踪。
> 证据链：`docs/automatic/20260824-2303_android-quality.md`（主报告）+ `docs/automatic/.work/20260824-2303/agent1-4-notes.md`。
> **行号均为审计基线（5c7e419）**，执行时以引用的代码内容重新定位，不盲信行号。

**Goal:** 修复审计确认的 21 项发现（AND-001~008、010~022），AND-009 记录为设计取舍不修。

**Architecture:** 5 条写域隔离 Lane（P 播放器 / S1 同步性能 / S2 同步服务 / N 导航浏览器 / L 安全锁），每 Lane 独立 git worktree + 独立 fixer 会话，两波合并回主树；每 Lane 内部任务串行（同文件自冲突），Lane 间文件零交集（已逐文件核对）。

**Tech Stack:** Kotlin 2.2.10 / Compose M3 / Media3 / coroutines；测试 JUnit4 + MockK + Turbine + coroutines-test。

## Global Constraints

- **类型验证唯一来源**：改完必跑 `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin`（LSP 已禁用，不信任何 LSP 诊断）。
- **测试纪律**：断言枚举/数量/调用次数，**绝不**断言用户可见文案；suspend 用 `coEvery`；CancellationException 不吞。
- **i18n**：所有用户可见文本（含 a11y contentDescription）进 `strings.xml`。
- **红线**：敏感信息（密码/密钥/凭据/volumeId/path）不进日志/UI；CE 先传播；AEAD v2 格式不动。
- **Diff 记录**：每任务 `git rev-parse HEAD` 记基线 → 完成后 `git diff --stat`；**不 commit**（集成阶段统一处理，除非用户明确要求）。
- worktree 内跑测试：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests "<模式>"`，Lane 收尾跑全量。

---

## 并行批次评估（写域矩阵与波次）

### 写域冲突分析结论

| Lane | 发现 | 独占文件（零交集，已核对） |
|---|---|---|
| **P 播放器**（7 项） | 001, 014, 012, 015, 016, 020, 018(player 半) | `player/PlayerScreen.kt`、`player/PlayerViewModel.kt`、`player/GesturePlayerView.kt`、`player/SeekPreviewExtractor.kt`、`res/values/strings.xml`(a11y 键)、player 测试 |
| **S1 同步性能**（4 项） | 010, 011, 013, 021 | `sync/SyncDownloadPipeline.kt`、`sync/RemoteFileClientFactory.kt`、`sync/smb/SmbFileClient.kt`、`sync/sftp/SftpFileClient.kt`、`sync/ImageMetadataExtractor.kt`、`sync/SyncProgressPublisher.kt`、sync 性能测试 |
| **S2 同步服务**（3 项） | 003, 008, 022 | `sync/SyncService.kt`、`sync/SyncEngine.kt`、`sync/SyncPreviewViewModel.kt`、`sync/SyncPreviewScreen.kt`、sync 服务测试 |
| **N 导航/浏览器**（6 项） | 002, 004, 006, 017, 019, 018(browser/search 半) | `navigation/AppNavHost.kt`、`MainActivity.kt`、`browser/BrowserContent.kt`、`browser/BrowserScreen.kt`、`browser/SearchScreen.kt`、`browser/BrowserViewModel.kt`、`browser/SearchViewModel.kt`、`ui/components/StarRatingInput.kt`、`imageviewer/ImageViewerViewModel.kt`、`imageviewer/ImageViewerScreen.kt`、`res/values/strings.xml`(星值键)、对应测试 |
| **L 安全锁**（2 项） | 005, 007 | `security/AppLockManager.kt`、`security/LockScreenOverlay.kt`、`data/repository/UserPreferencesRepository.kt`（只读不改也行）、AppLock 测试 |

**关键冲突消解**：
- AND-018（错误事件语义）横跨 P/N 两域 → 中央统一配方 `ErrorEvent(message, seq)`（见 P7），两 Lane 各实现各自半边，代码形态完全一致。
- AND-013 修复**限定在 SyncProgressPublisher**（完成事件走节流），不动 `SyncService.kt` 采集器 → 与 S2 Lane 零冲突。
- AND-002 播放器侧修复是**纯 writer 侧**（AppNavHost 写 SavedStateHandle，PlayerViewModel 既有 reader 不动）→ PlayerViewModel.kt 仅 P Lane 持有写权。
- `strings.xml`：Wave1 只有 P 追加（a11y 键），Wave2 只有 N 追加（星值键）→ 波次错开，无并发冲突。
- AND-009（浏览器深层文件夹进程死亡恢复）= 设计取舍，**记录不修**（如未来要做：SavedStateHandle 存最深 folder path，单独小任务）。

### 波次与派发（fixer 分档并发约束：glm≤2 并发 / clm≤3 并发）

| Wave | Lane | 派发 | worktree | 理由 |
|---|---|---|---|---|
| **1** | P | fixer-glm | `wt-rem-p` | 7 项、PlayerScreen 1000+ 行、播放器逻辑精细 → 中等以上 |
| **1** | S1 | fixer-glm | `wt-rem-s1` | AND-010 连接复用是结构性重设计 → 中等以上 |
| **1** | L | fixer-clm | `wt-rem-l` | 2 项、方案完全定死、小改动 → 简单 |
| **2** | S2 | fixer-glm | `wt-rem-s2` | AND-003 涉及 mutex/终态时序语义 → 中等以上 |
| **2** | N | fixer-glm | `wt-rem-n` | 6 项、AND-002 进程死亡语义 + 多文件 → 中等以上 |

- Wave 间无代码依赖，分波纯粹受 glm 并发上限约束；若某 Wave1 Lane 提前完成且 glm 空闲，Wave2 Lane 可提前启动（保持 glm 在跑数 ≤2、总写域不重叠即可）。
- **合并顺序**：每 Wave 结束后按 Lane 顺序串行合并回主树（文件不相交，理论零冲突；`strings.xml` 追加冲突不存在于同波）→ 合并后跑全量门禁：`compileDebugKotlin` + `testDebugUnitTest` + `lintDebug`。
- worktree 创建/回收走 `worktrees` skill（执行时加载）。

### Lane 内任务顺序（同文件自冲突规避）

- **P**：P1(AND-001) → P2(AND-014) → P3(AND-012) → P4(AND-015) → P5(AND-016) → P6(AND-020) → P7(AND-018a)。P1/P2 都动 PlayerScreen+PlayerViewModel，必须先后。
- **S1**：S1-1(AND-010) → S1-2(AND-011) → S1-3(AND-013) → S1-4(AND-021)。S1-1 最大先行，S1-4 是 S1-1 同文件一行日志。
- **S2**：S2-1(AND-003) → S2-2(AND-008) → S2-3(AND-022)。
- **N**：N1(AND-002) → N2(AND-004) → N3(AND-006) → N4(AND-017) → N5(AND-019) → N6(AND-018b)。N1/N2 同函数区域（navigate 调用点）连着做。
- **L**：L1(AND-005) → L2(AND-007)。

---

## Lane P — 播放器（Wave 1 · fixer-glm · worktree `wt-rem-p`）

### Task P1: AND-001 ON_STOP 即时持久化进度 + saveProgress 脱离 viewModelScope

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt`（ON_STOP 观察者 ~:254-267）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:192-203`（saveProgress）
- Test: `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelTest.kt`

**Fix spec:**
1. PlayerScreen ON_STOP 观察者：`exoPlayer.pause()` 后立即
   ```kotlin
   viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
   ```
2. PlayerViewModel.saveProgress：DB 写不再用 `viewModelScope.launch`——改用 VM 内自建短作用域（recents-swipe 路径 viewModelScope 可能已 cancel，launch 成 no-op，审计 F1 sub-risk）：
   ```kotlin
   private val progressSaveScope = CoroutineScope(SupervisorJob() + ioDispatcher) // @IoDispatcher 已注入则复用
   fun saveProgress(position: Long, duration: Long) {
       val item = uiState.value.videoItem ?: return
       progressSaveScope.launch {
           playbackRepository.saveProgress(item.id, position, duration) // 签名以现有代码为准
       }
   }
   ```
   周期落库（30-60s）**不做**（YAGNI；ON_STOP 已覆盖主要丢失路径，切视频/dispose 路径既有）。
3. 先读 `PlayerScreen.kt:138,192,254-267,269-280` 与 `PlayerViewModel.kt:192-203` 确认现有 saveProgress 签名与调用形态再动手。

**Acceptance Criteria:**
- `P1-C1`: PlayerViewModelTest 新增 `onStop saveProgress persists current position`：调用 `viewModel.saveProgress(12345L, 60000L)` → `coVerify { playbackRepository.saveProgress(any(), 12345L, 60000L) }`（参数按真实签名适配）。
- `P1-C2`: 新增 `saveProgress survives viewModelScope cancellation`：取消 viewModelScope 后调用 saveProgress → repository 仍被调用（证明非 viewModelScope 路径）。
- `P1-C3`: `grep -n "saveProgress" app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt` 显示 ON_STOP 观察者内存在调用（在 pause 之后）。
- `P1-C4`: compileDebugKotlin 通过。

- [ ] **Step 1** 写失败测试（P1-C1/C2 两条用例，MockK coEvery 假 repository）
- [ ] **Step 2** `./gradlew testDebugUnitTest --tests "cn.vectory.syncplayer.player.PlayerViewModelTest"` → FAIL
- [ ] **Step 3** 实现（上述 spec）；PlayerScreen ON_STOP 接线
- [ ] **Step 4** 同命令 → PASS；`compileDebugKotlin` → BUILD SUCCESSFUL
- [ ] **Step 5** `git rev-parse HEAD` 基线 + `git diff --stat` 记录

### Task P2: AND-014 手势 seek 提交点改用冻结基准

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/GesturePlayerView.kt:101-113`（seekBasePositionMs 冻结处 + ACTION_UP 回调）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:309-313`（提交点 `exoPlayer.currentPosition + offsetMs`）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:341-347`（onGestureSeekFinished）
- Test: `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelTest.kt`

**Fix spec:**
手势回调携带基准位置，提交统一 `base + offset`（与显示/预览路径同一基准，消除 d150590 引入的分叉）：
```kotlin
// GesturePlayerView：回调签名扩为 (offsetMs, basePositionMs)——onSeekTo 或等价回调字段
// PlayerScreen 提交点：
val targetMs = (basePositionMs + offsetMs).coerceIn(0L, exoPlayer.duration)
viewModel.onGestureSeekFinished(targetMs)   // VM 改收最终目标值，或收 (offset, base) 内部计算
exoPlayer.seekTo(targetMs)
```
VM 侧 `_currentPosition.value + offsetMs` 的旧算式删除（那是实时基准）。先读三个文件的现行回调链（gestureListener / rememberUpdatedState 模式）再改，保持 listener 构建 `remember` 形态不变。

**Acceptance Criteria:**
- `P2-C1`: PlayerViewModelTest 新增 `gesture seek target uses frozen base not live position`：base=10000、offset=5000、`_currentPosition` 已推进到 20000 → 断言最终 seek 目标为 15000（非 25000；按 VM 实际暴露的 state/命令断言）。
- `P2-C2`: `grep -n "currentPosition + offset\|currentPosition.plus(offset)" PlayerScreen.kt PlayerViewModel.kt` → 0 命中。
- `P2-C3`: compileDebugKotlin + 既有测试全绿。

- [ ] Step 1 失败测试（P2-C1） → Step 2 确认 FAIL → Step 3 实现 → Step 4 PASS+compile → Step 5 diff 记录

### Task P3: AND-012 SeekPreview 位图移除显式 recycle + prefetchScope 加 SupervisorJob

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:35-48,121-133`
- Test: 既有测试回归（无新测试；recycle 移除靠 grep+编译+既有套件）

**Fix spec:**
minSdk 34 → 位图像素在 native heap 由 GC 回收，显式 `recycle()` 反而制造 dispose 一帧窗口的 use-after-recycle（RenderThread 崩溃）。
1. `entryRemoved` 中删除 `oldValue.recycle()`（保留缓存移除逻辑本身）。
2. `release()` 的 `evictAll()` 保留（entryRemoved 不再 recycle 后安全）。
3. `prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`（消除单子失败禁用后续预取的隐患）。

**Acceptance Criteria:**
- `P3-C1`: `grep -c "recycle()" app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt` → 0。
- `P3-C2`: `grep -n "SupervisorJob" SeekPreviewExtractor.kt` → ≥1 命中。
- `P3-C3`: compileDebugKotlin + testDebugUnitTest 全绿。

- [ ] Step 1 实现 → Step 2 grep 验证 C1/C2 → Step 3 compile+test → Step 4 diff 记录

### Task P4: AND-015 seek 条无障碍语义

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:874-916`（主 seek 条）、`:929-948`（MinimizedProgressBar）
- Modify: `app/src/main/res/values/strings.xml`（新增 a11y 键）
- 范式参考: `browser/BrowserSortFilter.kt:306-326`

**Fix spec:**
```kotlin
// 主 seek 条 Box（drag handler 所在）：
Modifier.semantics {
    progressBarRangeInfo = ProgressBarRangeInfo(
        positionMs.toFloat(), TextRange(0f, durationMs.toFloat())
    )
    stateDescription = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}" // 复用现有时长格式化工具，勿硬编码文案骨架以外的字面用户文本
}
// 同一 Modifier 链追加可编程 seek 动作（±10s）：
.customAccessibilityAction(stringResource(R.string.player_a11y_seek_forward_10s)) { onSeekBy(+10_000L); true }
.customAccessibilityAction(stringResource(R.string.player_a11y_seek_backward_10s)) { onSeekBy(-10_000L); true }
```
`onSeekBy` = 现有 seek 通道（`exoPlayer.seekTo((currentPosition+delta).coerceIn(0,duration))` + VM 同步）。MinimizedProgressBar 仅补 `progressBarRangeInfo`。strings.xml 新键（仅 P Lane 本波追加）：`player_a11y_seek_forward_10s` / `player_a11y_seek_backward_10s`（中文文案如"快进 10 秒"/"快退 10 秒"）。

**Acceptance Criteria:**
- `P4-C1`: `grep -c "progressBarRangeInfo" PlayerScreen.kt` ≥ 2。
- `P4-C2`: `grep -c "customAccessibilityAction" PlayerScreen.kt` ≥ 2。
- `P4-C3`: `grep -c "player_a11y_seek" app/src/main/res/values/strings.xml` = 2。
- `P4-C4`: compileDebugKotlin 通过。

- [ ] Step 1 实现 → Step 2 grep 三项 → Step 3 compile → Step 4 diff 记录

### Task P5: AND-016 播放器设置 chips 语义（标签 + 选中态）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:983-1006`（调用点）、`:1031-1048`（IconOptionChip）、`:1050-1075`（TextOptionChip）
- Modify: `app/src/main/res/values/strings.xml`（图标按钮描述键）

**Fix spec:**
1. `IconOptionChip` 增加 `label: String` 参数，内部 `Icon(contentDescription = null)` → `Icon(contentDescription = label)`（或 Modifier.semantics，与 BrowserSortFilter 范式一致）。两个调用点（旋转/全屏）传 `stringResource(R.string.player_a11y_rotation)` / `player_a11y_fullscreen`。
2. `TextOptionChip`：`Surface(onClick, modifier = Modifier.semantics { selected = isSelected; role = Role.RadioButton })`——读屏播报"1x，已选中/未选中"。

**Acceptance Criteria:**
- `P5-C1`: PlayerScreen.kt 中 `contentDescription = null` 在 IconOptionChip 内部 0 命中（调用点均传标签）。
- `P5-C2**: `grep -c "selected = isSelected" PlayerScreen.kt` ≥ 1。
- `P5-C3**: strings.xml 含 2 个新 `player_a11y_` 键；compile 通过。

- [ ] Step 1 实现 → Step 2 grep → Step 3 compile → Step 4 diff 记录

### Task P6: AND-020 播放手势系统排除区

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/GesturePlayerView.kt`（View 级，onLayout 或 attach 时）

**Fix spec:**
```kotlin
private val exclusionRects = listOf(Rect())
override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    val cap = (200 * resources.displayMetrics.density).toInt() // Android 10+ 每边 200dp 限额
    exclusionRects[0].set(0, 0, minOf(cap, width), height)     // 左缘（右缘对称第二个 Rect，可列表两个）
    setSystemGestureExclusionRects(exclusionRects)
}
```
仅左右缘（横向 seek 与 back 手势冲突主场景；上下缘系统限额更紧，YAGNI）。若 GesturePlayerView 非 View 子类而是包装，则在其宿主 AndroidView 工厂内设置——以实际类层次为准。

**Acceptance Criteria:**
- `P6-C1**: `grep -c "setSystemGestureExclusionRects" GesturePlayerView.kt` ≥ 1。
- `P6-C2**: compileDebugKotlin 通过。

- [ ] Step 1 实现 → Step 2 grep+compile → Step 3 diff 记录

### Task P7: AND-018a 错误事件化（player 半边，中央配方）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（error 字段）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:504-509`（LaunchedEffect）

**中央配方（Lane N 的 N6 完全一致）:**
```kotlin
// UiState 内：
data class ErrorEvent(val message: String, val seq: Long)   // message 仍为已映射的资源文案（VM 不生成文案，红线不动）
val error: ErrorEvent?   // 原 String? 替换
// VM 内：
private val errorSeq = java.util.concurrent.atomic.AtomicLong(0L)
fun showError(msg: String) { _uiState.update { it.copy(error = ErrorEvent(msg, errorSeq.incrementAndGet())) } }
// Screen 内：
uiState.error?.let { ev ->
    LaunchedEffect(ev.seq) { snackbarHostState.showSnackbar(ev.message); viewModel.clearError() }
}
```

**Acceptance Criteria:**
- `P7-C1`: PlayerViewModelTest 新增 `identical consecutive errors get distinct seq`：连续两次 showError(同文案) → 两次 seq 递增、第二次事件可见。
- `P7-C2**: `grep -n "LaunchedEffect(ev.seq)\|LaunchedEffect(it.seq)" PlayerScreen.kt` ≥ 1；旧 `LaunchedEffect(it)`（key=文案）模式清零。
- `P7-C3**: compile + 全量测试绿。

- [ ] Step 1 失败测试 → Step 2 FAIL → Step 3 实现 → Step 4 PASS+compile → Step 5 diff

---

## Lane S1 — 同步性能（Wave 1 · fixer-glm · worktree `wt-rem-s1`）

### Task S1-1: AND-010 通道级客户端复用 + SMB 探测改单点查询

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:165-174`（per-file worker）
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:95-122`（connect 的 listFiles 探测）
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt`（如需 disconnect 幂等化）
- Test: `app/src/test/java/cn/vectory/syncplayer/sync/`（新增或扩展 pipeline 测试）

**Fix spec（结构性，先读 SyncDownloadPipeline.kt 全文件 + ec83906 拆分结构）:**
1. **worker 池化**：`for (action in toActuallyDownload) { semaphore.acquire(); launch { new client + connect } }` 改为 W=`MAX_CONCURRENCY`(3) 个长驻 lane，每 lane 建**一个** client，从 Channel 消费 action：
   ```kotlin
   val channel = Channel<SyncAction>(Channel.UNLIMITED)
   repeat(MAX_CONCURRENCY) {
       launch(Dispatchers.IO) {
           var client = getClient(source.protocol)
           try {
               client.connect(source, relation.remotePath)
               for (action in channel) {
                   client = performWithRetry(action, client) // 重试内失败分类 retryable → client.disconnect(); 重建+reconnect（保留 E5 每次重试重连语义）
               }
           } finally { runCatching { client.disconnect() } }
       }
   }
   toActuallyDownload.forEach { channel.send(it) }; channel.close()
   ```
   信号量语义由固定 W lane 天然保留；**不得**改变 CE-first / 重试白名单 / 终态计数（completedFiles+failedFiles==totalFiles 不变量在 SyncEngineBoundaryTest 有锁）。
2. **SMB 探测**：`SmbFileClient.connect` 的 `listFiles()` 全量枚举 → 根目录 `exists()` 单点查询（失败仍抛既有异常类型，保持错误分类不变）。
3. SyncEngineTest 用 relaxed mock 工厂、不断言生命周期（审计确认），无契约破坏。

**Acceptance Criteria:**
- `S1-1-C1**: 新测试 `N actions use at most MAX_CONCURRENCY clients`：mockk 工厂，喂 10 个 action → `verify(exactly = 3) { factory.create(any()) }`（或 =MAX_CONCURRENCY 常量）。
- `S1-1-C2**: 既有 SyncEngineTest/SyncEngineBoundaryTest/SyncEngineDryRunTest 全绿（不变量未被破坏）。
- `S1-1-C3**: `grep -n "listFiles" SmbFileClient.kt` 仅剩扫描/列举业务用途，connect 路径 0 命中。
- `S1-1-C4**: compile 通过。

- [ ] Step 1 失败测试（C1：当前实现 10 action 会 create 10 次 → FAIL） → Step 2 FAIL 确认 → Step 3 实现 → Step 4 全量 sync 测试 PASS → Step 5 diff

### Task S1-2: AND-011 图片元数据两遍解码

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt:44-62`
- Test: `app/src/test/java/cn/vectory/syncplayer/sync/ImageMetadataExtractorTest.kt`（新增）

**Fix spec:**
1. 抽薄解码层便于 JVM 测试：
   ```kotlin
   interface BitmapDecoder { // 内部接口，prod 实现包 BitmapFactory
       fun decodeBounds(stream: InputStream): IntArray  // inJustDecodeBounds=true → [w, h]
       fun decodeSampled(stream: InputStream, sampleSize: Int): Bitmap?  // inSampleSize
   }
   ```
2. `extractAndSave`：流1 decodeBounds 取宽高（写元数据）→ 计算 sampleSize（目标 ≤640px，标准 inSampleSize 2 幂算法）→ 流2 decodeSampled 出缩略图（替代全尺寸 decode+sampleThumbnail）→ 流3 EXIF（保留，仅元数据）。
3. 峰值内存从 ~192MB(48MP) 降到 ~1-2MB。加密流不可 reset，故每遍重开流是**预期形态**（现状本就二次 openInputStream）。

**Acceptance Criteria:**
- `S1-2-C1**: 新测试（fake BitmapDecoder）：48MP(8000×6000) → 断言 decodeBounds 被调、sampleSize ≥ 12（2 幂向下）、decodeSampled 收到该 sampleSize、decodeFull 从未被调用。
- `S1-2-C2**: `grep -c "decodeStream(input)" ImageMetadataExtractor.kt` 的无 Options 全尺寸路径 0 命中。
- `S1-2-C3**: compile + 既有图片管线测试绿。

- [ ] Step 1 失败测试 → Step 2 FAIL → Step 3 实现 → Step 4 PASS → Step 5 diff

### Task S1-3: AND-013 完成事件走节流

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:160-175,191-196`
- Test: 扩展 `SyncEngineProgressTest`（或同名位置既有测试）

**Fix spec:**
1. `completeDownload/markDeleted/beginDownload` 的发布改走与 `reportDownloadProgress` 相同的 500ms 节流阀（脏标记 + 定期 flush）。
2. **强制立即发布点**：phase 变化（DOWNLOADING→DELETING 等）与终态（COMPLETED/FAILED/CANCELLED）——保证最终 completedFiles 计数精确（4989375 的计数准确性意图保留）。
3. `completedPaths` 的 `toSet()` 拷贝仅在真实发布时做（不再每次完成事件 O(n) 拷贝）。

**Acceptance Criteria:**
- `S1-3-C1**: 测试：100ms 内 50 次 completeDownload → Turbine 收到的中间 emission ≤2 条，flush 后（advanceTimeBy 600ms 或终态触发）最终 state.completedFiles == 50。
- `S1-3-C2**: 终态 emission 无条件（同步既有"完成语义"测试调整后仍绿——允许用虚拟时间推进等待 flush）。
- `S1-3-C3**: compile。

- [ ] Step 1 失败/调整测试 → Step 2 确认 → Step 3 实现 → Step 4 PASS → Step 5 diff

### Task S1-4: AND-021 volumeId 出日志

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:115`

**Fix spec:**
`DebugLogger.i(TAG, "当前卷: ${store.volumeId}")` → `DebugLogger.i(TAG, "当前卷: <configured>")`（布尔语义"已配置"，不泄 SAF tree URI/卷 UUID）。红线 6 一致性；全仓库唯一违例点（对照 SyncDeleteExecutor.kt:94-98 的脱敏范式）。

**Acceptance Criteria:**
- `S1-4-C1**: `grep -rn "volumeId" app/src/main/java --include="*.kt" | grep -i "log\|DebugLogger"` → 0 命中。
- `S1-4-C2**: compile。

- [ ] Step 1 改一行 → Step 2 grep → Step 3 compile → Step 4 diff

---

## Lane L — 安全锁（Wave 1 · fixer-clm · worktree `wt-rem-l`）

### Task L1: AND-005 锁缓存冷启动 gate

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt:28,43-48,60-67`
- Test: `app/src/test/java/cn/vectory/syncplayer/security/AppLockManagerTest.kt`（新增或扩展）

**Fix spec:**
```kotlin
@Volatile private var cacheInitialized = false
// 既有 collector（Dispatchers.IO collect DataStore）：
cachedSecureLockEnabled = it; cacheInitialized = true
// onStateChanged ON_START 分支开头：
if (!cacheInitialized) {
    cachedSecureLockEnabled = runBlocking { userPrefs.secureLockEnabled.first() } // 仅首个 ON_START 一次，单文件短读
    cacheInitialized = true
}
if (cachedSecureLockEnabled) { /* 既有锁定逻辑 */ }
```
先读 `AppLockManager.kt` 与 `UserPreferencesRepository.kt:96-98` 确认 flow 类型（`first()` 需 suspend Flow）。runBlocking 限定在此单一首帧前路径（审计推荐方案），后续 ON_START 走缓存。

**Acceptance Criteria:**
- `L1-C1**: 测试 `cold start with lock enabled locks on first ON_START`：不启动 collector（缓存未初始化）、coEvery pref=true → onStateChanged(ON_START) → `isLocked` 为 true。
- `L1-C2**: 测试 `cache warm path unchanged`：缓存已初始化为 false → ON_START → 不锁（既有行为回归）。
- `L1-C3**: compile + 既有 AppLock 相关测试绿。

- [ ] Step 1 失败测试（L1-C1） → Step 2 FAIL → Step 3 实现 → Step 4 PASS → Step 5 diff

### Task L2: AND-007 锁屏冷却态 rememberSaveable

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/security/LockScreenOverlay.kt:28-31,44-65`

**Fix spec:**
1. `failCount/isCooldown/cooldownRemaining` 从 `remember` → `rememberSaveable`（Int/Boolean/Long 均可 Bundle 化，uiMode/fontScale 重建与进程死亡都存活）。
2. `LaunchedEffect(Unit)` 自动弹生物认证处加 `if (!isCooldown)` 门——冷却中不重弹（系统级 BiometricPrompt 限流仍是兜底）。
3. 不做 AppLockManager 上提（最小改动；manager 上提列为未来可选项）。

**Acceptance Criteria:**
- `L2-C1**: `grep -c "rememberSaveable" LockScreenOverlay.kt` ≥ 3（三个状态字段）。
- `L2-C2**: 认证触发 effect 有冷却门控（代码可见 `isCooldown` 条件）。
- `L2-C3**: compile。

- [ ] Step 1 实现 → Step 2 grep → Step 3 compile → Step 4 diff

---

## Lane S2 — 同步服务/UX（Wave 2 · fixer-glm · worktree `wt-rem-s2`）

### Task S2-1: AND-003 二次同步不再被丢（引擎消费循环）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:112-128`（consumeAndExecute）
- Test: `app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt`（扩展）

**Fix spec:**
在 `storageOperationMutex` 持有段内把"消费一次"改为"消费至空"：
```kotlin
storageOperationMutex.withLock {
    while (true) {
        val task = consumePendingTask() ?: break
        executeLocked(task)   // 既有执行+finalize 尾段
    }
}
```
- 效果：A 执行尾段（finalize 窗口）内 B 的 onStartCommand 即使被服务层 isActive 早退丢弃，B 的 pending task 也会在本轮 while 循环被消费——spinner 必得终态。
- **保持**：CE 先传播（NonCancellable DAO 清理块语义不动，审计 A4-4.12）；每轮终态 emission 语义不变（B 执行也发自己的 relationId 终态）。
- 服务层 `syncJob?.isActive` 早退守卫**保留**（防重复启动），不依赖它做排队。
- 先读 SyncEngine.kt 全文 + SyncPendingTaskManager，确认 consumePendingTask 返回语义（null=无任务）。

**Acceptance Criteria:**
- `S2-1-C1**: 测试：A 执行期间插入 B 的 pending task → 断言 B 也被执行（execute 调用两次/两条 relationId 终态）。
- `S2-1-C2**: 既有 SyncEngineTest/SyncPendingTaskManagerTest 全绿（RUNNING→PENDING 重置、cancelCleanup 语义未动）。
- `S2-1-C3**: compile。

- [ ] Step 1 失败测试 → Step 2 FAIL → Step 3 实现 → Step 4 PASS → Step 5 diff

### Task S2-2: AND-008 预览页双后退语义统一

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:90-108`

**Fix spec:**
TopAppBar 返回回调删除 `cancelSync()` 分支——**只** `onBack()`。取消同步的唯一入口 = 显式 Stop 按钮（:133-141，保留）。破坏性操作必须有明示可供性。

**Acceptance Criteria:**
- `S2-2-C1**: `grep -n "cancelSync" SyncPreviewScreen.kt` → 仅 Stop 按钮路径 1 处（或 0 处直接调用、经 VM stop）。
- `S2-2-C2**: compile。

- [ ] Step 1 实现 → Step 2 grep+compile → Step 3 diff

### Task S2-3: AND-022 dataSync FGS onTimeout

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt`

**Fix spec:**
```kotlin
override fun onTimeout(startId: Int) {   // API 35 Service.onTimeout(int)；compileSdk 35 可直写
    syncJob?.cancel()      // CE 路径：管线 CE-first 清理 + pendingTaskManager 的 NonCancellable RUNNING→PENDING 重置（审计验证在场）
    scheduleStop()         // 既有停止机制，不新造
}
```

**Acceptance Criteria:**
- `S2-3-C1**: `grep -n "onTimeout" SyncService.kt` ≥ 1。
- `S2-3-C2**: compileDebugKotlin 通过（方法签名 override 正确）。

- [ ] Step 1 实现 → Step 2 grep+compile → Step 3 diff

---

## Lane N — 导航/浏览器/图片（Wave 2 · fixer-glm · worktree `wt-rem-n`）

### Task N1: AND-002 队列进程死亡恢复（SavedStateHandle writer 激活）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt:66-74,240-246`（两个入口）
- Modify: `app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:66-71`（补 reader）
- Modify（常量归位）: `app/src/main/java/cn/vectory/syncplayer/navigation/NavigationHelper.kt` 或 Route 文件
- Test: `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelTest.kt` + ImageViewer 等价测试

**Fix spec:**
1. **先读** `PlayerViewModel.kt:99-150`：既有 `SAVED_VIDEO_IDS_KEY` reader（`savedStateHandle.getStateFlow(...)` + collector + `getAllIdsSorted()` 兜底）。确认它期望的值类型/格式（String 逗号分隔或 List），writer **严格按 reader 现状**写——reader 逻辑本任务不改（半成品设计已正确，缺的只是 writer）。
2. Writer（player 入口，AppNavHost）：
   ```kotlin
   navController.navigate(route) { launchSingleTop = true }   // 顺带覆盖 N2
   navController.currentBackStackEntry?.savedStateHandle?.set(
       SAVED_VIDEO_IDS_KEY, /* reader 期望的格式，来自即将入队的 id 列表 */
   )
   ```
   id 列表从 queue 入队前的同源数据取（NavigationHelper/回调参数——执行时定位 `videoQueue.setIds` 的调用方）。
3. ImageViewer 对称：`SAVED_IMAGE_IDS_KEY` 常量 + ImageViewerViewModel init 内镜像 player 的 collector；DB 兜底**可选**：仅当 ImageDao 已有 parentPath 查询时接（无则留空兜底，SavedStateHandle 路径已覆盖进程死亡主场景）。
4. 常量统一放 NavigationHelper（或单独 object），PlayerViewModel 改引用共享常量（仅 import 改动，不改行为）。

**Acceptance Criteria:**
- `N1-C1**: 测试（player）：SavedStateHandle 预置 `"videoIds"`（reader 格式）= [1,2,3]、queue 空 → `_allVideoIds` == [1,2,3] 且 `getAllIdsSorted()` **未被调用**（报告原始回归草案）。
- `N1-C2**: 测试（image）：同构断言 viewer 侧 reader 生效。
- `N1-C3**: `grep -rn "savedStateHandle\[\"videoIds\"\]\|set(SAVED_VIDEO_IDS_KEY" app/src/main` → writer ≥1 处、player/image 各一。

- [ ] Step 1 失败测试（N1-C1） → Step 2 FAIL → Step 3 实现 → Step 4 PASS → Step 5 diff

### Task N2: AND-004 入口导航防重

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt:69,74,242,246`
- Modify: `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:80-94`（通知导航路径）

**Fix spec:**
全部内容入口 navigate 加 `launchSingleTop = true`（player/image/通知入口）。范式同文件 :61/:218-221 已有。不做 300ms 节流（launchSingleTop 已消双击双栈；YAGNI）。

**Acceptance Criteria:**
- `N2-C1**: AppNavHost 内容入口 navigate 块全部含 `launchSingleTop = true`（grep 上下文核对 4 处）；MainActivity 通知导航 2 处同。
- `N2-C2**: compile。

- [ ] Step 1 实现 → Step 2 grep+compile → Step 3 diff

### Task N3: AND-006 恢复路径消费通知 intent

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:43`

**Fix spec:**
```kotlin
if (savedInstanceState == null || isNotificationIntent(intent)) pendingIntent.value = intent
```
`isNotificationIntent` 判定标记**执行时从 `sync/SyncNotificationHelper.kt:145-173` 的 PendingIntent 构造读取**（action 或专属 extra 名），不要发明标记。

**Acceptance Criteria:**
- `N3-C1**: MainActivity.kt 恢复分支条件含通知 intent 判定（代码可见）。
- `N3-C2**: compile。

- [ ] Step 1 读 SyncNotificationHelper 定标记 → Step 2 实现 → Step 3 compile → Step 4 diff

### Task N4: AND-017 筛选对话框控件语义与触点

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:480-506`
- Modify: `app/src/main/java/cn/vectory/syncplayer/ui/components/StarRatingInput.kt:32-52`
- Modify: `app/src/main/res/values/strings.xml`（星值/模式键）

**Fix spec:**
1. 模式切换按钮（36dp icon-only）：补 `contentDescription`（功能：星级筛选模式）+ `stateDescription`（当前模式：等于/大于/小于/关闭——4 个新 string 键）；OFF 态不得 contentDescription=null。
2. StarRatingInput：每星 `Modifier.semantics { contentDescription = ...; role = Role.Button }`，描述含星序（新键 `star_rating_a11y_n`，格式"第 %1$d 星，%2$s"，%2$s=已评/未评）；触点扩到 ≥48dp（外层 `Box(Modifier.minimumInteractiveComponentSize())` 包 28dp 星标，或 `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`）。
3. 范式参考 BrowserSortFilter.kt:306-326。

**Acceptance Criteria:**
- `N4-C1**: `grep -c "contentDescription = null" BrowserScreen.kt` 在模式按钮处 0 命中；StarRatingInput 每星描述带序号（代码可见格式串）。
- `N4-C2**: `grep -c "Role.Button\|minimumInteractiveComponentSize\|sizeIn(minWidth" StarRatingInput.kt` ≥ 2。
- `N4-C3**: strings.xml 新增 ≥5 键（4 模式 + 星序）；compile。

- [ ] Step 1 实现 → Step 2 grep+compile → Step 3 diff

### Task N5: AND-019 切图重置缩放

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt:247-250`

**Fix spec:**
```kotlin
var scale by remember(entity.id) { mutableFloatStateOf(1f) }
var offsetX by remember(entity.id) { mutableFloatStateOf(0f) }
var offsetY by remember(entity.id) { mutableFloatStateOf(0f) }
```
（`entity` 为当前图片参数，以实际变量名为准；双击复位逻辑保留不动。）

**Acceptance Criteria:**
- `N5-C1**: 三个变换状态 remember key 均为 entity id（代码可见）。
- `N5-C2**: compile。

- [ ] Step 1 实现 → Step 2 compile → Step 3 diff

### Task N6: AND-018b 错误事件化（browser/search 半边）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt`、`browser/SearchViewModel.kt`（error 字段）
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:345-350`、`browser/SearchScreen.kt:305-310`

**Fix spec:** 与 P7 **完全相同**的中央配方（`ErrorEvent(message, seq)` + `AtomicLong` + `LaunchedEffect(ev.seq)`）。两 VM + 两 Screen 各一套。

**Acceptance Criteria:**
- `N6-C1**: BrowserViewModel（或 Search）测试：连续同文案错误 seq 递增（同 P7-C1 形态）。
- `N6-C2**: 两 Screen 的 LaunchedEffect key 均为 seq；旧 key=文案模式清零。
- `N6-C3**: compile + 全量测试绿。

- [ ] Step 1 失败测试 → Step 2 FAIL → Step 3 实现 → Step 4 PASS → Step 5 diff

---

## 验证门禁（每 Wave 集成后）

1. 串行合并该波全部 worktree 分支回主树（文件不相交；如遇 `strings.xml` 追加冲突按 key 名并存消解——本计划波次设计已规避）。
2. 全量门禁（`release.sh` 同款本地链）：
   ```bash
   JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
   JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
   JAVA_HOME=/home/mar/android-studio/jbr ./gradlew lintDebug
   ```
   预期：compile BUILD SUCCESSFUL；394+新增 全绿 0 失败；lint 0 errors。
3. 红线抽查：`grep -rn "volumeId" app/src/main/java --include="*.kt" | grep -i log` → 0（S1-4 之后全局成立）。
4. 回收 worktree（worktrees skill 流程）。
5. commit 策略：每 Lane 一个 commit（`fix(<域>): remediate AND-xxx…`），提交前展示 `git status/diff --stat` 待用户确认。

## Criterion Ownership Matrix

| Criterion | 对应发现 | Owner | 验证（命令/测试 + 预期） | Final-only |
|---|---|---|---|---|
| P1-C1~C4 | AND-001 | Task P1 | PlayerViewModelTest 2 用例 PASS + grep + compile | N |
| P2-C1~C3 | AND-014 | Task P2 | VM 测试 + grep 旧算式 0 + compile | N |
| P3-C1~C3 | AND-012 | Task P3 | grep recycle=0 + SupervisorJob + 全量测试 | N |
| P4-C1~C4 | AND-015 | Task P4 | grep progressBarRangeInfo≥2 / customAccessibilityAction≥2 / strings=2 + compile | N |
| P5-C1~C3 | AND-016 | Task P5 | grep 标签/selected + compile | N |
| P6-C1~C2 | AND-020 | Task P6 | grep setSystemGestureExclusionRects + compile | N |
| P7-C1~C3 | AND-018(player) | Task P7 | seq 测试 + grep key=seq + compile | N |
| S1-1-C1~C4 | AND-010 | Task S1-1 | create 次数=3 测试 + 既有 sync 套件 + grep listFiles + compile | N |
| S1-2-C1~C3 | AND-011 | Task S1-2 | fake decoder 采样断言 + grep + compile | N |
| S1-3-C1~C3 | AND-013 | Task S1-3 | Turbine 节流断言 + 终态计数 + compile | N |
| S1-4-C1~C2 | AND-021 | Task S1-4 | grep volumeId-log=0 + compile | N |
| L1-C1~C3 | AND-005 | Task L1 | 冷启动锁定测试 ×2 + compile | N |
| L2-C1~C3 | AND-007 | Task L2 | grep rememberSaveable≥3 + compile | N |
| S2-1-C1~C3 | AND-003 | Task S2-1 | 双任务串行消费测试 + 既有套件 + compile | N |
| S2-2-C1~C2 | AND-008 | Task S2-2 | grep cancelSync 单入口 + compile | N |
| S2-3-C1~C2 | AND-022 | Task S2-3 | grep onTimeout + compile | N |
| N1-C1~C3 | AND-002 | Task N1 | SavedStateHandle 恢复测试 ×2 + grep writer + compile | N |
| N2-C1~C2 | AND-004 | Task N2 | grep launchSingleTop 6 处 + compile | N |
| N3-C1~C2 | AND-006 | Task N3 | 代码审查条件式 + compile | N |
| N4-C1~C3 | AND-017 | Task N4 | grep 语义/触点 + strings≥5 + compile | N |
| N5-C1~C2 | AND-019 | Task N5 | 代码审查 remember key + compile | N |
| N6-C1~C3 | AND-018(browser) | Task N6 | seq 测试 + grep + compile | N |
| GATE-1 | 全局 | 集成 | 全量 compile+test+lint 三绿 | **Y** |
| GATE-2 | 红线 6 | 集成（S1-4 后） | volumeId-log 全局 grep=0 | **Y** |
| REC-1 | AND-009 | 记录 | 不修（by-design；未来可选 SavedStateHandle 存最深 folder） | — |

## 记录项

- **AND-009**：浏览器深层文件夹进程死亡恢复 = 既定取舍（单 Browser 目的地 + VM 状态架构），不修。未来可选：最深 folder path 存 SavedStateHandle（小任务，非本次范围）。
- **AND-005 补充**：runBlocking 首读是审计推荐的最小方案；若实现中发现 ON_START 在主线程且 DataStore 首读可感知卡顿，降级方案为"未初始化即 fail-closed 锁定"（用户未启锁则首次冷启动误锁一次）——实现时二选一，L1-C1 测试适配所选方案。
