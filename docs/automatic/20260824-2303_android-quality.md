# Android Quality Audit

**RUN_ID**: 20260824-2303
**执行时间**: 2026-08-24 23:03 – 2026-08-25 00:4x
**审计类型**: 无人值守 · 4 子 Agent + 主 Agent 复查 · 静态为主 + 本机构建/测试/lint 门禁
**前序报告**: `docs/automatic/20260823-1758_deep-code-quality.md`（本报告避免与其重复，并交叉验证其修复状态）

---

## Executive Summary

本次审计覆盖 176 个主源文件（约全量）、39 个单测文件与全部构建配置。**七条项目安全红线（E8 守卫、卷所有权 fail-closed、CancellationException 优先传播、AEAD v2 格式、随机读抛错、敏感信息不进日志/UI、结构化错误）全部独立复核为完好**——v12.2.0 的修复（含 CQ-001 传输边界取消守卫）在全部 11 个传输边界确认在场。并发卫生在 v12.2.0 加固后处于很高水准（112 个 catch 站点全部 CE-first 或免于取消语义）。

共确认/存疑 **22 项 Verified Findings**（CONFIRMED 15 / PLAUSIBLE 7），**无 High 级正确性或数据安全问题**。最重要的问题集中在三类：

1. **播放进度持久化缺口**（AND-001）：播放中与 ON_STOP 从不写库，仅依赖组合销毁路径保存——后台化被系统回收后，本次会话的全部观看进度丢失。
2. **性能类**（AND-010/011）：同步管线每文件新建 SMB/SFTP 客户端，且 SMB connect 时对同步根做完整 `listFiles()` 枚举（O(N×E)）；图片元数据提取为读宽高而做全分辨率位图解码（48MP 照片 ≈192MB 峰值内存）。
3. **可达性系统性缺口**（AND-015~017）：播放器 seek 条对手势/读屏完全不可达（无任何 semantics），设置弹窗 chips 无标签无选中态——仓库内 `BrowserSortFilter` 已有正确范式，属未推广而非不会。

其余为窄窗口竞态（AND-003/005）、事件 vs 值反模式（AND-018）、以及一项红线一致性瑕疵（AND-021：volumeId 进入用户可见 DebugLogScreen）。

**验证限制**：本机无设备/模拟器（无 adb），全部结论为 STATICALLY_PROVEN（静态源码证明）或 PLAUSIBLE（机制成立、触发依赖运行时条件）；未执行任何旋转/弱网/进程杀死等运行时场景。构建门禁为本机从零引导的工具链（见 Build Results）。

---

## Project Profile

| 维度 | 值 |
|---|---|
| 语言 | Kotlin 2.2.10（纯 Kotlin，无 Java 源） |
| UI | 100% Jetpack Compose（Material3），无 XML 布局、无 Fragment |
| SDK | minSdk 34 / targetSdk 35 / compileSdk 35 |
| 构建 | Gradle 9.3.1 wrapper、AGP 9.1.0、KSP 2.3.6、单模块 `:app` |
| 架构 | Single Activity + Compose Navigation；MVVM + Repository（Player 为 MVI）；状态拆分（高频状态独立 StateFlow） |
| DI | Hilt 2.59.2（@HiltAndroidApp + @AndroidEntryPoint Service/Provider） |
| 导航 | Navigation Compose 2.9.0 + 内存 VideoQueue/ImageQueue 单例旁路传参 |
| 网络 | 无 OkHttp/Retrofit：sshj 0.39.0（SFTP）+ jcifs-ng 2.1.10（SMB）+ BouncyCastle 1.80 |
| 持久化 | Room 2.7.0（破坏性迁移，schema 导出至 app/schemas，parentPath 索引）、DataStore Preferences、EncryptedSharedPreferences |
| 媒体 | Media3/ExoPlayer 1.6.0（AndroidView 互操作）、Coil 2.7.0 |
| 协程 | coroutines 1.10.1；`@IoDispatcher` 注入；服务级 scope `Dispatchers.Default+SupervisorJob` |
| 服务/接收器 | SyncService（foregroundServiceType=dataSync）；无 WorkManager、无 BroadcastReceiver（manifest 验证） |
| 测试 | 单测 39 文件（JUnit4+MockK+Turbine+coroutines-test）；androidTest 3 文件（依赖声明为主） |
| flavors | 无；buildTypes: debug/release（R8 full-mode minify+shrink） |
| 版本 | git 派生 versionCode/versionName（配置期 gitOut） |

包结构：`audio/ browser/ crypto/ data/(local|model|repository) debug/ di/ imageviewer/ navigation/ player/ provider/ security/ settings/ storage/volume/ sync/(sftp|smb) ui/(components|theme|util)`。

---

## Git Baseline

```text
branch: main (up to date with origin/main)
HEAD: 5c7e41915980a8ddb2632c5c1ddb1f8babff1704
describe: v12.2.0-1-g5c7e419
status: clean（审计期间仅新增 docs/automatic/.work/ 与本报告）
```

### History 专项要点（263 commits 总量，重点近 60）

- **修复模式与红线同源**：`31d9869 fix(sync): preserve cancellation across SMB/SFTP boundaries`（CQ-001，本次验证已在全部传输边界生效）；`801608c 审计全面修复 — CE 守卫/泄漏脱敏/并发规范化/TOFU 回滚`；`eb5ec59 幽灵文件残留`；`cb0a2de 首次 SMB→SAF 下载失败`。
- **近期重构**（本次重点回归对象）：`c4f8052 refactor(browser): extract content coordinator`（BrowserContentCoordinator 抽取，未引入新问题，Agent 3 复核）；`ec83906 refactor(sync): split engine lifecycle collaborators`（SyncDownloadPipeline/SyncPendingTaskManager 拆分，AND-010 的每文件客户端结构即在此文件）。
- **AND-014 的引入点**：`d150590 fix(player): 手势拖动时缩略图持续播放 — 快照拖动基点位置`——为修缩略图抖动而冻结显示基准，但提交 seek 用实时位置，两路径自此分叉。
- **AND-005 的沿革**：`acb1a42 fix: AppLockManager 缓存改用 Flow.collect 跟踪设置变更`——解决了设置变更跟踪，但 @Volatile 默认 false + 异步首读的冷启动窗口自该实现起一直存在。
- **历史 bug 修复教训与现状吻合**：`6081303 derivedStateOf 捕获快照导致 completedPaths 永远为空`（现用 rememberUpdatedState 模式，Agent 3 确认现状正确）；`4989375 下载速度仅节流刷新`（AND-013 显示该节流哲学未覆盖完成事件）。
- **SDK/AGP 升级**：`1ba0f5a chore(build): AGP 9.1.0 / Kotlin 2.2.10 / Gradle 9.3.1`（本次审计基线）。
- **高频修改 Screen/VM**：PlayerScreen/PlayerViewModel（6+ 次行为修复）、SyncDownloadPipeline（拆分后新文件）、BrowserScreen 系。

---

## Build / Test / Lint Results

本机（Windows）原本**无任何 Android 工具链**（无 JDK17+、无 SDK、无 adb、无 Gradle 缓存）。审计期从零引导：Temurin JDK 21.0.12.1+1（TUNA 镜像）+ cmdline-tools + platform-35 + build-tools 35.0.0（dl.google.com 直连可达）+ Gradle 9.3.1 发行包（腾讯镜像直用，版本与 wrapper 一致；wrapper 官方源在本网络仅 ~150KB/s）。依赖解析走 google()/mavenCentral()（dl.google.com/dl/android/maven2 与 repo1.maven.org 均验证可达）。

```text
STAGE 1 compileDebugKotlin : BUILD SUCCESSFUL — COMPILE_EXIT=0（含两条并发链各一次成功：51m34s 与 31m16s，机器较慢 + 首次无缓存）
STAGE 2 testDebugUnitTest  : BUILD SUCCESSFUL in 2m40s — TEST_EXIT=0 · 39 个测试类 · 394 tests / 0 failures / 0 errors / 0 skipped
STAGE 3 :app:lintDebug     : BUILD SUCCESSFUL in 4m18s — LINT_EXIT=0 · 0 errors, 21 warnings（信息级；warningsAsErrors=false，不阻断；含 OldTargetApi/targetSdk=35 等环境性提示）
CHAIN DONE 00:07:46
```

过程备注：构建期间出现一次并发链交错（主 Agent 曾误判 KSP 卡死而重启链，实际 pkill 未生效于 Windows 进程，原链持续慢速推进）；项目锁使任务串行化，最终一次完整链跑完全部三阶段，其余链自然终止，无残留进程。此插曲不影响结果有效性——三阶段均至少一次成功执行且结论一致。

> 注：`release.sh` 门禁含 `lintDebug`（abortOnError=true, warningsAsErrors=false）。本次为独立验证，未触碰签名/发版配置。instrumented test 未运行（无设备）。

---

## Runtime Validation Environment

- **Emulator**: 无（本机无 AVD/模拟器程序）。
- **Device**: 无（`adb` 不存在，无连接设备）。
- **实际运行 UI**: **否**。
- **结论**: `No device/emulator runtime validation available.` 所有 Finding 的证据等级为 STATICALLY_PROVEN（源码逐字验证）或 PLAUSIBLE（机制链成立、触发概率依赖运行时条件）。第 6 节所列场景（rotation、进程重建、弱网、重复点击等）均**未**运行时执行，仅在静态层面论证其机制（报告各 Finding 的 Trigger 字段即对应场景推演）。

---

## Verified Findings

编号 AND-001 … AND-022。Finding Gate 全流程：DISCOVERED（Agent1-4）→ ANDROID_MECHANISM_CONFIRMED → STATICALLY_PROVEN（无运行时环境，无 REPRODUCED 级）→ ADVERSARIALLY_REVIEWED（Agent 4 逐项对抗复核）→ MAIN_AGENT_RECHECKED（主 Agent 逐字复查了 AND-001/002/003/005/007/010/011/014/021 的源码与 AND-004/006/008/015 的 grep 证据；其余 8 项由发现 Agent + Agent 4 双重独立读码确认）→ VERIFIED。

---

### AND-001 播放中/ON_STOP 从不持久化进度，仅组合销毁路径保存

**Severity**: Medium **Confidence**: CONFIRMED
**Affected**: 播放器
**Source**: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:138,192,254-267,269-280`、`player/PlayerViewModel.kt:192-203`
**Android Mechanism**: Compose `DisposableEffect.onDispose` 是唯一的常规保存点；`Lifecycle.Event.ON_STOP` 观察者仅 pause；`viewModelScope` 写入依赖 ViewModel 存活。
**Lifecycle/State Ownership**: 进度状态属 PlayerViewModel（`_currentPosition` StateFlow），持久化写入 `viewModelScope.launch`。
**Trigger**: 播放 30 分钟 → Home 键后台化（ON_STOP 只 pause）→ 进程被 LMK/系统回收（大视频 + Media3 场景常见）→ 重进播放器。
**Evidence**: ON_STOP 观察者只调 `exoPlayer.pause()`（PlayerScreen.kt:258-261）；`doExit` 注释明示"依赖 onDispose 保存"（:272-276）；`saveProgress` 调用点仅在 dispose/切视频/STATE_ENDED。
**Reproduction**: 静态证明（无设备）。
**Expected**: ON_STOP 或周期性写库；**Actual**: 恢复到进入播放器时刻的 record.position，本次会话全部进度丢失。
**User Impact**: 恢复观看体验倒退（进度回跳到很久之前）。
**Root Cause**: 保存职责全部押在组合销毁；进程死亡路径组合根本不会销毁，`viewModelScope` 随进程消失，连 dispose 保存都不会发生。
**Git Context**: 历来多次播放器修复（a678e0d、d150590）均未触及保存时机。
**Recommended Fix**: ON_STOP 观察者中调 `viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)`（暂停后立即同步写）；可选每 30-60s 周期落库。附带修复子风险：recents-swipe 销毁路径 viewModelScope 可能已清空，可在 saveProgress 中用 `NonCancellable` 语义的独立短作用域。
**Regression Test**: 单测：FakeLifecycle 走到 ON_STOP 后断言 `playbackRepository.saveProgress` 被以当前 position 调用。
**Adversarial Review**: UPHELD（Agent 4：机制逐字复现；严重度由 High 降 Medium——影响为续播精度而非数据损坏）。主 Agent 复查确认。

---

### AND-002 播放列表队列随进程死亡消失；SavedStateHandle 回退为死代码；DB 兜底=整个媒体库

**Severity**: Medium **Confidence**: CONFIRMED
**Affected**: 播放器 / 图片浏览器 / 导航
**Source**: `navigation/VideoQueue.kt:8-21`（纯内存 AtomicReference 单例）、`player/PlayerViewModel.kt:99-150`、`navigation/AppNavHost.kt:66-68,240-241`、`imageviewer/ImageViewerViewModel.kt:66-71`
**Android Mechanism**: 内存单例 + `consumeIds()` 原子读清；SavedStateHandle `"videoIds"` 键全仓库**无任何写入者**（grep 证明：唯一写入路径是 `videoQueue.setIds`）；进程死亡后 SavedStateHandle 恢复导航参数，但该键从未被写入。
**Lifecycle/State Ownership**: 队列所有权在导航层内存单例，未纳入任何进程死亡恢复机制。
**Trigger**: 从文件夹 F 播放视频 → 进程死亡 → 系统恢复 PlayerScreen。
**Evidence**: `loadFolderVideoIds()`（PlayerViewModel.kt:119-150）：queue 空且 handle 空 → `getAllIdsSorted()` 全库 fallback；ImageViewer 同构但无 DB 兜底 → 队列直接为空。
**Reproduction**: 静态证明。
**Expected**: 恢复进入时的文件夹队列；**Actual**: 下一个/上一个/播完自动连播遍历**整个媒体库**中无关视频；图片浏览器 next/prev 直接失效直到用户重进。
**User Impact**: 自动连播跳到无关内容（隐私/体验双重视角）。
**Root Cause**: 队列旁路传参设计漏掉进程死亡路径；`savedVideoIdsFlow`（getStateFlow 反应式观察）是修复意图的半成品——观察一个永远没人写的键。
**Git Context**: e46331f 引入 ImageViewer；队列机制自 a678e0d 沿用。
**Recommended Fix**: AppNavHost `onNavigateToPlayer` 处将 ids 写入当前 back stack entry 的 SavedStateHandle（`savedStateHandle["videoIds"] = ids.joinToString(",")`），使现有 `savedVideoIdsFlow` 观察真正生效；或队列改用 `saveable` 持久化。
**Regression Test**: 单测：构造 SavedStateHandle 含 videoIds → 断言 `_allVideoIds` 来自 handle 而非 `getAllIdsSorted()`（现有观察逻辑已有测试缝隙）。
**Adversarial Review**: UPHELD（Agent 4 grep 复核无写入者；`getAllIdsSorted()` 兜底位置确认）。主 Agent 复查确认。

---

### AND-003 第二次同步启动在窄竞态窗口内被服务层丢弃 → 目标关系 spinner 卡死

**Severity**: Low **Confidence**: PLAUSIBLE（原 Medium 候选经对抗复核降级）
**Affected**: 同步
**Source**: `sync/SyncService.kt:65-67`、`sync/SyncEngine.kt:112-128`、`sync/SyncPreviewViewModel.kt:157-206`
**Android Mechanism**: `onStartCommand` 的 `syncJob?.isActive == true` 早退守卫；`setPendingTask` 与 `consumeAndExecute` 共用 `storageOperationMutex` 串行化。
**Lifecycle/State Ownership**: 服务级 syncJob（SupervisorJob scope）；VM 级 `_isSyncing` 等待终态 emission。
**Trigger**: 关系 A 同步运行中 → 用户切到关系 B 预览页点开始 → B 的 `setPendingTask` 阻塞在 mutex 直到 A 执行段完成 → A 释放 mutex 后仍需执行 `finalize`（DB 写）尾段，此窗口（数十 ms）内若 B 的 `onStartCommand` 经 binder 到达 → 早退丢弃。
**Evidence**: 早退守卫 SyncService.kt:65-67；串行化链 SyncEngine.kt:114（withLock 包裹 consume+execute）+ SyncPreviewViewModel.kt:161（setPendingTask 同锁）；尾段 finalize SyncEngine.kt:126。
**Reproduction**: 静态证明窗口存在；触发概率低（ binder 时延 vs finalize 时长竞速）。
**Expected**: B 任务入队后终被消费执行；**Actual**: 窄窗口内 B 的 pending task 落库但本轮不被消费，B 预览页 `_isSyncing=true` 永不清除（等不到 relationId==B 的终态），开始按钮保持禁用。
**User Impact**: B 关系页面 spinner 卡死，需退出重进再点一次（pending task 仍在，数据无损）。
**Root Cause**: 服务层"运行中即丢"守卫与引擎层 mutex 串行化之间的时序缝隙；VM 只以终态 emission 作为完成信号，任务被丢弃时无信号。
**Git Context**: ec83906 拆分引入 consumeAndExecute 原子语义；E1 注释系列已处理 stopSelf 竞态但未覆盖此窗。
**Recommended Fix**: 早退分支改为将到达的 relationId 重新入队（或去掉早退、依赖 mutex 串行天然排队）；或 `observeSyncCompletion` 增加超时兜底。
**Regression Test**: 单测：模拟 A isActive 时 B onStartCommand → 断言 B 的 pending task 最终被消费或 VM 收到终态。
**Adversarial Review**: DOWNGRADED from Medium（Agent 4：原"第二同步必然被丢"场景实际被 mutex 串行化排除——B 的 setPendingTask 会阻塞到 A 执行完毕，onStartCommand 几乎总在 A syncJob 结束后到达；且空任务路径经主 Agent 复核**总会**发射终态 COMPLETED（SyncDownloadPipeline.kt:475-489 无条件终态发射），UI 开始按钮也有非空门控——仅剩 finalize 尾段窄窗）。主 Agent 复查确认降级成立。

---

### AND-004 入口导航无防重守卫：快速双击堆叠两个相同目的地

**Severity**: Medium **Confidence**: CONFIRMED
**Affected**: 导航 / 播放器 / 图片浏览器
**Source**: `navigation/AppNavHost.kt:63-92,240-246`（无 launchSingleTop）、`browser/BrowserContent.kt:223-231`（item 点击无节流）、对照正确范式 `AppNavHost.kt:61,218-221`
**Android Mechanism**: NavController 每次navigate 压栈；无 `launchSingleTop` 时同一目的地连续两次点击产生两个 back stack entry。
**Trigger**: 快速双击视频/图片卡片；或同步通知 PendingIntent 与手动点击竞速。
**Evidence**: grep 证明 launchSingleTop 仅 2 处（底部导航与抽屉）；入口回调直连 navigate。
**Reproduction**: 静态证明（重复点击行为确定性成立）。
**Expected**: 单实例；**Actual**: 返回需按两次；播放器路径瞬时创建两个 ExoPlayer（onDispose 释放，泄漏瞬时性）。
**User Impact**: 返回栈混乱；瞬时双播放器实例（可能闪屏/双音频起播）。
**Root Cause**: 防重模式存在于高频切换处但未覆盖内容入口。
**Git Context**: e46331f 图片路由引入时沿用了无守卫模式。
**Recommended Fix**: 入口 navigate 统一加 `launchSingleTop = true`；或提供节流包装（如 300ms）给 onNavigateToPlayer/ImageViewer。
**Regression Test**: compose-ui-test（需设备，本机不可运行）——至少单测 NavigationHelper 层的 navigate 参数。
**Adversarial Review**: UPHELD（Agent 4 验证）。主 Agent grep 复查确认。

---

### AND-005 安全锁冷启动竞态：首次 ON_START 时缓存未热 → 免锁进入

**Severity**: Medium（安全邻近）**Confidence**: PLAUSIBLE
**Affected**: 应用锁
**Source**: `security/AppLockManager.kt:28,43-48,60-67`、`data/repository/UserPreferencesRepository.kt:96-98`
**Android Mechanism**: `@Volatile cachedSecureLockEnabled = false` 默认值 + `register()` 在 `Dispatchers.IO` 异步 collect DataStore；ProcessLifecycleOwner 首个 ON_START 紧随 MainActivity.onStart，与 DataStore 首读（与 `purgeLegacyCiphertext` 等启动 IO 竞争）无 happens-before。
**Trigger**: 冷启动 + DataStore 首读慢于首个 ON_START（窗口通常几十 ms，磁盘忙时更大）。
**Evidence**: AppLockManager.kt:28（默认 false）、:45-47（异步 collect）、:60-66（ON_START 读缓存）。
**Reproduction**: 静态证明窗口；无运行时量化。
**Expected**: 开启安全锁的设备冷启动必锁；**Actual**: 窗口内直接进入主界面；下一次后台/前台循环才锁。
**User Impact**: 窄窗口内敏感媒体可被窥视（FLAG_SECURE 仅保护截屏/最近任务缩略图，不保护现场）。
**Root Cause**: 锁判定依赖异步填充的缓存而非同步首读/暂停门。
**Git Context**: acb1a42 将缓存改为 Flow.collect（解决设置跟踪）时引入该默认值窗口。
**Recommended Fix**: ON_START 时若缓存从未初始化（增加 `cacheInitialized` 标志），同步阻塞短读一次 DataStore 首值（`runBlocking` 限定于首帧前不可行则改为主界面 gate：未初始化完成前显示启动画面）。
**Regression Test**: 单测：缓存未初始化时调用 onStateChanged(ON_START) → 断言 `_isLocked` 不因默认 false 而放行（改为 gate 语义后）。
**Adversarial Review**: UPHELD as PLAUSIBLE（Agent 4：无 happens-before 边确认；窗口窄）。主 Agent 复查确认。

---

### AND-006 进程死亡恢复后，通知点击的 PendingIntent 导航被丢弃

**Severity**: Low **Confidence**: CONFIRMED
**Affected**: MainActivity / 通知
**Source**: `MainActivity.kt:43`
**Android Mechanism**: `if (savedInstanceState == null) pendingIntent.value = intent` —— 恢复启动路径（savedInstanceState != null）不消费 intent extras。
**Trigger**: 同步通知到达 → 用户点击时进程已死 → 系统以 savedInstanceState 重建 Activity 并投递通知 intent。
**Expected**: 解锁后导航到同步页；**Actual**: 停留当前屏，静默无响应（温启动与全新路径正常）。
**User Impact**: 低频导航失效。
**Root Cause**: 以 savedInstanceState 区分"全新启动"的经典写法漏掉 intent 数据分支。
**Recommended Fix**: `savedInstanceState == null || intent.hasNotificationExtras`（判 action/extras）时消费。
**Regression Test**: Robolectric/单测 ViewModel 层 pendingIntent 消费逻辑。
**Adversarial Review**: UPHELD（Agent 4）。主 Agent 复查确认（sed 逐字）。

---

### AND-007 锁屏遮罩冷却状态用 remember：uiMode 重建即重置并立即重弹生物认证

**Severity**: Low **Confidence**: PLAUSIBLE（两 Agent 独立发现，去重合并）
**Affected**: 应用锁
**Source**: `security/LockScreenOverlay.kt:28-31,44-65`
**Android Mechanism**: `failCount/isCooldown` 为组合级 `remember`（非 rememberSaveable）；manifest configChanges 覆盖 orientation/screenSize 但**不含 uiMode/fontScale**；`LaunchedEffect(Unit)` 在重组root后立即重新触发认证。
**Trigger**: 锁屏界面停留时从快捷开关切换深色模式（uiMode 配置变更 → Activity 重建）。
**Expected**: 5 次失败后的 30s 冷却持续；**Actual**: 冷却清零 + 立即重弹 BiometricPrompt（系统级生物锁定仍兜底）。
**Root Cause**: 安全状态放在组合局部而非锁管理器。
**Recommended Fix**: 冷却/failCount 提升到 AppLockManager（进程级单例），或至少 rememberSaveable。
**Regression Test**: 单测 AppLockManager 冷却状态机（提升后）。
**Adversarial Review**: UPHELD（Agent 4；与 3-F8 重复合并）。主 Agent 复查确认（逐字读取 28-31/59-65）。

---

### AND-008 同步预览页双后退语义分叉：工具栏返回=取消同步，系统返回=放行

**Severity**: Low-Medium **Confidence**: CONFIRMED
**Affected**: 同步预览
**Source**: `sync/SyncPreviewScreen.kt:90-108`（工具栏返回调 `cancelSync()`）、全文件无 `BackHandler`（grep=0）
**Android Mechanism**: 同屏两个后退可供性（预测性返回手势/系统键 vs TopAppBar arrow）触发不同代码路径。
**Trigger**: 同步运行中分别使用两种后退。
**Expected**: 语义一致（均为取消或均为放行，破坏性操作应明示）；**Actual**: 工具栏静默取消运行中同步，手势返回保留。
**User Impact**: 误取消同步（虽有 Stop 按钮承担明示取消，:133-141）。
**Root Cause**: 缺 BackHandler 对齐语义。
**Recommended Fix**: 工具栏返回同步运行时不取消（仅 onBack），取消交给显式 Stop；或加 BackHandler 复用同一逻辑。
**Regression Test**: 单测 VM cancelSync 调用路径 + compose 手势路径（需设备）。
**Adversarial Review**: UPHELD（Agent 4）。主 Agent 复查确认（sed 90-108 + grep BackHandler=0）。

---

### AND-009 浏览器深层文件夹状态不随进程死亡恢复（回退到 nav 参数层）

**Severity**: Low **Confidence**: CONFIRMED（已知取舍）
**Affected**: 浏览器
**Source**: `browser/BrowserContentCoordinator.kt:59`、`browser/BrowserScreen.kt:126-130`
**Android Mechanism**: 文件夹栈在 VM 内存；nav 参数仅携带入口 folder。
**Trigger**: 深入多层文件夹 → 进程死亡 → 恢复。
**Actual**: 回到入口文件夹而非最深位置。
**Root Cause**: 单 Browser 目的地设计的既定取舍（VM 状态仅覆盖配置变更）。
**Recommended Fix**: 可选——最深 folder path 存 SavedStateHandle/DataStore。
**Adversarial Review**: UPHELD as by-design degradation（Agent 4）。

---

### AND-010 同步管线每文件新建远程客户端；SMB connect 对同步根做完整 listFiles 枚举（O(N×E)）

**Severity**: Medium（性能）**Confidence**: CONFIRMED
**Affected**: 同步下载
**Source**: `sync/SyncDownloadPipeline.kt:165-174`、`sync/RemoteFileClientFactory.kt:15-18`、`sync/smb/SmbFileClient.kt:95-122`、`sync/sftp/SftpFileClient.kt:89-102`
**Android/JVM Mechanism**: 每文件 worker `getClient()` + `connect()`；SMB connect 新建 jcifs BaseContext 并以 `listFiles()` 全量枚举远端根目录作为连通性探测；SFTP connect 走完整 TCP+SSH KEX+认证+channel（每文件约 100-300ms 握手）。
**Trigger**: 首次大同步（N 文件 × 根目录 E 条目；3 并发通道）。
**Evidence**: 主 Agent 逐字复核 SyncDownloadPipeline.kt:168-174（worker 内 connect）与 SmbFileClient.kt:108/119（根枚举探测）。
**Expected**: 每批次/每通道复用连接；**Actual**: N 文件同步做 N 次根目录枚举 + N 次传输协商；1000 文件级别纯握手耗分钟级 + NAS 负载放大。
**Root Cause**: per-file 客户端结构自拆分(ec83906)继承；E5 每次重试重连是有意设计但 per-file 非测试契约（SyncEngineTest mock 工厂，不断言生命周期）。
**Recommended Fix**: 每并发通道（或每批次）建一个 client，worker 共享；SMB 连通性探测改 `SmbFile.exists()` 单点查询。
**Regression Test**: 单测：同一批 N action 断言 factory.create 调用次数 == 并发数而非 N。
**Adversarial Review**: UPHELD（Agent 4 复现）。主 Agent 复查确认。

---

### AND-011 图片元数据提取为读宽高做全分辨率位图解码

**Severity**: Low-Medium（性能/内存）**Confidence**: CONFIRMED
**Source**: `sync/ImageMetadataExtractor.kt:44-62`
**Mechanism**: `BitmapFactory.decodeStream(input)` 无 Options → 全尺寸 ARGB 解码（48MP ≈192MB 原生内存），仅用 width/height + 降采样至 640px 缩略图；随后第二次 openInputStream 重解密整文件读 EXIF。
**Scope owner**: SyncDownloadPipeline worker（Dispatchers.IO，已验证无主线程问题）；3 并发放大。
**Expected**: `inJustDecodeBounds` 读尺寸 + `inSampleSize` 采样解码缩略图（峰值 ~1-2MB）。
**User Impact**: 批量照片同步吞吐下降、内存压力/GC 抖动；无功能错误。
**Git Context**: c881d8c 图片支持引入。
**Recommended Fix**: 两遍解码（bounds → sampled）；EXIF 读可与缩略图共用已解码流或保留二次但仅元数据。
**Regression Test**: 单测注入假 stream 断言 Options 使用（或 Robolectric shadow BitmapFactory）。
**Adversarial Review**: UPHELD（Agent 4）。主 Agent 复查确认。

---

### AND-012 SeekPreview LruCache 回收与 UI 持有位图的窄窗口 use-after-recycle

**Severity**: Low **Confidence**: PLAUSIBLE
**Source**: `player/SeekPreviewExtractor.kt:35-48,121-133`、`player/PlayerScreen.kt:450-459,662-666`
**Mechanism**: 缓存逐出与 `release()` 的 `evictAll()` 直接 `recycle()` 位图；Compose 层 `seekPreviewBitmap` state + `Image(bitmap.asImageBitmap())` 无所有权移交。稳态安全（显示帧恒为 MRU 不可能为逐出受害者）；退出播放器时 `release()` 可能回收"已记录未绘制"帧 → RenderThread `trying to use a recycled bitmap` 崩溃（约一帧窗口）。另注：prefetchScope 缺 SupervisorJob，理论上一处未捕获子失败会禁用后续预取。
**Expected**: 位图生命周期由单一 owner 管理（如 handoff 或不 recycle 交 GC——minSdk 34 上 recycle 已非必需）。
**Recommended Fix**: 移除显式 recycle（API 26+ 位图像素存 NativeHeap 由 GC 回收；4MB LruCache 本就以字节预算约束），或退出路径先置 null state 再延迟 release。
**Adversarial Review**: UPHELD as PLAUSIBLE（Agent 4 确认稳态受害者分析成立、仅退出窗口残留）。

---

### AND-013 每文件完成事件绕过 500ms 节流直发 NotificationManager.notify

**Severity**: Low **Confidence**: CONFIRMED
**Source**: `sync/SyncProgressPublisher.kt:160-175,191-196`、`sync/SyncService.kt:41-47`
**Mechanism**: `completeDownload/markDeleted/beginDownload` 无条件发布（不受节流），每次 `completedPaths.toSet()` 拷贝（10k 文件 O(n²) 累计）；服务层每次 emission 转发 `updateNotification` → notify() binder 调用。StateFlow conflation 兜底，主要为 DELETE_LOCAL 尾段通知抖动。
**Git Context**: 4989375 确立"速度仅节流刷新"，完成计数即时发布为有意设计（计数准确性）。
**Recommended Fix**: 完成事件也走节流（或仅在计数变化阈值处强制刷）；completedPaths 用持久化集合结构。
**Adversarial Review**: UPHELD（约定级低效，非 bug）。

---

### AND-014 手势 seek 提交点系统性前跳：显示/预览用冻结基准，实际 seek 用实时位置

**Severity**: Low-Medium（正确性/UX）**Confidence**: CONFIRMED
**Affected**: 播放器手势
**Source**: `player/GesturePlayerView.kt:101-113`（基准冻结于手势起点 `seekBasePositionMs = currentPosition`）、`player/PlayerScreen.kt:309-313`（提交 `exoPlayer.currentPosition + offsetMs` 实时位置）、`player/PlayerViewModel.kt:341-347`
**Mechanism**: 播放中手势期间视频持续前进；overlay 与预览缩略图显示 `base+offset`（冻结），手指抬起实际落在 `live+offset`。拖动 3 秒即前跳约 3 秒；暂停播放时两路径一致无偏差。
**Trigger**: 播放状态下任意横向拖动。
**Root Cause**: d150590 为修"缩略图持续播放"冻结了显示基准，但未同步冻结提交基准——两路径分叉。
**Recommended Fix**: 提交也用 `seekBasePositionMs + offsetMs`（手势开始时把基准传给 VM，或 GesturePlayerView 在 onSeekTo 回调携带 base）。
**Regression Test**: 单测 VM：onGestureSeekFinished 期间模拟 currentPosition 推进 → 断言 seekTarget 基于 base 而非 live。
**Adversarial Review**: UPHELD（Agent 4 逐字比对两路径）。主 Agent 复查确认。

---

### AND-015 播放器 seek 条对无障碍完全不可达

**Severity**: Medium（a11y）**Confidence**: CONFIRMED
**Source**: `player/PlayerScreen.kt:874-916`（raw Box + pointerInput，无 semantics）、:929-948（MinimizedProgressBar 同）
**Mechanism**: 主传输控件无 `progressBarRangeInfo`/Slider role/`stateDescription`——TalkBack 无法聚焦或操作 seek；位置信息仅邻接文本可读。grep 证明全文件零 semantics 用法。
**Expected**: `Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(position, range); stateDescription = ... }` + 可编程 seek 动作（customAccessibilityAction）。
**User Impact**: 读屏用户无法独立调整播放位置。
**Recommended Fix**: 补 semantics（仓库内 BrowserSortFilter.kt:306-326 已有 semantics 范式可循）+ custom accessibility actions 做 ±10s。
**Adversarial Review**: UPHELD（Agent 4；grep 复核）。主 Agent 复查确认（grep semantics=0）。

---

### AND-016 播放器设置弹窗 chips 无标签且无选中态语义

**Severity**: Low-Medium（a11y）**Confidence**: CONFIRMED
**Source**: `player/PlayerScreen.kt:1031-1048`（IconOptionChip contentDescription=null）、:1050-1075（TextOptionChip 无 selected 语义）
**Mechanism**: 40dp Surface 按钮读屏播报"未命名按钮"；倍速 chips 无选中态播报。对照 BrowserSortFilter.kt:306-326 正确范式。
**User Impact**: 读屏用户不知按钮功能与当前档位。
**Recommended Fix**: 套用 BrowserSortFilter 的 `Modifier.semantics { contentDescription; selected }` 范式。
**Adversarial Review**: UPHELD（Agent 4 对比范式确认）。

---

### AND-017 高级筛选对话框：星档模式切换无标签、星控件无值语义且触点 28dp

**Severity**: Low（a11y）**Confidence**: CONFIRMED
**Source**: `browser/BrowserScreen.kt:480-499`（36dp icon-only，OFF 态 contentDescription=null 无状态描述）、`ui/components/StarRatingInput.kt:32-52`（每星通用"已评分/未评分"不带 1-5 值，bare clickable 无 Role，28dp 触点）
**Expected**: 模式切换按钮带当前模式描述；每星带值语义 + Role.Button；触点≥48dp（`minimumInteractiveComponentSize` 或扩大点击域）。
**Adversarial Review**: UPHELD（Agent 4）。

---

### AND-018 相同文案的连续错误事件被值语义吞掉

**Severity**: Low **Confidence**: PLAUSIBLE
**Source**: `browser/BrowserScreen.kt:345-350`、`browser/SearchScreen.kt:305-310`、`player/PlayerScreen.kt:504-509`
**Mechanism**: `error?.let { LaunchedEffect(it) { showSnackbar(it); clearError() } }` —— key 是错误**文案**；首个 snackbar 展示期间（~4s）第二个同文案错误 key 不变 → effect 不重启 → 不展示且被首个 clearError 清除。事件 vs 值反模式。
**Trigger**: 同类操作连续失败映射到同一资源文案。
**Recommended Fix**: 错误流改 Channel/SharedFlow 事件语义，或 key 加单调序号。
**Adversarial Review**: UPHELD as PLAUSIBLE（模式逐字验证，触发低频）。

---

### AND-019 图片浏览器切图不重置缩放/平移状态

**Severity**: Low **Confidence**: CONFIRMED
**Source**: `imageviewer/ImageViewerScreen.kt:247-250`（scale/offsetX/offsetY 无 key 的 remember）、:317-331（缩放态下边缘滑动允许换图）
**Actual**: 放大状态下切到下一图仍保持缩放/平移，仅双击可复位。
**Recommended Fix**: `remember(entity.id) { ... }` 或换图回调显式重置。
**Adversarial Review**: UPHELD（Agent 4）。

---

### AND-020 播放器手势未声明系统手势排除区

**Severity**: Low **Confidence**: PLAUSIBLE
**Source**: `player/GesturePlayerView.kt:163-198`（消费全部触摸但无 `setSystemGestureExclusionRects`，grep 全仓库零命中）
**Mechanism**: 手势导航下，起始于屏幕边缘的 seek/亮度/音量拖动会被系统 back/home 手势拦截。设备配置相关（全面屏手势模式）。
**Recommended Fix**: 全屏播放态设置排除矩形（注意 Android 10+ 每边 200dp 限额）。
**Adversarial Review**: UPHELD as PLAUSIBLE（grep 复核）。

---

### AND-021 volumeId 进入 DebugLogger（用户可见日志屏）——红线 6 一致性瑕疵

**Severity**: Low **Confidence**: CONFIRMED
**Affected**: 同步
**Source**: `sync/SyncDownloadPipeline.kt:115`（`DebugLogger.i(TAG, "当前卷: ${store.volumeId}")`）、`debug/DebugLogger.kt`（无构建门控：无条件转发 logcat + 环形缓冲，DebugLogScreen 用户可浏览）
**Mechanism**: SAF 卷的 volumeId 即完整 tree URI（含卷 UUID），属红线 6 明令不进日志/UI 的卷标识。仓库自身的卷不匹配日志（SyncDeleteExecutor.kt:94-98、SyncDownloadPipeline.kt:411-415）刻意省略该值——全仓库唯一违例点。
**Expected/Actual**: 删除卷标识或脱敏（如卷序列哈希前 4 字符）。
**Git Context**: ec83906 拆分时引入。
**Recommended Fix**: 改为 `DebugLogger.i(TAG, "当前卷: <configured>")` 布尔语义或脱敏标识。
**Regression Test**: 单测捕获 DebugLogger 快照断言不含 volumeId 值。
**Adversarial Review**: UPHELD（Agent 4 自证发现；主 Agent 逐字复核确认 DebugLogger 无门控）。

---

### AND-022 dataSync 前台服务未处理 Android 15 onTimeout

**Severity**: Low **Confidence**: PLAUSIBLE
**Source**: `sync/SyncService.kt`（全文件无 `onTimeout(int)` 覆盖；manifest 声明 foregroundServiceType=dataSync）
**Mechanism**: targetSdk 35 下 dataSync 6h/24h 累计配额耗尽时系统回调 `onTimeout`；未及时 stopSelf 将抛 `ForegroundServiceDidNotStopInTimeException` 崩溃。个人使用时长下触发概率低。
**Recommended Fix**: 覆写 `onTimeout` → 取消 syncJob + scheduleStop 即可。
**Adversarial Review**: Agent 4 Phase A 发现；主 Agent 通读全文件确认无覆盖。

---

## Lifecycle & State Review

见 AND-001~009。总体评价：导航/生命周期骨架健康——configChanges 明确、VM 状态暴露规范（StateFlow asStateFlow，无可变泄漏）、重复消费竞态已修（VideoQueue AtomicReference）、AppLock 的 ON_STOP 选择有据可查（注释记录 OEM 生物弹窗 onPause 陷阱）。主要缺口集中在**进程死亡路径**（AND-002/006/009）与**保存时机**（AND-001），符合"配置变更已覆盖、进程死亡覆盖不足"的典型演化形态。红点：SyncService 的 E1 系列注释与实现质量高，onStartCommand 重复 startForeground 处理正确。

## Coroutine / Threading Review

见 AND-010~013。总体为本次审计最健康维度：112 个 catch 站点全数 CE-first/免取消语义；五个自定义 scope 均为进程级或显式取消；`runBlocking` 仅存在于 MediaContentProvider pipe 线程上下文（调用方全部追溯到 ExoPlayer loader/Coil IO 线程，非主线程）；dispatcher 纪律（@IoDispatcher 注入）贯通。Agent 2 明确结论："v12.2.0 加固后并发卫生出色——剩余问题均为效率而非正确性"。

## Compose / View Review

见 AND-014~020。强项：Lazy 列表全部带稳定 key；列表构建在协调器 mapLatest+IO+debounce；PlayerViewModel 状态拆分符合架构规范（200ms 位置流独立 StateFlow）；rememberUpdatedState 反陈旧闭包模式应用正确（6081303 教训已内化）。缺口集中在无障碍（系统性的 semantics 缺失）与个别 effect/状态细节（AND-018/019）。无 XML/Fragment 层，无 ViewBinding/RecyclerView 议题。

## Navigation Review

见 AND-002/004/006。骨架（单一 NavHost + Route + 旁路队列）在小规模个人应用成立；风险全部源于旁路队列的进程死亡语义与入口防重缺失。预测性返回未启用（manifest 未声明 `android:enableOnBackInvokedCallback`）——记为信息项非发现（targetSdk 35 下非强制）。

## Persistence / Network Review

Room：schema 2.json 与实体逐字段吻合（Agent 4 核验）；破坏性迁移与"删关系留媒体"为文档化意图。网络层（sshj/jcifs 无 TLS 框架）超时已配置（SMB 5s 响应/So 超时，SftpFileClient 同级）；R8 keep 规则覆盖 BC/jcifs/sshj（ae7f0bb 修复后 release 验证）。数据不变量七红线全部完好。唯一持久化侧问题即 AND-021（日志泄卷标识）与 AND-022（FGS 超时）。

## Performance Review

AND-010（N×E 根枚举 + N 次握手）为最大性能项；AND-011（全尺寸解码）次之；AND-013（通知抖动 + O(n²) 集合拷贝）为第三。启动路径无阻塞发现（Application.onCreate 轻量，purgeLegacyCiphertext 在后台协程）。Compose 重组维度无放大问题（排序/过滤均离主线程）。

## Accessibility Review

系统性缺口：AND-015（seek 条不可达）> AND-016/017（chips/星控件）> 触点尺寸（28-36dp）。修复路径统一且成本低（仓库内已有正确范式）。文本缩放/深色对比未发现静态可证的违规（主题走 Material3 动态色）。

## Gradle / Build Review

构建配置健康：R8 full-mode + shrinkResources；packaging excludes 精确；lint 严格门禁（abortOnError）已付清债务（d67882d）；git 派生版本机制在无 tag 环境有 `dev` 兜底。debug/release 无行为分叉风险点（无 debuggable 条件逻辑）。AGP 9.1/Kotlin 2.2.10/Gradle 9.3.1 组合一致。

## Rejected Hypotheses

三 Agent 共记录 40 项被拒假设（全字段论证见 `.work/agentN-notes.md`）。代表性条目：

- **后台错误收集滥用/NavigationHelper 泄漏/FGS 启动时序违规/startService 后台崩溃/队列双消费竞态/ExoPlayer 泄漏/FLAG_SECURE 时序/ON_PAUSE 锁死**（Agent 1，14 项）——逐一被框架守卫或实现细节反驳。
- **runBlocking 主线程阻塞**（MediaContentProvider 全部调用链追溯至 loader 线程）；**CancellationException 吞噬**（112 catch 站点全数分诊）；**scope 泄漏**（5 个自定义 scope 全部有主）；**重组期排序**（协调器 IO+debounce）；**取消后重试**（传输客户端 CE-first）。（Agent 2，12 项）
- **主线程帧提取**（withContext(IO) 在场）；**陈旧闭包 effect**（rememberUpdatedState 全覆盖）；**Lazy 无 key**（全 keyed）；**硬编码 UI 文案**（i18n 约定守住了）；**rememberSaveable 旋转丢失**（configChanges+VM/DataStore 使大多数假设坍缩）。（Agent 3，14 项）
- **对抗复核降级/反驳**：1-F3 双半降级（mutex 串行化 + 空任务终态必发，主 Agent 终裁仅剩窄竞态）；空任务"spinner 卡死"残留被主 Agent 以 SyncDownloadPipeline.kt:475-489 无条件终态发射**反驳**。

## Inconclusive Investigations

- AND-005 冷启动窗口的**实际时延**（DataStore 首读 vs 首个 ON_START）需设备测量。
- AND-012 recycle 竞态的真实崩溃率需长时 scrub 压测（FQA 域）。
- AND-020 手势排除的实际影响取决于设备导航模式分布。
- `UiState` per-frame `userSeekPosition/gestureSeekPosition` 更新的重组成本：Agent 3 观察到但按证据标准未晋级（无实测帧数据）。

## Coverage / Device Limitations

- **无设备/模拟器/adb**：全部场景测试（rotation、进程重建、弱网、权限拒绝、深色/大字体实机渲染、TalkBack 实测）未执行；第 6 节场景仅在静态层面映射到各 Finding 的 Trigger 字段。
- 构建门禁运行于本机从零引导的工具链（JDK21/AGP9.1/Gradle9.3.1），非项目 CI 环境的精确复刻（无 CI 缓存语义），但任务集与 `release.sh` 本地门禁一致。
- androidTest（3 文件）未运行（依赖设备）。
- 审计时长约束下，`audio/`、`settings/` 子包为抽样覆盖（Agent 1/4 各自读了主路径）。

## Prioritized Remediation Plan

| 优先级 | 项 | 动作 | 预估 |
|---|---|---|---|
| P1 | AND-001 | ON_STOP 即时 saveProgress（+可选周期落库） | 小 |
| P1 | AND-010 | 通道级客户端复用 + SMB 探测改 exists() | 中 |
| P1 | AND-015/016/017 | 语义批改（套 BrowserSortFilter 范式） | 小-中 |
| P2 | AND-002 | nav 入口写 SavedStateHandle videoIds（激活现有观察者） | 小 |
| P2 | AND-004 | 入口 launchSingleTop/节流 | 小 |
| P2 | AND-005 | 锁缓存 gate（未初始化不放行） | 小 |
| P2 | AND-011 | 两遍解码 + inSampleSize | 小 |
| P2 | AND-014 | 提交基准改冻结 base | 小 |
| P3 | AND-006/007/008/012/013/018/019/020/021/022/003 | 各自小修 | 均小 |
| 记录 | AND-009 | 接受为设计取舍或纳入 P3 | — |

建议顺序：先 P1 三组（用户可感收益最大、风险最低），P2 同批合入（AND-002 与 AND-004 同文件区域），P3 打包一个 chore PR。全部修复均可用现有 JUnit4+MockK 栈写回归（compose-ui 项除外）。

## Workspace Integrity

- 共享工作区：`docs/automatic/.work/20260824-2303/`（本 RUN_ID）。内容：agent1-4-notes.md（全字段证据）、bootstrap-toolchain.sh、baseline-build.sh、build-chain.log。
- **保留原因**：agent 笔记为 22 项发现与 40 项拒绝假设的完整证据链，build-chain.log 为门禁原始输出，两脚本为工具链复现步骤——供后续复核与修复验证引用。无其他临时验证文件产生（未新增任何 Android test）。
- 仓库内除 `.work/` 与本报告外**零改动**：签名配置、发版脚本、manifest、版本机制均未触碰；工作树其余部分保持 clean（HEAD 仍为 5c7e419）。
- 仓库外产物：`/tmp/android-audit-tc/`（JDK21+Android SDK+Gradle，系统临时目录，随系统清理消失）；`~/.gradle`（依赖缓存，属常规构建产物）。
- Gradle 9.3.1 采用腾讯镜像发行包直用（与 wrapper 版本一致），因官方源在本网络仅 ~150KB/s；已在 Build Results 声明，非静默替换。
