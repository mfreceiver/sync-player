# Automatic Deep Code Quality Audit

- **RUN_ID**: `20260913-2030_deep-code-quality`
- **Date**: 2026-09-13 20:30 – 21:45 (local)
- **Working dir**: `docs/automatic/.work/20260913-2030_deep-code-quality/`（补充存档，**阅读本报告无需打开**）
- **编号消歧说明**：源码注释中已有的 `CQ-001` 标记（SftpFileClient/SmbFileClient 的取消语义注释）属于 **2026-08-23 旧整改轮**（见 `docs/ocmar/plans/2026-08-23-sync-player-12.2.0-remediation.md`），与本报告的 CQ-xxx 编号**无关**。
- **自包含声明**：本报告包含理解与修复全部问题所需的证据——关键代码摘录（§6 各 Finding 内）、复现断言与失败输出（§6 各 Finding 内）、可直接转正的完整回归测试源码（附录 A）。

---

## 1. Executive Summary

**审计结论**：仓库整体工程健康度**良好**——E8 守卫、两阶段提交、CE 先传播、App Lock 缓存代际等核心红线在对抗复核下全部站得住；BUG-001..022 修复无回归（581 个既有单测全绿）。但存在 **1 个真实的高价值功能缺陷（P1）**：下载重试机制对"新文件 + 瞬时网络错误"这一重试白名单最核心的目标场景**完全失效**，且会把重试成功提交的文件误删并误报为 STORAGE_ERROR。另有 1 个 P2（同步预览屏"假完成"）与 5 个 P3。

- **P0**: 0　**P1**: 1　**P2**: 1　**P3**: 5
- **最值得立即处理**：CQ-001（下载重试失效 + 误删已提交文件，已可执行复现，修复约 3 行；回归测试源码见附录 A.1，可直接转正）。
- **结构性风险**：无新的结构性风险；但 `docs/specs/` 权威文档与代码漂移（CQ-007）在本仓库"文档即门禁"的工作流下值得警惕——64eb027 大重构后 spec 未同步。

---

## 2. Repository & Git Baseline

| 项 | 值 |
|---|---|
| Branch | `main` |
| HEAD | `64eb027b434f24c39e0d9cf7045456716b007c2d` |
| 初始工作区 | 干净，仅既有未跟踪 `docs/automatic/.work/`（前次审计遗留） |
| 技术栈 | Kotlin + Jetpack Compose + Hilt + Room v4 + Media3 + JSch(SFTP) + jcifs(SMB)，MVVM/MVI，单 Activity，协程/Flow |
| 构建/测试环境 | Windows + `D:/codeDev/.audit-tools`（JDK 21 / Android SDK 35）。**无模拟器/无设备**——所有验证为 JVM 静态证明 + JVM 单测复现 |
| 既有审计基线 | BUG-001..022 已修复于 `93c5f47`；AND-023..038 在未合并远端分支 audit/android-quality（本审计未重复报告） |
| 本次审计重点 | `93c5f47` 之后的 10 个提交（a3196c0…64eb027），尤其 64eb027 存储大重构（弃 SAF 外置卷与静态加密）与 a3196c0 结构化终态 |

**仓库地图**（理解 Finding 的最小背景）：
- `sync/` — `SyncEngine`（全局 `storageOperationMutex` + relation 级 mutex 双层调度）；`SyncDownloadPipeline`（3 并发下载 worker、BUG-007 三阶段提交清理状态机 NOT_FINALIZED/FINALIZE_UNCERTAIN/COMMITTED）；`SyncRemoteScanner`（E8 扫描守卫 + dryRun 计划生成）；`SyncDeleteExecutor`；`SyncPendingTaskManager`（待执行任务持久化）；`SyncService` + `SyncServiceDrainCoordinator`（前台服务消费 pending 任务）；sftp/smb 客户端
- `storage/` — `MediaFileStore`（`.tmp` 写入 → close 校验长度 → 原子 rename 两阶段提交；加密已在 64eb027 移除）
- `data/` — Room v4（`fallbackToDestructiveMigration(dropAllTables=true)`）、6 实体、DAO/Repository 层
- `browser/ player/ imageviewer/ settings/ security/ provider/` — Compose UI + ViewModel 层
- `docs/specs/` — 权威架构/同步/存储/约定文档（本仓库以其为 agent 门禁）

---

## 3. Audit Methodology & Verification Protocol

本节说明证据的产生方式与标签含义，使报告可独立评判。

### 3.1 执行结构

无人值守审计，主 Agent + 4 个子 Agent 分两波：

- **Wave 1**（并行）：Agent 1（架构与可维护性，静态）+ Agent 2（正确性与边界，静态）。主 Agent 同时建立仓库地图、Git 基线、构建/测试基线（compileDebugKotlin / testDebugUnitTest / lintDebug 全绿），并对候选 Finding 逐行复核源码。
- **Wave 2**（并行）：Agent 3（可执行验证——唯一允许跑 gradle 的角色，把候选 Finding 写成定向 JVM 单测并运行）+ Agent 4（对抗复核——职责是**证伪**其他 Agent 的 Finding：检查可达性、调用方保护、类型/框架保护、是否误读协程/Room/StateFlow 语义、是否夸大影响）。

### 3.2 Finding 状态机与置信标签

```
DISCOVERED → INVESTIGATING → REPRODUCED 或 STATICALLY_PROVEN
           → ADVERSARIALLY_REVIEWED → MAIN_AGENT_RECHECKED → VERIFIED
```

子 Agent 最多只能声明到 REPRODUCED/STATICALLY_PROVEN；最终 VERIFIED 由主 Agent 在三重验证（发现 + 复现/证明 + 对抗反证）后赋予。被证伪 → REJECTED（§8）；证据不足 → INCONCLUSIVE（§9）。

报告中标签的含义：
- **REPRODUCED**：隔离 JVM 单测中，"断言正确行为"的测试在 HEAD 上**失败**（失败 = bug 在场）；同测试类的 bug-shape/control 测试在 HEAD 上**通过**，钉住确切的错误可观察值。断言遵循仓库约定（只断言枚举/计数/调用，不断言用户文案）。
- **STATICALLY_PROVEN**：确定性逻辑链 + 主 Agent 对每行代码的直接核对（非直觉推断）。
- **UPHELD / WEAKENED / REFUTED**：对抗复核结论——攻击失败（Finding 站得住）/ 部分成立（残余声明单独列出）/ 被证伪。

### 3.3 测试编写协议

复现测试以唯一前缀 `Audit20260913` 命名、放于既有测试树、只新增不改既有文件；运行后**全部删除**（`find app/src -name "Audit20260913*" | wc -l` → 0），源码完整收录于本报告附录 A（可随时重建重跑）。删除后全量回归 581 测试 0 失败，证明无干扰。

---

## 4. Audit Scope

**已覆盖**（全文件精读 + 验证）：
- sync 全协作类群（Engine/Pipeline/Scanner/DeleteExecutor/PendingTaskManager/DrainCoordinator/SpaceChecker/ProgressPublisher/NotificationHelper）
- MediaFileStore + tmp probe + verifyReadable（两阶段提交完整性）
- Room v4 schema、迁移、DAO（VideoItemDao 重点）
- SftpFileClient / SmbFileClient（异常映射、取消检查点、流关闭）
- SyncPreviewViewModel、SyncRelationEditViewModel、SyncSourceEditViewModel、GainAnalysisViewModel、StorageManagementViewModel、SettingsViewModel
- ImageViewerViewModel、PlayerViewModel（对照）、FileOperationsUseCase、UndoDeleteUseCase
- MediaContentProvider、AppLockManager/BiometricAuthManager
- docs/specs/* 与代码的一致性
- Git 历史（pipeline/scanner/preview 的演进考古）

**未覆盖 / 不可覆盖**：
- 设备/模拟器运行时行为（本机无 emulator）——分享 MIME 的接收方实际表现（§9 INC-002）、通知栏视觉抖动（§9 INC-001）只能静态证明
- androidTest（真实 in-memory Room 迁移测试）需设备
- UI 渲染/手势的实机表现（f8fc00e 的 edge-swipe 仅静态过目）
- `debug/DebugLogger`、`audio/AudioGainAnalyzer` 的 DSP 正确性仅静态过目；Gradle/CI 脚本未逐行审计

---

## 5. Commands & Tests Executed

| 命令 | 结果 |
|---|---|
| `./gradlew compileDebugKotlin compileDebugUnitTestKotlin` | BUILD SUCCESSFUL（exit 0） |
| `./gradlew testDebugUnitTest`（基线） | **70 个测试类、581 个测试，0 失败 0 错误** |
| `./gradlew lintDebug` | BUILD SUCCESSFUL（exit 0，无 abort） |
| `testDebugUnitTest --tests "*Audit20260913*"`（5 组定向复现，逐类隔离运行） | 见下表：5 个 repro 测试按预期**失败=复现 bug**，配套 bug-shape/control 测试通过 |
| `./gradlew testDebugUnitTest --rerun`（删除临时测试后全量回归） | 581/581 全绿，无干扰 |

**复现测试明细**（完整源码见附录 A）：

| 组 | 测试类 | 隔离运行结果 |
|---|---|---|
| V1 | `sync.Audit20260913V1DownloadRetryTest`（2 tests） | repro 失败（= CQ-001 在场）；bug-shape 通过 |
| V2 | `sync.Audit20260913V2StaleTerminalTest`（3 tests） | repro 失败（= CQ-002 在场）；bug-shape + control 通过 |
| V3 | `imageviewer.Audit20260913V3ImageRaceTest`（2 tests） | repro 失败（= CQ-003 在场）；control 通过 |
| V4 | `settings.Audit20260913V4DeleteCrashTest`（3 tests） | repro 失败（= CQ-004 在场）；bug-shape + control 通过 |
| V5 | `browser.Audit20260913V5SkippedCountTest`（2 tests） | repro 失败（= CQ-005 在场）；bug-shape 通过 |

> 测试环境说明：5 类在**同一次** gradle 调用中合并运行时，V4 故意逃逸的异常（即被复现的崩溃）会被 coroutines-test 全局跟踪记录，使后续测试类报 `UncaughtExceptionsBeforeTest`——这是复现测试集内部的跨类污染，非生产行为；逐类隔离运行为准，全部干净。

---

## 6. Verified Findings

### CQ-001 — 下载重试对"新文件 + 瞬时错误"完全失效：attempt-2 成功提交的文件被误删，误报 STORAGE_ERROR

**Severity**: P1 High
**Confidence**: REPRODUCED（三重验证：发现 → 可执行复现 → 对抗复核 UPHELD → 主 Agent 逐行复核）

**Location**: `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:308, 311, 342-352, 241-244, 417-427, 438-447`

**Relevant Git Context**: 重试白名单（P1-2）与 BUG-007 三阶段清理均出自既有整改；`ec83906` 拆分引擎协作类后该逻辑未再审视。`finalized == null` 处注释（:435-437）称触发场景是"并发删除"——但 `deleteMediaById`/`resetStorage`/`purgeOrphans` 全部持 `storageOperationMutex`，与 `executeSyncLocked` 互斥，注释声称的触发条件**不可达**；实际唯一可达触发路径就是本 bug 的陈旧 `assignedId`。

**Trigger**: 全新文件（本地无 DB 行、无文件）的 attempt-1 下载中途发生**任意瞬态失败**。SFTP/SMB 客户端把一切非 CE 传输异常包装为 `DOWNLOAD_FAILED`（如 `SftpFileClient.kt:284-289`），而它在重试白名单内：

```kotlin
// SyncDownloadPipeline.kt:696-703
private val RETRYABLE_CODES: Set<SyncErrorCode> = setOf(
    SyncErrorCode.TIMEOUT, SyncErrorCode.CONNECTION_REFUSED,
    SyncErrorCode.UNKNOWN_HOST, SyncErrorCode.NETWORK_UNAVAILABLE,
    SyncErrorCode.DOWNLOAD_FAILED, SyncErrorCode.UNKNOWN
)
```

弱网断流正是重试机制设计的目标场景。

**Execution Path**（关键代码摘录，每段均经主 Agent 逐行核对）：

```kotlin
// :308-311 —— existing/assignedId 在 attempt 循环外只读一次
existing = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)  // 新文件 → null
assignedId = existing?.id ?: 0L                                                      // → 0L

// :342-343 —— 占位行仅当 assignedId==0 时创建
if (assignedId == 0L) {
    assignedId = videoRepository.upsert(VideoItemEntity(... fileSize = 0 ...))       // attempt-1 → 新 id

// :237-244 —— attempt-1 瞬态失败后的 NOT_FINALIZED 清理
AttemptPhase.NOT_FINALIZED -> {
    guarded { store.cancel(id) }
    if (existing == null) {
        guarded { videoRepository.deleteById(id) }   // ← 占位行被删；assignedId 未重置（全文件仅 :311/:343 两处赋值）
    }
}

// :417-426 —— 瞬态错误 → 重试（assignedId 已是陈旧的旧 id）
withContext(NonCancellable) { cleanUpAttempt(attemptPhase, assignedId) }
if (!isRetryableSyncException(e)) throw e
if (attempts >= 3) throw e
delay(1000L * attempts)                              // → attempt-2

// attempt-2：assignedId != 0 ⇒ 占位行【不再重建】
// 下载 → finalize → verifyReadable 全部成功 → uncleanFinalizedId = assignedId

// :438-447 —— 元数据窗口
val finalized = videoRepository.getById(assignedId)  // → null（行已在 attempt-1 清理中删除）
if (finalized == null) {
    withContext(NonCancellable) { cleanUpAttempt(AttemptPhase.COMMITTED, assignedId) }
    // ↑ :275-278 COMMITTED 分支 → store.delete(id) —— 删掉刚刚成功提交的文件
    throw SyncException(SyncErrorCode.STORAGE_ERROR, ...)
}
// → 外层 catch :491 计 failedDownloadCount；同步终态 PARTIAL_FAILED
```

**Reproduction / Verification**（附录 A.1）：有状态 fake 仓库（`LinkedHashMap`，upsert 分配新 id、deleteById 真删行——既有 `SyncEngineBoundaryTest` 用无条件 mock `getById(...) returns saved`，把"被删的行"建模成"永远存在"，这正是该 bug 长期未被发现的原因）。客户端 `downloadTo` 第 1 次抛 `SyncException(DOWNLOAD_FAILED)`、第 2 次成功。断言正确行为的 repro 测试在 HEAD 失败，失败信息完整呈现 bug 形态：

```
AssertionError: sync must succeed when retry attempt 2 completes the transfer
(result=false, storeCalls=[openSink:1, cancel:1, openSink:1, finalize:1, verify:1, delete:1],
 publisher=SyncProgress(... completedFiles=0, failedFiles=1, phase=PARTIAL_FAILED ...))
```

store 调用序列逐项对应：attempt-1 `NOT_FINALIZED` 清理（`cancel`）→ attempt-2 完整提交链（`openSink→finalize→verify` 全部成功）→ `delete`（误删已提交文件）。bug-shape 测试（HEAD 上通过）另锁定：占位行最终不存在（`rows.isEmpty()`）、`store.delete(1)` 恰好调用 1 次、有序调用列表与上述完全一致。

**Expected Behavior**: attempt-2 重试成功，占位行重建（或行不删），最终计 SUCCESS。
**Actual Behavior**: 重试永不可能成功（attempt-3 同样撞 `finalized == null`）；成功传输的字节被删除；错误分类为 STORAGE_ERROR 而非瞬态码；同步终态 PARTIAL_FAILED；下次 dryRun 重新规划并从头重下。
**Root Cause**: `cleanUpAttempt` 删除占位行后未重置 `assignedId`，而占位行创建条件 `if (assignedId == 0L)` 使 attempt-2+ 永远跳过重建。
**Impact**: 新文件 + 一次网络抖动 ⇒ 重试机制完全失效（核心功能弱网路径）+ 带宽浪费 + 错误误分类。无永久数据丢失（下次 dryRun 收敛）。注意不对称性：进程崩溃遗留的占位行（existing != null → 行保留）反而能正确重试——证明该不对称非设计意图。
**Recommended Fix**: 在 `cleanUpAttempt` 删除占位行的三处（:243/:260/:271）同步 `assignedId = 0L`（或 attempt 循环头部重读 `existing`）。
**Recommended Regression Test**: 附录 A.1 源码直接转正（改名为 `SyncDownloadPipelineRetryTest` 之类放入 `app/src/test/.../sync/`）。
**Adversarial Review**: 攻击"deleteById 是否真删（DAO 层核实为真实 `DELETE FROM`，`VideoItemDao.kt:85-86`）/ 是否有隐藏的 assignedId 重置（全文件 rg 确认无）/ existing 是否实际非 null 使 bug 不可达（仅进程崩溃遗留行，反而是安全路径，恰证不对称非意图）"均失败；CE 路径安全（CE 在 :416 直接退出重试循环，无 attempt-2），与本 bug 无关。

---

### CQ-002 — 同屏再次点"开始同步"：立即消费上一次运行的陈旧终态，新同步"假完成"且无头执行

**Severity**: P2 Medium
**Confidence**: REPRODUCED（三重验证：发现 → 可执行复现 → 对抗复核 UPHELD → 主 Agent 复核）

**Location**: `app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:161-210`；按钮门控 `SyncPreviewScreen.kt:169-170`

**Trigger**: 同一预览屏实例上，同步 #1 以 PARTIAL_FAILED/FAILED/CANCELLED 结束（`progress` StateFlow 保留该终态，`relationId` 匹配）→ 非 COMPLETED 终态不清空 `_toDownload`/`_toDelete`（:192-194 仅 COMPLETED 清）→ 确认按钮保持可用（`previewLoaded && !isSyncing && lists non-empty`）→ 用户再点。

**Execution Path**（代码摘录）：

```kotlin
// SyncPreviewViewModel.kt:184-202
private fun observeSyncCompletion() {
    viewModelScope.launch {
        val result = syncEngine.progress.first { p ->
            (p.relationId == relationId || p.relationId == 0L) &&
                (p.phase == SyncPhase.COMPLETED || p.phase == SyncPhase.PARTIAL_FAILED ||
                 p.phase == SyncPhase.FAILED || p.phase == SyncPhase.CANCELLED)
        }   // StateFlow.first 先评估【当前值】→ 上一次运行的陈旧终态立即命中
        _isSyncing.value = false
        if (result.phase == SyncPhase.COMPLETED) { _toDownload.value = emptyList(); _toDelete.value = emptyList() }
        else if (result.phase == SyncPhase.PARTIAL_FAILED) { _error.value = context.getString(...) }
        ...
        _syncComplete.value = true
    }
}
```

`startSync()`（:161-175）先 `setPendingTask`（**真实任务入队，将由 SyncService 执行**）再调 `observeSyncCompletion()`——而新运行的第一次进度写入要等 服务启动→binder→drain→全局 mutex 之后，必然晚于这次同步消费。全仓唯一的 `resetProgress()` 调用点是 `loadPreview`（:121），且 `loadPreview` 仅由用户手动点按钮触发（`SyncPreviewScreen.kt:147,216,351`），无自动 LaunchedEffect——同屏路径无任何重置。

**Reproduction / Verification**（附录 A.2）：预置 `progress = SyncProgress(PARTIAL_FAILED, relationId=1)`，调 `startSync()`。repro 测试在 HEAD 失败：

```
AssertionError: isSyncing must remain true after startSync until a NEW terminal arrives
(actual isSyncing=false, syncComplete=true)
```

bug-shape 测试（HEAD 上通过）钉住完整形态：陈旧终态瞬间被消费（`isSyncing=false`、`syncComplete=true`）→ 随后注入 sync #2 的 `DOWNLOADING` 进度，UI 无反应（无头运行）→ 注入新的 `COMPLETED` 终态，`toDelete` 列表不被清空（终态从未被处理）。control 测试证明从非终态（IDLE）出发时机制完全正常——回归专属于"陈旧终态作为 first{} 的当前值被消费"。

**Expected**: 新同步运行期间 `isSyncing=true`，新终态到达才翻转。
**Actual**: 界面谎报完成并重显旧错误，同步 #2 无头运行（无进度 UI、无终态观察）；用户可能再次操作/重复入队（drain 协调器会顺序执行，不致数据损坏）。跨屏重进安全（新 VM + 强制 loadPreview），COMPLETED 终态也安全（列表清空、按钮禁用）。
**Root Cause**: 缺少运行代际（epoch/generation）区分；`StateFlow` 保留旧终态且 `first{}` 先读当前值。
**Recommended Fix**: `startSync` 入口处若引擎非运行态则 `resetProgress()`；或 `observeSyncCompletion` 只接受启动后新出现的终态（先读并丢弃当前值 / 引入 run id）。
**Recommended Regression Test**: 附录 A.2 源码转正。
**Adversarial Review**: 攻击"是否每次进屏必经 loadPreview 重置"失败——`loadPreview` 仅按钮触发，同屏按钮再点是真实可达路径；`relationId == 0L` 备选子句无法挽救（终态携带真实 id）。

---

### CQ-003 — ImageViewerViewModel.loadImage 无请求代际守卫：快速滑动/双击时陈旧图片覆盖新图片

**Severity**: P3 Low
**Confidence**: REPRODUCED（发现 → 可执行复现 → 对抗复核 UPHELD）

**Location**: `app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:96-115`；调用点 `ImageViewerScreen.kt:164,173,192,201`（滑动/双击直接调用，无节流）

**Execution Path**（代码摘录 + 仓库内对照）：

```kotlin
// ImageViewerViewModel.kt:96-107 —— 无代际守卫
fun loadImage(imageItemId: Long) {
    viewModelScope.launch {
        val image = videoRepository.getById(imageItemId)   // Room 并发查询执行器，完成顺序不定
        if (image != null) {
            val record = playbackRepository.getByVideoId(image.id)
            _uiState.update { it.copy(currentImage = image, ...) }   // 无条件提交
```

对照 `PlayerViewModel.kt:242-268`——本仓库已把同类问题定性为 **BUG-015** 并修复：`loadGeneration = AtomicLong(0L)`，发起即占代，旧代次在任何挂起点恢复后于提交前被丢弃：

```kotlin
// PlayerViewModel.kt:244,247,268（BUG-015 修复模式）
private val loadGeneration = AtomicLong(0L)
val generation = loadGeneration.incrementAndGet()
...
if (generation != loadGeneration.get()) return@launch   // 旧代次迟到，丢弃
```

图片查看器是未同步修复的同类站点。乱序完成后，`getPrevImageId/getNextImageId` 以错误的 `currentImage.id` 为基准索引 `_allImageIds`（:117-129），prev/next 从错误位置跳转。

**Reproduction / Verification**（附录 A.3）：`CompletableDeferred` 门控两个 `getById`；`loadImage(1)` → `loadImage(2)` → 先放行 2 再放行 1。repro 测试在 HEAD 失败：

```
AssertionError: currentImage must still be image 2 (stale load(1) must not overwrite it);
actual currentImage.id=1 expected:<2> but was:<1>
```

control 测试（顺序完成）通过——正常路径无回归，专属乱序场景。

**Expected**: 用户导航到图片 2 后，迟到的 load(1) 不得覆盖显示。
**Actual**: 显示退回图片 1，后续导航索引错位。
**Impact**: UX 层面（低频：需 IO 重负载下乱序，如 3 通道同步进行中），无数据损坏。
**Recommended Fix**: 移植 `loadGeneration` 模式（publish 前校验代际）。
**Recommended Regression Test**: 附录 A.3 源码转正。
**Adversarial Review**: 攻击"是否有手势节流/顺序保证"失败——滑动按拖拽完成逐次触发、双击区按次触发，全部直呼 `loadImage`；确认 Player 侧守卫存在而此处缺失，属一致性缺口而非设计选择。

---

### CQ-004 — SyncRelationEditViewModel.deleteRelation 无异常处理：存储层异常直接崩溃进程

**Severity**: P3 Low
**Confidence**: REPRODUCED（发现 → 可执行复现 → 对抗复核 UPHELD）

**Location**: `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:375-381`

**Execution Path**（代码摘录）：

```kotlin
fun deleteRelation() {
    viewModelScope.launch {
        if (relationId <= 0) return@launch
        syncRelationRepository.delete(relationId)   // ← 无 try/catch；Room DELETE 可抛（磁盘满 SQLiteFullException 等）
        _saveResult.value = RelationSaveResult.Deleted
    }
}
```

触发条件现实可信：本应用自带 `SyncSpaceChecker` 恰因"磁盘会满"而存在。全仓 rg 确认无任何 `CoroutineExceptionHandler` / 自定义 viewModelScope 工厂——异常到达默认未捕获处理器 = 进程崩溃。同文件 `save()`（:365-371）与姊妹 `SyncSourceEditViewModel.deleteSource`（:157-177）均有 CE-first + 泛型 catch + 类型化失败结果，唯独此路径裸奔，违背 conventions.md 错误处理一致性。

**Reproduction / Verification**（附录 A.4）：`coEvery { delete(5L) } throws RuntimeException("sqlite boom")`，以捕获式 `Thread.setDefaultUncaughtExceptionHandler` 观测（Android 上逃逸 viewModelScope 的异常正是走该路径 → 崩溃）。repro 测试在 HEAD 失败：

```
AssertionError: repository exception must be contained (handled as state, no uncaught crash);
uncaught=[RuntimeException:sqlite boom]
```

bug-shape 测试（HEAD 上通过）钉住：恰好 1 个未捕获异常且即仓库异常本体、`saveResult` 保持 null（协程死于赋值前）。control 测试：成功路径设 `Deleted`、零未捕获。

**Recommended Fix**: 照 `deleteSource` 的 catch 形状补类型化失败结果。
**Recommended Regression Test**: 附录 A.4 源码转正。
**Adversarial Review**: 确认无任何上游兜底；Room DELETE 在 WAL 写入失败时可真实抛出；"崩溃即反馈"无任何文档支持。

---

### CQ-005 — 文件夹删除部分失败文案中 SkippedNotFound 同时计入 success 与 skipped，计数总和超过条目数

**Severity**: P3 Low
**Confidence**: REPRODUCED（发现 → 可执行复现 → 对抗复核 UPHELD）

**Location**: `app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:356-360`；用户可见面 `strings.xml:387`（`error_folder_delete_partial`："成功 %1$d，跳过 %2$d，失败 %3$d"）

**Execution Path**（代码摘录）：

```kotlin
is DeleteMediaResult.SkippedNotFound -> {
    skipped++
    success++      // ← 双重计数：同一项同时进入两个互斥分类
}
```

**Reproduction / Verification**（附录 A.5）：2 项文件夹——id 1 文件已缺失（→ SkippedNotFound）、id 2 `exists()` 抛 IOException（→ Failed）；经 `getString` 的数值格式参数捕获计数（不断言文案）。repro 测试在 HEAD 失败：

```
AssertionError: success+skipped+failed must equal the item count (2); actual success=1 skipped=1 failed=1
— SkippedNotFound is double-counted as both success and skipped expected:<2> but was:<3>
```

bug-shape 测试（HEAD 上通过）：`success=1, skipped=1, failed=1`——实际删除 0 项却报 1 成功。测试还记录了两个 harness 陷阱（附录 A.5 注释内）：relaxed mockk 对 `getByLocalBasePath` 返回非空链式 mock 会误触"不能删根目录"守卫，需显式 stub null。

**Recommended Fix**: SkippedNotFound 分支删除 `success++`。
**Recommended Regression Test**: 附录 A.5 源码转正。
**Adversarial Review**: 逐行确认双递增真实存在；三计数经同一字符串资源插值同屏展示，用户可直接算出 2+1=3>2 的矛盾。

---

### CQ-006 — dryRunDelete 与 resetProgress 绕过全局 storageOperationMutex：可跨关系破坏正在运行的同步进度显示

**Severity**: P3 Low（对抗复核后从"潜在 TOCTOU"降级为"显示层破坏"，见 §8 RH-002）
**Confidence**: STATICALLY_PROVEN（主 Agent 逐行复核锁拓扑与 publisher 合并逻辑；对抗复核 WEAKENED——残余声明成立）

**Location**: `sync/SyncEngine.kt:145-177`；`sync/SyncRemoteScanner.kt:26-27,196`；`sync/SyncProgressPublisher.kt:305-325`

**Execution Path**（锁拓扑摘录，主 Agent 逐行核对）：

```kotlin
// SyncEngine.kt:145-177 —— 锁不对称
suspend fun dryRun(relationId: Long): DryRunResult =
    storageOperationMutex.withLock {                      // ← 全局锁
        getMutexForRelation(relationId).withLock { remoteScanner.dryRunLocked(relationId) }
    }

fun resetProgress() {                                    // ← 无任何锁
    progressPublisher.reset()
}

suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?> =
    getMutexForRelation(relationId).withLock {           // ← 仅 relation 锁，缺全局锁
        remoteScanner.dryRunDeleteLocked(relationId)
    }

suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean =
    storageOperationMutex.withLock { ... }               // ← 对照：执行路径持全局锁
```

`SyncRemoteScanner.kt:26-27` 的注释自认该不对称是"从旧实现保留"。场景：`consumeAndExecute(A)`（`SyncEngine.kt:121`，持全局锁）执行 A 的同步期间，用户经预览屏内置关系下拉切到 B、DELETE 页签、点预览 → `dryRunDelete(B)` 只需 B 的 relation 锁即可进入 → 扫描器写入全新 `SyncProgress(SCANNING, relationId=B)`（`SyncRemoteScanner.kt:196`）→ publisher 合并逻辑（`SyncProgressPublisher.kt:305-325`）取所有 requested 与 baseline 不同的字段 → A 的 `completedFiles/transferredBytes/isRunning` 归零、relationId 翻转为 B——正在下载的 A 的通知/进度被瞬间"重置"。`loadPreview` 的无门控 `resetProgress()`（`SyncPreviewViewModel.kt:121`）是同类更广的无锁写。`SyncEngine.isRunning`（:143）读的正是该 publisher，`StorageManagementViewModel` 的 `canReset` 门控依赖它。

**Impact**: 显示层破坏（500ms flush 内自愈，终态写入是全新值、正确）；"trash-scan-during-reset 旧计划删除远端文件"的更深后果经对抗复核判定在当前导航图下**不可达**（§8 RH-002）。
**Recommended Fix**: `dryRunDelete` 包一层 `storageOperationMutex.withLock`（与其姊妹 `dryRun` 对齐）；`resetProgress` 过锁或 isRunning 时拒绝。
**Recommended Regression Test**: 门控 JVM 测试——A 的 executeSync 进行中驱动 `dryRunDelete(B)`，断言 A 的进度单调不减。
**Adversarial Review**: 锁不对称、publisher 归零机制、经下拉切关系的可达性（`SyncPreviewScreen.kt:82-97`）均被确认；仅深层 TOCTOU 场景被导航图排除（预览屏无前进路径到设置屏、`popUpTo(inclusive)` 清 VM、重进必重建计划且 DB 已空）。

---

### CQ-007 — 权威 spec 文档与代码漂移（64eb027/a3196c0 后未同步）

**Severity**: P3 Low（文档层，但本仓库以 specs 为 agent 门禁的权威来源，杠杆高）
**Confidence**: STATICALLY_PROVEN（主 Agent 直接事实核验）

**Location**: `docs/specs/conventions.md:24,27,59,67`、`docs/specs/sync.md:31` vs 对应代码

**Evidence**（逐项核验）:

| 文档声明 | 代码实际 |
|---|---|
| conventions.md:24 "Room, schema **v2**" | `AppDatabase.kt:27` `version = 4` |
| conventions.md:59 "18 SyncErrorCode" | 实际 **17** 个（sync.md:42 已更正为 17，两权威文档互相矛盾） |
| conventions.md:27 "VideoItemEntity（15 列）" | 实际 17 列 |
| conventions.md:67 引用 `EncryptedMediaStore` | 类已在 64eb027 删除（行为并入 `MediaFileStore.checkExistence`） |
| sync.md:31 描述 `SyncSpaceChecker` 为 "cipherLengthFor 预检 + UnknownAvailableSpace/CipherSizeComputationFailed…" | 加密删除后实际仅 plain-size 检查、Result 仅 Ok/Insufficient（`SyncSpaceChecker.kt:36-44`） |

**Impact**: 未来维护者/agent 按虚假不变体验证改动。
**Recommended Fix**: 一次性对齐五处；将"spec 同步"纳入 release.sh 发版门禁清单。

---

## 7. Architecture & Maintainability Observations

（Suggestion 级——真实、静态可证，但非已确认 bug；不占 CQ 编号）

1. **引擎内 locale 文案入库（a3196c0 半迁移）**：`SyncDownloadPipeline.kt:513,577-583,677` 与 `SyncDeleteExecutor.kt:113,223` 仍把 `syncErrorMessage(e, context)` 解析的本地化文案写入 `sync_log` 表（`AboutScreen.kt:169` 直接渲染），而同一 commit 的结构化终态注释（pipeline:664、executor:210）明确写"不再拼文案；文案由展示层映射"。后果：换语言后历史日志文案冻结；数据层依赖 Context 生成展示文案（违背 conventions "Repository 层不依赖 Context"）；同一功能两套并行错误持久化模型。建议 sync_log 也存 `code.name`、渲染时映射。
2. **Throwable→SyncErrorCode 回退映射 4 处重复**：`ErrorMessageMapper.kt:61-66`（权威）、`SftpFileClient.kt:64-66`、`SyncRelationEditViewModel.kt:268-274`、`SyncSourceEditViewModel.kt:52-54`（后两处由 87cf9c4 添加，注释自认"镜像"）。建议提取 `Throwable.toSyncErrorCode()`。
3. **`SyncAction.relativePath` 双语义隐式契约**：DOWNLOAD 载 remote-stripped 路径、DELETE 载 local-prefixed 路径（`SyncRemoteScanner.kt:105` vs `:138,215`），消费者各自换算（pipeline:285/:555、executor:97），pending 表仅靠 `actionGroup` 0/1 区分。类型系统、schema、spec 均未记录该约定；违反时的失败模式是**静默 fail-open**（DELETE_LOCAL 尾部查无此行→按"已删除"计 SUCCESS，`SyncDownloadPipeline.kt:585-596`）。建议类型层面区分，并补一条贯穿 DAO 的 schema 回归测试。
4. **SyncDownloadPipeline 重构残留**：`toActuallyDownload = toDownload` 恒等别名 + 同一列表双重 `isNotEmpty()` 判断（:159,:199-201，64eb027 删除卷过滤后的疤痕）；worker 通道硬编码 `Dispatchers.IO`（:216）违背 `@IoDispatcher` 注入约定（conventions.md:32），也是虚拟时间不可控测试成本的直接来源；`strings.xml:293` `sync_disk_space_insufficient` 零引用；`SyncSpaceChecker.formatSize` 与 `ui.util.formatFileSize` 重复。
5. **FileOperationsUseCase 文档腐烂**：`deleteFolderContentsLocked` KDoc 仍描述已删除的卷所有权/密文语义并引用不存在的 `deleteMediaById`（:337-341）；`toggleTrashedChecked` KDoc 逐字重复两遍（:148-163）；同类内新旧两套错误模型（callback+字符串 vs 类型化 suspend）并存。
6. **引擎层反向依赖 UI 包**：`SyncDownloadPipeline.kt:22-23`、`ErrorMessageMapper.kt:5` import `ui.util.*`（`extractParentPath` 还是 DB `parentPath` 索引列的计算来源）——违背 architecture.md 分层；建议把纯函数下沉到共享包，顺带解决观察 2/4 的重复。
7. **MediaContentProvider.getType 与 ui.util.VideoUtils.getMimeType 分歧**（对抗复核定为 polish）：provider 对 jpg 返回非注册 MIME `image/jpg`（应为 `image/jpeg`）、mkv/avi/ts 返回具体值而 ui.util 返回 `video/*`（`MediaContentProvider.kt:138-145` vs `ui/util/VideoUtils.kt:28-49`）。Intent 解析与 chooser 过滤用 Intent 自带 type（正确），仅严格比对 `resolver.getType()` 的接收方可能降级处理（实际危害见 §9 INC-002）。一行别名表对齐即可。
8. `PlayerScreen.kt:407` `gestureView.edgeBackEnabled = true` 在组合体内直接赋值（f8fc00e 引入），与未合并 AND-036 同模式——建议一并处理。

---

## 8. Rejected Hypotheses

**RH-001（原候选，Agent 2 提出）— GainAnalysisViewModel finally 竞态打破 single-flight**
最初怀疑 `launchAnalysis` 的旧 job `finally { _isAnalyzing = false }`（`GainAnalysisViewModel.kt:224-227`）会在新 job 置 true（:142-143）之后落地，使 `retryFailed` 的重入门控（:128）失效、双分析并发。对抗复核证伪：机制真实存在（Main.immediate 下新 job 同步置 true，被取消旧 job 的 finally 仅被投递到主队列、必然后落地），但**所有入口都被 UI 门控**——`startAnalysis`/discover 按钮在 `!isAnalyzing` 才启用（`GainAnalysisScreen.kt:94,108-109`），retry 按钮均在 `!isAnalyzing` 分支内（:187,265,294,306）；`cancelAnalysis` 置空 job 句柄（:232-233）使后续 launch 不构成 supersede；竞态前提（isAnalyzing==false 且 job 活跃）只能由竞态本身创造——循环依赖，永不自举。降级为可选加固（startAnalysis 补 VM 级守卫）。

**RH-002 — trash-scan 与 resetStorage 交错导致旧计划删除远端文件**
（CQ-006 的深层场景）最初怀疑预览屏持有的 `_toDelete` 旧计划可在 resetStorage 清库后仍执行 DELETE_BOTH、executor 对 `videoItem == null` 仍删远端（`SyncDeleteExecutor.kt:98-99` 属实——`client.deleteFile` 无条件执行）。对抗复核证伪可达性：当前导航图中预览屏到设置屏无前进路径，关系切换 `popUpTo(inclusive=true)` 清 VM，重进必经新 dryRunDelete（此时 DB 已空 → 空计划）。代码形状保留为 CQ-006 的"潜在风险"注脚——若未来导航图改动（例如预览屏加入设置入口）将直接激活。

**RH-003 — "finalized == null 是并发删除所致"（CQ-001 处代码注释自身的解释）**
对抗复核 + 主 Agent 确认：`deleteMediaById`/`resetStorage`/`purgeOrphans` 全部持 `storageOperationMutex`，与 `executeSyncLocked` 互斥——注释声称的触发条件不可达。真实触发路径是 CQ-001 的陈旧 assignedId（该结论并入 CQ-001 证据，不独立成 Finding）。

---

## 9. Inconclusive Investigations

**INC-001 — SyncProgressPublisher 跨关系 clobber 的通知层表现**：CQ-006 证明了 StateFlow 层的字段归零与恢复（500ms flush 内自愈、终态正确），但系统通知栏在 clobber 窗口内的实际视觉抖动无法在本机（无设备）观察。修复 CQ-006 后此问题自然消失，无需单独调查。

**INC-002 — 严格 MIME 接收方对 `image/jpg` 的真实降级行为**（观察 7）：需要设备 + 真实接收方（Gmail/Drive）实验。静态证据已充分（两处实现字符串分歧确证），仅"实际危害程度"未知。

---

## 10. Tests / Build / Tooling Results

| 门禁 | 结果 |
|---|---|
| compileDebugKotlin + compileDebugUnitTestKotlin | ✅ PASS |
| testDebugUnitTest（基线，70 类） | ✅ 581/581 |
| lintDebug | ✅ PASS（无 abort） |
| 5 组定向复现（Audit20260913*，隔离运行） | repro 全部按预期失败（= bug 在场），bug-shape/control 通过 |
| 清理临时测试后全量回归（--rerun） | ✅ 581/581，无干扰 |

**测试体系评价**：JVM 单测覆盖广、约定执行严格（枚举/计数断言、无文案断言）。最大盲区由 CQ-001 暴露——`SyncEngineBoundaryTest` 的无条件 `getById` stub 把"被删的行"建模成"永远存在"，mock 掩盖了真实状态机；`SyncDownloadPipelineTest` 中已有正确的有状态 fake 模式（LinkedHashMap），值得推广为仓库标准。

---

## 11. Coverage and Blind Spots

- **覆盖**：sync 全链路（含 sftp/smb 客户端）、存储提交面、Room v4、全部设置/预览/浏览/图片查看器 ViewModel、AppLock、Provider、specs 一致性、10 个新提交的 diff 级审读。
- **盲区**：设备运行时（分享 MIME、通知视觉、手势实机表现）；androidTest 迁移测试；DSP 正确性；CI 脚本逐行。
- **并发会话说明**：审计期间工作区出现另一会话的 `docs/automatic/20260913-2029_android-quality.md`（并行 android-quality 审计），本审计未读取、未触碰。

---

## 12. Prioritized Remediation Plan

| 优先级 | 项 | 动作 | 成本 |
|---|---|---|---|
| 1 | **CQ-001** | `cleanUpAttempt` 删占位行处（:243/:260/:271）同步 `assignedId = 0L`；附录 A.1 测试转正 | ~3 行 + 测试现成 |
| 2 | **CQ-002** | `startSync` 入口非运行态先 `resetProgress()`（或引入 run 代际）；附录 A.2 测试转正 | ~5 行 + 测试现成 |
| 3 | **CQ-006** | `dryRunDelete` 包全局锁；`resetProgress` 过锁或 isRunning 拒绝 | ~6 行 |
| 4 | **CQ-004 / CQ-005 / CQ-003** | 分别：补 catch 形状 / 删一处 `success++` / 移植 loadGeneration；附录 A.3–A.5 测试可转正 | 各 1-10 行 |
| 5 | **CQ-007** | specs 五处对齐；考虑把 spec 校验纳入 release.sh | 文档 |
| 6 | 观察 1-8 | 错误映射提取、sync_log 存码、relativePath 类型化、pipeline 残留清理、ui.util 下沉、MIME 别名表 | 择机分批 |

---

## 13. Audit Integrity

- **最终 `git status --short`**：仅 `?? docs/automatic/.work/`（本审计证据存档，**可选**补充材料——JUnit XML、隔离运行日志；本报告与附录 A 已自包含其全部核心内容）与并行会话的 `?? docs/automatic/20260913-2029_android-quality.md`（非本审计产物）、本报告自身。
- **生产代码**：零修改。**既有测试**：零修改。
- **临时实验**：5 个 `Audit20260913*` 复现测试文件已全部删除（`find app/src -name "Audit20260913*" | wc -l` → 0），源码完整收录于附录 A。
- **Git**：全程未执行任何变更类命令（无 commit/checkout/reset/stash/push）。
- **未清理残留**：无。

---

## 附录 A：可执行复现测试完整源码（V1–V5）

> 说明：以下为审计期间实际运行的测试逐字源码（原位于 `app/src/test/java/cn/vectory/syncplayer/<area>/Audit20260913*.kt`，运行后删除）。每个测试类的**第一个测试**断言正确行为（在 HEAD 上失败 = bug 在场，即复现），后续测试为 bug-shape/control（在 HEAD 上通过，钉住确切可观察值）。可直接转正为回归测试：重命名类名放入对应包，删除注释中的 "AUDIT 2026-09-13" 标记即可。
> 重跑命令：`JAVA_HOME=<JDK21> ./gradlew testDebugUnitTest --tests "<全限定类名>" --console=plain`

### A.1 V1 — CQ-001 下载重试失效（`sync` 包）

```kotlin
package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncLogRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.storage.MediaFileStore
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V1 / CQ-001: stale `assignedId` breaks in-run retry of NEW-file downloads.
 *
 * Claim under test (SyncDownloadPipeline.executeSyncLocked):
 *  - line ~308: `existing = getByRelativePathAndRelationId(...)` is read ONCE per action,
 *    BEFORE the attempt loop;
 *  - line ~311: `assignedId = existing?.id ?: 0L` is also computed ONCE, outside the loop;
 *  - line ~342: the placeholder row is (re)created only `if (assignedId == 0L)`;
 *  - attempt-1 transient failure (SyncException DOWNLOAD_FAILED — retryable per RETRYABLE_CODES)
 *    -> cleanUpAttempt(NOT_FINALIZED, id) deletes the placeholder row (existing==null)
 *    but does NOT reset `assignedId`;
 *  - attempt 2: assignedId != 0 -> placeholder row NOT recreated -> download + finalize +
 *    verifyReadable all SUCCEED -> getById(assignedId) at the metadata window returns null
 *    -> cleanUpAttempt(COMMITTED) calls store.delete(id) — DELETING THE JUST-COMMITTED FILE
 *    -> throws STORAGE_ERROR -> counted as a failed download.
 *
 * Consequence: for a NEW file whose first transfer attempt fails transiently, the in-run retry
 * can never succeed, and each retry round deletes a fully-committed file. The correct behavior:
 * attempt 2 recreates the placeholder row (or re-reads existing), the file is counted as
 * completed, and store.delete is never invoked.
 *
 * Test 1 asserts the CORRECT behavior (fails on HEAD = reproduction).
 * Test 2 documents the exact buggy shape observable on HEAD (passes on HEAD).
 */
class Audit20260913V1DownloadRetryTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncLogRepository = mockk<SyncLogRepository>(relaxed = true)
    private val clientFactory = mockk<RemoteFileClientFactory>()
    private val storagePaths = mockk<StoragePaths>()
    private val store = mockk<MediaFileStore>(relaxed = true)
    private val storeLazy = mockk<Lazy<MediaFileStore>> { every { get() } returns store }
    private val context = mockk<android.content.Context>(relaxed = true)
    private val publisher = SyncProgressPublisher()
    private val deleteExecutor = SyncDeleteExecutor(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, clientFactory, storagePaths, storeLazy, context, publisher
    )
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>(relaxed = true)
    private val imageMetadataExtractor = mockk<ImageMetadataExtractor>(relaxed = true)
    private val sut = SyncDownloadPipeline(
        syncRelationRepository, syncSourceRepository, videoRepository, playbackRepository,
        syncLogRepository, videoMetadataExtractor, imageMetadataExtractor,
        clientFactory, storagePaths, storeLazy, context, publisher, deleteExecutor
    )

    private fun dlAction(name: String = "f.mp4") = SyncAction(SyncActionType.DOWNLOAD, name, 1000L, 0L)

    /** Stateful fake repository (pattern: SyncDownloadPipelineTest orphan-ciphertext case). */
    private val rows = LinkedHashMap<Long, VideoItemEntity>()
    private val nextId = AtomicLong(1L)
    private val storeCalls = CopyOnWriteArrayList<String>()

    private fun stubBasics() {
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncSourceRepository.getById(10L) } returns SyncSource(
            id = 10L, name = "s", protocol = SyncProtocol.SMB, host = "h", port = 445,
            username = "u", password = "p"
        )
        every { storagePaths.toLocalRelativePath(any(), any()) } answers {
            val base = firstArg<String>()
            val rel = secondArg<String>()
            if (base.isNotEmpty()) "$base/$rel" else rel
        }
        every { store.usableSpace() } returns Long.MAX_VALUE
        coEvery { store.openWriteSink(any(), any()) } coAnswers {
            storeCalls.add("openSink:${firstArg<Long>()}")
            mockk<OutputStream>(relaxed = true)
        }
        coEvery { store.finalize(any()) } coAnswers {
            storeCalls.add("finalize:${firstArg<Long>()}")
            true
        }
        coEvery { store.delete(any()) } coAnswers {
            storeCalls.add("delete:${firstArg<Long>()}")
            true
        }
        coEvery { store.cancel(any()) } coAnswers {
            storeCalls.add("cancel:${firstArg<Long>()}")
            true
        }
        coEvery { store.verifyReadable(any(), any()) } coAnswers {
            storeCalls.add("verify:${firstArg<Long>()}")
        }
        // Stateful fake video repository: NEW file (no existing row); upsert assigns fresh ids.
        coEvery { videoRepository.getByRelativePathAndRelationId(any(), 1L) } coAnswers {
            rows.values.firstOrNull { it.relativePath == firstArg<String>() }
        }
        coEvery { videoRepository.getById(any()) } coAnswers { rows[firstArg<Long>()] }
        coEvery { videoRepository.upsert(any()) } coAnswers {
            val e = firstArg<VideoItemEntity>()
            if (e.id == 0L) {
                val id = nextId.getAndIncrement()
                rows[id] = e.copy(id = id)
                id
            } else {
                rows[e.id] = e
                e.id
            }
        }
        coEvery { videoRepository.deleteById(any()) } coAnswers { rows.remove(firstArg<Long>()) }
    }

    /**
     * Scenario: one NEW file; transfer attempt 1 fails transiently mid-transfer
     * (SyncException DOWNLOAD_FAILED — retryable), attempt 2 succeeds.
     */
    private fun stubFlakyThenSuccessClient(): RemoteFileClient {
        val client = mockk<RemoteFileClient>(relaxed = true)
        every { clientFactory.create(SyncProtocol.SMB) } returns client
        val downloadCalls = AtomicInteger(0)
        coEvery { client.downloadTo(any(), any(), any()) } coAnswers {
            if (downloadCalls.incrementAndGet() == 1) {
                throw SyncException(SyncErrorCode.DOWNLOAD_FAILED, SyncOperation.DOWNLOAD, path = "f.mp4")
            }
            true
        }
        return client
    }

    /** Test 1 (REPRODUCTION): asserts the CORRECT behavior — fails on HEAD. */
    @Test
    fun `in-run retry of new file after transient failure succeeds and never deletes committed file`() = runTest {
        stubBasics()
        stubFlakyThenSuccessClient()

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        // Correct behavior: attempt 2 transfers, finalizes, verifies, writes metadata -> success.
        assertTrue("sync must succeed when retry attempt 2 completes the transfer (result=$result, " +
            "storeCalls=$storeCalls, publisher=${publisher.value})", result)
        assertEquals(0, publisher.value.failedFiles)
        assertEquals(1, publisher.value.completedFiles)
        // The committed file must NEVER be deleted by cleanup.
        coVerify(exactly = 0) { store.delete(any()) }
    }

    /** Test 2 (bug shape on HEAD — documents the exact failure mode; passes on HEAD). */
    @Test
    fun `bug shape - attempt two commits file then storage error deletes it`() = runTest {
        stubBasics()
        stubFlakyThenSuccessClient()

        val result = sut.executeSyncLocked(1L, listOf(dlAction()), emptyList())

        // Attempt 2 DID complete the full commit path for the placeholder id (1):
        coVerify(exactly = 1) { store.finalize(1L) }
        coVerify(exactly = 1) { store.verifyReadable(1L, 1000L) }
        // ... yet the metadata window finds no row (deleted by attempt-1 cleanup, never recreated)
        assertTrue("placeholder row must be gone (stale assignedId never recreated it)", rows.isEmpty())
        // ... the sync is counted FAILED ...
        assertFalse(result)
        assertEquals(1, publisher.value.failedFiles)
        assertEquals(0, publisher.value.completedFiles)
        // ... and the JUST-COMMITTED file was deleted via the COMMITTED cleanup branch.
        coVerify(exactly = 1) { store.delete(1L) }
        // Ordered shape: cancel (attempt-1 NOT_FINALIZED cleanup) -> finalize -> verify -> delete.
        assertEquals(
            listOf("openSink:1", "cancel:1", "openSink:1", "finalize:1", "verify:1", "delete:1"),
            storeCalls.toList()
        )
    }
}
```

### A.2 V2 — CQ-002 预览屏陈旧终态假完成（`sync` 包）

```kotlin
package cn.vectory.syncplayer.sync

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.model.SyncAction
import cn.vectory.syncplayer.data.model.SyncActionType
import cn.vectory.syncplayer.data.model.SyncPhase
import cn.vectory.syncplayer.data.model.SyncProgress
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V2 / CQ-002: stale terminal progress consumed on retry sync.
 *
 * Claim under test (SyncPreviewViewModel.startSync -> observeSyncCompletion, lines ~161-210):
 * after sync #1 ends PARTIAL_FAILED for relation R, the engine's progress StateFlow RETAINS
 * the terminal value terminal(relationId=R). Tapping start again -> startSync enqueues the task
 * and calls observeSyncCompletion, whose `progress.first { terminal && relationId matches }`
 * evaluates the CURRENT StateFlow value first -> instantly consumes the STALE terminal ->
 * isSyncing=false, syncComplete=true, old partial-failure error surfaced — while the real
 * sync #2 runs headless (its DOWNLOADING/COMPLETED emissions are never processed).
 *
 * Test 1 asserts the CORRECT behavior (fails on HEAD = reproduction).
 * Test 2 documents the buggy shape on HEAD (passes on HEAD).
 * Test 3 is the control: starting from a NON-terminal progress state, the mechanism works.
 *
 * Asserts on state shape only — never on user-facing string text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V2StaleTerminalTest {

    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var progress: MutableStateFlow<SyncProgress>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { syncRelationRepository.getById(1L) } returns SyncRelation(
            id = 1L, name = "r", syncSourceId = 10L, remotePath = "/remote", localBasePath = "videos"
        )
        coEvery { syncEngine.dryRunDelete(1L) } returns Pair(
            listOf(SyncAction(type = SyncActionType.DELETE_LOCAL, relativePath = "videos/a.enc", fileSize = 10L)),
            null,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(initial: SyncProgress): SyncPreviewViewModel {
        progress = MutableStateFlow(initial)
        every { syncEngine.progress } returns progress
        return SyncPreviewViewModel(
            syncEngine = syncEngine,
            syncRelationRepository = syncRelationRepository,
            context = context,
            savedStateHandle = SavedStateHandle(
                mapOf("relationId" to 1L, "initialTab" to "delete"),
            ),
        )
    }

    private fun staleTerminal() = SyncProgress(
        isRunning = false, phase = SyncPhase.PARTIAL_FAILED, relationId = 1L, failedFiles = 2
    )

    /** Test 1 (REPRODUCTION): asserts the CORRECT behavior — fails on HEAD. */
    @Test
    fun `startSync with stale terminal in flow keeps syncing until a NEW terminal arrives`() = runTest {
        val vm = createVm(staleTerminal())
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        // Sync #1 already ended (flow holds its terminal). User taps start again.
        vm.startSync()

        // Correct behavior: NO terminal for THIS run has been emitted yet -> still syncing,
        // completion not surfaced from the stale value.
        assertTrue(
            "isSyncing must remain true after startSync until a NEW terminal arrives " +
                "(actual isSyncing=${vm.isSyncing.value}, syncComplete=${vm.syncComplete.value})",
            vm.isSyncing.value
        )
        assertFalse("syncComplete must not be set from a stale terminal", vm.syncComplete.value)
    }

    /** Test 2 (bug shape on HEAD — documents the exact failure mode; passes on HEAD). */
    @Test
    fun `bug shape - stale terminal instantly consumed and real sync runs headless`() = runTest {
        val vm = createVm(staleTerminal())
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        vm.startSync()

        // The STALE terminal (from sync #1) was consumed instantly: no new emission happened.
        assertFalse("stale terminal must have flipped isSyncing off (bug)", vm.isSyncing.value)
        assertTrue("stale terminal must have flipped syncComplete on (bug)", vm.syncComplete.value)

        // The real sync #2 now runs headless: its DOWNLOADING progress never shows in the UI.
        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        assertFalse("UI must not reflect sync #2 running state (headless)", vm.isSyncing.value)

        // Its COMPLETED terminal is never processed either: the pending-delete list is never
        // cleared (the COMPLETED branch of observeSyncCompletion would clear it).
        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        assertTrue(
            "fresh COMPLETED terminal was never processed (toDelete would be cleared)",
            vm.toDelete.value.isNotEmpty()
        )
    }

    /** Test 3 (control): from a NON-terminal progress state the completion mechanism works. */
    @Test
    fun `control - startSync from non-terminal state observes the fresh terminal`() = runTest {
        val vm = createVm(SyncProgress(isRunning = false, phase = SyncPhase.IDLE))
        vm.loadPreview()
        assertTrue(vm.toDelete.value.isNotEmpty())

        vm.startSync()
        assertTrue("isSyncing true right after start (no terminal yet)", vm.isSyncing.value)

        progress.value = SyncProgress(isRunning = true, phase = SyncPhase.DOWNLOADING, relationId = 1L)
        assertTrue("running sync reflected", vm.isSyncing.value)

        progress.value = SyncProgress(isRunning = false, phase = SyncPhase.COMPLETED, relationId = 1L)
        assertFalse("fresh terminal stops syncing", vm.isSyncing.value)
        assertTrue("fresh terminal completes", vm.syncComplete.value)
        assertTrue("COMPLETED branch cleared the pending list", vm.toDelete.value.isEmpty())
    }
}
```

### A.3 V3 — CQ-003 图片查看器陈旧读竞态（`imageviewer` 包）

```kotlin
package cn.vectory.syncplayer.imageviewer

import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.browser.UndoDeleteUseCase
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.navigation.ImageQueue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * V3 / CQ-003: ImageViewerViewModel stale DB read race in loadImage.
 *
 * Claim under test (ImageViewerViewModel.loadImage, lines ~96-115): the coroutine does
 * `videoRepository.getById(id)` then `_uiState.update { copy(currentImage = ...) }` with NO
 * generation/sequence guard (contrast PlayerViewModel's loadGeneration). Two overlapping
 * loadImage calls can interleave so the OLDER read completes LAST and overwrites the NEWER
 * image in the UI — the user is left viewing the image they navigated AWAY from.
 *
 * Gating: `getById(id)` suspends on a per-id CompletableDeferred; release order controls
 * completion order deterministically.
 *
 * Test 1 asserts the CORRECT behavior (fails on HEAD = reproduction).
 * Test 2 is the control (in-order completion): passes on HEAD.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V3ImageRaceTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val imageQueue = mockk<ImageQueue>()
    private val undoDeleteUseCase = mockk<UndoDeleteUseCase>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { imageQueue.consumeIds() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun imageEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "image$id.jpg",
        fileName = "image$id.jpg",
        fileSize = 1000L,
    )

    private fun createVm(vararg gates: Pair<Long, CompletableDeferred<Unit>>): ImageViewerViewModel {
        gates.forEach { (id, gate) ->
            coEvery { videoRepository.getById(id) } coAnswers {
                gate.await()
                imageEntity(id)
            }
        }
        return ImageViewerViewModel(
            savedStateHandle = SavedStateHandle(),
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            imageQueue = imageQueue,
            undoDeleteUseCase = undoDeleteUseCase,
        )
    }

    /** Test 1 (REPRODUCTION): stale read completing last must not win — fails on HEAD. */
    @Test
    fun `out-of-order completion keeps the newest requested image`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val vm = createVm(1L to gate1, 2L to gate2)

        // User navigates: image 1 -> image 2 (both reads in flight).
        vm.loadImage(1L)
        advanceUntilIdle() // load(1) now suspended at gate1
        vm.loadImage(2L)
        advanceUntilIdle() // load(2) now suspended at gate2

        // load(2) completes FIRST (fast read of the newer request).
        gate2.complete(Unit)
        advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.currentImage?.id)

        // Then the STALE load(1) completes (slow read of the older request).
        gate1.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "currentImage must still be image 2 (stale load(1) must not overwrite it); " +
                "actual currentImage.id=${vm.uiState.value.currentImage?.id}",
            2L,
            vm.uiState.value.currentImage?.id
        )
    }

    /** Test 2 (control): in-order completion — passes on HEAD. */
    @Test
    fun `in-order completion shows the last requested image`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val vm = createVm(1L to gate1, 2L to gate2)

        vm.loadImage(1L)
        advanceUntilIdle()
        vm.loadImage(2L)
        advanceUntilIdle()

        gate1.complete(Unit)
        advanceUntilIdle()
        gate2.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.currentImage?.id)
    }
}
```

### A.4 V4 — CQ-004 deleteRelation 未捕获异常（`settings` 包）

```kotlin
package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V4 / CQ-004: deleteRelation() has no exception handling — a repository
 * failure escapes viewModelScope uncaught (SyncRelationEditViewModel lines ~375-381):
 *
 * ```
 * fun deleteRelation() {
 *     viewModelScope.launch {
 *         if (relationId <= 0) return@launch
 *         syncRelationRepository.delete(relationId)   // <-- no try/catch
 *         _saveResult.value = RelationSaveResult.Deleted
 *     }
 * }
 * ```
 *
 * Unlike every other action in this ViewModel (save/browse/test all have CE-first + generic
 * catch), a repository exception (e.g. SQLiteException) propagates to the default uncaught
 * exception handler => process crash on Android.
 *
 * Test 1 asserts the CORRECT behavior (fails on HEAD = reproduction).
 * Test 2 documents the bug shape on HEAD (passes).
 * Test 3 is the control (delete succeeds): passes on HEAD.
 *
 * Asserts on exception types/counts only — never on user-facing string text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V4DeleteCrashTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>(relaxed = true)
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.filesDir } returns File("userdata")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): SyncRelationEditViewModel = SyncRelationEditViewModel(
        syncRelationRepository = syncRelationRepository,
        syncSourceRepository = syncSourceRepository,
        remoteFileClientFactory = remoteFileClientFactory,
        storagePaths = storagePaths,
        context = context,
        ioDispatcher = UnconfinedTestDispatcher(),
        savedStateHandle = SavedStateHandle(mapOf("relationId" to 5L)),
    )

    /**
     * Installs capturing uncaught-exception handlers for the duration of [block].
     * On Android, an exception escaping viewModelScope reaches exactly this path
     * (handleCoroutineExceptionImpl -> Thread.uncaughtExceptionHandler) => app crash.
     */
    private fun withUncaughtCapture(block: () -> Unit): List<Throwable> {
        val caught = CopyOnWriteArrayList<Throwable>()
        val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        val originalCurrent = Thread.currentThread().uncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught.add(e) }
        Thread.currentThread().uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            block()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalDefault)
            Thread.currentThread().uncaughtExceptionHandler = originalCurrent
        }
        return caught
    }

    /** Test 1 (REPRODUCTION): asserts the CORRECT behavior — fails on HEAD. */
    @Test
    fun `repository failure during deleteRelation is contained and does not crash`() {
        coEvery { syncRelationRepository.delete(5L) } throws RuntimeException("sqlite boom")

        val vm = createVm()
        val caught = withUncaughtCapture { vm.deleteRelation() }

        assertTrue(
            "repository exception must be contained (handled as state, no uncaught crash); " +
                "uncaught=${caught.map { it.javaClass.simpleName + ":" + it.message }}",
            caught.isEmpty()
        )
    }

    /** Test 2 (bug shape on HEAD — documents the crash; passes on HEAD). */
    @Test
    fun `bug shape - repository failure escapes viewModelScope uncaught`() {
        coEvery { syncRelationRepository.delete(5L) } throws RuntimeException("sqlite boom")

        val vm = createVm()
        val caught = withUncaughtCapture { vm.deleteRelation() }

        assertEquals("exactly one uncaught exception (=> crash on Android)", 1, caught.size)
        val e = caught[0]
        assertTrue(
            "uncaught exception must be the repository failure itself, got ${e.javaClass}",
            e is RuntimeException && e.message == "sqlite boom"
        )
        // The success state was never set (coroutine died before the assignment).
        assertEquals("saveResult must stay null (never set to Deleted)", null, vm.saveResult.value)
    }

    /** Test 3 (control): successful delete sets Deleted, nothing uncaught. */
    @Test
    fun `control - successful delete sets Deleted result without crash`() {
        val vm = createVm()
        val caught = withUncaughtCapture { vm.deleteRelation() }

        coVerify(exactly = 1) { syncRelationRepository.delete(5L) }
        assertEquals(RelationSaveResult.Deleted, vm.saveResult.value)
        assertTrue(caught.isEmpty())
    }
}
```

### A.5 V5 — CQ-005 SkippedNotFound 双重计数（`browser` 包）

```kotlin
package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.storage.MediaFileStore
import cn.vectory.syncplayer.sync.SyncEngine
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V5 / CQ-005: FolderDeleteSummary double-counts SkippedNotFound as BOTH `skipped` AND
 * `success` (FileOperationsUseCase.deleteFolderContentsLocked ~356-360):
 *
 * ```
 * is DeleteMediaResult.SkippedNotFound -> {
 *     skipped++
 *     success++      // <-- double count
 * }
 * ```
 *
 * For a folder of N items whose media files are already missing (idempotent skip path),
 * success==N AND skipped==N, so success+skipped+failed == 2N — the summary no longer
 * partitions the item set. The only externally observable surface of these counts is the
 * partial-failure message format args (error_folder_delete_partial: success, skipped, failed).
 *
 * Scenario: 2 items — id 1 file-missing (SkippedNotFound), id 2 exists() IO failure (Failed).
 * Correct summary: success=0, skipped=1, failed=1 (sum == 2 items).
 * Buggy summary on HEAD: success=1, skipped=1, failed=1 (sum == 3 for 2 items).
 *
 * Asserts on the NUMERIC format args (counts), never on message text.
 */
class Audit20260913V5SkippedCountTest {

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val mediaFileStore = mockk<MediaFileStore>(relaxed = true)
    private val mediaFileStoreLazy = mockk<Lazy<MediaFileStore>>()
    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private lateinit var useCase: FileOperationsUseCase

    /** Format args (as Ints, any nesting flattened) of every error_folder_delete_partial call. */
    private val partialFmtArgs = CopyOnWriteArrayList<List<Int>>()

    @Before
    fun setup() {
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()
        every { mediaFileStoreLazy.get() } returns mediaFileStore
        // Guard queries: a relaxed mockk returns a NON-NULL chained mock for getByLocalBasePath,
        // which trips the "cannot delete base dir" early exit (onError without completing `done`).
        coEvery { syncRelationRepository.getByLocalBasePath(any()) } returns null
        coEvery { syncRelationRepository.getAllBasePaths() } returns emptyList()
        useCase = FileOperationsUseCase(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            mediaFileStoreLazy = mediaFileStoreLazy,
            syncEngine = syncEngine,
            context = context
        )
        every { context.getString(any<Int>(), any(), any(), any()) } answers {
            val nums = invocation.args.drop(1).flatMap { flattenInts(it) }
            if (nums.size == 3) partialFmtArgs.add(nums) // exactly the (success, skipped, failed) call
            ""
        }
    }

    private fun flattenInts(a: Any?): List<Int> = when (a) {
        is Array<*> -> a.flatMap { flattenInts(it) }
        is Int -> listOf(a)
        is Long -> listOf(a.toInt())
        else -> emptyList()
    }

    private fun videoEntity(id: Long) = VideoItemEntity(
        id = id,
        relativePath = "base/v$id.enc",
        fileName = "v$id.enc",
        fileSize = 1000L
    )

    /** Exceptions that escaped the deleteFolder coroutine (diagnostics for the count assertions). */
    private val scopeEscapes = CopyOnWriteArrayList<Throwable>()

    /**
     * Runs deleteFolder on 2 items: id 1 = file already missing (SkippedNotFound),
     * id 2 = exists() throws IOException (Failed). Waits for onError/onSuccess.
     */
    private fun runTwoItemFolderDelete() {
        coEvery { videoRepository.getByPathPrefix("base") } returns listOf(videoEntity(1), videoEntity(2))
        coEvery { videoRepository.getById(1L) } returns videoEntity(1)
        coEvery { videoRepository.getById(2L) } returns videoEntity(2)
        coEvery { mediaFileStore.exists(1L) } returns false            // missing file -> skip path
        coEvery { mediaFileStore.exists(2L) } throws IOException("io boom") // -> Failed

        val done = CompletableDeferred<Unit>()
        val handler = CoroutineExceptionHandler { _, e -> scopeEscapes.add(e); done.complete(Unit) }
        useCase.deleteFolder(
            path = "base",
            basePaths = emptyList<cn.vectory.syncplayer.data.model.SyncRelationWithSource>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler),
            onError = { done.complete(Unit) },   // both outcomes end the run (counts via getString)
            onSuccess = { done.complete(Unit) }
        )
        runBlocking { withTimeout(10_000) { done.await() } }
    }

    /** Test 1 (REPRODUCTION): summary counts must partition the item set — fails on HEAD. */
    @Test
    fun `skipped items are not also counted as success`() {
        runTwoItemFolderDelete()

        assertEquals(
            "partial-failure message must have been produced " +
                "(captured=${partialFmtArgs}, escaped=${scopeEscapes.map { it.javaClass.simpleName + ":" + it.message }})",
            1,
            partialFmtArgs.size
        )
        val (success, skipped, failed) = partialFmtArgs[0]
        assertEquals(
            "success+skipped+failed must equal the item count (2); actual success=$success " +
                "skipped=$skipped failed=$failed — SkippedNotFound is double-counted as both " +
                "success and skipped",
            2,
            success + skipped + failed
        )
    }

    /** Test 2 (bug shape on HEAD — documents the exact inflated counts; passes on HEAD). */
    @Test
    fun `bug shape - one skipped item inflates success to one`() {
        runTwoItemFolderDelete()

        assertEquals(1, partialFmtArgs.size)
        val (success, skipped, failed) = partialFmtArgs[0]
        // HEAD: skipped=1 (item 1), failed=1 (item 2) — correct — but success=1 although
        // ZERO items were actually deleted (item 1 was already missing = the skipped one).
        assertEquals(1, skipped)
        assertEquals(1, failed)
        assertEquals(1, success)
        assertTrue("no item was actually deleted, yet success > 0", success > 0)
    }
}
```
