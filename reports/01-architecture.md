# Phase 1：架构分层合规
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - `grep -rn "import cn.vectory.syncplayer.data.local" --include='*.kt' <ui|player|browser|settings|imageviewer 五包>` | scan_total=18 | adjudicated=18（4 CONFIRMED 违规行→2 canonical Finding，14 行判定 PASS=repository API 既定通货） | excluded_scope_or_FP=0 | unknown=0
  - `grep -rn "import cn.vectory.syncplayer.data.local" --include='*.kt' <navigation 包>` | scan_total=0（EMPTY，exit=1 零命中） | adjudicated=0 | excluded_scope_or_FP=0 | unknown=0
  - `grep -rnE '^import android\.' …(ViewModel/UseCase 文件过滤)` | scan_total=24 | adjudicated=15（6 行 CANDIDATE→1 canonical Finding，9 行 Log=PASS） | excluded_scope_or_FP=9（Context 豁免） | unknown=0
  - `grep -rn "@HiltViewModel\|@Inject" --include='*.kt' app/src/main` | scan_total=54 | adjudicated=10（铁律 8 样本，10 PASS） | excluded_scope_or_FP=0 | unknown=44（未入样本部分，未逐一裁定）
- 唯一发现: P0=0, P1=0, P2=3（其中 CONFIRMED=2, CANDIDATE=1）；引用他 Phase 的 REF: 0 条（不计入）
- U 条目: 0

## 1.0 规范条款编号（docs/specs/architecture.md，原文摘录）

| 条款 | spec 位置 | 原文（≤2 行摘录） | 判定 |
|---|---|---|---|
| ARCH-1 | architecture.md:28 | "Versions are centralized in `gradle/libs.versions.toml` (single source of truth). Do not pin versions inline in `app/build.gradle.kts`." | 符合 |
| ARCH-2 | architecture.md:34-44 | 分层图：Compose UI → ViewModel → Use case/service → Engine/Repository/Storage → Room DAO/Crypto/RemoteFileClient | 部分符合（见 1.1） |
| ARCH-3 | architecture.md:47 | "**General**: MVVM + Repository, Hilt DI。" | 部分符合（见 1.4 与 F-1-1） |
| ARCH-4 | architecture.md:48 | "**Player**: **MVI pattern** with unified `PlayerUiState`。" | 符合 |
| ARCH-5 | architecture.md:49 | "**Single Activity**: 所有 screen 是 `@Composable`，Navigation Compose 导航。" | 符合 |
| ARCH-6 | architecture.md:52 | "高频更新（如播放位置）**必须**放在独立 `StateFlow`，与主 `UiState` 分离，防止全屏重组。" | 符合 |
| ARCH-7 | architecture.md:53 | "`PlayerViewModel.currentPosition`（200ms 轮询）独立于低频 `PlayerUiState`。" | 符合 |
| ARCH-8 | architecture.md:56 | "订阅高频状态的 Composable（如进度条）**必须**提取为独立函数……" | 符合 |
| ARCH-9 | architecture.md:57 | "低频状态（isPlaying, isFullscreen）**必须**以 primitive 参数下传……" | 符合 |
| ARCH-10 | architecture.md:58 | "顶层 Screen 收集的 StateFlow 订阅不应扩散到子 Composable……" | 符合 |
| ARCH-11 | architecture.md:145-150 | OrientationPreference（持久化）与 OrientationMode（瞬时）"两个独立概念，不要混淆" | 符合 |

## 1.1 ARCH-2 分层：UI 侧直接 import Room DAO/entity

扫描（exit=0）命中 18 行（另有 navigation 包扫描 exit=1 零命中）。逐行分类：

| 位置 | 类型 | 判定 |
|---|---|---|
| settings/StorageManagementViewModel.kt:8,9,10 | ViewModel import **DAO** | **违规**（F-1-1） |
| settings/AboutScreen.kt:20 | Compose Screen import entity | **违规**（F-1-2） |
| browser/BrowserViewModel.kt:8 | ViewModel import entity | PASS（repository API 即返回 entity，见证据） |
| browser/FileOperationsUseCase.kt:12 | UseCase import entity | PASS（UseCase 位于 Repository 之上，消费 repository 通货类型） |
| settings/SettingsViewModel.kt:9 | ViewModel import entity | PASS（同上） |
| imageviewer/ImageViewerViewModel.kt:6,7 | ViewModel import entity | PASS（同上） |
| settings/GainAnalysisViewModel.kt:8 | ViewModel import entity | PASS（同上） |
| settings/MetadataRebuildViewModel.kt:10 | ViewModel import entity | PASS（同上） |
| player/PlayerViewModel.kt:8,9 | ViewModel import entity | PASS（同上） |
| browser/BrowseItemBuilder.kt:3,4,5,6 | 纯数据构建器 import DAO 投影类型/entity | PASS（spec architecture.md:74 明确声明其消费这些类型） |

"entity=通货"证据：`data/repository/VideoRepository.kt:20` `fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>> =`——Repository 公开 API 直接返回 entity，上层 import entity 是该设计的必然。spec 未禁止 entity 跨层；但 **DAO 直注 ViewModel** 与 **Composable 直接消费 entity** 无 spec 依据，立 Finding。

## 1.2 ARCH-3 附带：ViewModel/UseCase import android.*（Context 除外）

非 Context 框架类 import 共 15 行（9 行 `android.util.Log` 判 PASS——conventions 仅要求日志文本不本地化，未禁 Log；6 行 UI 框架类耦合力 F-1-3 CANDIDATE）：Uri/DocumentsContract（SyncRelationEditViewModel.kt:4,5）、Intent（FileOperationsUseCase.kt:4 / BrowserViewModel.kt:4 / SearchViewModel.kt:3）、ActivityInfo（PlayerViewModel.kt:3）。Context 9 行按任务书豁免。

## 1.3 ARCH-6/7 State Splitting（>300 行 ViewModel：BrowserViewModel 617、PlayerViewModel 371）

- PlayerViewModel.kt:76-80：`_uiState`/`PlayerUiState` 与 `_currentPosition`（79）**分离**，符合 ARCH-6/7。
- BrowserViewModel.kt:59-100：currentFolder/sortMode/filter/starFilterMode/browseMode/viewMode/flatTotalCount/error 等全部为**独立细粒度 StateFlow**，无单一 data class 大状态整体 emit，符合。
- 附带发现（转 Phase 4 处置）：PlayerViewModel.kt:83 `val _playerErrorFlow = MutableStateFlow<String?>(null)` 为 **public** 可变流暴露。

## 1.4 ARCH-3 Hilt 构造注入抽查（铁律 8）

清单：`grep -rn "@HiltViewModel\|@Inject" --include='*.kt' app/src/main`（54 行，exit=0），LC_ALL=C 排序。L=54，effectiveN=min(54,10)=10，hashByte=231，offset=231 mod 54=15，step=floor(54/10)=5；索引=(15,20,25,30,35,40,45,50,1,6)（mod 54）。样本 10/10 PASS（构造注入规范，@HiltViewModel 类均带 `@Inject constructor`）：

1. MainActivity.kt:31 `@Inject lateinit var appLockManager`（Activity 字段注入，规范允许）
2. audio/AudioGainAnalyzer.kt:26 `class AudioGainAnalyzer @Inject constructor(`
3. data/repository/SyncRelationRepository.kt:17 `class SyncRelationRepository @Inject constructor(`
4. imageviewer/ImageViewerViewModel.kt:39-40 `@HiltViewModel` + `@Inject constructor(`
5. navigation/VideoQueue.kt:8 `class VideoQueue @Inject constructor()`
6. settings/GainAnalysisViewModel.kt:30-31 `@HiltViewModel` + `@Inject constructor(`
7. settings/SettingsViewModel.kt:35 `class SettingsViewModel @Inject constructor(`
8. settings/SyncRelationListViewModel.kt:11-12 `@HiltViewModel` + `@Inject constructor(`
9. settings/SyncSourceListViewModel.kt:13-14 `@HiltViewModel` + `@Inject constructor(`
10. sync/SyncEngine.kt:66 `class SyncEngine @Inject constructor(`

## 1.5 ARCH-4/5/11 快速核验

- ARCH-4：player/PlayerViewModel.kt:43 `data class PlayerUiState(`，统一 MVI 状态存在。符合。
- ARCH-5：`grep -c '<activity' app/src/main/AndroidManifest.xml` = 1（exit=0）。Single Activity。符合。
- ARCH-11：data 层无 OrientationMode 引用（grep 于 data/ 包 exit=0 仅命中注释 2 行，非代码）；UserPreferencesRepository.kt:49,133 以 OrientationPreference enum.name 持久化。符合。
- ARCH-1：`grep -nE 'version\s*=?\s*"[0-9]' app/build.gradle.kts` exit=1 零命中（无内联钉扎）。符合。

## 发现条目

### F-1-1 [P2] [CONFIRMED] [ARCH-2/ARCH-3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:46
- 代码: `private val videoItemDao: VideoItemDao,`（同模式 47-48 行：PlaybackRecordDao、PendingSyncActionDao）
- 问题: ViewModel 直接注入并调用 3 个 Room DAO（66/95/106/143-145 行，含 deleteAll），绕过声明的 Repository 层（architecture.md:34-44），MVVM+Repository 分层被架空。
- 复现: grep -nE 'Dao\b' app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt （exit=0，命中 11 行）
- 建议: 收敛到独立 Repository（或 UseCase）暴露存储管理操作，ViewModel 仅依赖 Repository 抽象。

### F-1-2 [P2] [CONFIRMED] [ARCH-2]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/AboutScreen.kt:133
- 代码: `private fun LogEntryItem(log: SyncLogEntity) {`
- 问题: Composable 直接消费 Room entity 类型作参数，UI 层耦合数据层实现细节（与 entity 通货设计叠加后 UI↔Room 双向耦合，不利于替换数据源与预览测试）。
- 复现: grep -n 'SyncLogEntity' app/src/main/java/cn/vectory/syncplayer/settings/AboutScreen.kt （exit=0，命中 2 行）
- 建议: 在 ViewModel/Repository 边界映射为 UI 模型（如 LogEntry UI data class）。

### F-1-3 [P2] [CANDIDATE] [ARCH-2/ARCH-3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:5
- 代码: `import android.provider.DocumentsContract`（4 行 `import android.net.Uri`）
- 问题: ViewModel 层耦合 android 框架类（非 Context）：SyncRelationEditViewModel 的 Uri/DocumentsContract、PlayerViewModel.kt:3 ActivityInfo、BrowserViewModel.kt:4 与 SearchViewModel.kt:3 与 FileOperationsUseCase.kt:4 的 Intent，共 6 行。architecture.md 无显式禁令，但与"ViewModel 可脱离 Android 框架单测"的 MVVM 惯例相悖，SAF Uri 解析逻辑不可 JVM 单测。属分层债而非确定违约，故 CANDIDATE。
- 复现: grep -rnE '^import android\.' --include='*.kt' app/src/main/java/cn/vectory/syncplayer | grep -E '(ViewModel|UseCase)\.kt:' | grep -v 'android.content.Context' （exit=0，命中 15 行，其中 Log 9 行）
- 建议: Uri/DocumentId 解析下沉 Repository 或纯函数；Intent 构造移回 UI 层回调。
