# Sync Player 全面评估与改进执行报告

> 评估日期：2026-07-26  
> 评估基线：`d263977`（`v12.0.4-1-gd263977`）  
> 评估范围：`app/src/main`、`app/src/test`、`app/src/androidTest`、Gradle 配置、CI/发布脚本、现有工程文档  
> 评估方式：静态代码审查、架构/数据流分析、测试与工程配置盘点；**未进行真机交互、性能基准测试或可用性访谈**

本报告不是单纯的问题清单，而是后续 agent 可以直接拆分执行的改进基线。每项建议都尽量给出目标文件、改动边界、验证方式和完成标准。性能结论中，凡未经过运行时基准验证的内容均标记为“待测假设”，不要在未测量前进行高风险重构。

---

## 1. 结论摘要

### 1.1 总体判断

Sync Player 已经是一个功能完整、具有明确安全意识的个人媒体同步播放器，不是“需要推倒重来”的项目。它最有价值的基础包括：

- 加密媒体使用分块 AEAD v2，支持随机读取，并有独立的 `CryptoScheme` / `RangeDecryptor` 测试。
- 同步引擎具备结构化 `SyncException`、错误码映射、扫描失败保护（E8）、瞬态错误重试白名单、并发上限和存储卷所有权检查。
- Compose 播放器已经处理了高频播放进度的重组隔离、手势控制、预览帧、横竖屏/速度设置等复杂交互。
- 存储卷通过 `MediaVolume` 抽象，内部存储与 SAF 外部存储的边界清晰。
- CI、签名、发布和版本派生机制明显强于一般个人项目。

当前主要瓶颈不是缺少核心能力，而是**复杂度集中、用户首次使用缺少引导、整体测试覆盖不平衡、部分 UI/i18n/可访问性收尾不足**。

### 1.2 评分总表

| 维度 | 评分 | 判断 |
|---|---:|---|
| 功能完整度 | 8/10 | 浏览、播放、图片、SMB/SFTP、同步、加密、存储卷、锁屏均已覆盖 |
| 安全与数据保护 | 8.5/10 | AEAD、Keystore、TOFU、FLAG_SECURE、卷所有权保护较扎实 |
| 性能基础 | 7.5/10 | 调度器、并发、重组隔离、索引已有较好基础；大库/随机读取仍需基准数据 |
| 架构清晰度 | 7/10 | 分层和依赖方向基本合理，但 `SyncEngine`、浏览 UI/VM 过大 |
| 易用性 | 6/10 | 熟悉应用后效率高；首次配置、同步入口、删除恢复路径不够明显 |
| UI 视觉一致性 | 7/10 | Material 3、主题和组件基础良好，设置页和非播放器页面仍可打磨 |
| UX 交互成熟度 | 6.5/10 | 播放器优秀，浏览/同步/首次使用存在明显缺口 |
| 无障碍 | 5/10 | 有不少 `contentDescription`，但仍有触摸尺寸、手势提示、语义和颜色依赖问题 |
| 可维护性 | 6/10 | 核心逻辑测试扎实；UI、ViewModel、DAO、Provider 覆盖明显不足 |
| 文档与交付工程 | 8.5/10 | `AGENTS.md`、发布文档和 CI 很强，但文档存在少量过期项 |

**综合判断：7/10，状态为“可持续演进，但需要控制复杂度和补齐产品体验”。**

### 1.3 最重要的十项工作

按“用户影响 × 数据/回归风险 × 实施收益”排序：

1. **补齐首次使用引导**：空库不能只显示“当前目录为空”，应直接引导配置数据源和同步关系。
2. **修复用户可见的硬编码英文/中文与错误消息泄漏**：播放器 overlay、生物识别弹窗、存储重置失败、Provider 消息统一处理。
3. **为同步关键流程补充行为测试**：尤其是 `SyncEngine` 拆分后仍需覆盖取消、重试、空间不足、卷不匹配、部分失败。
4. **拆分 `SyncEngine.executeSyncLocked()`**：优先提取下载编排/重试/进度发布，不改变同步语义。
5. **为 `VideoItemDao` 建立 Room in-memory 测试**：锁定目录层级、过滤、排序、分页和删除查询行为。
6. **拆分 `BrowserScreen` / `BrowserViewModel`**：保持视觉和行为不变，只提取状态映射、列表项、筛选器和纯数据构建器。
7. **为删除/回收站增加撤销 Snackbar**：播放器、浏览器、图片查看器行为保持一致。
8. **对大库性能做可重复基准**：先测 SQL、列表构建、随机解密、缩略图和同步进度分配，再决定是否改查询或加缓存。
9. **做全局无障碍审计**：48dp 触摸目标、语义描述、动态字体、手势替代入口、对比度。
10. **建立覆盖率/回归门槛和文档校准机制**：先不追求全覆盖，优先守住核心行为和高风险边界。

---

## 2. 当前系统地图

### 2.1 分层与主要数据流

```text
Compose UI
  ├─ Browser / Search / Player / ImageViewer
  ├─ Settings / SyncPreview / LockScreen
  └─ ui/components + ui/theme
        ↓ StateFlow / callbacks / Navigation
ViewModel
  ├─ BrowserViewModel / SearchViewModel
  ├─ PlayerViewModel（MVI 风格）
  ├─ SyncPreviewViewModel
  └─ Settings / Storage / Gain / Metadata ViewModels
        ↓
Use case / service
  ├─ FileOperationsUseCase
  ├─ SyncService（前台服务）
  ├─ SeekPreviewExtractor
  └─ Audio/Video/Image metadata extractors
        ↓
Sync / repository / storage
  ├─ SyncEngine → SMB/SFTP RemoteFileClient
  ├─ Repository → Room DAO → entities
  ├─ EncryptedMediaStore → MediaVolume
  └─ MediaContentProvider → ExoPlayer/Coil
```

### 2.2 规模与复杂度信号

静态盘点结果：

- 主 Kotlin 代码约 **18,778 行**，测试 Kotlin 约 **2,787 行**。
- 主代码约 118 个文件，测试 15 个 JVM 测试文件，1 个插桩测试文件。
- 最大文件：`BrowserScreen.kt` 1116 行、`SyncEngine.kt` 1096 行、`PlayerScreen.kt` 1058 行。
- 其他需要关注的大文件：`SyncPreviewScreen.kt` 636 行、`BrowserViewModel.kt` 606 行、`VideoItemDao.kt` 300 行、`FileOperationsUseCase.kt` 417 行。
- 未发现生产代码中的 `TODO`、`FIXME`、`HACK`、`XXX`；这说明显式债务标记少，但也意味着不能把“没有 TODO”误认为“没有债务”。

### 2.3 核心流程

```text
Browser / Settings
      ↓
SyncPreviewViewModel.dryRun()
      ↓
SyncEngine.dryRun()
  连接 → 完整远端扫描 → 与本地 DB 比较
  扫描异常 → 清空下载/删除列表（E8）
      ↓
用户确认
      ↓
PendingSyncActionDao 持久化任务
      ↓
SyncService.consumeAndExecute()
      ↓
SyncEngine.executeSyncLocked()
  空间预检 → 并发下载/加密 → 元数据/缩略图 → 删除 → 结果记录
```

---

## 3. 已有优势：后续修改必须保护的设计

### 3.1 安全与数据完整性

以下设计应视为不可随意回退的约束：

1. **E8 扫描失败保护**：远端快照不完整时，`toDownload` 和 `toDelete` 必须都为空；不允许用部分扫描结果推导本地删除。
2. **卷所有权 fail-closed**：只有 `entity.volumeId == activeVolumeId` 时才允许删除对应密文/缩略图；未知或不匹配都不能触碰文件。
3. **同步错误结构化**：业务错误用 `SyncException.code`，显示文本交给 `ErrorMessageMapper` 和资源文件；不要把异常原文直接展示给用户。
4. **取消优先传播**：`CancellationException` 不得被普通异常处理吞掉；取消后要在 `NonCancellable` 中完成 pending task 清理。
5. **AEAD v2 格式不变**：header、AAD、nonce、chunk size、plaintext coordinate 语义均属于存储格式契约；任何优化必须先更新格式测试或明确版本迁移。
6. **随机读取失败必须抛错**：`RangeDecryptor` 认证失败、截断、非法 header 不能返回脏数据。
7. **敏感信息不进入日志/UI**：密码、密钥、完整远端凭据、卷标识等不能进入用户可见错误或普通日志。

### 3.2 性能基础

- `PlayerViewModel.currentPosition` 独立于低频 `PlayerUiState`，符合重组隔离原则。
- `BrowserViewModel` 对筛选器使用 300ms debounce，对文件夹切换使用 `flatMapLatest` / `mapLatest`，避免旧结果覆盖新结果。
- SAF、SMB、SFTP、媒体元数据提取等阻塞 I/O 基本放在 `Dispatchers.IO`。
- 同步下载使用 `Semaphore(3)` 限制并发，避免无限创建任务。
- `SeekPreviewExtractor` 有按 bitmap 大小计费的 LRU，且处理 bitmap 回收。
- `VideoItemEntity` 的 `parentPath`、`syncRelationId`、`volumeId` 等关键列已有索引。
- 同步进度有约 500ms 节流，避免通知和 UI 高频更新。

### 3.3 播放器 UX

播放器是当前产品体验的标杆：

- 左/右区域上下滑动分别控制亮度/音量。
- 横向滑动 seek，长按临时加速，双击区域提供前后切换/操作。
- seek preview 使用抽帧和缓存。
- 旋转策略、速度、音量平衡、进度显示等设置具备连续体验。
- 全屏/沉浸模式和退出行为已有明确处理。

后续 UI 工作应提升浏览、设置和同步页面到播放器的完成度，而不是削弱播放器已有手势和视觉设计。

### 3.4 交付工程

- 版本号由 git 派生，避免手动改版本导致不一致。
- `release.md` 对签名凭据、CI、失败排查和发布流程说明充分。
- CI 已包含编译、测试、lint、签名校验和缓存隔离等实践。
- `AGENTS.md` 对加密、同步错误、存储卷和验证命令有较详细约束。

---

## 4. P0：应优先处理的问题

P0 表示会造成明显的数据风险、用户无法完成关键任务，或在已有证据下很可能产生严重回归。P0 修改应先写测试或建立验收场景。

### P0-1 首次使用没有完成路径

**现象**：新用户进入空浏览器后，只能看到空目录状态；配置数据源 → 配置同步关系 → 预览 → 执行同步的路径隐藏在设置和上下文中。

**影响**：用户不知道应用要如何获得媒体，也不知道“数据源”和“同步关系”的区别；功能实际存在但不可发现。

**涉及文件**：

- `MainActivity.kt`
- `browser/BrowserScreen.kt`
- `settings/SettingsScreen.kt`
- `settings/SyncSourceListScreen.kt`
- `settings/SyncRelationListScreen.kt`
- `app/src/main/res/values/strings.xml`

**建议方案**：

1. 以“是否存在同步源/同步关系/媒体文件”作为状态，而不是只判断空列表。
2. 空库且无配置时展示首次使用卡片：说明用途、配置数据源、创建同步关系三个步骤。
3. “配置数据源”直接导航到 `SyncSourceEdit.createNew()`。
4. 完成数据源后提供“继续创建同步关系” CTA；完成关系后提供“立即预览同步” CTA。
5. 用户关闭引导后保留设置入口，不反复阻塞播放/浏览。
6. 引导状态保存在 DataStore，支持“稍后再说”，不要使用不可测试的全局布尔变量。

**验收标准**：

- 全新安装且无数据时，用户从浏览首页最多 2 次点击能进入数据源创建。
- 已有数据源但没有关系时，CTA 直接指向关系创建。
- 已有关系但没有媒体时，CTA 直接指向同步预览。
- 已有媒体时不显示首次使用卡片。
- 引导状态在进程重启后保持，测试覆盖三个分支。

**建议 agent**：`@designer` 负责卡片层级和文案布局；`@fixer` 负责状态、DataStore 和导航；完成后运行 Compose 手工验证。

### P0-2 用户可见文本不一致或泄漏异常原文

**现象**：整体中文 UI 中仍有播放器/图片查看器的英文 overlay（`PREVIOUS`、`NEXT`、`NO PREVIOUS`、`IN`、`OUT`、`FULLSCREEN`、`ROTATION`），并存在多个 Kotlin 中的硬编码用户文本。

**重点位置**：

- `player/PlayerScreen.kt` 约 762、1043 附近。
- `imageviewer/ImageViewerScreen.kt` 约 405 附近。
- `security/BiometricAuthManager.kt` 约 48–64。
- `settings/SettingsViewModel.kt` 约 120–121。
- `settings/StorageManagementViewModel.kt` 约 146、151。
- `data/repository/SyncRelationRepository.kt` 的默认名称。
- `provider/MediaContentProvider.kt` 的 `FileNotFoundException` 文本。

**建议方案**：

1. 所有用户可见文本迁移至 `strings.xml`，包括 BiometricPrompt 的 title/subtitle。
2. 将 `PlayerOverlay.Orientation(label: String)` 改为 `Orientation(mode: OrientationMode)`，在 UI 层资源化渲染；避免用本地化字符串做逻辑比较。
3. `StorageManagementViewModel` 只发布错误码/状态，界面通过 `syncErrorMessage` 或专用资源映射显示，不使用 `e.message`。
4. Provider 的异常消息只作为内部诊断；对外保持统一、不含卷标识和路径的错误。
5. 添加 lint/静态检查规则，至少对 `Text("`、`BiometricPrompt.Builder` 文本和 `e.message` 的 UI 使用做审查。

**验收标准**：

- 播放器、图片查看器、锁屏、存储重置、同步错误在默认语言下无英文泄漏。
- 用户界面不会显示异常原文、主机路径、卷标识或凭据相关信息。
- 单元测试不断言文案内容，只验证错误码/状态；必要时做资源存在性检查。

### P0-3 同步删除路径必须继续保持 fail-safe

这是当前已有设计，但所有后续重构都必须锁定它。尤其是拆分 `SyncEngine`、增加撤销、增加批量操作时，不能将删除动作绕过：

- `dryRun` 扫描失败不得生成删除动作。
- 预览页面不得在错误状态下启用删除确认。
- `volumeId` 不匹配的删除必须计为失败/跳过，而不能显示成功。
- 取消、进程重启、前台服务终止后，pending action 要可恢复或明确标记失败。

**建议新增的回归测试**：扫描抛出普通 `IOException`、`RemoteScanIncompleteException`、连接中断、远端目录部分可见、卷切换竞态、删除中取消。

---

## 5. P1：高收益改进

### P1-1 拆分 `SyncEngine` 上帝类

**现状**：`sync/SyncEngine.kt` 约 1096 行，构造函数注入约 11 个依赖，承担扫描、计划、pending action、空间预检、下载、重试、元数据、删除、进度、日志和结果状态。

**风险**：任何同步改动都可能影响多个阶段；难以独立测试；异常边界和取消语义容易被破坏。

**推荐拆分顺序**（一次只拆一层，避免大重写）：

1. `SyncProgressPublisher`
   - 迁移 `_progress`、500ms 节流、active download 映射、completed/failed 计数。
   - 对外只暴露不可变 `StateFlow<SyncProgress>`。
2. `SyncSpaceChecker`
   - 封装 `CryptoScheme.cipherLengthFor()`、总量预检和每文件复检。
   - 测试非法大小、溢出、未知可用空间、边界相等。
3. `DownloadOrchestrator`
   - 封装 `Semaphore(3)`、单文件下载、临时文件、finalize、重试白名单和清理。
   - 将每次尝试结果表达为结构化结果，而不是依赖嵌套 try/catch 的隐式状态。
4. `DeleteExecutor`
   - 封装卷所有权检查、密文/缩略图/DB 删除顺序和失败分类。
5. `SyncEngine`
   - 只保留流程编排、互斥锁、仓库交互和 public API。

**不可改变的契约**：

- 取消异常优先传播。
- 终态错误不重试；瞬态错误按白名单重试。
- 下载失败清理临时文件和占位记录。
- 元数据/缩略图失败的结果分类保持现有语义。
- `consumeAndExecute` 与存储切换仍由同一 `storageOperationMutex` 保护。

**验收标准**：

- `SyncEngine` 目标降至约 400–600 行，且只承担编排，不为“行数”牺牲可读性。
- 现有 `SyncEngine*Test` 全部通过，并新增至少：取消、终态不重试、临时文件清理、卷不匹配删除、空间不足。
- 重构前后对同一模拟远端快照产生相同 action 和结果。
- `compileDebugKotlin`、`testDebugUnitTest` 通过。

### P1-2 为浏览数据构建和 DAO 查询建立边界

**现状**：`BrowserViewModel.buildBrowseItems()` 包含大量文件/目录模式、筛选、排序、统计和映射逻辑；`VideoItemDao.kt` 约 300 行，查询较多。

**建议方案**：

1. 提取纯/近纯的 `BrowseItemBuilder`，输入 folder、sort、filter、star filter、records、relations，输出 `List<BrowseItem>`。
2. 将 SQL 结果 DTO 统一命名，避免 `VideoItemWithPlayback`、`FlatItemWithPlayback`、`BrowseItem` 三套相似字段继续扩张。
3. 将目录查询、文件模式查询、搜索查询按用途分组；不要为了抽象而强行合并不同查询。
4. 对 `LIKE :folder || '/%'`、`SUBSTR`、`DISTINCT` 等查询做 `EXPLAIN QUERY PLAN` 和真实数据基准，再决定是否增加 `parentPathDepth` 或物化 folder 表。
5. 大库文件模式需要分页时，优先使用 keyset pagination（按排序键 + id），不要直接使用深页 OFFSET。

**重要判断**：当前“LIKE 可能全表扫描”是性能假设，不是已证实的 P0。SQLite 绑定参数和索引计划必须用实际 schema、数据量和 `EXPLAIN QUERY PLAN` 验证后再改表结构。

**验收标准**：

- `BrowseItemBuilder` 有纯数据测试，覆盖目录/文件模式、空目录、星级边界、排序方向、过滤组合。
- `VideoItemDao` 有 in-memory Room 测试，覆盖根目录、深层目录、同名不同关系、空结果、搜索和分页顺序。
- 1K/10K/50K 条模拟数据下，记录导航、文件模式和搜索 P50/P95 延迟。

### P1-3 降低 UI 和 ViewModel 的重组/复杂度风险

**现状**：`BrowserScreen` 顶层收集多个状态并包含大量布局与事件逻辑；`PlayerScreen` 主 composable 也较长。Compose 会跳过稳定子树，但当前结构让状态边界和重组成本难以观察。

**建议拆分**：

- `BrowserTopBar`：路径、搜索、同步、设置。
- `BrowserFilterSheet`：排序、类型、星级、浏览模式、列数。
- `BrowserContent`：loading/error/empty/list/grid/staggered。
- `FolderItem`、`FileItem` 已有雏形，继续将事件转成稳定参数。
- `BrowseScrollTracker` 使用明确的 folder 参数和 `rememberSaveable`/ViewModel 方案，避免 stale capture。
- 播放器拆成 controls、gesture overlay、seek preview、toolbar、settings popup，但不改手势协议。

**状态原则**：

- 高频位置继续独立订阅。
- 低频状态传递 primitive 或稳定 immutable 参数。
- UI 不直接执行 Room、SAF、MediaMetadataRetriever 等阻塞操作。
- 复杂 `UiState` 应在 ViewModel 组合，避免每个 composable 各自理解多个 Flow。

### P1-4 删除操作增加撤销

**现状**：回收/删除是高风险操作；已有 `SnackbarHostState` 的页面可以复用，但动作执行后没有统一撤销通道。

**建议方案**：

1. 删除前保留足够的 `VideoItemEntity`/操作对象，构建 `UndoDelete` 命令。
2. 先执行可逆的“移入回收站”状态变更，Snackbar 显示“已移入回收站 / 撤销”。
3. Snackbar 超时后再做不可逆的物理清理；若当前模型不是延迟清理，则至少撤销 DB 状态和文件路径。
4. 播放器、浏览器、图片查看器复用同一 use case，不各自复制恢复逻辑。
5. 卷不匹配、文件已不存在、撤销过期要显示明确的部分失败状态。

**验收标准**：撤销在 Snackbar 存活期间有效；进程重启后不假装可撤销；物理文件和 DB 状态一致；相关 use case 有成功/失败/卷不匹配测试。

---

## 6. P2：中期质量与体验改进

### 6.1 UI/UX 改进清单

| 优先级 | 问题 | 建议实现 | 目标文件 |
|---|---|---|---|
| P2 | 浏览器没有下拉刷新 | 使用 `pullToRefresh`；刷新只重新触发 Flow/同步状态，不重复创建 VM | `BrowserScreen`, `BrowserViewModel` |
| P2 | 没有批量选择 | 增加 selection mode、全选/反选、批量回收/标星/已看 | `BrowserScreen`, `BrowserViewModel`, `FileOperationsUseCase` |
| P2 | 同步入口不明显 | 在 relation 列表和空库 CTA 都提供预览入口；显示关系当前状态 | `SyncRelationListScreen`, `SyncPreviewScreen` |
| P2 | 设置页偏稀疏 | 按同步、播放、存储、安全、维护分组；使用一致的 section header/card | `SettingsScreen` |
| P2 | 页面切换突兀 | 对普通页面加入 150–250ms 的 fade/slide；播放器沉浸切换保持即时 | `AppNavHost` |
| P2 | 文件夹导航语义不明确 | 子目录显示返回/面包屑；根目录显示 home；目录树对话框提供当前路径上下文 | `BrowserScreen`, `DirectoryTreeDialog` |
| P2 | 瀑布流缺少滚动指示 | 与列表/普通网格统一 scrollbar 行为 | `BrowserScreen` |
| P2 | 同步预览缺少上下文 | 显示 source、relation、上次同步时间、上次结果、当前空间；危险删除区单独强调 | `SyncPreviewScreen` |
| P2 | 锁屏冷启动偏空白 | 保留简洁安全视觉，不遮挡 biometric prompt；可加 app icon/微弱背景层 | `LockScreenOverlay` |
| P2 | 搜索结束后键盘体验 | 清除/提交时按意图隐藏 IME；不要让 clear 只清文本不清焦点 | `SearchScreen`, `SearchViewModel` |

### 6.2 无障碍验收基线

所有新 UI 和改动 UI 必须满足：

- 可点击控件最小触摸区域 48dp；视觉图标可以小于 48dp，但 hit target 不能小于 48dp。
- 所有非装饰性 icon 有 `contentDescription`；装饰性 icon 明确使用 null。
- 星级、进度、文件状态不能只依赖颜色，必须有文本或语义描述。
- 播放器手势必须有可发现的替代入口（按钮/设置菜单），并提供一次性手势提示。
- 支持系统字体放大，不能用固定高度裁剪标题、文件名和错误文案。
- TalkBack 顺序应符合“页面标题 → 操作 → 内容 → 状态”的阅读顺序。
- 深色/浅色主题下检查文本、图标、进度和错误颜色对比度。

建议为关键页面增加 Compose UI test：锁屏、空库引导、同步预览错误、浏览器筛选器、播放器主要按钮可访问性标签。

### 6.3 性能改进：先测量，再修改

| 项目 | 当前观察 | 风险判断 | 首选验证 | 只有验证后才做的优化 |
|---|---|---|---|---|
| 目录查询 | 多处 `LIKE`/`SUBSTR`/`DISTINCT`，已有 `parentPath` 索引 | 大库可能退化，未证实 | Room SQL callback + `EXPLAIN QUERY PLAN`，1K/10K/50K 数据 | depth 列、folder 表、分页或查询拆分 |
| Compose 重组 | Browser/SyncPreview 顶层收集多个 Flow | 可观测性差，实际帧率需测 | Layout Inspector recomposition、Macrobenchmark | 拆分 composable、稳定参数、derivedState |
| 随机解密 | 每次 `readRange` 解析 header、分块读取；可能重复解密 | seek-heavy 场景有收益空间 | 记录 readRange 次数、块命中、seek P95 | header/最近 chunk cache；注意并发和内存 |
| `completedPaths` | 进度更新可能复制不断增长的集合 | 大同步 O(n²) 分配风险 | 5K/50K action 的 allocation/耗时 trace | 只发布计数或增量事件；保留内部集合 |
| 缩略图/图片 metadata | 图片解码和 EXIF 可能打开两次 Provider URI | 批量同步额外 I/O | Perfetto 统计 Provider open/read 次数 | 评估可复用 InputStream 的实现约束 |
| ContentProvider volumeId | 每次访问重新解析卷 | 低到中风险，需测 | Provider 调用计数和耗时 | 带失效通知的缓存，不能简单永久缓存 |
| scroll positions | map 只增不减 | 长会话低风险 | 长时间浏览内存观察 | 固定容量 LRU |
| 下载 buffer | 每文件一次 64KiB 分配 | 通常低风险 | 大批量下载 allocation trace | 复用/调整 buffer，不能跨并发任务不安全共享 |

**性能验收指标建议**：

- 浏览：目录切换 P95 < 200ms（本地 10K 数据基线），筛选响应 P95 < 300ms。
- 搜索：输入停止后首批结果 P95 < 500ms；超过阈值再考虑 FTS。
- 播放：连续播放不出现明显掉帧；seek 后首帧时间和 Provider read 次数可记录。
- 同步：并发 1/3/5 的吞吐、CPU、内存和 NAS 错误率可比较；不要只追求文件数/秒。
- 内存：大列表、批量缩略图、连续 seek、长时间浏览后无持续增长趋势。

---

## 7. 可维护性与测试计划

### 7.1 当前测试覆盖地图

| 模块 | 当前状态 | 判断 |
|---|---|---|
| `sync` | `SyncEngine`、dry-run、进度、错误映射、客户端契约有测试 | 核心路径较好，继续补边界 |
| `crypto` | Scheme、Master、RangeDecryptor 有测试 | 强，属于保护区 |
| `storage` | Store、FileVolume、Provider、Paths 部分测试 | 中等，SAF 仍需插桩/替身测试 |
| `util` | `VideoUtils`、`FormatUtils` | 较完整 |
| `provider` | 主要只有 Uri 测试 | Provider 实际读写未覆盖 |
| `browser` | 无 ViewModel/UI 测试 | 高风险空白区 |
| `player` | 无 ViewModel/UI 测试 | 手势和状态回归风险 |
| `settings` | 无 ViewModel/UI 测试 | 表单、校验、导航回归风险 |
| `data` | DAO/Repository 基本无测试 | SQL 和映射风险 |
| `security` | 无测试 | 锁定/生物识别状态风险 |
| `imageviewer` | 无测试 | 队列、删除、手势风险 |

### 7.2 推荐测试优先级

#### T1：DAO/Repository 契约

使用 `Room.inMemoryDatabaseBuilder`：

- `VideoItemDao`：根目录/子目录/深层目录、同名不同 relation、过滤、排序、star、播放记录 join、搜索、删除。
- `PendingSyncActionDao`：replace、PENDING/RUNNING 恢复、completed/failed/cancelled 清理。
- `SyncRelationDao`：路径重叠、source 删除限制、当前关系匹配。
- Repository：entity ↔ domain 映射和异常转换。

#### T2：ViewModel 状态流

使用 `runTest`、`StandardTestDispatcher`、Turbine：

- `BrowserViewModel`：folder 切换取消旧构建、筛选 debounce、刷新、relation 匹配、scroll state。
- `SyncPreviewViewModel`：loading/success/error、扫描失败时禁用确认、执行中取消、部分失败。
- `PlayerViewModel`：播放记录加载、队列前后边界、恢复位置、速度/方向持久化。
- `SettingsViewModel`：安全锁、存储、导航状态和失败状态。

不要在 ViewModel 单测中断言完整中文文案；断言状态、错误码、数量和调用次数。

#### T3：Use case/Provider

- `FileOperationsUseCase`：active volume、未知 volume、文件缺失、缩略图缺失、DB 回滚/部分失败。
- `MediaContentProvider`：非法 URI、未知 id、卷不匹配、媒体/thumbnail MIME、Provider open 失败。
- `DecryptingFdCallback`：随机 offset、跨 chunk、EOF、认证失败和取消。

#### T4：Compose UI / 插桩测试

先覆盖关键任务，不追求全屏幕像素快照：

- 空库 → 配置 CTA。
- 浏览筛选/清除筛选。
- 同步预览错误和删除确认禁用。
- 删除后撤销。
- 播放器 play/pause/exit/trash/settings 的语义标签。
- 锁屏重试按钮和错误状态。

### 7.3 CI 门槛建议

分阶段执行，避免一次引入不稳定的全量门禁：

1. 当前：`compileDebugKotlin` + `testDebugUnitTest` + 已有 lint。
2. 增加 DAO/ViewModel 测试后：CI 运行 unit test，并保存测试报告。
3. 引入 Kover 后先只报告覆盖率，不失败；连续 2–3 个版本稳定后再设低门槛。
4. 首个门槛建议只保护核心包：`crypto`、`sync`、`storage`、`data`，不要用全项目平均值掩盖 UI/核心逻辑空白。
5. 插桩测试稳定后再加入 release gate；不要让模拟器偶发故障阻塞普通分支检查。

### 7.4 工程文档校准

需要修复或补充：

- `AGENTS.md` 中分支名仍写 `master`，实际当前为 `main`。
- 目录清单中 `LegacyCiphertextException.k` 应为 `.kt`。
- `SyncRelationListScreen.kt` 在清单中重复。
- 增加 `debug/DebugLogScreen.kt`、`docs/ocmar/` 等当前实际目录说明。
- 在 `AGENTS.md` 增加“报告/基准数据存放位置”和“不得把未经基准验证的性能假设当作 P0”的原则。

---

## 8. 架构决策建议

### 8.1 不建议现在做的事情

以下改动风险高、收益尚未证明，当前不应作为第一批任务：

- 不要因为 `SyncEngine` 很大就整体重写同步流程。
- 不要在没有 `EXPLAIN QUERY PLAN` 和真实数据测量时新增路径物化表或修改数据库 schema。
- 不要为了“统一架构”强行把所有 ViewModel 改成完整 MVI；先统一状态命名、事件边界和测试方式。
- 不要简单永久缓存 `volumeId`；存储卷可以运行时切换，必须有失效机制。
- 不要把 `RangeDecryptor` 缓存做成全局 bitmap/byte cache；必须有大小上限、线程安全和认证失败策略。
- 不要将所有错误重新改回 `Throwable.message`；结构化错误模型是项目的重要资产。

### 8.2 推荐的架构演进方向

采用“渐进提取、每一步可编译可回滚”的路线：

```text
现有大类
  ↓ 提取纯函数/DTO（无行为变化）
可测试的 builder / mapper / checker
  ↓ 提取单职责执行器（保留现有接口）
可独立测试的 orchestration components
  ↓ 再决定是否调整 ViewModel/UI 状态形态
稳定的分层边界
```

统一规则优先于统一模式：

- ViewModel 公开 immutable `StateFlow`，内部状态不可外泄。
- UI 只订阅 lifecycle-aware flow。
- 一次性事件使用明确的 effect/event channel 或状态字段，不用共享 singleton 副作用传递。
- Repository 不返回 UI 文案。
- Sync/Storage 层不依赖 Compose。
- Provider 的同步 API 只保留在 Android 进程边界，内部用明确的 IO 协作。

### 8.3 VideoQueue/ImageQueue 的处理

当前 `AtomicReference` 已避免最直接的并发读清空竞态，但它仍是进程级隐式状态。短期不要贸然删除，建议分两阶段：

1. 先为导航参数增加明确的 `SavedStateHandle`/route payload，并让 queue 只作为兼容回退。
2. 稳定后移除 singleton queue，改为带来源、folder、selected id list 的明确导航结果。

完成标准是快速连续导航、进程重建、搜索结果进入播放器时都不会播放旧列表。

---

## 9. 分阶段执行路线图

### 阶段 A：安全与易用性止血（1–3 天）

**目标**：不改数据模型，不改加密格式，解决用户立即可见问题。

- [ ] 抽取所有用户可见硬编码文案到 `strings.xml`。
- [ ] 移除 `StorageManagementViewModel` 的异常原文展示。
- [ ] 统一播放器/图片 overlay 的本地化和枚举语义。
- [ ] 设计并实现空库首次使用 CTA。
- [ ] 修正 `AGENTS.md` 过期目录/分支信息。
- [ ] 运行 `compileDebugKotlin`、`testDebugUnitTest`、lint。

**验收**：默认语言 UI 无明显英文/异常泄漏；新用户可以从空库开始配置；现有测试全绿。

### 阶段 B：行为保护和高风险测试（3–7 天）

- [ ] `VideoItemDao` in-memory 测试。
- [ ] `PendingSyncActionDao` 测试。
- [ ] `FileOperationsUseCase` volume/delete 测试。
- [ ] `SyncPreviewViewModel` 和 `BrowserViewModel` 关键状态测试。
- [ ] `MediaContentProvider`/`DecryptingFdCallback` 错误边界测试。
- [ ] 加入删除撤销前的操作状态模型测试。

**验收**：同步、删除、卷切换和预览错误有可重复的自动化证据。

### 阶段 C：复杂度拆分（1–2 周）

- [ ] 提取 `BrowseItemBuilder`。
- [ ] 拆分 `SyncEngine` 的 progress/space/download/delete 组件。
- [ ] 拆分 Browser/Player UI 子 composable。
- [ ] 清理稳定 DTO 和重复映射。
- [ ] 为每次提取保留行为对比测试。

**验收**：大型文件职责明显下降；测试可以在不启动 Compose/Service 的情况下覆盖核心行为。

### 阶段 D：测量驱动的性能优化（1–2 周）

- [ ] 建立 1K/10K/50K 模拟数据库。
- [ ] 记录目录导航、文件模式、搜索 P50/P95。
- [ ] 做 Compose 重组计数和滚动帧率基线。
- [ ] 做随机读取、seek、Provider open/read 基线。
- [ ] 做同步吞吐、内存、GC、并发错误率基线。
- [ ] 只对超过阈值的瓶颈实施查询/缓存/进度结构优化。

**验收**：每个性能改动都有前后数据，且没有破坏加密/卷/取消语义。

### 阶段 E：产品体验完善（持续）

- [ ] 下拉刷新。
- [ ] 批量选择和批量操作。
- [ ] 同步关系健康信息。
- [ ] 导航过渡。
- [ ] 设置页分组和视觉层级。
- [ ] 无障碍语义、动态字体和手势替代路径。
- [ ] Compose UI/instrumentation 关键任务测试。

---

## 10. Agent 执行拆分建议

为了避免并发修改冲突，按文件所有权拆分：

### Lane A — UI/UX（`@designer`）

负责：

- 空库首次使用卡片/CTA。
- 设置页分组、同步预览信息层级。
- 删除撤销 Snackbar 的视觉反馈。
- 无障碍可发现性和触摸目标设计。

写入范围：`BrowserScreen.kt`、`SettingsScreen.kt`、`SyncPreviewScreen.kt`、相关 `ui/components`、资源文案（需与 Lane B 协调）。

### Lane B — i18n/错误文本（`@fixer`）

负责：

- `strings.xml` 补充。
- `BiometricAuthManager`、`SettingsViewModel`、`StorageManagementViewModel`、播放器/图片 overlay 文本迁移。
- 将错误状态改为错误码/资源解析。

写入范围：上述 ViewModel/security/provider 和 `strings.xml`；不要同时重构 UI 布局。

### Lane C — 数据与测试（`@fixer`）

负责：

- `VideoItemDao` Room 测试。
- `PendingSyncActionDao`、Repository 测试。
- `BrowseItemBuilder` 提取及纯数据测试。

写入范围：`data/`、`browser/BrowserViewModel.kt`、测试目录；不要改 UI 文件。

### Lane D — 同步引擎（`@fixer`）

负责：

- 先补 `SyncEngine` 回归测试。
- 提取 progress/space/download/delete 组件。
- 保持 `SyncService` public contract 和错误语义。

写入范围：`sync/` 及对应测试；不要同时改 storage schema 或 UI。

### Lane E — 验证/评审

由独立 verifier 或 reviewer 检查：

- 加密格式未变。
- E8、卷所有权、取消和重试白名单仍满足。
- `compileDebugKotlin`、`testDebugUnitTest`、lint。
- UI lane 的 Compose 交互和无障碍手工检查。

**并行规则**：Lane B 与 Lane D/C 可并行；Lane A 与 Lane B 共享 `strings.xml` 时必须先分配资源命名空间或串行合并。Lane C 的 `BrowserViewModel` 提取与 Lane A 的 `BrowserScreen` 修改存在依赖，应先完成接口设计再并行。

---

## 11. 每项改动的统一验收模板

后续 agent 提交改动时，报告中至少应包含：

```text
目标：
涉及文件：
行为变化：
未改变的安全/数据契约：
新增或更新测试：
验证命令：
验证结果：
未解决风险：
```

最低验证命令：

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

涉及 Android 资源、Manifest、Compose 或 Provider 时，根据变更范围增加：

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew lintDebug
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug --no-daemon
```

涉及 release 或签名时，不以 debug 编译代替 release gate，应按 `docs/release.md` 执行。

---

## 12. 最终优先级清单

### 现在做

- 用户可见文本本地化和异常泄漏修复。
- 空库首次使用引导。
- 同步删除/取消/卷不匹配回归测试。
- `VideoItemDao` 和 pending action 测试。
- 校准 `AGENTS.md`。

### 下一轮做

- 拆分 `SyncEngine`。
- 提取 `BrowseItemBuilder`。
- Browser/Player UI 子组件化。
- 删除撤销。
- ViewModel 状态流测试。

### 有测量再做

- `LIKE`/路径层级 schema 优化。
- `RangeDecryptor` chunk cache。
- `completedPaths` 数据结构改造。
- ContentProvider `volumeId` 缓存。
- FTS 搜索。
- 下载 buffer 复用。

### 暂不做

- 全面重写架构。
- 改 AEAD v2 格式。
- 放宽卷所有权检查。
- 用异常原文替代结构化错误。
- 为了统一模式而强制所有页面改成同一种 MVI。

---

## 附录 A：关键文件索引

| 主题 | 文件 |
|---|---|
| App 入口/锁屏 | `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt`, `SyncPlayerApp.kt`, `security/*` |
| 导航 | `navigation/Route.kt`, `navigation/AppNavHost.kt`, `navigation/VideoQueue.kt`, `ImageQueue.kt` |
| 浏览 | `browser/BrowserScreen.kt`, `BrowserViewModel.kt`, `SearchScreen.kt`, `FileOperationsUseCase.kt` |
| 播放 | `player/PlayerScreen.kt`, `PlayerViewModel.kt`, `GesturePlayerView.kt`, `SeekPreviewExtractor.kt` |
| 同步 | `sync/SyncEngine.kt`, `SyncService.kt`, `SyncPreviewViewModel.kt`, `SyncPreviewScreen.kt` |
| 远端 | `sync/smb/SmbFileClient.kt`, `sync/sftp/SftpFileClient.kt`, `RemoteFileClient.kt` |
| 加密 | `crypto/CryptoScheme.kt`, `ChunkedEncryptSink.kt`, `RangeDecryptor.kt`, `DecryptingFdCallback.kt`, `MediaCryptoMaster.kt` |
| 存储 | `storage/volume/EncryptedMediaStore.kt`, `MediaVolume.kt`, `FileVolume.kt`, `SafVolume.kt` |
| Provider | `provider/MediaContentProvider.kt`, `MediaUris.kt` |
| 数据库 | `data/local/AppDatabase.kt`, `data/local/entity/*`, `data/local/dao/*`, `data/repository/*` |
| 主题/组件 | `ui/theme/*`, `ui/components/*` |
| 文案 | `app/src/main/res/values/strings.xml` |
| 工程约束 | `AGENTS.md`, `docs/release.md`, `.gitea/workflows/*` |

## 附录 B：评估边界

本报告基于静态代码和现有测试盘点。以下内容尚未被本报告证明：

- 真机上的实际帧率、功耗、SAF Provider 差异和网络吞吐。
- 1 万、5 万以上媒体库的实际 SQL 延迟。
- 不同 SMB/SFTP 服务端的错误码兼容性。
- TalkBack、字体放大、横屏/折叠屏等真实设备体验。
- 应用进程被系统杀死、Provider 重建、存储卷热切换的所有时序组合。

因此，后续性能和系统边界问题应先建立最小可重复实验，再决定是否改变架构或数据格式。
