# sync-player 审计修复方案（待审阅，未执行）

> 状态：**DRAFT — 供用户审阅，尚未改动任何代码**。
> 输入：`reports/summary.md`（49 条发现）+ 编排者独立复核（rev-1，15/15 P0/P1 重验通过）+ 用户三项裁决（见 §1）。
> 执行原则：每批次完成后跑 `compileDebugKotlin` + `testDebugUnitTest`（AGENTS.md 硬门禁）；行号以 grep 锚点为准（前序批次会使行号漂移）。

## 0. 审计报告勘误（复核发现，以本方案为准）

审计发现层全部可信，但两处**总结性结论**与其自身证据矛盾，修复方案按修正后的事实执行：

1. **R2 非 PASS，实为 PARTIAL**：SyncEngine 路径（DELETE_LOCAL :663 / DELETE_BOTH :867）删前比对 + 不匹配计 FAILED，成立；但浏览器路径 `FileOperationsUseCase.deleteOtherVolumeIndexLocked`（:590-592）卷不匹配时返回 `Success` 未计 FAILED（即 F-2-1）。处置见 §1 决策 D1。
2. **"引擎/数据层 CE 守卫率 100%" 不成立**：`SyncEngine.kt:998/1002/1016/1020`（F-2-15/16）吞 CE，取消时文件被记"删除失败"（假失败，与 F-2-2 同型）。升为 P1 CONFIRMED，纳入批次 1 修复。

## 1. 决策记录（用户已裁决）

| # | 议题 | 裁决 | 落点 |
|---|---|---|---|
| D1 | F-2-1 卷所有权 spec 漂移 | **改 spec 认可现状**：浏览器 index-only delete 为合法语义，仅 SyncEngine 流程要求计 FAILED | 批次 4（文档） |
| D2 | F-2-28 DebugLogger 双通道 | **保留现状**：接受该泄漏面；F-2-27/F-2-30 只修不经 DebugLogger 的站点 | 批次 2 范围据此划定 |
| D3 | 修复范围 | **本轮仅形成方案供审阅**，不执行 | 本文档 |

严重度再定级（复核后编排者裁决，供参考）：F-2-2 维持 P0（用户可稳定触发假错误）；F-2-3..F-2-11（34 站点，仅 VM 销毁期可达）降 P1 但**全部修复**（红线 3 无例外）；F-2-15/16 升 P1 CONFIRMED。

---

## 2. 批次 1 — CancellationException 守卫统一修复（红线 3）

**改动模式（统一）**：在每处 `catch (e: Exception)`（及其同型 `_` 变量）之前插入先行重抛：

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
```

### 2.1 VM 层 35 站点（10 文件）

| Finding | 文件 | 站点（audit 行号） | 备注 |
|---|---|---|---|
| F-2-2 | sync/SyncPreviewViewModel.kt | 224 | **P0 主站点**：cancelSync→CE→`_error` 假错误 |
| F-2-5 | sync/SyncPreviewViewModel.kt | 85, 93, 142, 165, 190 | 与上同文件共 6 处 |
| F-2-3 | browser/BrowserViewModel.kt | 132, 174, 185, 398, 409, 420, 431, 451 | 8 处；116 组（F-2-17 瞬态）一并修 |
| F-2-4 | settings/SettingsViewModel.kt | 71, 81, 91, 101, 111, 139, 150 | 7 处；162（F-2-18）一并修 |
| F-2-6 | settings/SyncRelationEditViewModel.kt | 183, 217, 285 | finally disconnect 无挂起检查，重抛后 CE 恢复传播即可，不改 finally |
| F-2-7 | player/PlayerViewModel.kt | 111, 140, 169, 191 | |
| F-2-8 | imageviewer/ImageViewerViewModel.kt | 86, 174 | |
| F-2-9 | settings/SyncSourceEditViewModel.kt | 94, 109, 129 | |
| F-2-10 | settings/GainAnalysisViewModel.kt | 93 | 内层 :174/:199 已守卫，仅补外层 |
| F-2-11 | settings/StorageManagementViewModel.kt | 152 | :66/:75（F-2-19）一并修 |

### 2.2 引擎/数据层（复核后升级项）

| Finding | 文件:行 | 修复 |
|---|---|---|
| F-2-15 | SyncEngine.kt:998, 1002 | `deleteStoreMedia` 两层 catch 前插 CE 重抛；取消时直接向上传播，不再记"删除失败" |
| F-2-16 | SyncEngine.kt:1016, 1020 | `deleteStoreThumb` 同型 |
| F-2-12 | SyncEngine.kt:480, 642 | worker client 清理瞬态吞 CE → 重抛 |
| F-2-13 | SyncEngine.kt:562-563 | `verifyReadable` 把 CE 包装成 SyncException → 包装前重抛 CE（**需核对调用方对 SyncException 的消费逻辑不受影响**——CE 重抛后走引擎外层既有守卫 :754-763） |
| F-2-14 | SyncEngine.kt:603 | 元数据提取吞 CE 后记 SUCCESS → 重抛 |
| F-2-22 | settings/MetadataRebuildViewModel.kt:188 | 循环内吞 CE 计失败 → 重抛（取消停止循环） |
| F-2-23 | storage/volume/EncryptedMediaStore.kt:137 组（3 处） | 与批次 2 F-2-30 **同站点合并编辑**，见 §3.1 |
| F-2-24 | storage/volume/SafVolume.kt:132, 156 | delete/rename 瞬态吞 CE 返回 false → 重抛 CE，真实异常仍返回 false |
| F-2-25 | SyncPlayerApp.kt:48 | `runCatching` 吞 CE 记 purge skipped → 改 try/catch + CE 重抛（runCatching 会捕 CE，禁再用） |
| F-2-20/21 | sync/VideoMetadataExtractor.kt:45 组 / ImageMetadataExtractor.kt:61 | 挂起点瞬态吞 → 重抛 |

### 2.3 新增回归测试（conventions：断言枚举/调用次数，不断言文案）

1. `SyncPreviewViewModelTest`：`cancelSync()` 打断 `executeDelete` → `_error` **不**被置位（F-2-2 回归锁）。MockK `coEvery` + `runTest`。
2. 代表性 VM 一例（建议 SettingsViewModel init collect 取消）：取消后无 `_error` 置位。

### 2.4 验证

- 全量 `compileDebugKotlin` + `testDebugUnitTest`；现有 28 个测试文件必须全绿（StateFlow 私有化等后续改动若碰测试，同批修）。

---

## 3. 批次 2 — 敏感信息泄漏（红线 6，按 D2 划定范围）

### 3.1 F-2-30 + F-2-23 + F-3-1 合并修复（同站点）

`storage/volume/EncryptedMediaStore.kt:136-137`：
- 现状：`MediaExistence.IO_ERROR(e.message ?: e.javaClass.simpleName)`——`e.message` 经 `metadata_scan_io_error` 进用户文案（红线 7：不回退 Throwable.message）；同函数丢弃异常对象仅留 message（F-3-1 丢 cause）；组内 3 处吞 CE（F-2-23）。
- 修复：reason 改为 `e.javaClass.simpleName`（**去掉 e.message**）；CE 先行重抛；诊断日志保留完整异常对象（cause 不丢，日志按 D2 可含细节）。

### 3.2 F-2-27 volumeId 7 站点（6 修 1 留）

| 位置 | 通道 | 处置 |
|---|---|---|
| SyncEngine.kt:399 | DebugLogger（双通道） | **保留**（D2：接受） |
| SyncEngine.kt:671-672 | logcat | 修：去掉 volumeId 插值，改为 `volume mismatch (id=$id)` 不带卷值 |
| SyncEngine.kt:680-683 | 同步日志/UI 错误文案 | 修：同上（用户可见，必修） |
| SyncEngine.kt:871-872 | logcat | 修 |
| SyncEngine.kt:880-883 | 同步日志/UI | 修 |
| FileOperationsUseCase.kt:471-473 | logcat | 修（`volume='${video.volumeId}'` → 去值） |
| UndoDeleteUseCase.kt:372-373 | logcat | 修 |

原则：诊断语义保留（记录"id X 卷不匹配"这一事实），**不输出卷标识值本身**。

### 3.3 明确不修

- F-2-28（DebugLogger 堆栈进 UI）：D2 保留现状。
- F-2-29（TOFU fingerprint/hostname 进 logcat）：公开元数据 + 规范未禁 + 与 D2 精神一致 → 建议 **CLOSE**（见 §6）。

---

## 4. 批次 3 — 并发（2 项 P1）

### 4.1 F-4-1 share 链路 Main 线程 runBlocking

链路（复核确认）：`ItemContextMenu.kt:185`（Composable 点击，Main）→ `FileOperationsUseCase.kt:235-278`（无 withContext，:241 读 `mediaStore.volumeId`）→ `EncryptedMediaStore.kt:40` `runBlocking { volume() }` → DataStore `.first()`。

**推荐方案（局部包裹，最小爆炸半径）**：`FileOperationsUseCase` share 路径函数已是 suspend → 将 `:235-278` 中含 `volumeId` 读取的区段包 `withContext(Dispatchers.IO) { ... }`（runBlocking 改阻塞 IO 线程而非 Main）。`EncryptedMediaStore.volumeId` 同步访问器别处仍有使用，不动根。
备选（根修）：`EncryptedMediaStore` 增 suspend `volumeId()` 变体并改造全部调用方——改动面大，本轮不做。

### 4.2 F-4-3 公开可变 StateFlow（2 处）

`PlayerViewModel.kt:83`、`ImageViewerViewModel.kt:54`：`MutableStateFlow` 补 `private` + 暴露 `val x = _x.asStateFlow()`；文件内写入点改用 `_x`；外部消费者（含测试）如有直写需同步调整。

---

## 5. 批次 4 — P2 低成本项 + spec 更新（含已裁决 D1）

| 项 | 改动 | 类型 |
|---|---|---|
| **D1/F-2-1** | `AGENTS.md` 红线 2 与 `docs/specs/sync.md`：明确"卷不匹配不删密文"对所有路径强制；**计 FAILED 仅适用于 SyncEngine 同步流程**；浏览器/手动删除路径 index-only delete 返回 Success 为合法语义（引用 FileOperationsUseCase.kt:443-453 注释的设计依据） | 文档（已裁决） |
| F-2-26 | `docs/specs/crypto.md` purge 标志条款：DataStore flag → 卷根 marker 文件（代码行为更优，文档失真） | 文档 |
| F-2-31 | `sync/ErrorMessageMapper.kt:89` 组：用户文案去 relativePath（改稳定措辞）；spec 措辞歧义（红线"path" vs storage-security"主机路径"）在 D1 同次文档修订中澄清 | 代码+文档 |
| F-3-1 | 已并入 §3.1 | — |
| F-8-1 | `SyncEngine.kt:960` 硬编码英文诊断串 → `strings.xml` key | 代码 |
| F-4-2 | `MetadataRebuildViewModel.kt:166` 组 14 处硬编码 Dispatchers → 注入 `@IoDispatcher`（conventions 条款） | 代码 |
| F-4-4 | `FileVolume.kt:22` IO 原语包 `withContext(Dispatchers.IO)`（与 SafVolume 对齐） | 代码 |
| F-4-5 | `SyncRelationEditViewModel.kt:261` canonicalFile 磁盘 IO → `withContext(IO)` | 代码 |
| F-5-1 | `SyncPlayerHostKeyVerifier.kt:88` known_hosts 写失败路径补 tmp 清理 | 代码 |
| F-10-2 | Manifest 移除 WAKE_LOCK（代码零使用） | 代码 |
| F-7-1 | TOFU verifier / 协议异常分类器补单测 | 测试（可选） |
| F-9-1/F-9-2 | 12 个超长函数拆分 / 26 行重复块——**建议本轮不做**（纯重构、无行为收益、扩大回归面），记录为后续独立任务 | 暂缓 |

---

## 6. CANDIDATE 处置表（24 项，修复方案终局建议）

| 处置 | 项 | 理由 |
|---|---|---|
| **FIX**（并入批次 1/4） | F-2-12/13/14/15/16/17/18/19/20/21/22/23/24/25（R3 瞬态族 14 项）、F-2-31、F-5-1 | 红线字面 + 修复零边际成本；同批顺手 |
| **FIX**（spec，已裁决） | F-2-1（D1）、F-2-26 | 文档对齐现实 |
| **CLOSE** | F-2-29（TOFU 日志）、F-9-3（debug/ 命名）、F-10-1（POST_NOTIFICATIONS 静态推断，个人侧载应用手动授权即可）、F-1-3（VM 耦合框架类，规范无禁令） | 与 D2 接受的威胁模型一致 / 纯改名churn / 需真机 / 无规范依据 |
| **CLOSE 或 WIRE**（二选一待你定） | F-9-4（isPathSafe 生产零调用） | 建议直接删除该函数及测试；或接入调用链（工作量更大） |
| **DEFER** | F-1-1/F-1-2（VM 直注 DAO / Composable 消费 entity）、F-9-1/F-9-2（超长函数/重复块） | 架构债与重构，独立任务处理 |
| **UNKNOWN** | U-10-1（依赖 CVE） | 禁联网不可复现，保持开放 |

## 7. 执行顺序与门禁（获批后）

```
批次 1（CE 守卫，~16 文件 ~120 行）→ 门禁
批次 2（泄漏，3 文件）              → 门禁   [与批次 1 无写域冲突但含同站点合并项，须在批次 1 后]
批次 3（并发，3 文件）              → 门禁   [PlayerViewModel 与批次 1 重叠，须串行]
批次 4（P2+spec，~8 文件+3 文档）   → 门禁
```

- 全程单 fixer 串行（PlayerViewModel/EncryptedMediaStore/SyncEngine 跨批次重叠，并行写域冲突 > 收益）。
- 每批次门禁：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin && ... testDebugUnitTest`。
- 完成定义：49 项发现全部落到 FIX/CLOSE/DEFER 之一并有对应 commit；批次 1 含 2 个新回归测试；最终全量门禁绿。

## 8. 风险提示

- F-2-13（verifyReadable 重抛 CE）是批次 1 唯一非纯机械点，需核对调用方消费逻辑——执行时单独 review 该 hunk。
- 批次 1 改 35+ 站点 catch 顺序，理论上不影响非取消异常路径（CE 分支先行插入，原分支不动）；门禁 + 新回归测试兜底。
- 行号来自审计快照，执行时以 grep 锚点定位为准。
