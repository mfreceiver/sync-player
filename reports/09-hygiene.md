# Phase 9：代码卫生
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - `grep -rnE 'TODO|FIXME|HACK|XXX' --include='*.kt' app/src` | scan_total=1 | adjudicated=1（StorageManagementViewModelTest.kt:55 测试夹具字符串含 "XXXX"→FP） | excluded_scope_or_FP=1 | unknown=0 —— **main 源码零标记**
  - 超长函数两步法（10 个 >400 行文件 × fun 起点正则） | 候选=20 | 实测确认（括号配平）=12 处 >80 行 | 排除 8（包装函数/嵌套函数误距） | unknown=0
  - 顶层类型符号零引用扫描 | scan_total=全部顶层声明 | adjudicated=3（DatabaseModule/DispatchersModule/SyncModule——Hilt @Module 注解入口，排除） | excluded_scope_or_FP=3 | unknown=0
- 唯一发现: P0=0, P1=0, P2=4（CONFIRMED=2, CANDIDATE=2）；引用他 Phase 的 REF: 0
- U 条目: 0

## 9.1 TODO/FIXME/HACK/XXX

主源码 **0 处**（唯一命中 StorageManagementViewModelTest.kt:55 为泄漏用例的夹具字符串 `secretInternalMessage="volumeId=…XXXX…"`，FP）。附带卫生观察：crypto/ 三文件注释引用易失路径 `/tmp/opencode/sync-player-aead-spec.md`（CryptoScheme.kt:18、RangeDecryptor.kt:10、ChunkedEncryptSink.kt:8）——不立项，建议改为 docs/specs/crypto.md 引用。

## 9.2 超长函数（两步机械法 + 括号配平实测）

第①步多修饰符正则定位 20 个候选（起点差 >80）；第②步逐个括号配平验证真实行数，**确认 12 处 >80 行**：

| 位置 | 实测行数 | 函数 |
|---|---|---|
| player/PlayerScreen.kt:77 | **701** | PlayerScreen（主 Composable，含手势/对话框/退出逻辑） |
| sync/SyncEngine.kt:384 | **400** | executeSyncLocked |
| settings/GainAnalysisScreen.kt:25 | 345 | GainAnalysisScreen |
| sync/SyncEngine.kt:833 | 155 | executeDeleteLocked |
| imageviewer/ImageViewerScreen.kt:234 | 147 | ZoomableImage |
| sync/SyncEngine.kt:249 | 124 | dryRunLocked |
| browser/FileOperationsUseCase.kt:460 | 117 | deleteMediaByIdLocked |
| sync/SyncPreviewScreen.kt:518 | 112 | SyncProgressCard |
| browser/BrowserContent.kt:154 | 107 | GridContent |
| browser/SearchScreen.kt:365 | 86 | SearchFileItem |
| browser/BrowserContent.kt:345 | 86 | ListContent |
| browser/BrowserContent.kt:563 | 82 | FileItem |

排除 8 处：PlayerScreen:269 doExit（嵌套函数，实长 12）、SyncPreviewScreen:30（4 行包装）、BrowserScreen:60（5 行包装）、SearchScreen:47（6 行）、ImageViewerScreen:99（5 行）、BrowserViewModel:181（9 行）、PlayerScreen:951（79 行）、BrowserContent:264（78 行）。

### F-9-1 [P2] [CONFIRMED] [P9-超长函数]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:384
- 代码: `internal suspend fun executeSyncLocked(`
- 问题: 12 个函数实测超过 80 行（上表全列；最大 PlayerScreen.kt:77 达 701 行、executeSyncLocked 400 行）——认知负担与回归风险集中点（SyncEngine 三个核心流程全部超标）。
- 复现: grep -nE '^([[:space:]]{4})?((public|private|…|suspend)[[:space:]]+)*fun[[:space:]]+' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0；配合括号配平脚本，见附录说明）
- 建议: executeSyncLocked 拆下载 worker/删除段/收尾段；PlayerScreen 提取对话框与手势区段。

## 9.3 重复模式（≥15 行近同块）

- **PlayerViewModel.kt:209-234 ≡ ImageViewerViewModel.kt:119-144**：toggleTrashed 26 行近逐字相同（仅 videoId/imageId、`videoItem`/`currentImage` 状态访问器不同；连注释都平行）。对应测试 PlayerViewModelToggleTest(199 行)/ImageViewerViewModelToggleTest(176 行)亦平行。
- VideoMetadataExtractor vs ImageMetadataExtractor：结构平行（extractAndSave→writeThumb→upsert）但 diff 105 行差异、无 ≥15 行逐字块；sftp/ vs smb/ 客户端 testConnection 骨架 8 行同构（<15，协议差异所致，可接受）。

### F-9-2 [P2] [CONFIRMED] [P9-重复]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:209
- 代码: `fun toggleTrashed(onSuccess: (Long) -> Unit = {}) {`
- 问题: 与 ImageViewerViewModel.kt:119 构成 26 行近同块（错误处理/状态刷新/onSuccess 语义完全一致），双份维护（R3 守卫等改动需同步两处）。
- 复现: diff <(sed -n '209,234p' app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt) <(sed -n '119,144p' app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt) | grep -c '^[<>]' （exit=0，差异仅 8 行——全部为 videoId/imageId 替换）
- 建议: 提取共享"toggle+刷新+守卫"helper（按 id + 状态选择器参数化）。

## 9.4 debug/ 包归属

`grep -n "debug" app/build.gradle.kts` 仅命中 :155 `debugImplementation(libs.compose.ui.tooling)`；**无 buildType/sourceSet 配置**将 `debug/` 包（DebugLogger）或 DebugLogScreen 排除出 release——两者随 release 分发。DebugLogScreen 是设置页内的产品功能（个人使用诊断 UI），非构建期调试设施，功能上有意保留；但包名 `debug/` 具误导性。

### F-9-3 [P2] [CANDIDATE] [P9-命名/归属]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt:17
- 代码: `object DebugLogger {`
- 问题: `debug/` 包名暗示构建期调试设施，实际为随 release 分发的产品功能（DebugLogScreen 消费），且该功能正是 F-2-28 的敏感信息暴露面——命名与归属易误导维护者放宽其安全约束。CANDIDATE（命名/组织债，非行为违规）。
- 复现: grep -n 'debug' app/build.gradle.kts （exit=0，命中 1 行，仅 tooling 依赖）
- 建议: 更名（如 `diagnostics/`）并明确注释其 release 可见性与日志契约约束。

## 9.5 疑似未引用符号

顶层 class/object/interface/typealias 全集扫描：零外部引用仅 3 个 Hilt `@Module` object（注解入口，任务书要求先排除的 Hilt 入口——**判定非未引用**）。另发现成员函数 1 个：`StoragePaths.isPathSafe`（:99）仅被 StoragePathsTest 引用，生产代码 0 调用。

### F-9-4 [P2] [CANDIDATE] [P9-未引用]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:99
- 代码: `fun isPathSafe(relativePath: String): Boolean = try {`
- 问题: 生产代码无调用（仅测试引用）；作为安全校验辅助函数闲置——或为 UI 预检预留（KDoc :95"for UI pre-checks"）但从未接线。CANDIDATE（可能是有意保留的 API）。
- 复现: grep -rnw 'isPathSafe' --include='*.kt' app/src （exit=0，命中 6 行——1 声明 + 5 测试）
- 建议: 接线使用或删除（测试随之调整）。
