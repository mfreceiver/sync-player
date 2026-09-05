# Sync Player BUG-001..022 修复 — 执行交接提示词 v2（2026-09-05 晚）

> 你是接续执行本修复任务的编排/实施 agent。前任因会话过长交接。**本文是唯一权威交接物**，配合
> `docs/ocmar/plans/2026-09-05-execution-handoff.md`（原始交接方案，阶段 A→B→C→D 流程与写域映射仍有效）使用。
> 项目根：`/home/mar/personal_projects/sync-player`（git main @ `2f785ce` + 大量未提交工作树改动——**改动就是交付物，绝不 reset/checkout**）。

## 0. 环境铁律（本阶段实测，违反即报废）

1. **子代理不可用**：同账户子代理运行约 15-25 分钟后被账户级验证击杀（报错 `captcha verify failed` 或 `model/user concurrency limit exceeded`）。**报错在击杀时才返回，不是启动失败**——被杀代理死前已把部分工作写上磁盘。教训：派发失败后必须先 `git status` 审计磁盘遗留，符合计划的部分保留续用。
2. **因此：一切实施在主会话直接做**（Bash/Read/Write/Edit 不受影响）。只读评审类短任务可尝试单发一个前台代理（A1 评审 24 分钟幸存），但要有失败预案；**绝不并发 2 个以上代理**。
3. 一切 Gradle 命令：`JAVA_HOME=/home/mar/android-studio/jbr`。编译是类型验证唯一来源（LSP 已禁用）。
4. 不 commit/push；不碰 AGENTS.md、docs/specs/**、scripts/**、app/build.gradle.kts、被否决的 `2026-09-05-comprehensive-bug-repair.md`。

## 1. 已完成（磁盘现状，已验证）

### 车道门禁状态
| 车道 | 状态 |
|---|---|
| L1 sync-engine 计划 v3.1 | **9.6/10 PASS**（独立评审完成；结论+4 项 Minor 已存 `docs/ocmar/reports/2026-09-05-gate-history.md`） |
| L2 smb-dao 计划 | 9.6 PASS（原编排会话评审） |
| L3 settings-security 计划 | **待返工 2**（5 项问题清单见原 handoff §4.A2）→ 复审门槛 9.5 |
| L4 player-browser-util 计划 | **待修订**（吸收 L2 计划「Lane4 交接项」L4-1..L4-4，见 L2 计划 :709/:778-847/:831）→ 首审 9.5 |

### B-L2 车道实施：✅ 完成（全量 486 测试 0 失败验证过）
- `VideoItemDao.kt`：8 处 @Query LIKE→substr 二进制精确前缀（BUG-011）；getSubFolders/getSubFoldersOnce 投影包一层 `WHERE folderName != ''`（BUG-022）；+`updateAudioGain`（BUG-013）。
- `PlaybackRecordDao.kt`：getByFolderFlow/updateCompletedByFolder/getVideoIdsWithoutRecord 三查询 substr+root CASE 重写（BUG-012/020）。
- `VideoRepository.kt`：+updateAudioGain 透传。
- `SmbFileClient.kt`：listFiles 入口空 share 拒绝 + extractRelativePath 锚定 startsWith 重写，均抛 RemoteScanIncompleteException（BUG-006 双防线）。
- 测试：`VideoItemDaoPathSemanticsTest`（17 用例）、`PlaybackRecordDaoFolderSemanticsTest`（6，含 1 个计划外 sanity 用例=偏差）、`PlaybackRepositoryMarkFolderCompletedTest`（3）、`SmbFileClientRootScanTest`（6）、`SyncRemoteScannerTest` +1。
- TDD 红证据已采集（21 红 + 5 红，与计划预期红灯集完全一致）。

### B-L1 车道实施：🔄 进行中（约 60%）
**已完成且已验证绿：**
- **BUG-009**（SFTP NO_SUCH_FILE 幂等）：`SftpFileClient.kt` deleteFile 增 SFTPException 分支（+17 行）；`SftpTransportCancellationTest` +6 用例。全绿。
- **BUG-010**（initial connect 白名单重试）：`SyncDownloadPipeline.kt` +`connectWithRetry` + 调用点替换（+38 行）；`SyncDownloadPipelineClientReuseTest` +4 用例。全绿。
- **BUG-007 存储层**：`MediaVolume.kt` +existsStrict 接口；`SafVolume.kt` +全路径 strict traversal（findChildDocIdStrict：null cursor/异常/EXTRA_LOADING→IOException）；`FileVolume.kt` +existsStrict（Files.readAttributes，NoSuchFileException→false）；`EncryptedMediaStore.kt` +包级 `TmpProbeState` +`probePendingTmp`。测试 S1-S4（`EncryptedMediaStoreTmpProbeTest`）、S5-S9（`SafVolumeExistsStrictTest`，已修 harness：补 `treeUri.normalizeScheme()/toString()` 桩）、S10（`FileVolumeExistsStrictTest`，新写）。全绿。另：`EncryptedMediaStoreTest` 匿名 MediaVolume 补了 existsStrict 委托（接口新增的连带修复）。

**已实施但尚未验证（最后一轮编辑后只跑了 compileDebugKotlin=BUILD SUCCESSFUL，未跑测试）：**
- **BUG-007 管线重构**（`SyncDownloadPipeline.kt`）：类级 `AttemptPhase` 枚举；per-action 循环内提升声明 `var existing/assignedId/uncleanFinalizedId` + 局部 suspend fun `cleanUpAttempt(phase, id)`（三阶段分派、探测单独保护收敛 UNKNOWN、`uncleanFinalizedId=0` 清零点）；while 循环内 `attemptPhase` 跟踪 + finalize 包装（CE/异常/false→FINALIZE_FAILED，true→COMMITTED+登记）+ verifyReadable 失败改抛 STORAGE_ERROR（单点回滚）；attempt 级 CE/generic catch 均走 `withContext(NonCancellable){ cleanUpAttempt }`；元数据 upsert 成功后清零；外层 CE 补偿 catch 读登记簿非 0 才补偿。+import TmpProbeState。

## 2. 立即要做的下一步（按序）

1. **验证既有测试未回归**：`JAVA_HOME=... ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineTest' --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineClientReuseTest' --tests 'cn.vectory.syncplayer.sync.TransportCancellationRegressionTest'`。
2. **写 BUG-007 管线测试 15 个用例**（计划 §BUG-007.3 管线组，落 `SyncDownloadPipelineTest.kt` 追加）：#1-#14+13b，逐条标注红/回归锁（#1/2/5/6/8/9/12/13/13b 红；#3/4/7/10/11/14 回归锁）。harness 抄 `SyncDownloadPipelineClientReuseTest` 的 `stubBasics()`+`stubSuccessfulDownloadPath()`（注意 `store.usableSpace()` 须 stub Long.MAX_VALUE 否则 INSUFFICIENT_SPACE；`storagePaths.toLocalRelativePath` 已在其 stubBasics 有拼接实现）；existing 行用 `mockk<VideoItemEntity>{ id=42; volumeId="vol-active" }`；probe 三态用 `coEvery { store.probePendingTmp(42) } returns TmpProbeState.ABSENT/PRESENT/UNKNOWN`（store 是 relaxed mockk，注意先打桩）。#14 用有状态 fake repository+fake store 调用记录切片断言（`calls.drop(snapshot.size)` 不含 42）。
3. **BUG-008**：`SyncDownloadPipeline.kt` :557-574 两处终态 fresh 对象→`progressPublisher.value.copy(...)`；`SyncDeleteExecutor.kt` :190-196 同形态；`SyncService.kt` onTimeout/cancelSync 通知改读 `syncEngine.progress.value.copy(phase = CANCELLED)`。测试 4 个（计划 §BUG-008.3；#2/#3/#4 红测试，注入点 :518 updateLastSync / :190 catch-all）。
4. **BUG-001**：`SyncDeleteExecutor.executeDeleteLocked` 循环体重排 remote-first（卷检查保持在最前）；`SyncDeleteExecutorTest` +3 用例；新建 `SyncTrashResurrectionRegressionTest`（2 用例：#5 复活红测试=有状态 fake repo；#6 DELETE_LOCAL 自愈回归锁）。
5. **BUG-018**（最重）：新建 `SyncServiceDrainCoordinator.kt`（计划 §BUG-018.2 逐行照抄，含 onForcedStop/cancelActiveDrainAndArmForcedStop/stopTimerFired 令牌主门/LAZY 注册三语句）；`SyncService.kt` 全量接线（onCreate coordinatorScope=Main.immediate、onStartCommand→onSyncRequested、cancelSync 整体重写、onTimeout、onDestroy 补 coordinatorScope.cancel、删 syncJob/scheduleStop）；`SyncEngine.kt` :110-112 KDoc 更正；新建 `SyncServiceDrainCoordinatorTest`（22 用例 #1-#17+#20-#22，含 FakeStopScheduler detachOnCancel+firedTokens；#16 专用 UnconfinedTestDispatcher；#16/#22 须做 mutation check）；`SyncEngineTest` +2 表征用例。评审 4 项 Minor（gate-history.md 有全文）：#13 用例改名、8/9 用例名 ambiguous→finalize-uncertain、#10 注释按事实、BUG-007 测试 4 按红测试对待。
6. **阶段 C**：全量 `compileDebugKotlin` + `testDebugUnitTest`（基线 486+新增，全须绿）；`git status` 核对改动 ⊆ 四车道写域 ∪ 计划文档。
7. **阶段 A 收尾**（可与 B 并行视代理可用性）：A2=按原 handoff §4.A2 五项问题返工 L3 计划 → 派只读代理复审 ≥9.5；A3=把 L2 计划 L4-1..L4-4 交接项整合进 L4 计划 → 首审 ≥9.5。**若代理仍被杀：把评审任务转为主会话自查 + 在报告中如实标注评审独立性问题**。
8. **阶段 D**：写 `docs/ocmar/reports/2026-09-05-execution-report.md`（结构见原 handoff §7；gate-history.md 已有门禁史素材）；完成后暂停等用户，不发版不 commit。

## 3. 已记录偏差（写报告用）

1. 计划 BUG-006 用例 7 断言字段写 `scanError`，实际 `DryRunResult.error`——按实际适配。
2. `PlaybackRecordDaoFolderSemanticsTest` 多 1 个计划外 sanity 用例（无害表征）。
3. B-L1 被杀代理遗留工作经审计与计划一致被采纳（BUG-009/010 全部、存储测试 S1-S9）；SafVolumeExistsStrictTest harness 缺 normalizeScheme/toString 桩已补；S10 由接续者新写；EncryptedMediaStoreTest 匿名卷补 existsStrict 委托。
4. L1 评审 4 项 Minor 以简报形式执行（不改计划文档）。

## 4. 关键文件索引

- 原始交接方案（流程/写域/纪律）：`docs/ocmar/plans/2026-09-05-execution-handoff.md`
- L1 计划（1266 行）：`docs/ocmar/plans/2026-09-05-plan-sync-engine.md`
- L2 计划（868 行，含 Lane4 交接项）：`docs/ocmar/plans/2026-09-05-plan-smb-dao.md`
- L3 计划：`docs/ocmar/plans/2026-09-05-plan-settings-security.md`；L4 计划：`docs/ocmar/plans/2026-09-05-plan-player-browser-util.md`
- 门禁史（A1 评审结论 + 4 Minor 全文）：`docs/ocmar/reports/2026-09-05-gate-history.md`
- 权威标签：`todo.md`（009=SFTP NO_SUCH_FILE 幂等、010=初连重试）；缺陷详情：`docs/automatic/20260905-0012_bug-hunting.md`、`docs/automatic/20260905-0114_bug-hunting.md`
