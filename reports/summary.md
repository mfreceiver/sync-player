# sync-player 代码质量审计汇总（Phase 11 终产物）

> 本报告自包含；仅依据 `reports/00-10` 与 `progress.md` 汇总，未引用任何未落盘记忆。
> **静态审计声明（原文照抄 Phase 0 第 6 条）：本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。**

## 1. 执行摘要（≤10 句）

1. 该代码库工程纪律显著高于个人项目平均水平：四份 spec 的 69 条契约 62 条 PASS，E8/卷所有权/AEAD v2/随机读四条数据安全红线全部有守卫且有测试锁定。
2. 最严重的问题是**红线 3（CancellationException 先传播）在 ViewModel 层系统性失守**：153 个通用捕获点中 35 处（R3_FAIL）位于 VM fire-and-forget 协程，CE 被吞后协程正常完成；其中 1 处（F-2-2）可由用户稳定触发并显示假错误。
3. 引擎/数据层（SyncEngine/FileOperationsUseCase/UndoDeleteUseCase/Provider）的 CE 守卫覆盖率 100%，问题模式高度集中、修复面小（约 10 个函数）。
4. 第二类必改问题是**敏感信息暴露**（红线 6）：卷标识 volumeId 进普通日志与用户可见 UI（F-2-27）、DebugLogger 把异常堆栈原文写入用户可见的 DebugLogScreen（F-2-28）、底层 e.message 经 MetadataRebuild 进用户文案（F-2-30）。
5. R6 的暴露面部分与一个设计耦合：`debug/DebugLogger` 是"logcat+用户可见环形缓冲"双通道，任何经它的日志都等同 UI 曝光。
6. 并发与资源管理基本无可挑剔：零 GlobalScope、全部 Cursor/流 use{}、播放器/服务生命周期配对完整；唯 share 链路在 Main 线程 runBlocking 读 DataStore（F-4-1）。
7. 异常处理结构化程度高：18 个错误码全映射资源文案、202 处 catch 仅 1 处质量缺陷（丢 cause）。
8. 测试纪律与 conventions 一致：零文案断言、零 suspend 误 mock、5 项关键安全行为全部行为级锁定；缺口是 TOFU/协议异常分类器无直接单测。
9. i18n 与配置近乎满分：364 个资源 key 零缺失、依赖版本与 spec 逐项一致且全集中管理。
10. 综合判断：**无数据丢失风险、无加密契约破坏**；P0 全部为红线 3 字面违规（其中 34/35 实际影响限于 VM 销毁期竞态），按任务书映射立 P0，供人工统一再定级。

## 2. Top 10 风险（P0 → 高影响 P1；内嵌完整 canonical Finding）

### F-2-2 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:224
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: `cancelSync()`（:201 `deleteJob?.cancel()`）**主动取消**本协程时，`executeDelete` 挂起点抛出的 CancellationException 被 catch(Exception) 捕获并当作同步失败渲染成用户可见错误（假错误提示），取消未按红线 3 先传播。用户可稳定触发（删除中点取消）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt （exit=0，命中 6 行）
- 建议: 在 :224 前增加 `catch (e: CancellationException) { throw e }`（同文件 :142/:165/:190 同改）。

### F-2-3 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:398
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to save sort mode", e)`
- 问题: 8 处同型（132,174,185,398,409,420,431,451）：try 内 suspend 调用（userPrefs 读写全 suspend），catch(Exception) 无 CE 守卫，CE 被吞、协程正常完成。可达路径为 viewModelScope 取消（VM 销毁期），实际影响有限但违反红线 3 字面。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt （exit=0，命中 11 行；8 行属本 Finding，另 3 行为 F-2-17 瞬态站点）
- 建议: 统一加 CE 先行重抛（或封装 safeLaunch 工具统一处置）。

### F-2-4 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:71
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load settings", e)`
- 问题: 7 处同型（71,81,91,101,111,139,150）：init collect（长驻挂起、CE 可达性最高）与 6 个 setter 均吞 CE 无守卫。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt （exit=0，命中 8 行；7 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-5 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:85
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: 5 处同型（85,93,142,165,190）：init 加载/loadPreview/startSync/observeSyncCompletion 吞 CE 为 _error 状态且无恢复挂起点。
- 复现: 同 F-2-2 命令（5 行属本 Finding）
- 建议: 同 F-2-2。

### F-2-6 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:183
- 代码: `} catch (e: Exception) {` / `_browseError.value = classifyRemoteError(e)`
- 问题: 3 处同型（183,217,285）：browseRemote/testRemotePath/saveRelation 的 suspend 调用 CE 被吞为 UI 状态且 finally disconnect 无挂起检查、CE 不再恢复。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt （exit=0，命中 6 行；3 行属本 Finding，另 3 行为清理/PASS 站点）
- 建议: 同 F-2-2。

### F-2-7 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:111
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load preferences", e)`
- 问题: 4 处同型（111,140,169,191）；:191 saveProgress 高频调用、可达性最高。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt （exit=0，命中 5 行；4 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-8 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:86
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load image $imageItemId", e)`
- 问题: 2 处同型（86,174）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt （exit=0，命中 3 行；2 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-9 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:94
- 代码: `} catch (e: Exception) {` / `_testResult.value = context.getString(R.string.source_connect_failed, syncErrorMessage(e, context))`
- 问题: 3 处同型（94,109,129）：testConnection/save/deleteSource 吞 CE 为结果状态。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt （exit=0，命中 3 行）
- 建议: 同 F-2-2。

### F-2-10 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:93
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: discover 外层吞 CE（同文件 :174/:199 内层已正确守卫，仅此遗漏）。
- 复现: grep -nE 'catch \(e: (kotlinx\.coroutines\.)?(Exception|CancellationException)' app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt （exit=0，命中 5 行；:93 属本 Finding，:174/:199 为 CE 守卫、:176/:201 为已守卫 PASS 站点）
- 建议: discover 外层补 CE 守卫。

### F-2-11 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:152
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to reset storage", e)`
- 问题: resetStorage（含 withContext+mutex+suspend DAO 删除链）吞用户取消后正常收尾。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt （exit=0，命中 4 行；:152 属本 Finding，另 3 行见 F-2-19/PASS）
- 建议: 补 CE 守卫后再 catch(Exception)。

> 紧随其后的最高影响 P1（建议一并优先处理）：F-2-27 volumeId 进日志/UI、F-2-28 DebugLogger 堆栈进 UI、F-2-30 e.message 进 UI（三者共同构成红线 6 实际泄漏面）、F-4-1 share 链路 Main 线程 runBlocking。

## 3. 唯一发现索引表（每个 canonical ID 恰好一行；按严重度+Phase 排序）

| ID | 严重度 | 确定性 | 规则 | 位置 | 一句话 |
|---|---|---|---|---|---|
| F-2-2 | P0 | CONFIRMED | R3 | sync/SyncPreviewViewModel.kt:224 | 主动取消被 catch(Exception) 转成用户可见假错误 |
| F-2-3 | P0 | CONFIRMED | R3 | browser/BrowserViewModel.kt:398(8处) | VM 偏好写入链吞 CE 无守卫 |
| F-2-4 | P0 | CONFIRMED | R3 | settings/SettingsViewModel.kt:71(7处) | init collect 与 setter 吞 CE |
| F-2-5 | P0 | CONFIRMED | R3 | sync/SyncPreviewViewModel.kt:85(5处) | 加载/同步启动链吞 CE 为 _error |
| F-2-6 | P0 | CONFIRMED | R3 | settings/SyncRelationEditViewModel.kt:183(3处) | 浏览/测试/保存链吞 CE |
| F-2-7 | P0 | CONFIRMED | R3 | player/PlayerViewModel.kt:111(4处) | 加载/进度保存吞 CE |
| F-2-8 | P0 | CONFIRMED | R3 | imageviewer/ImageViewerViewModel.kt:86(2处) | 图片加载/完成标记吞 CE |
| F-2-9 | P0 | CONFIRMED | R3 | settings/SyncSourceEditViewModel.kt:94(3处) | 连接测试/保存/删除源吞 CE |
| F-2-10 | P0 | CONFIRMED | R3 | settings/GainAnalysisViewModel.kt:93 | discover 外层吞 CE（内层已守卫） |
| F-2-11 | P0 | CONFIRMED | R3 | settings/StorageManagementViewModel.kt:152 | 整卷重置链吞取消 |
| F-2-27 | P1 | CONFIRMED | R6/C-ST | sync/SyncEngine.kt:399(7处) | 卷标识 volumeId 进日志与用户可见 UI |
| F-2-28 | P1 | CONFIRMED | R6/R7 | debug/DebugLogger.kt:57 | 异常堆栈原文写入用户可见日志屏 |
| F-2-30 | P1 | CONFIRMED | R6/R7 | storage/volume/EncryptedMediaStore.kt:137 | e.message 经 metadata_scan_io_error 进 UI |
| F-4-1 | P1 | CONFIRMED | C-CV性能 | ui/components/ItemContextMenu.kt:185 | share 链路 Main 线程 runBlocking 读 DataStore |
| F-4-3 | P1 | CONFIRMED | P4流暴露 | player/PlayerViewModel.kt:83(2处) | 公开可变 StateFlow 暴露 |
| F-2-12 | P1 | CANDIDATE | R3 | sync/SyncEngine.kt:480(+:642) | worker client 清理瞬态吞 CE |
| F-2-13 | P1 | CANDIDATE | R3 | sync/SyncEngine.kt:562(+:563) | verifyReadable 探针把 CE 包装成 SyncException |
| F-2-14 | P1 | CANDIDATE | R3 | sync/SyncEngine.kt:603 | 元数据提取吞 CE 后记 SUCCESS 日志 |
| F-2-15 | P1 | CANDIDATE | R3 | sync/SyncEngine.kt:998(+:1002) | deleteStoreMedia 吞 CE 记删除失败 |
| F-2-16 | P1 | CANDIDATE | R3 | sync/SyncEngine.kt:1016(+:1020) | deleteStoreThumb 同型 |
| F-2-17 | P1 | CANDIDATE | R3 | browser/BrowserViewModel.kt:116(3处) | init 四连 try 瞬态吞 CE（后继裸挂起点恢复） |
| F-2-18 | P1 | CANDIDATE | R3 | settings/SettingsViewModel.kt:162 | withContext 内吞 CE（退出恢复） |
| F-2-19 | P1 | CANDIDATE | R3 | settings/StorageManagementViewModel.kt:66(+:75) | 同型瞬态吞 |
| F-2-20 | P1 | CANDIDATE | R3 | sync/VideoMetadataExtractor.kt:45(3处) | writeThumb 挂起点 CE 瞬态吞 |
| F-2-21 | P1 | CANDIDATE | R3 | sync/ImageMetadataExtractor.kt:61 | 同型 |
| F-2-22 | P1 | CANDIDATE | R3 | settings/MetadataRebuildViewModel.kt:188 | 循环内提取吞 CE 计失败 |
| F-2-23 | P1 | CANDIDATE | R3 | storage/volume/EncryptedMediaStore.kt:137(3处) | 卷操作/标记写瞬态吞 CE |
| F-2-24 | P1 | CANDIDATE | R3 | storage/volume/SafVolume.kt:132(+:156) | delete/rename 瞬态吞 CE 返回 false |
| F-2-25 | P1 | CANDIDATE | R3 | SyncPlayerApp.kt:48 | runCatching 吞 CE 记 purge skipped |
| F-1-1 | P2 | CONFIRMED | ARCH-2/3 | settings/StorageManagementViewModel.kt:46 | VM 直注 3 个 Room DAO 绕过 Repository |
| F-1-2 | P2 | CONFIRMED | ARCH-2 | settings/AboutScreen.kt:133 | Composable 直接消费 Room entity |
| F-3-1 | P2 | CONFIRMED | P3丢cause | storage/volume/EncryptedMediaStore.kt:136 | checkExistence 丢弃异常对象仅留 message |
| F-4-2 | P2 | CONFIRMED | C-CV性能 | settings/MetadataRebuildViewModel.kt:166(14处) | 业务层硬编码 Dispatchers 而非 @IoDispatcher |
| F-4-4 | P2 | CONFIRMED | C-CV性能 | storage/volume/FileVolume.kt:22 | IO 原语不自包 Dispatchers.IO（与 SafVolume 不一致） |
| F-4-5 | P2 | CONFIRMED | C-CV性能 | settings/SyncRelationEditViewModel.kt:261 | Main 线程执行 canonicalFile 磁盘 IO |
| F-8-1 | P2 | CONFIRMED | C-CV-6 | sync/SyncEngine.kt:960 | 硬编码英文诊断串进用户可见 lastSync 文案 |
| F-9-1 | P2 | CONFIRMED | P9超长 | sync/SyncEngine.kt:384(12处清单) | 12 个函数实测 >80 行（最大 701 行） |
| F-9-2 | P2 | CONFIRMED | P9重复 | player/PlayerViewModel.kt:209 | 与 ImageViewerViewModel:119 构成 26 行近同块 |
| F-10-2 | P2 | CONFIRMED | P10权限 | AndroidManifest.xml:9 | WAKE_LOCK 声明后代码零使用 |
| F-1-3 | P2 | CANDIDATE | ARCH-2/3 | settings/SyncRelationEditViewModel.kt:5(6处) | VM 耦合 android 框架类（Uri/DocumentsContract 等） |
| F-2-1 | P2 | CANDIDATE | R2 | browser/FileOperationsUseCase.kt:592 | 卷不匹配 index-only 删除返回 Success 未计 FAILED（spec 歧义） |
| F-2-26 | P2 | CANDIDATE | R4/C-CR | storage/volume/EncryptedMediaStore.kt:178 | purge 幂等标志实现（marker 文件）与 spec 文字（DataStore flag）漂移 |
| F-2-29 | P2 | CANDIDATE | R6 | sync/sftp/SyncPlayerHostKeyVerifier.kt:59 | TOFU hostname+fingerprint 进 logcat（公开元数据，规范未禁） |
| F-2-31 | P2 | CANDIDATE | R6 | sync/ErrorMessageMapper.kt:89(2处) | relativePath 进用户错误文案（红线"path"与 spec"主机路径"措辞歧义） |
| F-5-1 | P2 | CANDIDATE | P5临时文件 | sync/sftp/SyncPlayerHostKeyVerifier.kt:88 | known_hosts 写失败路径 tmp 残留 |
| F-7-1 | P2 | CANDIDATE | 测试缺口 | sync/sftp/SyncPlayerHostKeyVerifier 等 | TOFU/协议异常分类器无直接单测 |
| F-9-3 | P2 | CANDIDATE | P9命名 | debug/DebugLogger.kt:17 | debug/ 包名误导（实际为随 release 分发的产品功能） |
| F-9-4 | P2 | CANDIDATE | P9未引用 | storage/StoragePaths.kt:99 | isPathSafe 生产零调用（仅测试） |
| F-10-1 | P2 | CANDIDATE | P10权限 | AndroidManifest.xml:8 | POST_NOTIFICATIONS 声明但无运行时请求（通知可能不可见） |

## 4. 维度评分（1-5）

| 维度 | 分 | 依据（引用 Phase 锚点） |
|---|---|---|
| 架构 | 4 | ARCH 11 条款 9 符合；1.1 扫描 18 行仅 4 违规行；Hilt 抽样 10/10 PASS |
| 安全红线 | 3 | R1/R2/R4/R5 全 PASS（CHECK-2-1/3/5/6）；R3 153 点 35 FAIL；R6 101+13 日志行 4 Finding |
| 错误处理 | 4 | Phase 3 审计 202 处 catch，仅 1 处 CONFIRMED（丢 cause）；Provider 边界零泄漏 |
| 并发 | 4 | GlobalScope 0；手动 scope 5/5 有属主；106 可变流仅 2 暴露；1 处 Main-runBlocking 链 |
| 资源 | 5 | 17 候选 10 实点全 use{}/契约闭环；ExoPlayer/通知/前台服务配对完整 |
| 契约 | 4 | 69 条款=62 PASS+3 FAIL+4 PARTIAL；18 显式必查行全覆盖 |
| 测试 | 4 | 353 断言 0 文案违规；107 every 0 suspend 误 mock；5 安全行为 CHECK 全 PASS |
| i18n | 5 | 264 Text 仅 6 数据/格式字面量；364 key 100% 成对；1 处英文串（F-8-1） |
| 卫生 | 4 | main 零 TODO；12 超长函数；1 组 26 行重复块；零未引用类型 |
| 配置 | 4 | 25 版本锚点全一致且集中；git 版本派生；2 权限问题 |

## 5. 统计核对

- Σ各 Phase 唯一发现：P1=3、P2=31、P3=1、P4=5、P5=1、P6=0、P7=1、P8=1、P9=4、P10=2（Phase 0=0）→ **总 49**
- 索引表行数 = **49** ✓（机械命令 `grep -cE '^### F-' reports/0*.md reports/10-*.md` 求和=49，Phase 11 全量重验见 verification.md）
- 严重度合计：**P0=10、P1=19、P2=20**，Σ=49 ✓
- 确定性合计：**CONFIRMED=25 + CANDIDATE=24 = 49** ✓
- 分级细目：P0 全 CONFIRMED（10）；P1 CONFIRMED 5（F-2-27/28/30、F-4-1/4-3）+ CANDIDATE 14；P2 CONFIRMED 10 + CANDIDATE 10
- U 条目：**1**（U-10-1，reports 内 `grep -cE '^### U-'` = 1）✓

## 6. U-/CANDIDATE 汇总（供人工裁决）

**UNKNOWN（1）**
- U-10-1 依赖版本时效/CVE：任务书禁联网且无本地带日期权威证据，不可复现地下结论（依据：任务书 E 节 + Phase 10.1）。

**CANDIDATE（24，按裁决价值排序）**
1. F-2-1（R2）：浏览器路径卷不匹配返回 Success 而非计 FAILED——红线 2 字面 vs sync.md:51-52 的 sync 流程限定，需人工定夺（期望：红线 2；现状：FileOperationsUseCase.kt:592）。
2. F-2-31（R6）：relativePath 进用户错误文案——红线 6 列"path"，storage-security.md:51 用词"主机路径"，两处规范措辞不一致。
3. F-2-26（R4/C-CR-12）：purge 标志 spec 说 DataStore flag、代码用卷根 marker（行为更优、文档失真）。
4. F-2-29（R6）：TOFU fingerprint/hostname 入 logcat——公开元数据、规范未禁，威胁模型裁决。
5. F-10-1：POST_NOTIFICATIONS 无运行时请求→通知不可见（静态推断，需真机验证）。
6. F-2-13/14/15/16/12（R3 瞬态族）：引擎内 5 组 CE 瞬态破坏（后继挂起点必恢复）——若判定"等价传播"成立可降级/关闭。
7. F-2-17/18/19/20/21/22/23/24/25（R3 瞬态族续）：VM/存储层 9 组同型。
8. F-1-3：VM 耦合 android 框架类（spec 无禁令，可测试性债）。
9. F-5-1：known_hosts 写失败 tmp 残留（罕见路径）。
10. F-7-1：TOFU/协议分类器无直接单测（spec 未强制）。
11. F-9-3：debug/ 包命名与 release 归属。
12. F-9-4：isPathSafe 生产未接线。

**红色线级人工再定级建议**：10 条 P0 中 F-2-3…F-2-11（34 站点）的可达场景均为 VM 销毁期，若评审采纳"等价传播/teardown 豁免"可整体降为 P1；F-2-2（用户可触发）建议维持 P0。R6 三条 P1 CONFIRMED（F-2-27/28/30）建议按实际威胁模型决定修复优先级（个人使用、单用户设备）。
