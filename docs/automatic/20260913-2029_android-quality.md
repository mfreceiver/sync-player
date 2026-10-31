# Android Quality Audit

- **Run ID**: 20260913-2029（2026-09-13 20:29 启动）
- **Lane**: android-quality（第 4 轮深度审计）
- **基线**: `main @ 64eb027b434f24c39e0d9cf7045456716b007c2d`（`v12.3.5-1-g64eb027`），工作树干净
- **本报告自包含**：全部证据（代码摘录、行号、上轮 finding 描述、外部条目说明）均已内联；不需要查阅任何过程文件或远端分支即可理解。行号均对应基线 64eb027。

## Methodology & Evidence Standard

主 Agent（profile/git 基线/构建门禁/对抗复核/编号裁定）+ 4 个并行子审计（lifecycle-state-navigation / coroutines-threads-performance / compose-view-accessibility / data-build-adversarial）。每个候选 finding 经过如下门禁后才计为 VERIFIED：

```text
DISCOVERED → ANDROID_MECHANISM_CONFIRMED → REPRODUCED/STATICALLY_PROVEN
→ ADVERSARIALLY_REVIEWED（子审计自反 + 对抗守卫清单 + 主 Agent 逐项代码复核）→ VERIFIED
```

反 folklore 纪律（本轮实际执行）：存 Context/Activity/View ≠ 泄漏（须证 holder 生命周期 > 被引对象且有真实引用链）；`collect` ≠ 泄漏（查 owner 与启动 scope）；`remember` 不机械换 `rememberSaveable`（先判断状态是否应跨重建存活）；重组本身 ≠ bug（须有真实成本机制或用户可见后果）；每个候选必须主动寻找既有框架/应用层守卫并记录为 Counterargument。**本仓库旋转不重建 Activity**（见下），多个常见误报源由此被驳回（见 Rejected Hypotheses）。

关键框架事实（本报告多个 finding 的前提）：
- `AndroidManifest.xml` MainActivity 声明 `android:configChanges="orientation|screenSize|smallestScreenSize|density|keyboardHidden|layoutDirection|locale"` → **旋转/密度/语言变更不重建 Activity**；**`uiMode`（深色模式）与 `fontScale` 不在列表 → 会重建**；进程死亡随时可能。
- 未启用 `enableOnBackInvokedCallback` → 经典返回路径，无预测式返回动画。

## Executive Summary

第 4 轮 android-quality 深度审计，在 `main @ 64eb027`（SAF 外部卷与静态加密移除、播放器手势改造之后）执行。基线门禁全绿：compileDebugKotlin + 581 个 JVM 单测（0 失败）+ lintDebug（0 error，36 条信息级警告）。

产出 **17 个 VERIFIED findings（AND-039..055）**，编号接续前轮（AND-001..022 已修复；AND-023..038 在未合并的远端报告 `origin/audit/android-quality` 中跟踪，其全称与描述已收录在本报告"Prior Findings Status"一节）：

- **最重要 AND-039（High）**：深色模式/字体缩放切换重建 Activity 后，播放器恢复源用的是**只在 `loadVideo` 时写入的内存 `playbackRecord` 快照**，而非轮询持续更新的实时位置 → 播放位置回退到本次加载起点；且后续 ON_STOP/dispose 保存会把**回退位置覆盖写回 DB**，真实进度永久丢失（非仅显示问题）。
- **AND-040（Medium）**：锁屏冷却（5 次生物识别失败后 30s）期间发生重建，`authFailed` 用 `remember` 而同组冷却字段用 `rememberSaveable` → 重试按钮与倒计时一起消失（都嵌在 `if (authFailed)` 下），自动认证 effect 又因冷却跳过 → 冷却结束后**无任何路径再触发认证**：锁屏死屏直到杀进程（fail-closed，无安全旁路，但完全不可用）。
- **AND-041（Medium）**：`SyncRelationDao.findConflictingBasePaths` 的 LIKE 以 DB 列与用户输入为模式且未转义 `_`/`%`——含下划线的目录名（如 `one_piece`）导致关系冲突检测误报（阻止合法创建）或漏报（允许作用域实际重叠的关系共存）。
- **AND-042（Medium-Low）**：通知深链导航无 `popUpTo` 栈归一化；重复点击同目的地通知会替换顶条目（新 ViewModel、状态重置）。
- 其余 13 项为 Low / Low-Med / Info：Compose 视觉与性能小缺陷（滚动条幻影闪现、氛围光带每帧重组、fontScale 裁剪、16dp 触摸目标、错误遮罩不拦截输入）、一次性错误事件重建后重放、同步期全表布尔流、SMB context 不 close、音频解码不可取消尾巴、Provider openFile 忽略 mode、播放位置无周期 checkpoint、pill 最坏对比度、硬编码 Dispatchers.IO。

**无崩溃级、无数据损坏级新发现。** 项目安全红线（E8 扫描失败保护、下载两阶段提交、CancellationException 优先传播、Provider 异常边界、删除 fail-closed、结构化错误）全部复验仍然成立。

前轮 AND-023..038 核对：**2 项已修复/实质缓解（AND-037、AND-038），2 项部分修复/实质变更（AND-028、AND-036），其余 12 项仍在**（逐项状态与证据见 Prior Findings Status）。

## Project Profile

| 项 | 值 |
|---|---|
| Language | Kotlin 2.2.10（K2，compose compiler plugin），无 Java 源码 |
| UI | 100% Compose（Material3，BOM 2025.12.00），无 Fragment/XML View |
| Build | AGP 9.1.0 / Gradle 9.3.1 / KSP 2.3.6 |
| SDK | minSdk 34 / targetSdk 35 / compileSdk 35 |
| Modules | 单模块 `:app`（包 `cn.vectory.syncplayer`） |
| Architecture | MVVM + Repository（Player 为 MVI），Hilt 2.59.2 DI，Single Activity + Compose |
| Navigation | Navigation-Compose 2.9.0，startDestination = Browser（folder String 参数，默认 ""） |
| Networking | 无 HTTP 栈；jcifs-ng 2.1.10（SMB）+ sshj 0.39.0（SFTP），阻塞式 IO 库，均配 5s 级超时 |
| Persistence | Room 2.7.0（schema v4，破坏性迁移为文档化约定）+ DataStore-Preferences |
| Media | Media3/ExoPlayer 1.6.0（经 GesturePlayerView AndroidView 封装），Coil 2.7.0 |
| Coroutines | 1.10.1；全仓库无 GlobalScope |
| Background | SyncService（dataSync 前台服务；无 WorkManager）；MediaContentProvider（exported，签名级读权限） |
| Security | 生物识别 App Lock + FLAG_SECURE；凭证 EncryptedSharedPreferences；SSH TOFU |
| Tests | 71 个 JVM 测试类（JUnit4 + MockK + Turbine + Robolectric Compose 布局测试），4 个 androidTest 类 |
| Build types | release（R8 minify + shrinkResources + local.properties 签名）/ debug；无 flavors |

Manifest 关键声明：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`（仅声明，从未运行时请求——见 Prior AND-029）；签名权限 `cn.vectory.syncplayer.permission.READ_MEDIA` 保护 exported Provider；`allowBackup=false`。

## Git Baseline

```text
branch: main（与 origin/main 同步）
HEAD: 64eb027 feat(storage)!: drop SAF external volume and at-rest media encryption
describe: v12.3.5-1-g64eb027
status: clean（审计用 .work/ 为 gitignored，已删）
```

近两次大提交直接决定本轮重点：
- `64eb027`：删除 SAF 外部卷与静态媒体加密（99 文件，+1384/−6578 行）→ 存储层重构是 AND-049/050 的背景，也是 AND-037"已修复"结论的来源。
- `f8fc00e`：播放器边缘滑动返回、半透明 overlay、1s 回收站 snackbar → AND-044/048 及 AND-036"实质变更"评估的背景。

历史扫描：无 Fragment→Compose 迁移残留（项目从未用过 Fragment）；无 SavedStateHandle/scope 的历史性破坏改动；SDK 34→35 与 AGP 9 为近期一次性跃迁。

## Build / Test / Lint Results

```text
环境: JAVA_HOME=D:/codeDev/.audit-tools/jdk-21.0.2（Windows 本机；AGENTS.md 的 Linux 路径在本机无效）
命令: ./gradlew compileDebugKotlin testDebugUnitTest lintDebug
结果: BUILD SUCCESSFUL in 3m 22s，exit 0
单测: 581 tests / 0 failures / 0 errors / 0 skipped（70 个结果 XML）
Lint: 0 errors（abortOnError=true 门禁通过）；36 条信息级 warning：
  NewerVersionAvailable×14、UnusedResources×11、GradleDependency×5、
  UseKtx×1、UsableSpace×1、OldTargetApi×1
未运行: instrumented test（无设备）；assembleRelease（签名凭据不在审计范围）
```

## Runtime Validation Environment

```text
No device/emulator runtime validation available.
```

本机无 emulator 组件、无 AVD、无连接设备。所有结论为**静态证明**（机制推导 + 源码/字节码核验，部分对照 androidx navigation 2.9.0 字节码与 Compose 官方性能文档）或引用**已有 JVM 单测**；无任何 finding 声称设备复现。场景测试（rotation、background/foreground、进程死亡、返回导航、慢网络、offline、空/畸形响应、权限拒绝、深色模式、大字体、长内容、长列表、快速连点）均未实际操作 UI，以静态机制推导完成。

## Verified Findings

> 每项均通过 Methodology 一节的 Finding Gate。行号对应基线 64eb027。

---

### AND-039 uiMode/fontScale 重建后播放位置回退至加载时快照，且后续保存将回退位置覆盖写回 DB

**Severity**: High
**Confidence**: 0.85
**Affected Screen / Component**: PlayerScreen / PlayerViewModel 位置恢复链
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:125-134`（恢复 seek）、`:114`（dispose 保存）、`:245-263`（ON_STOP 保存）、`:218-241`（200ms 轮询）；`player/PlayerViewModel.kt:273`（`playbackRecord` 唯一常规写入点）、`:300-305`（轮询更新）、`:362`（getSavedPosition）
**Android Mechanism**: `uiMode`/`fontScale` 不在 `configChanges` → 系统深色模式切换（含定时自动切换）或字体缩放变更销毁重建 Activity。重建后 Compose 全新组合，`LaunchedEffect(uiState.videoItem)` 重新执行；PlayerViewModel 存活，但其 `playbackRecord` 字段自 `loadVideo` 后从未被 200ms 轮询或保存链更新（轮询只写独立的 `_currentPosition`；保存消费者只写 DB）。
**Lifecycle / State Ownership**: ExoPlayer（随组合销毁重建）vs PlayerViewModel（跨重建存活，持有陈旧 record）。
**Trigger**: 播放中深色模式手动/定时切换，或系统字体缩放变更。
**Evidence**（静态，主 Agent 全链复核）:
```kotlin
// PlayerScreen.kt:125-132 —— 重建后新组合重新执行
LaunchedEffect(uiState.videoItem) {
    val item = uiState.videoItem ?: return@LaunchedEffect
    exoPlayer.setMediaItem(...)
    val savedPos = viewModel.getSavedPosition()   // PlayerViewModel.kt:362:
    if (savedPos > 0) exoPlayer.seekTo(savedPos)  //   _uiState.value.playbackRecord?.position ?: 0L
    exoPlayer.prepare()
}
// PlayerViewModel.kt:300-305 —— 轮询只更新 _currentPosition/duration，不碰 playbackRecord
fun updatePlaybackProgress(position: Long, duration: Long) {
    _currentPosition.value = position
    if (_uiState.value.duration != duration) { _uiState.update { it.copy(duration = duration) } }
}
// playbackRecord 的写入点仅有 loadVideo(:273 record=playbackRepository.getByVideoId(...)) 与
// toggleTrashed 刷新(:337)；progressSaveScope 消费者(:127-136)只写 DB，不回写 uiState
```
**Reproduction**（静态推导，无设备）: 播放视频 30 分钟 → 切深色模式 → 重建前 ON_STOP 触发 `saveProgress`（正确位置入 DB）→ 重建 → 新组合 `seekTo(load 时的 record 位置)` → 在回退位置继续播放 → 下一次 ON_STOP/dispose（`PlayerScreen.kt:114/255-261`）把**回退位置**写回 DB，覆盖真值。
**Expected**: 重建后从当前播放位置继续。
**Actual**: 回退到本次 `loadVideo` 时的位置；DB 真值随后被回退时间线覆盖。
**User Impact**: 中断场景下数分钟～数小时进度**永久丢失**（非仅显示）。
**Root Cause**: 恢复源选择了"只在加载时写入的内存快照"；持续更新的 `_currentPosition.value`（VM 内、跨重建存活）与 DB 均未被用作恢复源。
**Git Context**: `seekTo(getSavedPosition())` 模式为长存代码；f8fc00e 手势改造未触及该链。
**Recommended Fix**: 恢复源改为实时位置（`_currentPosition.value`）或 suspend 重读 DB；可与 AND-053（周期 checkpoint）同点收口。
**Regression Test**: Robolectric/Compose：置入 record → 推进 `_currentPosition` → 重组 → 断言 `seekTo` 参数等于实时位置而非 record。
**Adversarial Review**: 反方——dispose/ON_STOP 已把正确位置落 DB（数据不丢）。驳回：新组合不读 DB，且回退位置随后覆盖 DB 真值；自动深色定时切换使触发真实存在。维持 VERIFIED。

---

### AND-040 锁屏冷却期间 uiMode/fontScale 重建 → 重试按钮与倒计时消失、冷却结束后无任何认证触发路径（死锁屏）

**Severity**: Medium（fail-closed 方向，无安全旁路，但锁屏不可用直到杀进程）
**Confidence**: 0.85
**Affected Screen / Component**: LockScreenOverlay（App Lock）
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/security/LockScreenOverlay.kt:29`（`authFailed` 用 `remember`）、`:30-32`（`failCount/isCooldown/cooldownRemaining` 用 `rememberSaveable`）、`:60-68`（自动认证 `LaunchedEffect(Unit)` 被 `!isCooldown` 跳过）、`:102-127`（重试按钮+倒计时嵌于 `if (authFailed)`）、`:70`（BackHandler 阻断返回）
**Android Mechanism**: uiMode/fontScale 重建：`remember` 状态丢失、`rememberSaveable` 存活 → 状态组合变为 `authFailed=false, isCooldown=true`。`LaunchedEffect(Unit)` 在新组合重跑但因 `isCooldown=true` 跳过；倒计时 effect 结束只清 `failCount/isCooldown`，不触发认证；重试按钮与倒计时文本都在 `if (authFailed)` 块内 → 不可见。
**Lifecycle / State Ownership**: LockScreenOverlay 组合局部状态；AppLockManager 锁定态（进程级）。
**Trigger**: ≥5 次生物识别失败进入 30s 冷却 → 期间深色模式切换/字体缩放变更。
**Evidence**（静态，主 Agent 复核 UI 门控结构）:
```kotlin
var authFailed by remember { mutableStateOf(false) }          // :29 重建即丢
var failCount by rememberSaveable { mutableIntStateOf(0) }    // :30
var isCooldown by rememberSaveable { mutableStateOf(false) }  // :31 存活
var cooldownRemaining by rememberSaveable { mutableIntStateOf(0) } // :32 存活
...
LaunchedEffect(Unit) { if (!isCooldown) biometricAuthManager.authenticate(...) } // :60-68
...
if (authFailed) {                                   // :102 authFailed=false → 整块消失
    Button(enabled = !isCooldown, onClick = { ... authenticate(...) }) { ... } // :104-117
    if (isCooldown) { Text(stringResource(R.string.lock_cooldown, cooldownRemaining)) } // :118-126
}
```
**Reproduction**（静态推导）: 失败 5 次 → 冷却激活（`onAuthError` 置 `isCooldown=true, cooldownRemaining=30`）→ 切深色模式 → 重建 → `authFailed` 归 false、`isCooldown` 存活为 true → 按钮区消失；30s 后倒计时 effect 清标志但不认证 → 无按钮、无 effect → 仅可杀进程。
**Expected**: 重建后按钮（禁用态）+倒计时可见，冷却结束自动或手动可重试。
**Actual**: 按钮与倒计时同时消失；冷却结束后无任何认证触发路径；BackHandler 仍阻断返回。
**User Impact**: 锁屏死屏（应用保持锁定——安全性未破坏——但可用性归零直到杀进程）。
**Root Cause**: 同组语义状态一半 `remember` 一半 `rememberSaveable`，且 UI 与自动触发都以 `authFailed` 为门。与前轮 AND-033（冷却时长从硬编码 30s 重启）同文件不同缺陷，可叠加发生。
**Git Context**: 前轮 AND-033 修复引入三个 saveable 字段，但 `authFailed` 未同步。
**Recommended Fix**: `authFailed` 改 `rememberSaveable`；或冷却结束改为事件驱动（effect 末尾直接 `authenticate`）。
**Regression Test**: Robolectric：failCount=5 触发冷却 → 重组 → 断言按钮/冷却文本可见；倒计时归零后断言 `authenticate` 被调用。
**Adversarial Review**: 反方——需"连续失败 + 恰在 30s 内切深色"双条件，频率低。驳回：夜间自动深色恰是真实组合；影响为完全不可用。维持 VERIFIED。

---

### AND-041 `findConflictingBasePaths` 的 LIKE 通配符未转义——关系冲突检测误报/漏报

**Severity**: Medium
**Confidence**: 0.8
**Affected Screen / Component**: SyncRelationEdit（关系创建/编辑的路径冲突校验）
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:31-36`
**Android Mechanism**: SQLite `LIKE` 中 `_` 匹配任意单字符、`%` 匹配任意串；该查询把 **DB 列**（`localBasePath`）与**用户输入**（`checkPath`）都直接当作 LIKE 模式，无 ESCAPE 子句。
**Evidence**（静态）:
```sql
SELECT * FROM sync_relation
WHERE localBasePath != :excludePath
  AND (:checkPath = localBasePath
    OR :checkPath LIKE localBasePath || '/%'      -- 列作模式：one_piece 中的 _ 匹配 one-piece
    OR localBasePath LIKE :checkPath || '/%')     -- 输入作模式：同一问题反向
```
**Trigger**: 本地基路径含 `_`（媒体目录极常见：`one_piece`、`my_videos`）或 `%`。
**Reproduction**（静态推导）: 已有关系 `base="/m/one_piece"`；新建 `checkPath="/m/one-piece"` → 第二子句 `'/m/one-piece' LIKE '/m/one_piece/%'` 不成立，但列值若为模式方向（`:checkPath LIKE localBasePath||'/%'` 在 checkPath 为更长路径时）`_` 会跨字符匹配 → 冲突误报，合法创建被拒；反向子句可漏报真实重叠。
**Expected**: 精确前缀比较。
**Actual**: 通配符导致误报（阻止合法关系）或漏报（允许作用域实际重叠的关系共存）。
**User Impact**: 关系创建被莫名阻止；或两条同步作用域交叠的关系进入引擎（完整性上游风险）。
**Root Cause**: 与 BUG-011（deleteFolder/searchByFileName 的 LIKE 未转义，见附录）同一缺陷类；其修复未覆盖本查询。
**Git Context**: 查询随关系冲突校验功能引入，无后续转义修补。
**Recommended Fix**: 两侧改 `LIKE :pattern ESCAPE '\'` 并对列值/输入做 `\_%` 转义；或改 `substr` 精确前缀比较（`localBasePath || '/' = substr(:checkPath, 1, length(localBasePath)+1)`）。
**Regression Test**: DAO 测试：插入 `base="/m/one_piece"` → 断言 `findConflictingBasePaths("/m/one-piece")` 为空、`("/m/one_piece/sub")` 命中。
**Adversarial Review**: 反方——仅影响创建期校验，不直接删数据。驳回：漏报分支允许重叠关系进入同步引擎，属完整性上游风险；误报直接阻断功能。维持 VERIFIED（Medium）。

---

### AND-042 通知深链导航无 back-stack 归一化；重复点击替换顶条目导致状态重置

**Severity**: Medium-Low
**Confidence**: 0.75（机制静态证实；UX 影响幅度未运行时验证）
**Affected Screen / Component**: MainActivity 通知导航 / AppNavHost
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:85-103`（`handleNotificationNavigation`）；`navigation/AppNavHost.kt`（Browser/ SyncPreview 路由）
**Android Mechanism**: `navController.navigate(...) { launchSingleTop = true }` 无 `popUpTo`：(a) 顶为异目的地 → 纯压栈；(b) 顶为同目的地（id 相同、参数不同）→ Navigation 2.9.0 重建顶条目并应用新参数（新 ViewModel）。已对照本地 Gradle 缓存中 navigation 2.9.0 字节码（`ComposeNavigator.navigate` 追加条目；`NavController.navigateInternal` 处理 singleTop 替换）与 androidx 源码核验。PendingIntent 的 `FLAG_ACTIVITY_CLEAR_TOP` 作用于 Activity 层，不作用于 NavController 栈。
**Evidence**:
```kotlin
// MainActivity.kt:96-103（folder 完成通知）与 syncPreview 分支同构
"browser" -> {
    val basePath = intent.getStringExtra("notification_local_base_path") ?: ""
    navController.navigate(Route.Browser.create(basePath)) {
        launchSingleTop = true        // ← 无 popUpTo 归一化
    }
}
```
**Trigger**: (a) 停留 Settings/Player 时点同步完成通知 → 栈变 `[browser-root, settings, browser(X)]` 或 `[browser, player, browser(X)]`：系统返回先回 Settings，或**退回到仍在播放的 Player**（重建 ExoPlayer 并从保存位置续播——用户"从文件夹返回"却回到播放中的视频）；(b) 已在深链 folder 上再点同一通知 → 顶条目替换 → BrowserViewModel 重建、列表重载、滚动丢失。
**Expected**: 深链对栈归一化（`popUpTo` startDestination），重复点击幂等。
**Actual**: 压栈/替换如上。
**User Impact**: 返回栈语义混乱；重复点击丢浏览状态。
**Root Cause**: 深链 navigate 缺 `popUpTo(Route.Browser.route)`。
**Git Context**: 通知导航随 `handleNotificationNavigation` 引入后未改。
**Recommended Fix**: 通知导航统一 `popUpTo(Route.Browser.route) { inclusive = false } + launchSingleTop`。
**Regression Test**: navigation 测试：栈 `[browser, settings]` 深链 → 断言栈 `[browser(X)]`；同参重复深链 → 断言顶条目未重建（ViewModel 存活）。
**Adversarial Review**: 反方——个人应用"跳进去"可接受。主 Agent 初期"singleTop 丢弃新参数=死点击"假设被字节码证据推翻（实为替换+应用新参数），已列 Rejected Hypotheses #2；本 finding 按正确机制成立。VERIFIED。

---

### AND-043 三种 VerticalScrollbar 变体在首次组合后 800ms 出现"幻影"淡入闪现

**Severity**: Low-Medium（可见外观缺陷）
**Confidence**: 0.85
**Affected Screen / Component**: Browser 网格/列表/搜索等所有带滚动条界面（3 处同构变体）
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/ui/components/VerticalScrollbar.kt:79-92`（同构块 `:170-185`、`:262-278`）
**Android Mechanism**: `LaunchedEffect(isScrolling)` 初次组合以 key=false 进入 `else` 分支，`delay(800)` 后**无条件**执行淡出循环——首帧 alpha 即被赋 0.9 再 300ms 衰减，而此前滚动条从未显示（初始 alpha 0，从未滚动过）。
**Evidence**:
```kotlin
LaunchedEffect(isScrolling) {
    if (isScrolling && isScrollable) {
        scrollbarAlpha = 1f
    } else {
        delay(800)
        if (!state.isScrollInProgress) {
            repeat(10) { i ->
                scrollbarAlpha = 1f - (i + 1) / 10f   // ← 未滚动也执行：0.9→0.0
                delay(30)
            }
            scrollbarAlpha = 0f
        }
    }
}
```
绘制门仅为 `scrollbarAlpha > 0.01f && thumbInfo.size > 0f`；`computeThumbSize` 对任何非空列表返回 >0（含不可滚动的满高 thumb）。既有 `VerticalScrollbarTest` 只测 thumb 计算，未覆盖淡出行为。
**Trigger**: 任何非空列表新组合（进 App、切视图模式、搜索结果出现）后 800ms 内未滚动。
**Reproduction**（静态推导）: 首组合 t=0（alpha=0，isScrolling=false）→ t+800ms 进入淡出循环 → alpha 跳 0.9 → 300ms 衰减到 0 → 用户看到约 300ms 的滚动条闪现（有效峰值透明度 ≈0.9×0.7 颜色 alpha）。
**Expected**: 从未滚动则不出现滚动条。
**Actual**: 以淡出形状闪现一次。
**User Impact**: 外观毛刺，无功能影响。
**Root Cause**: else 分支未区分"曾显示后需要淡出"与"从未显示"。
**Recommended Fix**: `else` 分支首行 `if (scrollbarAlpha <= 0f) return@LaunchedEffect`（或从当前 alpha 起淡出）。
**Regression Test**: Compose 测试断言未滚动路径 alpha 恒 0。
**Adversarial Review**: 反方——或可视为"滚动条提示"设计；代码/测试无此意图证据，且 0.9 起始是淡出形状而非展示形状。VERIFIED（外观级）。

---

### AND-044 AuroraBands 使用参数式 `graphicsLayer(alpha=)`——整个播放会话每帧重组

**Severity**: Low（机制确定，幅度受限）
**Confidence**: 0.8
**Affected Screen / Component**: PlayerScreen 氛围光带（顶/底渐变辉光）
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/player/AuroraPlayerUi.kt:317-342`（`:331`、`:339` 两处；`PlayerScreen.kt:498` 无条件使用）
**Android Mechanism**: `Modifier.graphicsLayer(alpha = alpha)` **参数形式**在组合期读取 State → `rememberInfiniteTransition` 每帧更新 `alpha` 即失效该作用域并重组；lambda 形式 `graphicsLayer { this.alpha = alpha }` 才把读取推迟到 layer/draw 相（Compose 官方性能文档明确反模式）。视频经 SurfaceView 渲染不驱动 Compose 帧时钟 → 该动画是播放会话内保持 Choreographer/重组器常热的驱动源（含控制栏隐藏、暂停亮屏时段）。K2 strong-skipping 不缓解组合期 State 读取。
**Evidence**:
```kotlin
val alpha by transition.animateFloat(0.90f, 1.00f, AuroraTokens.AuroraGlowBreath, ...)
Box(Modifier...background(AuroraTokens.AuroraTopBandBrush)
    .graphicsLayer(alpha = alpha))      // :331 —— 参数式，每帧重组本作用域
Box(Modifier...background(AuroraTokens.AuroraBottomBandBrush)
    .graphicsLayer(alpha = alpha))      // :339 —— 同
```
**Trigger**: 整个 PlayerScreen 生命周期。
**Expected**: 动画只在 layer/draw 相更新，组合空闲。
**Actual**: AuroraBands 作用域以显示刷新率持续重组（子树仅 3 个 Box，绝对每帧成本小，机制为常驻帧时钟活动 + 该作用域 skip 失效）。
**User Impact**: 无直接可见缺陷；持续功耗/性能税（幅度未设备量化）。
**Root Cause**: graphicsLayer 参数形式误用。
**Recommended Fix**: 两处改 `Modifier.graphicsLayer { this.alpha = alpha }`。
**Regression Test**: 无直接单测；以 Compose 编译器 metrics/重组计数宏基准验证（可选）。
**Adversarial Review**: 反方——绝对成本微小、无设备量化。机制为官方文档明确反模式。VERIFIED（Low）。

---

### AND-045 fontScale ≥~1.6 时固定高度播放器 chrome 与 320dp 上下文菜单裁剪/溢出

**Severity**: Low-Medium（a11y）
**Confidence**: 0.7（静态布局推导，无设备实测）
**Affected Screen / Component**: 播放器快捷回收行、精扫放大镜、文件上下文菜单
**Source Location**: `player/AuroraPlayerUi.kt:797-826`（`height(52.dp)` 行内 bodyMedium+12sp 两行文本）、`:1090-1117`（FineScrubMagnifier 含 48.sp 时间文本、无滚动）、`ui/components/ItemContextMenu.kt:103-224`（`width(320.dp)` Card 内 CJK 多按钮 Row）
**Android Mechanism**: fontScale 不在 `configChanges` → 调整字体缩放重建 Activity；sp 文本随缩放线性放大而 dp 容器固定 → 溢出。52dp 行容纳 ≈20sp+16sp 行高（52dp ≈ 缩放 1.0 恰好），fontScale 2.0 时需 ≈72dp；菜单按钮 Row（分享+移入回收站+取消，CJK 标签）在 ≥~1.6 时总宽超过 320dp−32dp padding 可用宽，被 Card 圆角裁剪。
**Evidence**: `AuroraPlayerUi.kt:198` `AuroraRecycleRowHeight = 52.dp`（消费点 `:800`）；`ItemContextMenu.kt:105` `Modifier.width(320.dp)`；strings 确认 CJK（`values/strings.xml:434-436` 移入回收站等）。
**Trigger**: 系统字体缩放 ≥~1.6（重建后即生效）。
**Expected**: 文本缩放不裁剪（sp 容器 wrap/scroll）。
**Actual**: 回收行文字压叠邻行；放大镜内容可超出屏幕；菜单按钮被裁剪。
**User Impact**: 大字体用户不可读/不可点部分 UI。
**Root Cause**: 固定 dp 容器装 sp 文本，无弹性。
**Recommended Fix**: 回收行高度改 `heightIn(min=52.dp)`+wrap；菜单改宽度上限 `widthIn(max=320.dp)` + 按钮自适应换行。
**Regression Test**: Robolectric Compose 以 fontScale 2.0 布局断言无裁剪（文本可测高对比容器高）。
**Adversarial Review**: 反方——个人侧载应用、单行 48dp 行与单选行在 2.0 下仍完好。VERIFIED（静态，Low-Med）。

---

### AND-046 DirectoryTreeDialog 展开箭头为 16dp 触摸目标（行点击=导航，展开仅箭头）

**Severity**: Low-Medium（a11y）
**Confidence**: 0.9
**Affected Screen / Component**: 目录树对话框（关系编辑选远端目录）
**Source Location**: `app/src/main/java/cn/vectory/syncplayer/ui/components/DirectoryTreeDialog.kt:261-267`
**Android Mechanism**: 自定义 `Icon.clickable` 不受 Material `LocalMinimumInteractiveComponentEnforcement` 保护（该强制仅作用于 Material 组件的 clickable 修饰注入）；16dp 视觉 = 16dp 逻辑命中区，低于 WCAG 2.5.8 AA 下限 24dp 与推荐 48dp。
**Evidence**:
```kotlin
Icon(
    imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
    contentDescription = ...,
    modifier = Modifier.size(16.dp).clickable { onToggleExpand() }  // :266-267
)
```
行本体（约 36-40dp）点击语义是"导航进入"，不能替代展开。
**Trigger**: 任何使用目录树对话框的场景。
**Expected**: 展开/收起目标 ≥48dp（至少 24dp）。
**Actual**: 仅 16dp。
**User Impact**: 精细动作障碍/大手指用户难以展开节点。
**Root Cause**: 视觉尺寸即命中区，未扩。
**Recommended Fix**: 仿同仓 `StarRatingInput.kt:48-58` 的 `sizeIn(minWidth=48.dp, minHeight=48.dp)` 包小视觉范式。
**Regression Test**: 布局断言命中区 ≥48dp（或 touch 扩展语义）。
**Adversarial Review**: 反方——行可点但语义不同（导航），无法替代展开。VERIFIED。

---

### AND-047 一次性错误事件在 uiMode/fontScale 重建后重放（Browser 与 Player 同模式）

**Severity**: Low
**Confidence**: 0.8
**Affected Screen / Component**: BrowserScreen / PlayerScreen 错误 snackbar
**Source Location**: `browser/BrowserScreen.kt:412-417`；`player/PlayerScreen.kt:474-479`
**Android Mechanism**: 重建取消悬挂中的 `LaunchedEffect(ev.seq)`（snackbar 展示窗口 ~4s 内 `showSnackbar` 尚未返回）→ `clearError()` 未执行 → StateFlow 保留事件 → 新组合重放，时长重置。
**Evidence**:
```kotlin
uiState.error?.let { ev ->
    LaunchedEffect(ev.seq) {
        snackbarHostState.showSnackbar(ev.message)   // 重建时在此挂起点被取消
        viewModel.clearError()                        // 未执行 → 事件存活 → 重放
    }
}
```
**Trigger**: 错误 snackbar 可见窗口内发生 uiMode/fontScale 重建。
**Expected**: 事件消费一次。
**Actual**: snackbar 再次展示（重置时长）。
**User Impact**: 轻微：已看过的错误再次弹出。
**Root Cause**: "StateFlow + 展示后清除"模式在 effect 被重建取消时未原子消费。
**Recommended Fix**: Channel（`receiveAsFlow`）+ `receive()` 消费，或已展示 seq 的 `rememberSaveable` 去重。
**Regression Test**: Turbine/Compose 测试：事件发出 → 模拟 effect 取消重组 → 断言不重放。
**Adversarial Review**: 反方——自限性外观问题；既有 seq 机制解决的是"相同文案连发被 StateFlow 合并"（AND-018/030 一族），非重建重放。VERIFIED（Low）。

---

### AND-048 播放错误全屏遮罩不拦截指针输入——手势仍作用于下层播放器

**Severity**: Low
**Confidence**: 0.75
**Affected Screen / Component**: PlayerScreen 错误覆盖层
**Source Location**: `player/PlayerScreen.kt:594-644`
**Android Mechanism**: 遮罩 `Box(fillMaxSize().background(Color.Black.copy(0.7f)))` 无任何 pointer 修饰 → Compose 命中测试穿透到下层 GesturePlayerView：单击仍切换 chrome、中央双击在错误态仍触发重试播放；TalkBack 亦可聚焦底层控件。同文件模态 `AuroraSettingsModal` 的遮罩是正确对照（`AuroraPlayerUi.kt:737` `.clickable(onClick = onDismiss)`）。
**Evidence**:
```kotlin
if (uiState.hasPlayerError) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))) { ... }
    // ↑ 无 clickable/pointerInput —— 输入穿透
```
**Trigger**: 任意播放错误后，点击 Close/Retry 以外区域。
**Expected**: 模态外观的遮罩消费杂散输入。
**Actual**: 手势直达播放器。
**User Impact**: 轻微：错误态下意外触发底层手势。
**Root Cause**: 遮罩缺 pointer 消费。
**Recommended Fix**: 遮罩加 `pointerInput(Unit) { detectTapGestures { } }` 或 clickable no-op。
**Regression Test**: Compose 测试断言遮罩区域点击不触达下层回调。
**Adversarial Review**: 反方——错误态下这些手势无害甚至有用；与同文件模态范式不一致仍成立。VERIFIED（Low）。

---

### AND-049 `getAllItemsFlow()`（`SELECT *`）被两个布尔消费方各自全表物化

**Severity**: Low-Medium（同步期间的放大）
**Confidence**: 0.8
**Affected Screen / Component**: BrowserViewModel firstRunState / libraryEmpty
**Source Location**: `data/local/dao/VideoItemDao.kt:227-229`；`browser/BrowserViewModel.kt:141`（combine 内 `media.isNotEmpty()`）、`:359-361`（`map { it.isEmpty() }`），均 `stateIn(Lazily)`
**Android Mechanism**: Room 失效跟踪为表级——`video_item` 每次写入（同步下载逐文件 upsert，上限 MAX_REMOTE_FILES=10000）令两条独立 Flow 链各自重跑全表 SELECT、重新分配整表实体 List，仅为求一个布尔。
**Evidence**:
```kotlin
// VideoItemDao.kt:227-229
@Query("SELECT * FROM video_item")
fun getAllItemsFlow(): Flow<List<VideoItemEntity>>
// BrowserViewModel.kt:359-361
val libraryEmpty: StateFlow<Boolean> = videoRepository.getAllItemsFlow()
    .map { it.isEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, true)
```
**Trigger**: Browser 打开（或 onboarding VM 存活）期间活跃同步。
**Expected**: `SELECT EXISTS(SELECT 1 FROM video_item LIMIT 1)` 级查询。
**Actual**: 全表物化 ×2/写（批量 upsertAll 可部分合并失效）。
**User Impact**: 同步期间瞬时 GC 压力/主线程 Room 分发负担；无正确性问题。
**Root Cause**: 布尔语义复用了全实体查询。
**Recommended Fix**: DAO 增 `@Query("SELECT NOT EXISTS(SELECT 1 FROM video_item LIMIT 1)") fun isEmptyFlow(): Flow<Boolean>` 供两处使用。
**Regression Test**: DAO 测试断言查询语义即可（行为等价）。
**Adversarial Review**: 反方——个人库 ≤1 万行、约百字节/行、批量 upsert 缓解。性能卫生项。VERIFIED（Low-Med）。

---

### AND-050 `SmbFileClient.disconnect()` 丢弃 CIFSContext 而不 close()——线程/套接字靠空闲回收兜底

**Severity**: Low（资源卫生）
**Confidence**: 0.75
**Affected Screen / Component**: SMB 客户端（浏览/测试连接/同步 lane 各一份实例）
**Source Location**: `sync/smb/SmbFileClient.kt:153-155`
**Android Mechanism**: 每次 `connect()` 新建 `BaseContext`（自带 SmbTransportPool，每 transport 一个 reader 线程）；不调用 `CIFSContext.close()`（jcifs-ng 2.1.10 存在，经 Gradle 缓存 jar 字节码确认）则套接字与守护线程存留至空闲会话回收（字节码可见 "Closing session after timeout"，sessionTimeout 默认 ~35s）。守护线程 + 回收 → 非永久泄漏。
**Evidence**:
```kotlin
override suspend fun disconnect() {
    cifsContext = null        // ← 仅置 null，未 close()
}
```
对照：`SftpFileClient.kt:127-136` 正确执行 `sftpClient?.close(); sshClient?.disconnect()`。
**Trigger**: 每次 SMB 浏览/测试连接/同步完成。
**Expected**: 确定性套接字/线程拆除。
**Actual**: 依赖 ~35s 空闲回收。
**User Impact**: 短时资源驻留（≤35s、守护线程、lane ≤3）；无功能影响。
**Root Cause**: 遗漏 close()。
**Recommended Fix**: `disconnect()` 中 `cifsContext?.close()` 后置 null。
**Regression Test**: 集成级（需真实 SMB 或 jcifs 桩）；静态评审守卫即可。
**Adversarial Review**: 反方——有界且守护。VERIFIED（Low，卫生）。

---

### AND-051 AudioGainAnalyzer 阻塞解码不响应协程取消——取消后 ≤3 个在途解码跑完且结果丢弃

**Severity**: Low
**Confidence**: 0.9
**Affected Screen / Component**: 设置 → 增益分析批量任务
**Source Location**: `audio/AudioGainAnalyzer.kt:32-62`（analyze 无取消检查）、`:93-140`（MediaCodec 解码循环）；调用方 `settings/GainAnalysisViewModel.kt:166-216`（`async(Dispatchers.IO)` + Semaphore(3)）
**Android Mechanism**: `analyze()` 为纯阻塞函数（MediaExtractor+MediaCodec 解码至多 60s 音频），循环内无 `ensureActive()`/`isActive`；`viewModelScope` 取消（离开页面/取消分析）后，信号量内已在途的 ≤3 个解码继续跑完（数秒 CPU），随后挂起调用点抛 CancellationException，结果丢弃（已入库文件不受影响）。
**Trigger**: 批量分析进行中用户离开页面或点取消。
**Expected**: 取消尽快停止解码。
**Actual**: ≤3 个解码尾巴跑完，结果丢弃。
**User Impact**: 浪费 CPU 数秒；无泄漏（codec 在 finally 释放）。
**Root Cause**: 阻塞循环缺协作式取消检查（仓库两处 remote client 逐 chunk `ensureActive()` 是正确范式）。
**Recommended Fix**: 解码循环每 chunk `currentCoroutineContext().ensureActive()`。
**Regression Test**: JVM 不可测 MediaCodec；以桩 + 取消注入验证调用方不再收结果即可（可选）。
**Adversarial Review**: 反方——有界（≤3）、无泄漏。VERIFIED（Low）。

---

### AND-052 MediaContentProvider.openFile 忽略 mode 参数——写请求得到只读 fd 的延迟失败

**Severity**: Low
**Confidence**: 0.85
**Affected Screen / Component**: MediaContentProvider（外部消费者契约）
**Source Location**: `provider/MediaContentProvider.kt:43-59`（openFile 不读 mode）、`:65-76`（恒 MODE_READ_ONLY）
**Android Mechanism**: `openFile(uri, mode)` 从不检查 `mode`；请求 `"w"/"rw"` 的调用方拿到只读 PFD，写入时才 EBADF 失败，而非立即显式 `FileNotFoundException`。方向 fail-safe（签名级只读权限本就禁止写）。
**Evidence**:
```kotlin
override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {  // mode 未用
    ...
    ParcelFileDescriptor.open(store.mediaFile(id), ParcelFileDescriptor.MODE_READ_ONLY) // :71
}
```
**Trigger**: 外部消费方以写模式 open。
**Expected**: 非 "r" 模式显式拒绝。
**Actual**: 只读 fd 延迟失败。
**User Impact**: 应用内无消费方触发；仅外部契约卫生。
**Recommended Fix**: 非 "r" 模式抛安全文案的 FileNotFoundException。
**Regression Test**: Provider 单测断言 mode="w" 抛 FileNotFoundException。
**Adversarial Review**: 反方——无已知写入方且权限已拦。VERIFIED（Low，契约卫生）。

---

### AND-053 播放位置无周期性 checkpoint——前台进程被杀时会话内进度丢失

**Severity**: Low
**Confidence**: 0.6（机制确定，触发依赖 LMK 行为，无设备验证）
**Affected Screen / Component**: PlayerScreen 位置持久化
**Source Location**: `player/PlayerScreen.kt:218-241`（200ms 轮询仅内存）、`:245-263`（仅 ON_STOP 保存）、`:114`（仅 dispose 保存）
**Android Mechanism**: LMK 前台杀进程不派发 ON_STOP、不执行组合 dispose → PlaybackRecord 停留在上次换片/ON_STOP 时刻，会话内（可能数小时）进度丢失。
**Evidence**: 轮询体只调 `viewModel.updatePlaybackProgress(playerPos, playerDur)`（内存 StateFlow）；全文件仅 `:114`（dispose）、`:179`（换片）、`:255-261`（ON_STOP）三处 `saveProgress`。
**Trigger**: 长视频前台播放中系统杀进程。
**Expected**: 恢复接近实际位置。
**Actual**: 恢复到上次换片/ON_STOP 的 checkpoint。
**User Impact**: 进度回退数分钟～数小时。
**Root Cause**: 无周期性落盘。
**Recommended Fix**: 轮询循环内每 30-60s `saveProgress` 一次（与 AND-039 同点收口）。
**Regression Test**: 虚拟时钟测试断言周期保存被发出。
**Adversarial Review**: 反方——前台播放中被 LMK 杀概率低。VERIFIED（Low）。

---

### AND-054 半透明 pill 文案最坏情形对比度 ~3.5:1（WCAG AA 4.5:1 以下）

**Severity**: Low（a11y，静态最坏情形）
**Confidence**: 0.6
**Affected Screen / Component**: 播放器手势反馈 pill（音量/亮度/seek 时间/倍速）
**Source Location**: `player/AuroraPlayerUi.kt:153-172`（tokens：`AuroraSurface #101318`、`AuroraPillFill = AuroraSurface @0.78`、`AuroraText #F5F7FA`），消费点 `:1139-1172`（AuroraPill）、`:1180-1213`（AuroraSpeedPill）、`:1059-1064`（预览字幕）
**Android Mechanism**: 任意视频帧上的 alpha 合成——纯白帧时混合背景相对亮度 ≈ 0.22×1.0+0.78×0.008 ≈ 0.226；文本 #F5F7FA 相对亮度 ≈0.92 → 对比度 ≈(0.92+0.05)/(0.226+0.05) ≈ 3.5:1 < 4.5:1（WCAG 2.1 AA 正文文本；bodyMedium 非大文本）。
**Trigger**: 亮/雪/白场景帧恰在 pill 显示窗口（用户手势期间 ~750ms）。
**Expected**: ≥4.5:1。
**Actual**: 最坏 ≈3.5:1。
**User Impact**: 高亮帧上瞬时反馈可读性下降。
**Root Cause**: 固定半透明底不随内容自适应。
**Recommended Fix**: pill 底提升至 α≥0.87（或加 blur/加深边框内衬）。
**Regression Test**: 静态色彩断言（计算合成对比度）即可。
**Adversarial Review**: 反方——瞬时反馈、平均情形远好、渐变边框助辨识。静态数学成立。VERIFIED（Low，static）。

---

### AND-055 硬编码 `Dispatchers.IO` 绕过 `@IoDispatcher` 注入（约 15 处）

**Severity**: Info（约定漂移，无运行时行为差异）
**Confidence**: 1.0
**Affected Screen / Component**: 同步管线/SMB/SFTP/删除用例等
**Source Location**: 主源 31 处 `Dispatchers.IO`，其中绕过注入的实质站点：`SyncService.kt:63`、`SyncDownloadPipeline.kt:216`、`SyncSpaceChecker.kt:56`、`SmbFileClient.kt:95,172,195,234,359,423`、`SftpFileClient.kt:91,153,160,237,292`、`GainAnalysisViewModel.kt:166`、`UndoDeleteUseCase.kt:283,374`、`FileOperationsUseCase.kt:200,235,315`
**Android Mechanism**: 生产注入值即 `Dispatchers.IO`，无行为差异；但这些点无法以 TestDispatcher 替换（可测性），且违反仓库自定 convention（AGENTS.md / DispatchersModule KDoc：阻塞 IO 用注入的 `@IoDispatcher`）。
**Trigger**: 无（静态约定事实）。
**Expected**: 全部经注入 dispatcher。
**Actual**: ~15 站点硬编码。
**User Impact**: 无直接；测试与维护成本。
**Recommended Fix**: 逐点改注入（可与测试改造合并）。
**Adversarial Review**: 反方——SMB/SFTP client 内部属库封装层，注入收益低。Info 级 VERIFIED（事实）。

## Lifecycle & State Review

总体健康：全部 UI 收集走 `collectAsStateWithLifecycle`（108 处；`collectAsState` 0 处）；无 GlobalScope；四个自管 scope（appScope/safetyScope/progressSaveScope/prefetchScope）各有与历史 bug 挂钩的书面理由（如 `PlayerViewModel.kt:108-116` 注释：progressSaveScope 独立于 viewModelScope 以保证 recents-swipe 终态保存——经分析该 scope 仅由 VM 字段根住，VM 不可达后整链可被 GC，无泄漏）；SyncService 的 drain coordinator 是规范的单消费者 Main.immediate 确域设计；播放队列经 SavedStateHandle 落到正确 entry；进程死亡的导航栈+队列恢复正确。

**薄弱边界集中在 MainActivity ↔ NavController 接缝与重建路径**：
1. AND-039——播放位置恢复源错误（本轮最高优先级）。
2. AND-040——锁屏 overlay 的 remember/rememberSaveable 混用。
3. AND-042 + AND-024（前轮，仍在）——深链栈归一化缺失与通知 intent 重建重放。
4. AND-047——一次性事件重建重放。

## Coroutine / Threading Review

总体优秀：CancellationException-first catch 遍布全仓；两个 remote client 逐 chunk/逐目录 `ensureActive()`；下载管线三段式回滚协议（NOT_FINALIZED/FINALIZE_UNCERTAIN/COMMITTED + NonCancellable 清理）；进度发布 500ms 节流 + 终态强制 flush（SyncProgressPublisher，通知收集器在节流下游）；SMB/SFTP 超时均配置（5s 级，无无限挂起配置）；Flow 卫生符合自有约定（重型流 300ms debounce、folder 切换 flatMapLatest/mapLatest、WhileSubscribed(5s) 与 Lazily 选择合理）。启动路径干净（provider 延迟存储、后台孤儿清理、锁读取有界 2s）。

残留：AND-051（增益解码不可取消尾巴）、AND-050（SMB context 不 close）、AND-055（注入约定漂移）、前轮 AND-028 残留（手势拖动 seek 仍逐 MotionEvent 写主 UiState：`PlayerViewModel.kt:484-489` `_uiState.update { it.copy(isGestureSeeking=true, gestureSeekPosition=pos) }`；音量/亮度 overlay 逐事件 `showOverlay` 触发主 UiState 全量 copy + overlayHideJob 取消重启——`PlayerScreen.kt:327/336`；进度条 scrub 路径已拆分为独立 `ProgressScrubUiState` 修复）与前轮 AND-026（SFTP disconnect 阻塞调用方 dispatcher，仍可从 Main 触达）。

## Compose / View Review

强项：状态拆分真实有效（PlayerViewModel 13 个独立 StateFlow；200ms 位置轮询不进全屏作用域——`PlayerScreen.kt:70-74` 有书面理由）；`rememberUpdatedState` 用于长生命周期 View listener 正确（f8fc00e 后 gestureListener `remember` 化，`PlayerScreen.kt:296-404`）；LazyList/Grid 全部有稳定 key（browser/search/directory-tree 按 `relativePath`，sync-preview 按 `dl_`/`rm_` 前缀，gain-analysis 按防撞前缀+id）；回收站 undo snackbar 有防御性工程（request-unique 视觉身份 + Main.immediate deadline 复查 + 抢占策略）且有单测；AndroidView 仅 factory 内写属性。

缺陷：AND-043（滚动条幻影闪现）、AND-044（graphicsLayer 参数形式）、AND-045（fontScale 裁剪）、AND-046（16dp 箭头）、AND-048（遮罩不拦输入）；前轮 AND-036 残留——组合期无条件写 View 属性（`PlayerScreen.kt:279` `gestureView.setOnSingleTapCallback{...}`、`:404` `gestureView.gestureListener = gestureListener`、`:407` `gestureView.edgeBackEnabled = true`）且 `edgeBackEnabled` setter 无相等守卫（`GesturePlayerView.kt:53-57` 每次 set 都跑 `updateSystemGestureExclusionRects()`）→ 每次 PlayerScreen 重组都重设系统手势排除矩形。

## Navigation Review

导航栈本身（Browser folder 入栈/出栈、SAVED_VIDEO_IDS 经 `currentBackStackEntry?.savedStateHandle` 回传——`navigate()` 同步更新 currentBackStackEntry 后 `.set()` 落在目标 entry，与 PlayerViewModel 读取处一致）验证无误。问题集中在深链：AND-042（无 popUpTo 归一化 + 同目的地顶条目替换语义）与前轮 AND-024（通知冷启动会话每次重建重放深链：`MainActivity.kt:43-46` `if (savedInstanceState == null || isNotificationIntent(intent)) pendingIntent.value = intent`——通知启动的会话中 `getIntent()` 一直带 extras，重建即重放）。边缘滑动返回（f8fc00e）与系统返回无冲突：未启用 `enableOnBackInvokedCallback`；两边缘 24dp 级条带经 `setSystemGestureExclusionRects` 排除（`GesturePlayerView.kt:98-113`）；自定义边缘返回与系统返回都路由到幂等 `doExit()`；预测式返回动画缺失是已知取舍。

## Persistence / Network Review

安全红线全部复验成立：
- E8 扫描失败清空 toDownload/toDelete（`SyncRemoteScanner.kt:156-177`）；
- 下载两阶段提交三态 tmp 探测、UNKNOWN 只删行不删 final（`SyncDownloadPipeline.kt:239-270`）；
- Provider 异常边界零泄漏（`MediaContentProvider.kt:43-59`：所有异常统一转安全文案 FileNotFoundException，不透传 message/URI/路径）；
- 删除 fail-closed 三态存在性（`MediaFileStore.kt:141-161` `existsStrict`/`checkExistence`）；
- 17 个 SyncErrorCode ↔ string 资源一一映射（`ErrorMessageMapper.kt:68-107`），重试白名单仅瞬态码。

数据层其他验证：schema v4 JSON 与实体一致（64eb027 清理干净，volumeId 字段已随移除）；多步操作均 @Transaction；无 DataStore 写竞态（全走 `dataStore.edit` actor 串行）。网络层两 client 超时齐全；ACCESS_NETWORK_STATE 在连接前真实咨询（NetworkUtils，两 client 均调用）。缺陷：AND-041（LIKE 转义）、AND-049（全表布尔流）、AND-052（openFile mode）。

## Performance Review

- AND-044：播放会话常驻每帧重组（机制确定）。
- AND-049：同步期全表物化 ×2。
- 前轮 AND-028 残留：逐 MotionEvent 全屏重组 + overlayHideJob churn。
- AND-043：无谓的首次组合动画。
- 启动无阻塞项；无主线程阻塞网络 IO 新发现（前轮 AND-026 仍在跟踪）。
- 运行时量化（重组计数/jank/内存）不可用（无设备），幅度均为机制推导。

## Accessibility Review

投入真实：`StarRatingInput` 范本级（48dp 目标 + 每星独立 contentDescription，`StarRatingInput.kt:48-58`）；进度区 `progressBarRangeInfo` + `stateDescription` + 自定义语义动作；单选行 role/selected；图标控件普遍有 contentDescription；播放器 chrome 按钮全 48dp。

缺口：AND-046（16dp 箭头）、AND-045（fontScale 裁剪）、AND-054（最坏对比度）、AND-048（TalkBack 可聚焦被遮罩控件）；前轮 AND-034（上一/下一媒体切换仅双击手势区可达，TalkBack 用户无法主动切换，仍在）。

## Gradle / Build Review

构建卫生 unusually 强（多项 folklore 被证据驳回，见 Rejected Hypotheses 7-12）：
- 签名：凭据缺失时 AGP `validateSigningRelease` **大声失败**（无静默 debug-key 回退）；历史上 CI 曾在构建前 keytool 校验（该 CI 发版链已于 2026-10-31 移除，发版改本地 assembleRelease）。
- R8：keep 规则与实际反射路径吻合（BouncyCastle 经 jcifs-ng/sshj 反射实例化而非 Security 注册；jcifs 用自传 Handler 构造，无需全局 URL 注册）；文件内有 release-verified 注记。
- 依赖解析：slf4j 解析到 2.0.13（ServiceLoader，无 StaticLoggerBinder 类引用）。
- 资源收缩安全：全仓零 `getIdentifier` 动态查找。
- git 派生版本：CI 全量 clone + fetch tags + `-PreleaseVersion`，无浅克隆风险；configuration cache 未启用（gradle.properties 无相关键），config-time gitOut 无陈旧缓存问题。
- debug 变体：`debugImplementation(compose-ui-test-manifest)` 实拆 AAR 验证仅添加一个无 intent-filter 的 exported ComponentActivity 声明，**不会**产生第二启动图标。
- lint 36 条信息级警告构成见 Build 节。

## Rejected Hypotheses

> 对抗审查驳回的候选（含驳回依据，防后续轮次重复提出）。每条均已核对源码/字节码/构建产物。

1. **progressSaveScope 消费者"每会话泄漏一个常驻协程"**——驳回：作用域仅由 VM 字段根住；VM 不可达后整链（scope+channel+挂起接收者）皆可回收，无静态根。两子审计独立得出同结论。
2. **singleTop 同目的地 navigate 丢弃新参数=死点击**——驳回：Navigation 2.9.0 字节码证实同 id 顶条目被**替换**并应用新参数（新 ViewModel）；真实行为并入 AND-042。
3. **ImageViewer 单/双击被拖拽处理器饿死**——驳回：Main pointer pass 由内向外分发，内层 `detectTapGestures`（第二个 pointerInput）先于外层 drag 收到事件。
4. **边缘滑动返回与系统 predictive back 冲突**——驳回：未启用 `enableOnBackInvokedCallback`；两边缘已 exclusion；`doExit()` 幂等。
5. **LazyList 缺 key**——驳回：browser/search/directory-tree/sync-preview/gain-analysis 全部有 key（含防重复前缀设计）。
6. **1s 回收站 snackbar 时长/排队竞态**——驳回：request-unique 视觉身份 + Main.immediate deadline 复查 + 抢占策略，且有 `TrashUndoSnackbarTest` 覆盖。
7. **debug APK 出现第二启动图标（ui-test-manifest）**——驳回：实拆 AAR，声明无 intent-filter，无 MAIN/LAUNCHER 合并项。
8. **无凭据时静默未签名/调试签名 release**——驳回：AGP 校验大声失败 + CI 先行 keytool 校验。
9. **R8 剥离 BouncyCastle/jcifs/sshj 反射**——驳回：keep 规则与实际反射路径吻合（见 Gradle Review）。
10. **slf4j 1.7 StaticLoggerBinder missing-class 破坏 R8**——驳回：解析到 2.0.13（ServiceLoader 机制）。
11. **shrinkResources 剥离动态资源**——驳回：零 getIdentifier。
12. **CI 浅克隆损坏 git 版本派生**——驳回：全量 clone + fetch tags。
13. **Provider URI 路径穿越**——驳回：id 经 `toLongOrNull()`+>0（`MediaUris.kt:58-67`），路径仅由 Long 派生（`MediaPaths.kt`），文件名段不参与寻径。
14. **saveProgress FK 竞态崩溃播放器**——驳回：消费循环逐保存 catch(Exception)（`PlayerViewModel.kt:127-136`）。
15. **DataStore 双屏写竞态**——驳回：全走 `dataStore.edit`（actor 串行）。
16. **同步通知更新风暴**——驳回：500ms 节流 + 终态 flush。
17. **200ms 位置轮询引发广域重组**——驳回：`currentPosition` 独立流窄消费（`PlayerScreen.kt:70-74` 注记）。
18. **START_REDELIVER_INTENT 重投 CANCEL 死循环**——驳回：重投即再停，良性。
19. **startForeground 错过 10s 窗口**——驳回：onCreate（`SyncService.kt:44-48`）与每条 onStartCommand（`:95-100`）都调用。
20. **SAVED_VIDEO_IDS 写错 entry**——驳回：navigate 同步更新 currentBackStackEntry 后 `.set()` 落在目标 entry。
21. **ExoPlayer remember 泄漏**——驳回：dispose 释放（`PlayerScreen.kt:114-117`）；旋转不重建；未存入 VM/单例。
22. **BiometricPrompt onPause 重锁循环**——驳回：锁按进程 ON_STOP 武装（`AppLockManager.kt:114-134` 文档化设计）。
23. **锁屏后首帧内容闪现**——驳回：进程 ON_START 在首帧前置 `_isLocked`；FLAG_SECURE 恒开兜底。
24. **"collect 无生命周期感知"类通用指控**——驳回：`collectAsStateWithLifecycle` 108 处、`collectAsState` 0 处；服务侧收集器在 onDestroy 取消的 serviceScope 内（`SyncService.kt:157-165`）。
25. **Hilt 单例持 Activity Context**——驳回：全部 `@ApplicationContext` 注入（DatabaseModule/StoragePaths/RemoteFileClientFactory/SyncEngine）；`SeekPreviewExtractor` 持 LocalContext 但为 remember 作用域 + DisposableEffect 释放。

## Inconclusive Investigations

1. **BrowserContent 滚动恢复越界**（`BrowserContent.kt:462-469`：`scrollToPosition(saved.first, saved.second)` 在同步删除缩小列表后）：需验证 `LazyListState.scrollToItem` 越界 coercion 语义或 Robolectric 实验。
2. **快速双次回收切换丢失首个 undo 提示**（`PlayerScreen.kt:450-470`：`pendingUndoTrigger` 1→2 同帧 relaunch 抢掉首个 snackbar）：需时序测试；或属可接受去重。
3. **PREVIOUS 双击缺 NEXT 的 500ms 防抖**（`PlayerScreen.kt:346-367`）：功能正确仅抖动。
4. **jcifs-ng 实际回收墙钟**（AND-050 相关）：需设备/实验（开 SMB 会话→弃置→0/10/35/60s 线程转储）。
5. **Android 15 `onTimeout(int,int)` 运行时行为**（AND-023）：框架语义已经 API 35 `Service.java` 源码证实（两参为 dataSync 超时入口，默认实现不委托单参）；运行时层面本机不可验证。
6. **AND-039 真实触发频率**（自动深色定时的实际分布）：机制静态完备，频率需设备。

## Coverage / Device Limitations

- 无 emulator/AVD/设备：rotation、深色切换、fontScale、进程死亡、慢网络、offline、权限拒绝、TalkBack、长列表滚动等场景均未实际运行；全部为静态机制证明（部分对照 androidx 源码/字节码与官方文档）。
- androidTest（4 类）未运行；instrumented 验证为空白。
- 运行时性能（重组计数、jank、内存）未量化；AND-044/049 等性能项幅度为机制推导。

## Prior Findings Status（AND-023..038）

> 上轮 android-quality 报告（2026-09-05，16 项 VERIFIED）位于远端分支 `origin/audit/android-quality`（commit c7fbae6），未合并 main。为使本报告自包含，各条全称、一句话描述与当前状态如下（行号为当前 HEAD 64eb027 实测）：

| ID | 全称与描述 | 本轮状态 | 当前证据 |
|---|---|---|---|
| AND-023 | **dataSync 超时回调覆盖错误重载**：Service 仅覆盖单参 `onTimeout(startId)`（shortService 专用），Android 15 dataSync 6h 预算到期调用两参 `onTimeout(int,int)`（默认空实现不委托单参）→ 超时清理/通知路径永不执行，进程被系统杀 + 24h 禁跑 dataSync FGS | **仍在** | `SyncService.kt:127` 仅 `override fun onTimeout(startId: Int)`；全文件无两参覆盖 |
| AND-024 | **通知冷启动会话 Activity recreate 重放深链**：通知启动的会话中 `getIntent()` 恒带 extras，每次重建 `isNotificationIntent(intent)` 为真 → pendingIntent 重放 → 重复导航 | **仍在** | `MainActivity.kt:43-46` `if (savedInstanceState == null \|\| isNotificationIntent(intent)) pendingIntent.value = intent` |
| AND-025 | **SeekPreview prefetch 取消竞态**：`cancel()`（无 join）后并发 `release()` 与在途 `getFrameAtTime` 重叠（逻辑级已复现） | 仍在 | 未见改动 |
| AND-026 | **SFTP disconnect 阻塞式网络关闭运行在调用方 dispatcher**：可从 Main 触达（browseRemote/testRemotePath finally、删除链 finally）→ ANR-adjacent | 仍在 | `SftpFileClient.kt:127-136` `sftpClient?.close(); sshClient?.disconnect()` 于调用方 dispatcher |
| AND-027 | **ExoPlayer 无 audio focus / becoming-noisy 处理**：来电不暂停、拔耳机直接外放 | 仍在 | 全仓无 `setAudioAttributes(...,handleAudioFocus)` / `setHandleAudioBecomingNoisy` |
| AND-028 | **手势高频状态留在主 PlayerUiState**：每个 MotionEvent 全屏重组（违反项目 State Splitting 约定） | **部分修复** | 进度条 scrub 已拆 `ProgressScrubUiState` 窄消费；拖动 seek 仍逐事件写主 UiState（`PlayerViewModel.kt:484-489`）+ 逐事件 overlay 全量 copy/job churn（`PlayerScreen.kt:327,336`） |
| AND-029 | **POST_NOTIFICATIONS 声明但从未请求**：全新安装（Android 13+）全部同步通知静默不可见 | 仍在 | 仅 `AndroidManifest.xml:8` 声明；全仓零运行时请求（无 rememberLauncherForActivityResult/requestPermissions） |
| AND-030 | **ImageViewer 错误流无 seq 去重**：相同文案连续错误在 Snackbar 展示窗口内被 StateFlow 值合并吞掉 | 仍在 | `ImageViewerViewModel.kt:60-63` `MutableStateFlow<String?>` |
| AND-031 | **ImageViewer completed 标记写 viewModelScope**：recents-swipe 时 viewModelScope 已取消 → 图片"已看完"标记静默丢失 | 仍在 | `ImageViewerViewModel.kt:193-204` `markCompleted` 于 `viewModelScope.launch`；对照 Player 的独立 progressSaveScope |
| AND-032 | **SettingsViewModel.authCapabilityInfo 构造期一次性快照**：注册生物识别后返回不刷新 | 仍在 | `SettingsViewModel.kt:40` 默认参数 `= biometricAuthManager.getAuthCapabilityInfo()` 构造时一次求值 |
| AND-033 | **锁屏冷却倒计时从硬编码 30s 重启**：uiMode recreate / process death 后冷却总时长被拉长 | 仍在（形式微变） | 三字段已改 `rememberSaveable`（`:30-32`），但倒计时 effect 重建仍 `var remaining = 30`（`:40` 置 30、`:47` 重新起算）——保存值仅瞬间显示。另见本轮 AND-040（叠加缺陷） |
| AND-034 | **prev/next 媒体切换仅存在于手势层**：TalkBack 用户无法主动切换上一/下一媒体 | 仍在 | 控制行仅 ±10s/旋转/设置/播放，无 prev/next 控件 |
| AND-035 | **AdvancedFilterDialog 模式切换按钮 36dp 触摸目标 < 48dp** | 仍在（反方记录） | 代码未变（36dp Surface）；新反证：M3 clickable Surface 的 `minimumInteractiveComponentEnforcement` 默认 ON，**有效**命中区可能已 48dp，视觉仍 36dp |
| AND-036 | **PlayerScreen 组合阶段无条件向 View 写 listener 属性**（未包 SideEffect/DisposableEffect） | **实质变更** | f8fc00e 已将 gestureListener `remember` 化（消除每次重组的 listener 重建）；残留：组合期无条件属性写（`PlayerScreen.kt:279,404,407`）+ `edgeBackEnabled` setter 无相等守卫（`GesturePlayerView.kt:53-57`）→ 每次重组重跑 `setSystemGestureExclusionRects()` |
| AND-037 | **删除循环整链 Main dispatcher + 每 action runBlocking volumeId** | **已修复** | volumeId 已随 64eb027 移除；删除路径均在 IO（`FileOperationsUseCase.kt:200,315`、`MetadataRebuildViewModel.kt:158` `withContext(ioDispatcher/Dispatchers.IO)`） |
| AND-038 | **AppLockManager 冷启动 ON_START 主线程 runBlocking 读 DataStore**（有意的安全权衡） | **已实质缓解** | `AppLockManager.kt:150-168`：`runBlocking { withTimeoutOrNull(coldStartReadTimeoutMs) { ... } } ?: true`（超时 fail-closed）+ IOException fail-closed + 代次协议防陈旧覆盖；仅冷启动后台收集未就绪时触发一次 |

## External Cross-References（本报告引用的历史条目速查）

> 历史编号在本报告正文出现处均已内联含义；此处给出一句话定义与报告出处，便于深入。

- **BUG-011**（`docs/automatic/20260905-0012_bug-hunting.md`，main）：deleteFolder 的 LIKE 通配符/大小写越界 → 兄弟目录密文误删——AND-041 属同类未覆盖查询。
- **BUG-002**（同上 Round 1 报告）：并发进度保存丢失更新——现由 progressSaveChannel 单消费者串行化修复（`PlayerViewModel.kt:120-124` 注释）。
- **BUG-015**（Round 2 报告，main）：loadVideo 乱序覆盖——现由 loadGeneration 代次门控修复（`PlayerViewModel.kt:268,284`）。
- **BUG-018**（Round 2）：consumeAndExecute 无法消费循环中途入队任务——现由 drain coordinator（token 化停机 + Main.immediate 确域）修复，本轮复验仍稳。
- **BUG-014**（Round 2）：AppLockManager DataStore 流无恢复 → 锁静默失效——AND-038 的缓解即其修复形态。
- **AND-001..022**：前两轮（已修复）findings，编号含 AND-001（progressSaveScope 独立作用域由来）、AND-013（进度 500ms 节流由来）、AND-014（seek 手势 base-freeze 契约由来）、AND-018（错误事件 seq 去重由来）、AND-022（onTimeout 初版修复）。出处：`origin/audit/android-quality` 历史与 main 上的修复 commits。
- **前轮完整报告**：`origin/audit/android-quality:docs/automatic/20260905-0042_android-quality.md`（AND-023..038 全文证据）。

## Prioritized Remediation Plan

1. **AND-039**（High）：播放恢复源改实时位置/DB suspend 重读；同点收口 AND-053 周期 checkpoint；补回归测试。
2. **AND-040**（Med）：`authFailed` 改 rememberSaveable 或冷却结束事件驱动；顺手按 AND-033 用 `cooldownRemaining` 作倒计时初值。
3. **AND-023**（前轮 High，仍未修）：补两参 `onTimeout(int, Int)` 覆盖——一行级修复，Android 15 超时路径才真正生效。
4. **AND-041**（Med）：LIKE ESCAPE 或 substr 前缀比较；与 BUG-011 修复同批做全仓 LIKE 审计。
5. **AND-042 + AND-024**（同接缝）：深链 `popUpTo` 归一化 + 重放守卫收紧（`savedInstanceState == null` 即可，去掉 `|| isNotificationIntent`）。
6. AND-028 残留 / AND-036 残留：手势拖动状态拆流；View 属性写包 SideEffect/相等守卫。
7. AND-043/044/046/048：小修批量（alpha 守卫 / graphicsLayer lambda / sizeIn / pointerInput）。
8. AND-047/049/050/051/052/054/055：机会性偿还。
9. AND-029（全新安装通知不可见）：首安装引导或设置页一次性请求。

## Workspace Integrity

- 过程工作区 `docs/automatic/.work/20260913-2029/`（基线门禁日志 + 4 份子审计笔记）**已删除**（用后即删规程；全部关键证据已内联本报告）。
- 本运行未修改任何 tracked 文件、签名配置、manifest、版本机制；未创建临时 Android 测试。
- 审计期间工作树曾短暂出现两个未跟踪测试 `app/src/test/.../sync/Audit20260913V{1,2}*Test.kt`（时间戳 20:55/20:56）——后确认为并行 deep-code-quality lane 的临时复现测试（`Audit20260913` 前缀），该会话已按自身规程自行删除（其报告 §13 记录删除后全量回归 581 测试 0 失败）；本 lane 未触碰。
- 本报告与同日 deep-code-quality 报告（`20260913-2030_deep-code-quality.md`，CQ-001..007）作为一次 commit 提交；两个任务的全部过程资料（`.work/20260913-2029/`、`.work/20260913-2030_deep-code-quality/` 复现存档——后者关键内容已完整收录其报告附录 A）随后按用户指示删除。
- 审计期间本运行未再运行 gradle（基线门禁在子审计启动前完成）；审计基线 HEAD 为 64eb027。
