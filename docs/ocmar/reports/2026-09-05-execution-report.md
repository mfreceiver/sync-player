# BUG-001..022 执行报告 — 2026-09-05（终版）

> 执行基线：`main @ 2f785ce`（docs(audit): round-2 deep bug-hunting report）。交付物 = **未提交工作树**：
> 43 个修改文件（+4161/−410）+ 18 个新增源文件（2 生产 + 16 测试）+ 8 份计划/评审文档 + 本报告。
> 交付流程：依 [`2026-09-05-execution-handoff-v2.md`](../plans/2026-09-05-execution-handoff-v2.md) 阶段 D 产出 → 三车道最终复审 + 阻断修复收口（§3）→ 独立终验（§5）。
> **未 commit/push、未发版**——按交接约定保持工作树交付态，等待用户处置。

---

## 1. 计划门禁分数

四条车道计划全部通过门禁（终分 **9.6/10 PASS**）：

| 车道 | 计划 | 评分轨迹 | 终态 |
|---|---|---|---|
| L1 sync-engine | `2026-09-05-plan-sync-engine-bug001.md` | 7.2 → 7.0 → 8.0 → **9.6** | PASS（v3.1 独立复审代理，无未决 Critical/Major；4 项 Minor 以简报附件执行，不改计划文档） |
| L2 smb-dao | `2026-09-05-plan-smb-dao.md` | 6.5 → 8.6 → 9.2 → **9.6** | PASS（原编排会话评审） |
| L3 settings-security | `2026-09-05-plan-settings-security.md` | 7.8 → 8.0 → 9.2 → **9.6** | PASS（三轮返工：七项→五项+申诉裁决→唯一阻断项 T4a/T4b 成功写入桩） |
| L4 player-browser-util | `2026-09-05-plan-player-browser-util.md` | 8.0 → 8.3 → 9.2 → **9.6** | PASS（v5 复审 9.4 两 Minor + 文字扫尾后 v6 终版） |

注：[`2026-09-05-gate-history.md`](2026-09-05-gate-history.md) 中 L3/L4 段为中途快照（"待返工2"/"待修订"），终分以上表为准。

## 2. BUG-001..022 执行明细

状态：22/22 **已完成**，全部有对应单元测试且包含在 659 全绿内。`app/src/main/java/cn/vectory/syncplayer/` 前缀省略。

| BUG | 主题 | 生产文件（关键行为结果） | 测试文件（@Test 数） |
|---|---|---|---|
| 001 | DELETE_BOTH local-first 回收站复活循环 | `sync/SyncDeleteExecutor.kt`（循环重排 **remote-first**：卷检查恒最前；远端失败不动本地——行留回收站标记，下次清理重推 DELETE_BOTH 自终止；远端成功后本地失败留 DB 行，由下次镜像 dryRun 推导 DELETE_LOCAL 自愈） | `sync/SyncDeleteExecutorTest.kt`(7)，`sync/SyncTrashResurrectionRegressionTest.kt`(2，新) |
| 002 | 进度保存乱序/丢失 | `player/PlayerViewModel.kt`（`progressSaveChannel = Channel(UNLIMITED)` 单消费者逐项子 Job `join()`：完成序==入队序；CE 不杀唯一消费者） | `player/PlayerViewModelSaveProgressTest.kt`(9) |
| 003 | 偏好流 IOException 冻结设置状态 | `settings/SettingsViewModel.kt`（init collect 链 `retryWhen`：仅 IOException 重订阅，指数退避 `ioRetryBackoffMs` 1s→30s 封顶；非 IOException 含 CE 原样传播） | `settings/SettingsViewModelTest.kt`(4) |
| 004 | 刷新竞态旧值覆盖 | `settings/StorageManagementViewModel.kt`（`refreshEpoch: AtomicLong` 领取代次，旧 refresh 发布权即时失效） | `settings/StorageManagementViewModelTest.kt`(12) |
| 005 | 统计查询异常未降级 | `settings/StorageManagementViewModel.kt`（refresh/switch 全路径 CE 先传播 + generic catch 降级，`getTotalItemCount` 等以 "—" 占位） | 同上 |
| 006 | SMB 空 share/root 扫描产出垃圾相对路径 → DELETE_LOCAL 误删 | `sync/smb/SmbFileClient.kt`（双防线 E8：`listFiles` 入口 `share.isEmpty()` → `RemoteScanIncompleteException`，枚举前失败绝不返回快照；`extractRelativePath` 锚定 `startsWith("smb://host/share/base/")` 失败即抛） | `sync/smb/SmbFileClientRootScanTest.kt`(6，新) |
| 007 | 下载清理/finalize 竞态误删旧 final | `sync/SyncDownloadPipeline.kt`（类级 `AttemptPhase{NOT_FINALIZED, FINALIZE_UNCERTAIN, COMMITTED}`；`cleanUpAttempt` 单点三阶段分派；attempt 级 CE/generic catch `withContext(NonCancellable)` 回滚；`uncleanFinalizedId` 登记簿两清零点；verifyReadable 失败抛 STORAGE_ERROR→COMMITTED 回滚删 .enc+删行恰一次）；存储层 `storage/volume/MediaVolume.kt`(+`existsStrict` 接口)、`SafVolume.kt`(`findChildDocIdStrict` 全路径 traversal：null cursor/异常/EXTRA_LOADING→IOException，绝不折算不存在)、`FileVolume.kt`(`Files.readAttributes`：NoSuchFile→false，其余 IOException 上抛)、`EncryptedMediaStore.kt`(+`TmpProbeState{PRESENT,ABSENT,UNKNOWN}` + `probePendingTmp`) | `sync/SyncDownloadPipelineTest.kt`(17)，`storage/volume/EncryptedMediaStoreTmpProbeTest.kt`(4，新)，`FileVolumeExistsStrictTest.kt`(1，新)，`SafVolumeExistsStrictTest.kt`(6，新)，`EncryptedMediaStoreTest.kt`(+1 匿名卷委托) |
| 008 | 终态进度重置丢 DELETING 计数 | `sync/SyncDeleteExecutor.kt` + `sync/SyncDownloadPipeline.kt`（终态改 `progressPublisher.value.copy(...)`）；`sync/SyncService.kt`（onTimeout/cancelSync 读 `syncEngine.progress.value.copy(phase=CANCELLED)`） | `sync/SyncEngineProgressTest.kt`(13)，`sync/SyncProgressPublisherTest.kt`(6) |
| 009 | SFTP 删除 NO_SUCH_FILE 误报 REMOTE_DELETE_FAILED | `sync/sftp/SftpFileClient.kt`（deleteFile +catch `SFTPException`：先 `ensureActive()`（CE 不包装）；`NO_SUCH_FILE`→return false 幂等，对齐 SMB `!exists→false` 契约；PERMISSION_DENIED 等仍真失败） | `sync/sftp/SftpTransportCancellationTest.kt`(16) |
| 010 | initial connect 无重试 | `sync/SyncDownloadPipeline.kt`（`connectWithRetry(client, source, relation.remotePath)` 白名单瞬态码重试，替代裸 connect） | `sync/SyncDownloadPipelineClientReuseTest.kt`(6，新) |
| 011 | DAO `LIKE :folderPath||'/%'` 通配符/大小写折叠误匹配 | `data/local/dao/VideoItemDao.kt`（8 处 @Query 改 `substr(relativePath,1,length(:folderPath)+1) = :folderPath||'/'` 二进制精确前缀） | `data/local/VideoItemDaoPathSemanticsTest.kt`(17，新) |
| 012 | PlaybackRecordDao folder 语义同缺陷 | `data/local/dao/PlaybackRecordDao.kt`（三查询同型 substr+root CASE 重写） | `data/local/PlaybackRecordDaoFolderSemanticsTest.kt`(6，新=计划5+1 sanity)，`data/local/PlaybackRepositoryMarkFolderCompletedTest.kt`(3，新) |
| 013 | 音量增益整行 upsert 回滚并发列（如 star）/复活已删行 | `data/local/dao/VideoItemDao.kt:246` + `data/repository/VideoRepository.kt:132` + `settings/GainAnalysisViewModel.kt:164`（+`updateAudioGain(id, audioGainDb)` 单列更新替代整行 upsert） | `settings/GainAnalysisViewModelUpdateTest.kt`(2，新)，`settings/GainAnalysisConcurrencyTest.kt`(3，新) |
| 014 | AppLock 偏好收集 IOException 崩溃/冷启动无界阻塞 | `security/AppLockManager.kt`（`startPreferenceCollection` retryWhen 5s 固定 backoff；`publishCache(value, expectGeneration)` 融合写锁三元组 cachedSecureLockEnabled/cacheInitialized/cacheGeneration；`lockEnabledForStart` 有界冷读 `withTimeoutOrNull(2s)` + IOException/超时 fail-closed true） | `security/AppLockManagerTest.kt`(12，+290 行) |
| 015 | loadVideo 迟到旧请求覆盖新状态 | `player/PlayerViewModel.kt`（`loadGeneration.incrementAndGet()` 发起即占代；加载提交与耗尽发布双代次门控，旧代次迟到丢弃） | `player/PlayerViewModelLoadVideoTest.kt`(10，新) |
| 016 | undo 槽位 prev hole 静默丢 pending 硬删 | `browser/UndoDeleteUseCase.kt`（+`autoCommitPendingHardDeleteLocked`：换槽前先提交**不同 id** 的 pending 硬删；先清态后执行防并发 undo 双清；同 id 重删幂等） | `browser/UndoDeleteUseCaseTest.kt`(32，+133 行) |
| 017 | share 临时文件同名冲突互相截断 | `ui/util/ShareTempFiles.kt`（+`uniqueShareFile(context, sanitized, videoId)`：`stem-<id>[-n]` 防撞后缀永不截断；UTF-8 字节预算 MAX_LEAF_BYTES，code point 边界截断，预算不足回退 ASCII stem "share"；`check(++n < 1_000)` 收敛 guard；衔接 TTL 清扫）；`browser/FileOperationsUseCase.kt`（改用 uniqueShareFile；解密失败清理职责覆盖"文件创建→decrypt 返回"全窗口，`runCatching{delete}` 后 CE 原样重抛） | `ui/util/ShareTempFilesTest.kt`(40，新)，`browser/FileOperationsUseCaseTest.kt`(27，+113 行) |
| 018 | SyncService 尾部 pending race（亚毫秒窗口丢 PENDING 行） | `sync/SyncServiceDrainCoordinator.kt`（**新文件**：confined 单串行域；UNLIMITED buffer + LAZY drain 循环三语句拆分启动；armStop/cancelStop 闭包令牌；stopTimerFired 纵深；drainLoop CE 先重抛 + 普通异常边界见 §3）；`sync/SyncService.kt`（重构接入：onStartCommand→onSyncRequested；用户取消=立即 CANCELLED 通知+`cancelActiveDrainAndArmForcedStop`；stop 武装改协调器管理）；`sync/SyncEngine.kt`（+KDoc 真实协议说明：请求缓冲仅在用户取消/系统超时/destroy 后丢弃，关系可再次启动恢复） | `sync/SyncServiceDrainCoordinatorTest.kt`(20，新) |
| 019 | 队列耗尽静默黑屏（无 ErrorEvent 量级） | `player/PlayerViewModel.kt`（UiState +`queueExhausted` 结构化终态：加载先清终态，id 消失且无后续队列项时发布）；`player/PlayerScreen.kt`（`LaunchedEffect(uiState.queueExhausted)` 消费并退出，同正常 EOF） | `player/PlayerViewModelLoadVideoTest.kt`（耗尽发布受代次门控用例） |
| 020 | 子文件夹/后代查询同 LIKE 缺陷 | `data/local/dao/VideoItemDao.kt`（getSubFolders/getSubFoldersOnce 后代判定同 substr 重写） | `data/local/VideoItemDaoPathSemanticsTest.kt` |
| 021 | >PB 字节数组越界 | `ui/util/FormatUtils.kt`（units +"PB"，`digitGroups.coerceAtMost(units.size-1)` 与单位同时 clamp，≥1 EiB caps at PB） | `ui/util/FormatUtilsTest.kt`(4，新) |
| 022 | 空文件夹名幽灵文件夹 | `data/local/dao/VideoItemDao.kt`（子文件夹投影外包一层 `WHERE folderName != ''`） | `data/local/VideoItemDaoPathSemanticsTest.kt` |
| L4-1 | 保存入口远端路径规范化 | `settings/SyncRelationEditViewModel.kt`（+`normalizeRemotePath(raw, protocol)` 纯函数：SMB 剥前导 `/` 但裸 root 拒（BUG-006 E8 洞前移至保存期）；SFTP 保留前导 `/`（绝对锚定），`/` 合法；裸 `.` 两协议均拒；`..` 段拒；空段/非首 `.` 段折叠；null=拒绝；不日志不回显输入）；`res/values/strings.xml` +`relation_invalid_remote_path` | `settings/SyncRelationEditViewModelRemotePathTest.kt`(7，新) |

改动清单汇总：生产 27 文件修改 + 2 新增（`sync/SyncServiceDrainCoordinator.kt`、`sync/SyncSafeLog.kt`）；测试 16 文件修改 + 16 新增；`git diff --stat`: 43 files, +4161/−410（未跟踪文件不计入）。TDD 证据（红→绿测试名）见各计划文档 §测试 与 gate-history.md 各轮 FAIL 记录。

## 3. 最终复审与修复收口（阻断项已全部清零）

四车道实现完成后，对三条高风险车道做交付前独立复审；复审发现的阻断项已全部修复并纳入终验（§5 的 659 全绿含全部修复测试）：

| 复审对象 | 结论 | 发现 → 修复 |
|---|---|---|
| settings-security | **PASS** | 仅 1 项**非阻断**建议（AppLock 相关注释完善），不改行为、不构成阻断，记录在案 |
| sync coordinator（drain 协调器/下载管线） | **95/100 PASS** | 三项阻断修复：① **普通异常 drain**——drainLoop 原先无普通 Exception 边界，`finalize` 等抛普通异常会静默终结 launch、FGS 通知滞留至超时；现补齐：CE 先重抛（红线不变），普通异常经 `onPassFailure` 回调上报后按队列态收尾（空队列→有界武装停服；非空→不武装、继续 drain 由后续 pass 接管），不再反杀循环（`sync/SyncServiceDrainCoordinator.kt`）。② **BUG-007 普通元数据补偿**——元数据提取窗口此前仅 CE 有补偿路径，普通异常落同窗口未覆盖；补齐（`sync/SyncDownloadPipeline.kt`）。③ **安全日志**——sync 写域存在 path/fileName/Throwable message 级诊断日志（触红线 6）；新增 `sync/SyncSafeLog.kt`（只承载操作名、结构化错误码、异常类别 simpleName、非敏感行 id/计数/布尔，绝不拼接 path/fileName、绝不传入原始 Throwable/cause/message），SyncDeleteExecutor/SyncDownloadPipeline 写域日志全部改用；`SyncSafeLogTest` 锁定"输入异常携带敏感信息也只产安全 token"，管线行为测试锁定 debug ring 无 path/fileName token。复审通过后另追加 **CE/arm 边界修复**（协调器取消路径与停服武装令牌边界收口）。 |
| browser（undo/share/UI marker） | **93/100 PASS** | 五项修复：① **Long chunk**（复审发现项，已修复）；② **分享全取消清理**——清理职责扩展覆盖"文件创建 → decrypt 返回"全窗口（openCiphertextRead/contentKey 失败也清理预创建空文件），CE 清理后原样重抛（红线 3）；③ **close CE**——`decryptWholeTo` 显式建模 `handle.close()` 失败：主失败优先，close 失败延迟到 withContext 边界外抛（异常实例不被 dispatcher 替换）+ suppressed 附加，close 的 CE 作为 deferred cancellation 返回；④ **Committing 债务**——失败的硬删提交保留为"欠账、不可撤销"债（marker 不清，后续可重发），`BrowserViewModel.hasCommittingDelete` 查询 + UI guard 放行新删除以 drain 旧债；⑤ **UI marker 类型硬删除查询**——`reconcilePendingDeleteMarker`（`browser/BrowserScreen.kt`）：Snackbar 回调仅在仍持有其请求时所见的 marker 时才同步权威状态，stale 回调不得清除更新的 pending 操作。新增 `browser/BrowserScreenTest.kt`(5，新) 锁定 marker/债务语义。 |

## 4. 执行偏差记录（均已审计采纳）

1. BUG-006 用例 7 断言字段计划写 `scanError`，实际实体为 `DryRunResult.error` —— 按实际适配。
2. `PlaybackRecordDaoFolderSemanticsTest` 6 用例 = 计划 5 + 1 计划外 sanity 用例。
3. 被杀代理遗留工作审计后采纳：BUG-009/010 全部 + 存储测试 S1-S9；`SafVolumeExistsStrictTest` harness 补 normalizeScheme/toString 桩；S10 由接续者新写；`EncryptedMediaStoreTest` 匿名卷补 existsStrict 委托（+1 行）。
4. L1 评审 4 项 Minor（测试4按红测试对待、#10 注释、#13 用例名、测试8/9用例名）以简报附件执行，未改计划文档。

## 5. 最终独立验证事实（终验）

验证日志（全量 stdout/stderr + 命令与退出码）：`/home/mar/personal_projects/sync-player/.ocmar/workflows/bug001-022-final-verify/verify-1788634025.log`（branch `main`，HEAD `2f785ce`，工作树 67 行与复审修复后一致；验证器只产出 build 工件 + 日志，未改任何源/测试/文档）

- `./gradlew compileDebugKotlin --no-daemon`（fresh）→ **EXIT=0, BUILD SUCCESSFUL**（≈1m03s）
- `./gradlew testDebugUnitTest --no-daemon`（fresh）→ **EXIT=0, BUILD SUCCESSFUL**（≈2m04s，37 tasks 全 executed 非 UP-TO-DATE）
- JUnit XML 聚合（**72 个 suite**）：**tests=659, failures=0, errors=0, skipped=0**；REAL-FAIL marker 扫描 none found
- `git diff --check` → exit 0（无空白错误）
- 非阻塞警告（既有 opt-in 提示，非错误）：`SyncServiceDrainCoordinator.kt:138:56/176:34/187:44` ExperimentalCoroutinesApi；`UndoDeleteUseCaseTest.kt:830:37` unstable-inheritance

## 6. 已验证 vs 未覆盖

**已验证**（均有可复现证据）：
- 单元级行为：**659/659 全绿**（fresh、--no-daemon），每 BUG 对应测试文件见 §2，复审修复测试见 §3。
- `compileDebugKotlin` fresh 编译通过（debug 变体）。
- `git diff --check` 干净；工作树与交接基线 diff 可审计。
- 四车道计划门禁评审通过（终分 9.6）+ 三车道最终复审全部 PASS（settings PASS / sync coordinator 95 / browser 93），复审阻断项修复收口完毕、无未闭合缺口。

**未覆盖**（本次验证范围外，不声称）：
- 真机/集成级：SAF DocumentsProvider 真实行为（EXTRA_LOADING、云 provider）、SMB/SFTP 真实服务器、FileProvider 分享端到端、FGS 生命周期真机时序。
- `assembleRelease`/R8：未运行（验证仅 compileDebugKotlin + testDebugUnitTest）。
- 性能基准：FIFO 保存消费时延、drain 循环开销无测量数据。
- 文件系统兼容矩阵：FAT 变体等命名规则未实测。

## 7. 已知残留风险（复审收口后更新）

1. **BUG-002 FIFO 无时延/容量上界**：`Channel(UNLIMITED)` + 逐项 `join()` 仅保证完成序==入队序（代码注释明示"仅串行保证，无时延上界"）；极端入队速率下积压无界。
2. **BUG-016 prev deleted hole**：`autoCommitPendingHardDeleteLocked` 先清态后执行（防并发 undo 双清的刻意选择）；进程崩溃落于两步之间时该 pending 硬删丢一次机会（硬删幂等可重发）。
3. **BUG-017 share TTL/文件名**：TTL 清扫（`cleanupOldShareTempFiles`）依赖后续 share 调用触发，无后台清扫——过期后若不再发起 share，`share/` 临时文件滞留。
4. **BUG-017 长名 1000 候选非墙钟**：`check(++n < 1_000)` 是候选数上限（n=0..999）的防御性收敛，非时间界；耗尽按分享失败处理（异常边界）。
5. **FileProvider/JVM 边界**：`MAX_LEAF_BYTES` 按内核/ext4 255 字节上限建模；JVM 单测验证预算与截断逻辑本身，不覆盖设备文件系统差异（如 FAT 变体的 255 UTF-16 码元规则）。
6. **计划未解决的系统依赖边界**（保留现状，非本次可闭合）：
   - BUG-018 ① stopService 调出与实际销毁之间新到达请求的行为依赖系统调度（真机时序，未覆盖）。
   - BUG-007 ④ 外部写者在 rename 与探测之间删 tmp → ABSENT 误判；SAF `existsStrict` 边界①②不可普遍证明（兜底 IOException→UNKNOWN→只删行）；13b 探测 CE 生产不可达（防御 mock 违约）。

> 复审中曾列为阻断的两项——sync 写域敏感日志（原 §6-6）、BUG-018 ② finalize 非 CE 致 drainLoop 静默死亡（原 §6-7②）——已分别由 `SyncSafeLog` 与普通异常 drain 边界修复收口，不再列为残留风险。

## 8. 简报

- 22 个 BUG（L1 001/007/008/009/010/018、L2 006/011/012/013/020/022、L3 003/004/005/014、L4 002/015/016/017/019/021 + L4-1/L4-2 交接项）全部实现并有测试。
- 四车道计划门禁终分 9.6 PASS；随后最终复审三车道全部 PASS（settings PASS 仅非阻断注释建议；sync coordinator 95/100——普通异常 drain、BUG-007 普通元数据补偿、安全日志，随后 CE/arm 边界修复；browser 93/100——Long chunk、分享全取消清理、close CE、Committing 债务、UI marker 类型硬删除查询），阻断项全部修复收口、无未闭合缺口。
- 执行偏差 3 项 + 1 项遗留审计采纳已记录（§4）。
- 独立终验：fresh `compileDebugKotlin --no-daemon` + `testDebugUnitTest --no-daemon` 全绿，**659 tests / 0 failures / 0 errors**（0 skipped，72 suites），`git diff --check` clean；日志 `/home/mar/personal_projects/sync-player/.ocmar/workflows/bug001-022-final-verify/verify-1788634025.log`。
- 残留风险 6 类已知并记录（§7），均为未覆盖边界/刻意设计取舍；复审发现的阻断项已全部修复，不再列入。
- 交付态 = 基线 `2f785ce` + 未提交工作树；**未 commit、未 push、未发版**，release 流程未启动。后续动作（commit/发版/真机验证）由用户决定。
