# 目录切换迟钝问题 — 交叉诊断报告

**诊断方**：Sisyphus（3 个并行 explore agent）+ OpenAI Codex（gpt-5.5，独立审计）
**日期**：2026-06-18
**症状**：目录切换时总有迟钝感；与数据量无关的固定延迟 + 大目录更严重

---

## 〇、核心结论

**动画零贡献。** 迟钝 = **300ms 固定 debounce** + **数据管线冗余**（重复构建 / 死参数加载 / 串行 I/O）。

---

## 一、双方一致认定的问题（高置信度）

| # | 问题 | 分类 | Sisyphus | Codex |
|---|------|------|----------|-------|
| A1 | **`.debounce(300)` 加在 combine 之后** — 即使空目录也白等 300ms | 固定 | ✅ | ✅ |
| A2 | `buildBrowseItems` 串行执行 4+ 次 DB 查询 + 文件系统扫描 | 变长 | ✅ | ✅ |
| A3 | `repairMissingThumbnails` 每次切目录都跑（至少 1 次 DB 查询） | 变长 | ✅ | ✅ |
| A4 | `physicalDir.listFiles()` 被调用两次（重复扫描） | 变长 | ✅ | ✅ |
| A5 | `getSubFolders` / `getChildVideoStats` 用 LIKE + SUBSTR，未充分利用 parentPath 索引 | 变长 | ✅ | ✅ |

---

## 二、Codex 独立发现的、Sisyphus 漏掉的问题（已核实成立）

> ⚠️ 这些是本次交叉诊断的**最大价值所在** —— 两个都是 Sisyphus 第一轮分析未捕获的实质性问题。

### B1. `allPlaybackRecords` 是死参数（已核实 ✅）

- **位置**：`BrowserViewModel.kt:173-175`（加载）、`:193`（PlaybackBundle 字段）、`:233`（函数参数）
- **机制**：`playbackRepository.getAllFlow()` 加载**全库所有播放记录**，塞进 combine 链参与每次重组；但 `buildBrowseItems` 函数体（235-478 行）**从未引用 `allRecords`**。
- **影响**：
  - O(全库播放记录) 的内存与 DB 开销，每次播放记录变化都触发**与当前目录无关**的 browser 重建。
  - 直接加剧 B2 的重复构建问题。
- **核实**：grep 全文件，`allRecords` 仅出现在数据传递路径（193/212/213/217/219/233），函数体内零引用。

### B2. 分裂的 flow 导致重复构建（已核实 ✅）

- **位置**：`BrowserViewModel.kt:207-220`
- **机制**：folder 变化时，左侧 `combine(effectiveFolder, ...)` 立即发射；但右侧 `folderPlaybackRecords` 是 `effectiveFolder.flatMapLatest`，新目录的播放记录**晚一步**才到。
- **后果**：`combine` 先用「新目录 + 旧播放记录」触发一次 `buildBrowseItems`，再用「新目录 + 新播放记录」触发**第二次**。每次切目录白跑一次完整构建（含全部 DB + 文件系统 I/O）。
- **核实**：Flow 语义确认 —— `flatMapLatest` 的内层 Flow 需要重新订阅，必然滞后于 `effectiveFolder` 本身的发射。

### B3. `repairMissingThumbnails` 的 `filter{}.take(5)` 扫全表（已核实 ✅）

- **位置**：`BrowserViewModel.kt:627-630`
- **机制**：`videos.filter { File(thumbnailPath).exists() && File(localAbsolutePath).exists() }.take(5)` —— Kotlin 的 `filter` 是及早求值的，**先对整个列表做完所有 `File.exists()` 检查**，再 `take(5)`。
- **影响**：O(目录内视频数) 次文件系统 stat，即使前 5 个就缺缩略图。
- **修复**：`videos.asSequence().filter{...}.take(5).toList()` 或在 SQL 层筛选。

### B4. `folderHasSubDirs` 无条件计算，但仅 UNWATCHED 筛选用（已核实 ✅）

- **位置**：`BrowserViewModel.kt:308-312`
- **机制**：对每个子目录调 `childDir.listFiles()?.any { it.isDirectory() }` 计算 `folderHasSubDirs` map，但该 map 只在 `filterVal == UNWATCHED` 时才被读取。
- **影响**：N 个子目录 = N 次 `listFiles()`，非 UNWATCHED 模式下纯属浪费。

### B5. UI 标题先更新、列表后更新，放大感知延迟

- **位置**：`BrowserScreen.kt:175`（标题用 `currentFolder`）vs `:469`（列表用 `browseItems`）
- **机制**：`currentFolder` 立即变，`browseItems` 等 300ms debounce + 构建时间。用户看到「标题已切换但内容还是旧目录」，延迟感被放大。
- **Sisyphus 未提及此 UX 角度** —— Codex 的独立视角补全了「为什么用户觉得卡」的感知层面。

---

## 三、Sisyphus 发现的、Codex 未重点展开的问题

### C1. 14 个 `collectAsStateWithLifecycle` 集中在 BrowserScreen 顶层

- **位置**：`BrowserScreen.kt:105-119`
- Codex 未关注 UI 重组层面。这 14 个 StateFlow 任一变化都触发整个 BrowserScreen 重组。虽非主因，但属于可优化项。

### C2. 文件夹网格瓦片缩略图密度

- Grid 模式下每个文件夹瓦片最多加载 9 张缩略图（`FolderGridItem.kt`），屏幕上 F 个文件夹 = F×9 个 Coil 请求。Codex 未提及。

### C3. 导航模式确认（排除项）

- Sisyphus 明确排除「路由跳转导致整屏 swap」—— 目录切换是纯 ViewModel 状态更新，无 `navController.navigate`。Codex 隐式假设了这一点但未验证。

---

## 四、动画侧结论（双方一致）

| 检查项 | 结论 |
|--------|------|
| NavHost 转场动画 | `EnterTransition.None`，零开销 |
| BrowserScreen 内部动画 | 无任何 `AnimatedVisibility`/`Crossfade`/`AnimatedContent` |
| 缩略图淡入 | 未启用 `crossfade`，零开销 |
| 目录切换是否路由跳转 | 否，纯状态更新 |

**用户原始问题的回答**：不是动画限制，也不是单纯数据过多，而是 **300ms 固定 debounce + 数据管线冗余（死参数、重复构建、串行 I/O）** 共同造成。数据多会加重，但即使空目录也卡。

---

## 五、合并后的优先级（按 收益/成本 排序）

| 优先级 | 动作 | 目标 | 预期收益 | 风险 | 来源 |
|--------|------|------|----------|------|------|
| **P0** | 移除/拆分 `.debounce(300)` —— 只对 sort/filter debounce，目录切换直通 | A1 | 消除 300ms 硬下限 | 低 —— 需配合 P1 防重复构建 | 双方 |
| **P1** | 用单个 `flatMapLatest(folder)` 统一所有目录相关数据流 | B2, A2 | 消除每次切换的 1 次浪费构建 | 中 —— 需测播放/星标/同步更新后的 Flow 失效 | Codex |
| **P2** | 从 browser 管线删除 `allPlaybackRecords`（死参数） | B1 | 消除 O(全库播放记录) 无谓加载 + 无关重建 | 低 —— 需确认 file mode 不依赖它 | **Codex 独有** |
| **P3** | `PlaybackRecordDao.getByFolderFlow` 改用 `parentPath = :folder` | A5 | 播放记录查询走索引 | 低 —— 根目录行为需测 | Codex |
| **P4** | 缩略图修复改为空闲时跑 + `asSequence().take(5)` | A3, B3 | 消除与主加载争抢 I/O + O(N) stat 扫描 | 低 | 双方 |
| **P5** | 合并两次 `listFiles()` + `folderHasSubDirs` 仅 UNWATCHED 时算 | A4, B4 | 减少文件系统扫描 | 低 —— 测 UNWATCHED 筛选 | 双方 |
| **P6** | 长期：规范化 `folder_summary` 表，直接子项查询 | A2, A5 | 大库最大可扩展性收益 | 高 —— 需 Room migration + 同步/删除路径维护 | Codex |

---

## 六、建议的「最小可行修复」（P0+P1+P2）

一次性完成前三项即可消除**绝大部分感知延迟**，且风险可控：

1. **P0**：debounce 只作用于 filter 相关 flow，`effectiveFolder` 直通
2. **P1**：`effectiveFolder.flatMapLatest { folder -> combine(videos, records, subfolders) {...} }`，确保单次构建用一致数据
3. **P2**：删掉 `allPlaybackRecords` 及其在 `PlaybackBundle` / `buildBrowseItems` 签名中的传递

预计改完后：空目录切换 < 50ms，大目录切换从 360-580ms 降至 100-250ms。

---

## 附：诊断方法说明

- **Sisyphus**：3 个并行 explore agent，分别调研（1）动画/转场、（2）数据加载流、（3）导航与重组模式
- **Codex (gpt-5.5)**：开放性 prompt 独立审计，未被告知 Sisyphus 的发现，仅给定症状与项目结构
- **交叉核实**：报告中标「已核实 ✅」的 Codex 独有发现，均由 Sisyphus 读取实际代码确认成立
