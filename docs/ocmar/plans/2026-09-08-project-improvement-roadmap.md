# 项目改进路线（建议方案 · 未批准实施）

> **阅读顺序更新**：先读[主分析者优化版](../../analysis/2026-09-08-recommended-direction.md)。本文保留原始任务分解与证据映射；优先级、推荐选择及执行范围以优化版为准。本文不是全量必做清单。

- **日期**: 2026-09-08 ｜ **基线**: `93c5f47ae0f4d6a7452e7192f94510bdea7f0850`
- **上游报告**: [docs/analysis/2026-09-08-project-review.md](../../analysis/2026-09-08-project-review.md)

## 0. 状态声明（必读）

1. **本文档是静态分析衍生的建议路线，不是已批准的实施计划。** 任何任务开工前需用户显式确认。
2. **不包含完整补丁，不假定未定接口的实现细节**——凡涉及 schema、错误协议等未定决策的任务，先列决策项，决策后才能进入实现。
3. **本文档仅为静态方案，后续实施与验证均不在本轮执行**；「未来实施后验证建议」仅为行为级描述（不含 shell 命令集），届时由验证 owner 制定。
4. 阶段内任务无依赖关系者可并行；跨任务依赖见任务表「依赖」列与 §8 批次。

---

## 1. Goal（目标）

在不触碰既有安全契约与架构选型的前提下，修复已确认的正确性/健壮性缺口（F01–F06）、收敛维护性债务（F07–F14）、对齐文档与 CI 契约，并为 Provider/Service 层的集成盲区补上验证设计。

**不改变的前提**（= Architecture 约束）：
- 保持单 app、Single Activity + Compose、MVVM + Repository（Player 为 MVI）、Hilt DI。
- **AEAD v2 格式不变**（`SPM2` header 16B / iv 8B / chunk 64KiB / tag 16B / nonce / AAD —— `docs/specs/crypto.md` 存储契约）。
- E8 扫描失败保护、卷所有权 fail-closed、CancellationException 优先传播等安全红线不动。
- Room v2 + 破坏性迁移的现状下，**任何 schema 变更必须单独过数据安全决策**。

## 2. Tech Stack（不变量）

Kotlin + Coroutines/Flow、Room 2.x（dropAllTables 迁移）、DataStore、Media3、Hilt、sshj/jcifs、Jetpack Compose；CI 为 Gitea Actions（branch-check / integration-check / release 三 workflow）。

## 3. Global Constraints（全局约束）

1. 敏感信息不进日志/UI：凭据、卷标识、底层 message、完整远端路径不暴露给用户；logcat 诊断允许 relativePath 级别信息（`docs/specs/storage-security.md:49-52`）。
2. 结构化错误不回退 `Throwable.message`；UI 一律错误码 → 资源文案。
3. 所有用户可见文案入 `app/src/main/res/values/strings.xml`；Repository 不生成文案。
4. 阻塞 I/O 在 `Dispatchers.IO`（注入 `@IoDispatcher`，可测）；测试断言枚举/数量/调用次数，不断言文案内容。
5. 执行 lane 边界：**机械逻辑/协议/调度/schema → fixer**（fixer-glmf 等）；**UI 文案、提示流、交互布局 → designer 设计并实现**；两者交接以「结构化状态 + 资源 ID」为契约。（不依赖本地不存在的技能引用。）
6. 类型/语法验证的唯一来源是 Gradle `compileDebugKotlin`（AGENTS.md 约定，无 LSP 兜底）；**该验证属实施期活动，本文档阶段不执行**，仅作为各任务「未来实施后验证建议」的固定项。

---

## 4. 阶段总览

| 阶段 | 主题 | 任务 | 前置 |
|---|---|---|---|
| **P0** | 高风险正确性 + 发布安全 | T1 日志边界、T2 搜索管道、T3 结果协议决策、T4 发布 ref 校验 | 无（T3 只做决策） |
| **P1** | 设置页状态治理 | T5 设置状态 I/O 治理 | 无（独立于 T3） |
| **P2** | 一致性与防御增强 | T7 调度一致、T8 discovery 重入、T9 编辑结构化状态、T10 轻量完整性、T11 发布产物对齐、T12 文档漂移 | 无（T9 独立实施；若 T6 已落地可**可选**对齐其结构模式）；T6 若排入需 T3 完成 |
| **P2'** | 协议落地 | T6 结果协议落地 | **T3（硬前置）**；若走 schema 路线另需数据保护措施 |
| **P3** | 集成验证设计 + 待定项 | T13 集成测试设计、R 项处置 | P0–P2 收口；**不自动开工** |

---

## 5. 任务详情

> 每任务字段：来源发现 ｜ 文件范围 ｜ 内容 ｜ 依赖 ｜ 方案取舍 ｜ 验收标准（静态可检查项，实施后核验；编译/测试类验证归入「未来实施后验证建议」，本轮不执行）｜ 未来实施后验证建议 ｜ 回滚风险 ｜ lane 边界

### P0

**T1 同步扫描日志边界（来源 F01，P1/确认）**
- **文件**: `app/src/main/java/cn/vectory/syncplayer/sync/SyncRemoteScanner.kt:182`
- **内容**: dryRun 失败处不直接传 Throwable 给 `DebugLogger.e`，改用 `SyncSafeLog.failure` 安全摘要（与 `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:678-679` 同款）；logcat 中相应记录的即为同一安全摘要。
- **依赖**: 无。
- **取舍**: 沿用既有 SyncSafeLog mapper（纯格式化函数、有 SyncSafeLogTest 锁定）而非新造日志工具；UI 与 logcat 记录的安全摘要语义一致，不承诺保留更多原始诊断。
- **验收标准**: A-T1-1 `SyncRemoteScanner` 中不再出现 `DebugLogger.e(TAG, …, scanError)` 形态的 Throwable 传参；A-T1-2 `SyncSafeLog` 用法与 Pipeline 一致。
- **未来实施后验证建议（非本轮执行）**: `compileDebugKotlin` 通过；触发 dryRun 失败后，DebugLogScreen 展示安全摘要（无堆栈/底层 message）。
- **回滚**: 单行级改动，git revert 即可；无数据/契约影响。
- **lane**: fixer。

**T2 搜索流单管道化（来源 F03，P1/确认）**
- **文件**: `app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:64-76`
- **内容**: 收敛为 `_query.debounce(300).flatMapLatest { query -> combine(repo.search(query…), scopedFolderPaths, _searchScope) { …build(query,…) } }`；同一 query 值捕获进整个下游。**不得只给两条分支各加 debounce**。
- **依赖**: 无。
- **取舍**: flatMapLatest 天然取消旧查询（保留现语义）；不做额外 stateIn 重构，改动最小化。
- **验收标准**: A-T2-1 `_query` 仅剩一条下游订阅路径；A-T2-2 空 query 短路（现 :69,74 行为）、scope 切换语义保留。
- **未来实施后验证建议（非本轮执行）**: `compileDebugKotlin` 通过；若有可复用的 Search 相关单测（仓库现有测试非本 VM 专用，不假设其覆盖此 VM）按需扩展：快速连续输入 + 中途切换 scope，结果集始终对应最终 query；DAO 查询仅 debounce 后触发。
- **回滚**: 单文件改动，revert 即可。
- **lane**: fixer。

**T3 同步结果协议决策（来源 F02，P1/确认 —— 仅决策，不改代码）**
- **内容**: 确定结构化终态的持久化方案。决策项：
  1. 载体：a) `app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncRelationEntity.kt` 新增字段（phase/错误码/统计）；b) 新表；c) 不动 schema（运行时内存态 + DataStore 键值）。**风险对比（修正版）**：a 与 b 都改 Room schema，在当前 `dropAllTables=true` 迁移策略下**同样可能触发破坏性 fallback**；c 虽不动 schema，但引入 DataStore 与 DB 的跨存储一致性问题（关系删除/重建、旧 relationId 残留清理），**并不天然更安全**。
  2. **推荐方向**: 优先同 Room 内 typed 终态（a 或 b）；实施前先确认「针对性非破坏迁移」（编写迁移脚本保留数据）能否作为破坏性迁移规范的**显式例外**（需用户批准并修订 `docs/specs/conventions.md#database`），或由用户明确接受一次性重同步成本。**不把 DataStore 当默认规避手段**。
  3. 历史数据兼容：`lastSyncResult`（`SyncRelationEntity.kt:29-30`）旧文案无法可靠反推状态——解析不出的一律置 **UNKNOWN（保守处理，不得默认成功）**。
  4. 状态枚举至少考虑：无记录（NULL）/成功/部分失败/失败/取消（若同步可取消）/未知历史（UNKNOWN）。
  5. UI 映射：各状态展示（颜色/图标/详情可见性）由 designer 设计。
- **依赖**: 无（但 T6 被 T3 阻塞）。
- **取舍**: 强约束——Room v2 破坏性迁移会 drop 全部表；**若涉 schema，必须先回答如何保护媒体索引与播放历史，绝不能悄悄升 schema**。文档与文案改动（换关键词）一律不是方案。
- **验收标准**: A-T3-1 决策记录写入本文件附录或独立决策笔记，明确「选定载体 + schema 影响评估 + 保护措施 + 旧数据 UNKNOWN 策略 + 枚举全集」。
- **未来实施后验证建议（非本轮执行）**: 决策评审通过后 T6 方可开工。
- **回滚**: 决策可推翻重议，无代码影响。
- **lane**: 用户/父 agent 决策，fixer 提供技术评估。

**T4 发布流程 ref/tag 一致性校验（来源 F11，P1/P2）**
- **文件**: `.gitea/workflows/release.yml:8-12,74`；`scripts/ci/release-check.sh:11-12`；`scripts/gitea/publish-release.sh`
- **内容**: ① 删除裸 `workflow_dispatch`，或改为必填 tag 输入：workflow **checkout 该选定 tag 本身**（而非默认分支的 `github.sha`），再校验 checkout 的 commit 与 `git rev-parse "refs/tags/$TAG^{commit}"`（**解引用到 commit；不可用 raw tag object 比较**）一致；② 发布脚本接收 tag 时做同样的 `^{commit}` 解引用校验；③ publish 步骤保持既有默认成功传导（无 `continue-on-error`/`if: always()` 绕过即可，**无需额外强制加装 `if: success()`**——workflow 步骤默认仅在前序成功时执行）。
- **依赖**: 无。
- **取舍**: 「删除 dispatch」最简但牺牲手动补救通道；「required tag + tag checkout + commit 校验」保留灵活性且不误拒合理 dispatch。两案均**只建议在隔离 sandbox/fixture（本地裸仓 + workflow 干跑）验证**，**不要求真跑生产 release 作为测试**。
- **验收标准**: A-T4-1 workflow YAML 中不再存在无输入的 `workflow_dispatch:`（或 dispatch 输入含必填 tag）；A-T4-2 脚本含 `refs/tags/$TAG^{commit}` 解引用校验且与 checkout commit 比较；A-T4-3 publish 步骤无成功传导绕过。
- **未来实施后验证建议（非本轮执行）**: sandbox 干跑：在非 v* 分支手动触发 → 应直接拒绝；正常 tag 流程 → 发布产物与 tag commit 一致。
- **回滚**: 恢复 workflow 原文即可；无数据影响。
- **lane**: fixer。

### P1

**T5 设置页状态 I/O 治理（来源 F04 + F05 + F06）**
- **文件**: `app/src/main/java/cn/vectory/syncplayer/settings/SettingsScreen.kt:78-87`；`app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:228-243`（含 `fallbackUriLabel` :244-249）；`app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:54-88,182-199`；`app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementScreen.kt:35-46`
- **内容**:
  1. treeUri 显示名移入注入 IO 的状态源（VM 内按 URI 刷新的 StateFlow），组合期零阻塞；失败/无权限回退**固定资源通用文案**（如「已选择的文件夹」类通用 label，文案由 designer 定）——**不复用 `fallbackUriLabel` 作安全回退**（其在解析失败时回退为完整 URI 字符串，:244-249）；合法 displayName 的展示策略另行确定。
  2. 移除 `SettingsState.usableSpaceLabel` 及 `refreshUsableSpace` 无效 IO（SettingsScreen 无消费者）；若需保留则 latest-wins 收敛写回。
  3. `takePersistableUriPermission` 的 SecurityException 改为安全错误事件 + 重试入口（文案走资源，不泄漏 URI/message）。
- **依赖**: 无。
- **取舍**: 删除无用状态优于新建全局空间监控框架；显示名刷新按「URI 变化 + 手动重试」粒度，不做轮询。
- **验收标准**: A-T5-1 SettingsScreen 组合期无 ContentResolver 调用（直接/间接均可 grep 验证）；A-T5-2 usableSpace 相关代码移除后 rg 零引用；A-T5-3 错误事件为结构化类型且回退 label 来自固定资源（非 URI 派生）。
- **未来实施后验证建议（非本轮执行）**: `compileDebugKotlin` 通过；设置页打开不卡首帧；换无权限 URI 出现错误提示与重试；偏好连续变更不产生可用空间读放大。
- **回滚**: VM/Screen 各自独立可 revert；无存储层影响。
- **lane**: 1、2 为 fixer；3 与 1 的通用 label 文案、错误提示与重试交互由 **designer 设计并实现**，fixer 仅做状态源机械接线。

**T6 同步结果协议落地（来源 F02，前置：T3）**
- **文件（写入点全列，据 grep `updateLastSync`）**:
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:169`（空间不足中止分支）、`:663`（成功/PARTIAL_FAILED）、`:687`（FAILED）——**不止 642-687，早期错误与空间不足分支同样写文案入库**；
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncDeleteExecutor.kt:181,186,204`；
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:129,142`（preview up-to-date 文案）；
  - 通道与存储：`app/src/main/java/cn/vectory/syncplayer/data/repository/SyncRelationRepository.kt:94-95`、`app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:45`、`app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncRelationEntity.kt:29-30`（按 T3 选定载体调整）；
  - UI：`app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationListScreen.kt:80-99`；
  - 只读复用：`app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt`。
- **内容**: 按 T3 决策持久化结构化终态（phase + 统计 + 错误码）；**所有列出的写入点**改写结构化数据、不再拼 `sync_result_success` 等文案入库；列表 UI 以结构化字段驱动状态与详情；资源文案仅展示层。
- **依赖**: **T3（硬前置）**；若走 schema 路线，另需 T3 决策的数据保护措施先行。
- **取舍**: T3 枚举全集为最小完备集（含 UNKNOWN）；不重写 ErrorMessageMapper，错误码原样持久化、展示时映射。**不暗示仅改文案能修复**。
- **验收标准**: A-T6-1 上列写入点均不再出现 `getString(sync_result_success` 等文案写库路径；A-T6-2 UI 判定不再 `contains` 任何文案常量；A-T6-3 旧文案无法解析时展示 UNKNOWN（不默认成功）。
- **未来实施后验证建议（非本轮执行）**: `compileDebugKotlin` + `testDebugUnitTest` 通过（新增映射有单测，断言枚举/码值）；部分失败 → 列表状态**与成功可区分且失败详情可见**（具体颜色/图标由 designer 设计实现）；任意 `sync_error_*` 场景 → 显示失败态而非成功色；详情文案与错误码一一对应。
- **回滚**: **前向修正为首选**（新代码兼容读旧文案值并迁移到新表示）；**绝不能以「恢复旧 schema」为回滚手段——旧二进制不得直读降级后的 DB**。实施前按 T3 决策明确「迁移前备份/恢复」或「用户明确接受重同步」策略；DataStore 路线也非无风险（跨存储一致性需一并回退考虑）。
- **lane**: fixer 负责协议/存储/UI 状态的机械接线；**视觉与交互（状态区分、详情呈现）由 designer 设计并实现**（非 designer 只出建议、fixer 代做）。

### P2

**T7 调度一致性（来源 F07）**：`app/src/main/java/cn/vectory/syncplayer/browser/BrowserContentCoordinator.kt:409-419` 的 I/O 段（Room 查询 `getSubFoldersOnce`）改用已注入的 `ioDispatcher`（:56）。验收标准：A-T7-1 该 loadSubFolders I/O 段使用注入 dispatcher、测试可注入替换（**不要求全文件无 `Dispatchers.Default`**——其它纯计算段合理保留）。回滚无风险。lane：fixer。未来实施后验证建议（非本轮执行）：`compileDebugKotlin` 通过。

**T8 增益分析重入防护（来源 F08）**：`app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:73-102`。先核实 UI 可达连点路径；为 discover 引入**独立的** `discoverJob` 或 epoch 门控（single-flight 或 latest-wins 二选一，明确定义语义即可，不大改）。**不得复用 `analysisJob`**（:70）——那是 `launchAnalysis`（:119-127）的分析执行取消句柄，复用会混淆 discover 与分析两个生命周期。验收标准：A-T8-1 discover 重入行为有注释明确定义且逻辑单一（独立 job/epoch）；A-T8-2 多 flow 写入路径唯一。回滚无风险。lane：fixer。未来实施后验证建议（非本轮执行）：`compileDebugKotlin` 通过；连点 discover 无旧结果覆盖新结果。

**T9 编辑页结构化保存结果（来源 F09）**：`app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:245-259`、`app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:193-200,226-229`（:38 仅取资源、非控制流）、对应 VM（`app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:364`、`app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:111`）。saveResult/testResult 改结构化（sealed/枚举）驱动颜色与导航，移除全部 `contains` 判定与冗余 `source_save_success` 匹配。**独立实施，不依赖 T6**（编辑页内聚改动）；若 T6 已落地可**可选**对齐其结构模式。验收标准：A-T9-1 rg 验证两个 Screen 内无 `contains(` 用于状态判定；A-T9-2 导航由类型分支触发。lane：fixer（结构）+ designer（状态展示设计实现）。未来实施后验证建议（非本轮执行）：`compileDebugKotlin` 通过；编辑页保存/测试连接的颜色与导航行为不变。

**T10 落盘后轻量完整性检查（来源 F14）**：`app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:129-143`。现有探针仅 4B magic（:132 `ByteArray(CryptoScheme.MAGIC.size)`）；扩为 O(1) 的**完整 16B header 解析**（含 header 声明的 plaintextLength）+ 实际密文大小 + **可信预期明文长度**三方匹配。**接口依赖需先明确**：当前 `verifyReadable(id)` 无 fileSize 参数，且不能假设 DB 在 upsert 前有最终 metadata——预期长度由 pipeline 侧已有 `action.fileSize` 显式传入，或先定义该接口契约。**不做全文 GCM**（昂贵，播放路径 RangeDecryptor 已认证兜底）。验收标准：A-T10-1 header 字段解析复用 `app/src/main/java/cn/vectory/syncplayer/crypto/CryptoScheme.kt` 既有常量、无格式重复定义；A-T10-2 检查失败抛异常（不返回脏明文）；A-T10-3 预期长度来源为显式参数（无 DB 假设）。回滚：探针增强失败路径需与 SyncEngine finalize 补偿链（`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:393-432`）兼容。lane：fixer。未来实施后验证建议（非本轮执行）：`compileDebugKotlin` + 相关单测；人为截断密文文件后 finalize 探针应拒绝。

**T11 发布产物契约对齐（来源 F10）**：`docs/release.md:16,137,157,301,357`、`docs/specs/gitea-cicd.md`（mapping 约定同需对齐）、`scripts/release.sh:19,122`、`.gitea/workflows/release.yml:3` 注释 → 统一为「APK + SHA256SUMS（个人侧载，不上传 mapping；本地保留可选）」。**依现有有意行为对齐文档，不把有意不上传当构建故障**。与 T4 同涉 release 脚本/CI 文件域，**需串行执行**（见 §8）。验收标准：A-T11-1 rg "mapping" 在上述文件中仅出现在「不上传/可选」语义处。lane：fixer（文档）。

**T12 权威文档漂移修订（来源 F12）**：`docs/specs/conventions.md:7` 路径改 `app/src/test`；Gradle 版本以 `gradle/wrapper/gradle-wrapper.properties`（9.3.1）为准修订 AGENTS.md 速查与相关 spec；补记 release.sh 门禁含 lintDebug、非 main 仅 warning。**AGENTS.md 修订需用户批准后进行（本轮不改）**。验收标准：A-T12-1 文档内版本号与 wrapper 文件一致；A-T12-2 路径描述与实际目录一致。lane：fixer（文档）。

### P3（设计先行，不自动开工）

**T13 集成验证设计 + R 项处置（来源 F13 + R1–R6）**
- **内容**: ① 设计 Provider/Service 集成验证场景（跨进程 FD 生命周期、卷切换序列 R2、前台 Service 存活）与 androidTest CI 执行的收益评估——只写设计文档与验收标准，明确真实 IO/墙钟为已接受成本，不强推覆盖率指标；② R 项逐项处置建议（下表）；③ 产出场景清单交验证 owner 排期。
- **依赖**: P0–P2 收口；用户批准后逐项开工。
- **lane**: 设计文档 fixer；场景评审 designer/父 agent。

**R 项处置建议表（T13 输入，均非已证缺陷）**:

| R 项 | 建议处置 | 理由 |
|---|---|---|
| R1 SMB 递归无 ensureActive（`app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:290-327`） | 挂起/低收益观察 | 底层 jcifs 阻塞调用不因加 checkpoint 立即可中断；对齐仅减少白做工 |
| R2 Provider 卷解析 TOCTOU（`app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt:95-117`） | T13 设计序列后单独处理 | 未证错误明文可读（GCM 兜底）；如处理倾向捕获同一卷句柄，不扩全局 mutex |
| R3 浏览器 route/VM 双状态源（`app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt:52-58`、`app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:77`） | 先核实再定 | Hilt 默认 scope 下每 entry 独立实例的复用行为未证明可达 |
| R4 ON_STOP 不自动恢复（`app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:242-262`） | 维持现状 | 产品取舍（AND-001 只管保存），不擅自加 resume |
| R5 TOFU 日志含 host/指纹（`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:49,67`） | 小项独立做 | 网络元数据非密钥泄漏；按隐私策略截短 host 显示即可 |
| R6 listDirectories 错误返回空（`app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:234`、`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:160`；消费方 `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:227,267`） | 维持现状 | 规范已接受残余，不影响 E8 扫描 |

> R7（签名脚本 umask）为**范围外独立建议**，不占风险编号语义、不入 T13 任务范围。

---

## 6. 需求/发现 → 任务矩阵

| 发现/风险 | 优先级 | 任务 | 阶段 | 验收 ID | 状态 |
|---|---|---|---|---|---|
| F01 日志边界 | P1 | T1 | P0 | A-T1-1/2 | 建议/待批准 |
| F03 搜索分叉 | P1 | T2 | P0 | A-T2-1/2 | 建议/待批准 |
| F02 结果协议 | P1 | T3（决策）→ T6（落地） | P0→P2' | A-T3-1；A-T6-1/2/3 | 建议/待批准 |
| F11 发布 ref | P1/P2 | T4 | P0 | A-T4-1/2/3 | 建议/待批准 |
| F04/F05/F06 设置页 | P1/P2 | T5 | P1 | A-T5-1/2/3 | 建议/待批准 |
| F07 调度 | P2 | T7 | P2 | A-T7-1 | 建议/待批准 |
| F08 重入 | P2 | T8 | P2 | A-T8-1/2 | 建议/待批准 |
| F09 contains 判定 | P2 | T9 | P2 | A-T9-1/2 | 建议/待批准（独立实施） |
| F14 完整性 | P2 | T10 | P2 | A-T10-1/2/3 | 建议/待批准 |
| F10 产物契约 | P2 | T11 | P2 | A-T11-1 | 建议/待批准 |
| F12 文档漂移 | P2 | T12 | P2 | A-T12-1/2 | 建议/待批准（AGENTS 部分需批准） |
| F13/R1–R6 | P2/观察 | T13 | P3 | 场景清单评审 | 建议/待批准，不自动开工 |

---

## 7. 暂不做清单（明确排除）

1. **不换播放器架构**：保持 Media3 + MVI + generation/channel 竞态防护现状；不加 ON_START 自动恢复播放（R4 是产品取舍）。
2. **不做全面重构**：不引入多模块化、不做 Repository 层抽象合并、不改单 app 形态。
3. **不动 AEAD v2 格式**与 RangeDecryptor/ChunkedEncryptSink 契约（T10 仅加外部 O(1) 检查）。
4. **不为修 F02/F09 重写 ErrorMessageMapper**，也不以换文案关键词为修复手段。
5. **不默默升 Room schema**：一切 schema 变更先过 T3 数据安全决策。
6. **不重复添加播放器无障碍语义**（AuroraPlayerUi.kt:456-470,515-549,579-582,663-677,781-784,828-836 已有播放/暂停、±10s、设置、旋转、速度单选语义）；browser/search 聚合状态的可发现性、long-click 语义、亮度/音量手势的替代覆盖仅列未来设计与设备验证项。
7. **不评审视觉/布局**（本轮无截图）；不做覆盖率指标强推；不把 Provider TOCTOU（R2）定性为已证漏洞——待 T13 验证设计后单独处理。
8. **不处理本机凭据文件权限**（不在基线证据内）；签名脚本 umask 仅作独立小建议（R7），不捆绑进任何任务。
9. **不改 CI 的既有三条 workflow 分工**（branch/integration/release），T4 仅收紧触发与校验。

---

## 8. 执行批次建议（与 §4 依赖表一致，均待批准）

```
批次 1: T1（sync 日志）      ∥ T2（browser 搜索）        [2 fixer 并行]
批次 2: T3（协议决策）       ∥ T7（browser 调度）        [T3 仅出决策]
批次 3: T4（CI/发布脚本）    ∥ T5（settings 设置页）
批次 4: T8（settings 增益）  ∥ T9（settings 编辑页；独立，不依赖 T6）
批次 5: T11（发布产物文档）  ∥ T10（storage 探针）        [T11 与 T4 同域，分批串行 ✓]
批次 6: T6（协议落地）       ∥ T12（spec 文档）           [T6 硬依赖 T3（批次 2 已完成）]
批次 7: T13（收口设计，不自动开工）
```

- **并行度 ≤ 2 fixer**；同文件域任务跨批串行：T4（批次 3）、T11（批次 5）、T12（批次 6）均涉及 release 脚本/CI/spec 文档，已按批错开，不并行。
- 批次内写域互不重叠（T8/T9 同在 settings/ 但文件不同：GainAnalysisViewModel vs EditScreen/VM）。
- 验收 ID（§6 矩阵）在各批次完成时由引用复核 owner 核验静态可检查项；**编译/测试类验证属实施期活动，本轮与本文档阶段均不执行**（见 §0.3）。
