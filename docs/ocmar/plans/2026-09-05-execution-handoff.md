# Sync Player BUG-001..022 修复 — 执行交接方案（Handoff）

> 交接方：原编排会话（计划制定与门禁评审阶段负责人）。
> 接收方：新编排 agent（本机，具备多 subagent 并行执行能力）。
> 日期：2026-09-05。项目根：`/home/mar/personal_projects/sync-player`（git main @ `2f785ce`）。
>
> **你的职责边界**：完成下述阶段 A→B→C→D（计划收口 → 实施 → 全量验证 → 简报回交）。
> **不属于你**：发版（`./scripts/release.sh`）、最终代码评审裁决、spec/AGENTS 修改——由原编排会话在收到你的简报后执行 review 并决定。

---

## 0. 背景一段话

22 个已确认缺陷（BUG-001..022，静态核对完毕）需按 4 个不重叠写域分车道修复。每车道一份 TDD 修复计划文档，须过独立评审门禁 **>=9.5/10** 后方可实施。当前 Lane2 已过门禁；Lane1/Lane3 计划差最后一轮收口；Lane4 计划需吸收 Lane2 交接项后首审。生产/测试代码**尚无任何改动**（git status 仅未跟踪的计划文档）。

## 1. 权威来源与硬约束（不可违背）

**必读**（开工前，一次性）：
- `AGENTS.md`（项目根）— 环境与红线速查
- `todo.md`（项目根）— BUG 标签权威（注意：R1 报告中 BUG-009/010 标签互换，009=SFTP NO_SUCH_FILE 幂等、010=初连重试，以 todo.md 为准）
- 缺陷详情：`docs/automatic/20260905-0012_bug-hunting.md`、`docs/automatic/20260905-0114_bug-hunting.md`
- 4 份计划文档（见 §3）

**安全红线**（摘自 AGENTS.md，实施时逐条遵守）：
1. E8 扫描失败保护：部分远端快照绝不推导 DELETE_LOCAL
2. 卷所有权 fail-closed：volumeId 不匹配（含空/未知）不删密文；SyncEngine 流程内计 FAILED；浏览器/手动路径 index-only delete 返回成功为合法语义
3. CancellationException 始终先传播，不被 catch(Exception) 吞
4. AEAD v2 格式不变（header/AAD/nonce/64KiB chunk/明文坐标是存储契约）
5. 随机读失败必须抛异常，不返回脏明文
6. 敏感信息不进日志/UI：密码/密钥/凭据/卷标识/path/底层 message 不暴露给用户
7. 结构化错误不回退到 Throwable.message：UI 用错误码→资源文案

**环境与验证**：
- 一切 Gradle 命令须 `JAVA_HOME=/home/mar/android-studio/jbr`
- Kotlin LSP 已禁用——`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` 是类型/语法验证唯一来源；代码改动后必须跑
- 全量单测：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest`
- 测试约定（conventions.md）：JUnit4 + MockK + Coroutines Test；断言枚举/数量/调用次数，**绝不**断言用户文案；suspend 用 `coEvery`/`coVerify`；CE 不吞；`isReturnDefaultValues=true` 已配置

**禁入**：
- `docs/ocmar/plans/2026-09-05-comprehensive-bug-repair.md` 是被否决的旧总计划（评审 3.5/10），**禁止打开、禁止引用、禁止按其实施**
- 不改 `AGENTS.md`、`docs/specs/**`、`scripts/**`、`app/build.gradle.kts`（计划均不要求改 build 配置；Robolectric 4.13 与 isReturnDefaultValues 均已就位）
- 不 `git commit` / `git push`——所有改动留在工作树供原会话 review
- 不改各车道写域之外的源文件（写域映射见 §5）

## 2. 通用执行纪律（源自本阶段实测教训）

- **计划修订纪律**：每源文件只读一次；自顶向下一次成文；API 不确定时定点查证一次即前进；修订期间不跑 gradle；**每修完一节立即落盘**（防中断丢稿）。
- **评审独立性**：同一artifact的修订者与评审者不得为同一会话/上下文；评审者只读，禁止改文件。
- **并发与限速**：历史上同账户 2 个并发重型子代理三度触发账户级速率限制（「您的账户已达到速率限制」）；「1 重型修改者 + 1 评审者」并发实测可行。由你按你方模型配额决定并发度；同账户重型任务建议串行。
- **零输出恢复**：子代理 completed 但零输出 + 磁盘无改动 = 中途失联特征。应先磁盘核实（文件行数/关键串），再复用原会话续跑（上下文仍在），而非盲目重派新会话。
- **评审输出格式**（统一要求）：
  ```
  VERDICT: PASS | FAIL
  SCORE: X.X/10
  [FAIL] 问题列表：每条 [Critical|Major|Minor] + 位置(文件:行) + 为什么 + 具体修改要求
  [PASS] 残留风险清单
  原问题逐项：已修复 / 未彻底（说明）
  申诉逐项：接受 / 拒绝（理由）
  ```
- **门禁标准**：>=9.5 且无未决 Critical/Major 才 PASS；不过则携问题清单返工（原会话复用优先），返工→复审循环直至通过。

## 3. 当前状态

| 车道 | 计划文档 | 行数 | 门禁状态 |
|---|---|---|---|
| L1 sync-engine（BUG-001/007/008/009/010/018） | `docs/ocmar/plans/2026-09-05-plan-sync-engine.md` | 1266（v3.1） | 评分史 7.2→7.0→8.0；v3.1 **待复审** |
| L2 smb-dao（BUG-006/011/012/013/020/022） | `docs/ocmar/plans/2026-09-05-plan-smb-dao.md` | 868 | 6.5→8.6→9.2→**9.6 PASS**，可直接实施 |
| L3 settings-security（BUG-003/004/005/014） | `docs/ocmar/plans/2026-09-05-plan-settings-security.md` | 545 | 7.8→8.0；**待返工 2**（问题清单见 §4.2） |
| L4 player-browser-util（BUG-002/015/016/017/019/021） | `docs/ocmar/plans/2026-09-05-plan-player-browser-util.md` | 772 | **待修订+首审**（吸收 L2 交接项，见 §4.3） |

git 状态：main @ `2f785ce`，生产/测试零改动；未跟踪文件仅为上述 4 计划 + 被否决总计划 + 本文档。

## 4. 阶段 A：计划收口（三线并行或按限速串行）

### A1. L1 复审（计划已修订至 v3.1，只差评审）

派独立评审角色（只读）复审 v3.1。门槛 9.5。除按 §2 格式输出外，重点核对上一轮 7 项+2 附注是否真正修复：

1. LAZY 启动窗口：v3.1 称已拆「创建/注册/启动」三条语句，#16 改 `backgroundScope + UnconfinedTestDispatcher(scheduler)` + mutation check——验证代码形态与 Kotlin RHS 求值序论证、测试去掉修复后确实变红
2. 停服资格 TOCTOU：`onSyncRequested` 入口**先**作废令牌+取消计时器**再** trySend；新增 #22 锁「已交付未恢复窗口旧 ELIGIBLE 到期不停服」；终局性证明补前提（pass 正常完成+stopDelayForPass 非 null）
3. Service 全量接线：cancelSync→CANCELLED+cancelActiveDrainAndArmForcedStop；onTimeout→cancelActiveDrain+立即停；onDestroy→coordinatorScope.cancel()；FORCED/ELIGIBLE 分流失效协议；#20/#21 覆盖旧 FORCED 回调漏网——对照生产 `app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:127-139` 验证接线完整性
4. existsStrict 收窄：SAF 全路径 strict traversal（中间目录 null cursor/异常/EXTRA_LOADING→IOException）；FileVolume 用 `Files.readAttributes`（仅 NoSuchFileException→false）；显式支持边界（provider 完整一致+无外部写者）；S8/S9/S10 新测试
5. 清理 CE 防护：cleanUpAttempt 探测单独保护（catch→UNKNOWN 继续清理）；管线 13b（原 CE 实例 `===` 上抛+保守删行+final 不删）；probePendingTmp 普通 API 的 CE 契约不变
6. 测试确定性：S2 改「合法非负超格式上限 plainLength」；#17 FakeStopScheduler detachOnCancel+firedTokens 双回调断言；#10 相对增量；#13 删重入模拟改「无重入前提」测试；#14 记录切片断言
7. 术语：NOT_FINALIZED 统一；AMBIGUOUS 非第四阶段；START_REDELIVER_INTENT 不作恢复保证
- 附注(a) 弃置契约=「取消后不再完成该请求」；(b) 三重校验降级分层措辞（令牌主门+纵深），isActive≠idle

**v3.1 的 5 项申诉需逐项裁决**（作者主张均给了源码证据）：①分层措辞替代三校验单项失败用例；②#11/#12 排空断言降级为回归锁；③FORCED/ELIGIBLE 分流即失效协议；④删 #13 重入模拟（真实接线无重入路径，SyncService.kt:122-125）；⑤探测 CE 可达性边界如实标注（生产路径 NonCancellable+无挂起点，仅 mock 可达）。
另须主动猎捕 v3.1 新引入的问题（新接线/新测试/新契约的内部一致性）。

### A2. L3 返工 2 → 复审

**返工**（修订角色，改 L3 计划文档）5 项问题（上轮复审 FAIL 8.0 的剩余项）：

1. **[Critical] 缓存协议仍有未闭合交错**（计划 :353-360、:395-418）：`cacheInitialized` 检查与 generation 快照在锁外，可观察到发布中间态——collector 在 synchronized 内已 incrementAndGet（generation→1）但未写字段时暂停；冷启动见 initialized==false、genBefore==1、first() 得 false；collector 随后写 fail-closed true+initialized=true；冷启动进锁发现 generation 仍 1 → 发布 false 覆盖故障态。AtomicLong+两个 volatile 不构成整体原子快照；且 :411-417 直接写字段代码与「publishCache 唯一入口」声明不符。
   改法：同一 `cacheWriteLock` 非挂起临界区内完成「是否需要冷读」判断+generation 快照（已初始化则跳过读取）；`first()` 仍锁外；条件检查与发布同一锁内；统一收口或如实声明两个遵循同一锁协议的入口；补「已递增 generation 尚未写字段」确定性交错测试（现 T12 只覆盖另一种顺序）。
2. **[Major] T14 `dispatchTimeoutMs` 救不了同线程阻塞的 runBlocking**（:453、:469、:480）：测试线程进入永不返回的内层 runBlocking 后外层驱动无法继续，dispatch timeout 不是线程看门狗；修复前 T14 可能挂死套件。
   改法：冷启动调用放独立可清理真实线程/executor，测试线程有界等待；超时后 interrupt/cancel 回收工作线程、回传其异常；用「临时移除生产超时」变异验证测试能限时失败且不遗留线程；收窄 :427/:436/:489「主线程最多阻塞 2s」绝对保证——协程超时依赖协作式取消，非任意阻塞 I/O 硬实时截止；保留当前 DataStore 可取消读取路径的有界等待设计，但不声称一般磁盘 hang 已无条件消除。
3. **[Major] T8 恢复后源更新缺调度同步点**（:463）：`runCurrent→advanceTimeBy(5_000)→runCurrent` 正确，但后半段 `source.value = true → ON_STOP/ON_START → assertTrue` 缺 runCurrent()——StandardTestDispatcher 不会在 StateFlow.value 赋值时立即执行 collector，首次上锁断言会假失败；写 false 场景同病。
   改法：每次 source.value 变更后、生命周期事件前显式 runCurrent() 或等 collector 已消费的同步信号。
4. **[Major] T4a/T4b external 成功发布路径缺 Android URI 脚手架**（:263-264）：新用例首次引入非空 external URI；JVM stub 下 `Uri.decode()` 返回 null → `fallbackUriLabel()` 字符串操作失败 → resolveTreeDisplayName 的 catch 再调同一 fallback 仍逃逸 → 新刷新 C 走降级路径而非成功发布（对照 `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:191-212`）。
   改法：为两用例显式配置目录显示名解析 JVM 桩（含 fallback 所需 URI decode）或直接桩住显示名解析边界；**释放旧刷新 A 栅栏前**先断言 C 已发布 isExternal=true/canSwitch=true，再释放 A 断言保持不变——确保测的是过期发布而非环境异常；静态桩测试结束清理。
5. **[Minor] T4 默认值引用仍是展示字符串断言**（:262）：`StorageState().usableSpaceLabel` 仍是用户可见标签，conventions.md:9 无「引用默认值即可例外」条款（该项申诉已被拒）。
   改法：删标签相等断言，保留 canSwitch/canReset 布尔断言+补 DAO/空间查询未执行等调用次数证据；不为测试扩展生产状态模型。

上轮申诉裁决（返工须按此执行）：a（保留 2s 有界同步等待）**接受设计但须修 T14 与边界表述**；b（版本化发布）**方案可行但须先修锁外快照原子性**；c（代次失效）接受，补「检查到赋值非原子、当前 Main confinement 下成立」的准确表述；d **拒绝**，按第 5 项改。

**复审**（独立评审角色）：核对上述 5 项+申诉落实；重点：锁协议原子性论证是否闭合全部交错、T14 线程隔离可执行性、T4a/T4b 桩正确性、无新引入问题。门槛 9.5。

### A3. L4 修订 → 首审

**修订**（修订角色）：L2 计划（已定稿 9.6）把一部分工作移交 L4——读 `docs/ocmar/plans/2026-09-05-plan-smb-dao.md` 的 L4 交接章节（§写域边界 :17、「移出本 lane 写域」清单 :709、L4-3 共用规则 :778-847、L4-4 门禁 :831），将其整合进 L4 计划（`2026-09-05-plan-player-browser-util.md`），要点：
- **L4-1 保存入口协议校验**：协议未知（relation_invalid_remote_path）→不保存的入口策略；SMB/SFTP 路径策略分开（SFTP 绝对/相对语义保留、`.` 不转 root、`..` 拒绝范围明确）——涉及 `settings/SyncRelationEditViewModel.kt`
- **L4-2 增益调用点替换单列 API**：`settings/GainAnalysisViewModel.kt:160-162` 与 `retryFailed:112` 两入口改用 L2 新增的 DAO 单列 `updateAudioGain(id, Float?)`（API 规格以 L2 计划为准）
- **L4-3 三个 VM 测试类**（严格照搬 L2 计划的共用规则①双条件终态+真实时间有界等待、②五步清理顺序）：L4-1 契约测试；类 A（mock 契约，mockkObject(MediaUris)——非 mockkStatic）；类 B `GainAnalysisConcurrencyTest`（真实 Room in-memory + CountDownLatch 门控）
- **L4-4 实施门禁**：compileDebugKotlin + 全量单测
- 同时保留 L4 原有 6 BUG（002 FIFO 保存/015 stale load/016 undo/017 share 唯一名/019 队列/021 文件大小）内容；注意作者已自查项：`FileOperationsUseCaseTest:507-543` 截断用例须随唯一名方案同步更新（唯一必改的既有测试）
- 写域：`player/**`、`browser/FileOperationsUseCase.kt`、`util/ShareTempFiles*`、`FileSizeFormatter*`、`settings/GainAnalysisViewModel.kt`、`settings/SyncRelationEditViewModel.kt` + 对应测试

**首审**（独立评审角色）：门槛 9.5。重点：015 代次守卫两挂起点覆盖、016 auto-commit 对称契约（:545）、019 耗尽语义（i18n 红线）、017 createNewFile 原子独占、共用规则①②与 L2 定稿逐字一致、类 B 测试在 JVM+Robolectric 4.13 的可执行性、写域不越界（不碰 DAO/SMB）。

## 5. 阶段 B：实施（TDD，按车道并行）

**前置**：每车道只在**自己的计划过门禁后**开工。L2 已过可立即开工；L1/L3/L4 待 A 阶段。**L4 的 L4-2/L4-3 依赖 L2 代码先落地**（新 DAO API），其余 L4 项无依赖。

**写域映射**（冲突即事故，严格执行）：

| 车道 | 生产代码 | 测试代码 |
|---|---|---|
| L1 | `sync/SyncService.kt`、`sync/SyncEngine.kt`、`sync/SyncDownloadPipeline.kt`、`sync/SyncDeleteExecutor.kt`、`sync/SyncProgressPublisher*`、`sync/SyncServiceDrainCoordinator.kt`(新)、`sync/sftp/SftpFileClient.kt`、`storage/volume/{EncryptedMediaStore,SafVolume,FileVolume,MediaVolume}.kt` | `app/src/test/java/cn/vectory/syncplayer/sync/**` |
| L2 | `sync/smb/SmbFileClient.kt`、`sync/SyncRemoteScanner.kt`、`data/local/dao/{VideoItemDao,PlaybackRecordDao,SyncRelationDao}.kt`、`data/repository/VideoRepository.kt` | `app/src/test/java/cn/vectory/syncplayer/data/**`、`sync/smb/**` |
| L3 | `settings/SettingsViewModel.kt`、`settings/StorageManagementViewModel.kt`、`security/AppLockManager.kt` | `app/src/test/java/cn/vectory/syncplayer/{settings,security}/**`（Gain/SyncRelation 除外） |
| L4 | `player/**`、`browser/FileOperationsUseCase.kt`、`util/ShareTempFiles*`、`util/FileSizeFormatter*`、`settings/GainAnalysisViewModel.kt`、`settings/SyncRelationEditViewModel.kt` | `app/src/test/java/cn/vectory/syncplayer/{player,browser,util}/**` + 两个 VM 的测试类（L4-1 契约/类 A/类 B） |

边界纠纷原则：两车道都声称要改的文件不存在；若实施中发现计划间隐含冲突，停下记录到简报，不得自行跨域修改。

**实施纪律**：
- 严格按过审计划执行：TDD 红→绿顺序、测试名、验收矩阵照计划；发现计划与源码不符（行号漂移等），最小合理适配并在简报中记录偏差与理由
- 每车道完成或中途每完成一个 BUG：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin`；车道全绿后跑定向 `--tests` 过滤的该域测试
- 不删既有测试除非计划明示（已知唯一：FileOperationsUseCaseTest:507-543 随 017 方案更新）
- 并行度：写域不重叠可并行；同账户重型子代理并发注意 §2 限速教训

## 6. 阶段 C：全量验证

全部车道完成后（工作树状态）：
```
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```
- 两命令必须全绿（0 fail）；任何失败→修复→重跑（修复也应遵循红线）
- `git status` 核对：改动文件 ⊆ 四车道写域 ∪ 计划文档；无 spec/build/脚本改动

## 7. 阶段 D：简报与回交

写报告 `docs/ocmar/reports/2026-09-05-execution-report.md`（目录不存在则创建）：
1. **车道门禁史**：每车道各轮评分与终态（须全部 PASS >=9.5，附最后评审的 VERDICT/SCORE 原文）
2. **改动清单**：每车道逐文件（路径+增删行数）+对应 BUG 映射
3. **TDD 证据**：每 BUG 红→绿测试名摘要（不必贴全部日志，贴关键断言名）
4. **验证结果**：compileDebugKotlin 与 testDebugUnitTest 的最终输出摘要（通过/失败/忽略计数）；`git status` 全文
5. **偏差与申诉**：实施中偏离计划的所有决定（行号漂移适配、计划错误修正等）+理由
6. **残留风险**：评审 PASS 时附的残留风险清单原文+实施后评估
7. **简报正文**：≤30 行的执行摘要（可直接转交原编排会话）

完成后：将简报路径告知用户转交原会话；若你的运行时有跨主会话消息能力，也可直接通知标题含 "sync-player" 的原编排主会话。**不要自行发版、不要 commit。**

## 8. 22 BUG → 车道速查

| 车道 | BUG |
|---|---|
| L1 | 001 remote-first DELETE_BOTH+卷保护 / 007 finalize 后取消清理 / 008 progress 终态 / 009 SFTP NO_SUCH_FILE 幂等 / 010 初连重试 / 018 服务尾部 pending race（状态机最复杂） |
| L2 | 006 SMB root/E8 / 011+012+020 LIKE 前缀匹配族 / 013 单列增益更新 / 022 路径防御 |
| L3 | 003 Settings 流恢复 / 004+005 Storage DataStore IO / 014 AppLock fail-closed |
| L4 | 002 progress FIFO 保存 / 015 stale load / 016 undo 自动提交 / 017 share 唯一名 / 019 队列耗尽 / 021 文件大小格式化 |

## 9. 历史教训速查（避免重蹈）

- 同账户 2 重型并发 → 速率限制三连杀；修改+评审混合并发 OK
- 子代理零输出+磁盘无变化 = 失联，复用会话续跑（上下文还在），别盲目新开
- 计划写作要增量落盘（每节即写），防长文一次成稿被中断清零
- `advanceUntilIdle()` 在只剩 backgroundScope 工作时会提前停（coroutines 1.10.1）——测试推进用 runCurrent/advanceTimeBy 精确步进（各计划 Harness 规则已固化此约定）
- `.catch {}` 无 emit 会正常 complete 流——恢复必须 retryWhen（L3 已定稿方向，源码级验证过）
