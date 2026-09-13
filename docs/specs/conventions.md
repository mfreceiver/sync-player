# Conventions: Testing, i18n, Database, Performance, Versioning

> 本文档是 Sync Player 工程约定的权威来源。`AGENTS.md` 仅引用本文。

## Testing

- 单测位于 `app/src/test`；androidTest（真实 in-memory Room）位于 `app/src/androidTest`。
- 框架：JUnit 4 + MockK + Coroutines Test + Turbine。
- **断言枚举、集合大小、MockK 调用次数——绝不断言用户可见文案**（strings 进 `strings.xml`，文案断言在 i18n 变更时脆裂）。
- 高覆盖目标：`SyncEngine`、`MediaFileStore`、`ErrorMessageMapper`、`BrowseItemBuilder`、`UndoDeleteUseCase`、`FileOperationsUseCase`。
- suspend mock 用 `coEvery`/`coVerify`（非 `every`）。
- 取消语义测试：`CancellationException` 必须原样传播，不得被 `catch(Exception)` 吞掉转成失败结果。
- 重试用例的真实退避（`delay(1000*attempts)` on `Dispatchers.IO`）无法被虚拟时间控制，是已知可接受成本。

## i18n / Strings

- **所有**用户可见中文文本在 `app/src/main/res/values/strings.xml`（单一来源）。
- 解析方式：`stringResource`（Composable）、`context.getString`（Notification）、`syncErrorMessage(e, context)`（ViewModel）。
- **内部异常/日志诊断文本不是本地化目标**：sync/data-layer 异常携带 typed `SyncErrorCode`（非显示文本）；`Log.w/d/i` 中文诊断保持硬编码（开发者面向，debuggability > i18n）。
- Repository 层不依赖 Android Context 生成默认文案；返回 null/typed missing state，由 UI mapper 层用 `stringResource` 处理。

## Database

- Room，schema **v2**，`exportSchema=true`（schema 导出到 `app/schemas/`）。KSP arg `room.schemaLocation=$projectDir/schemas`。
- **破坏性迁移**：`fallbackToDestructiveMigration(dropAllTables=true)` —— v1 clean-start app；破坏性 schema 变更重建 DB（个人使用，用户重装/重同步）。不要无深思熟虑就加非破坏性迁移。
- **优化**：用 `parentPath` 索引列做高速文件夹浏览，而非 `LIKE` 子串。
- 6 实体：VideoItemEntity（15 列，parentPath/syncRelationId/relativePath+syncRelationId unique 索引）、PlaybackRecordEntity（FK CASCADE, videoItemId unique）、SyncSourceEntity、SyncRelationEntity（FK RESTRICT）、SyncLogEntity、PendingSyncActionEntity。

## Performance

- **Debounce**：重型 UI 合并 Flow（如文件浏览）≥300ms debounce。
- **I/O Safety**：所有阻塞 I/O（Retriever、文件操作、Room 查询）**必须**在 `Dispatchers.IO`。通过 `@IoDispatcher`（`DispatchersModule`）注入可测试。
- **Recomposition**：高频状态独立 StateFlow + 独立 Composable；低频状态 primitive 下传。
- **进度节流**：同步进度 ~500ms 节流，避免通知/UI 高频更新。

## Versioning（git 派生）

- `versionCode` = `git rev-list --count HEAD`
- `versionName` = `git describe`
- 见 `app/build.gradle.kts` 的 `gitVersionCode` / `gitVersionName`。
- 发版用 `./scripts/release.sh <x.y.z>`（打 tag + push → `release.yml`）。
- **不要手改** versionCode/versionName。

## Player UX 约定

- Media3 ExoPlayer，保留手势控制：全画面滑动 seek/音量/亮度、长按 2x、双击四区动作。
- 视觉方向为“聚焦极光”：黑色视频画布、上下 10% 极光带、`#64B5F6 → #9575CD` 激活渐变；播放器专用 token 不跟随动态主题色。
- Top bar 仅含退出和文件名；不放回收按钮。
- 底部 toolbar 为设置、快退 10 秒、播放暂停、快进 10 秒、旋转五枚圆钮；播放暂停居中且为 64dp，其他按钮为 48dp；controls 可见时即显示（FULLSCREEN 态同样适用，不再隐藏）。
- 速度（1x/1.5x/2x）在底部设置胶囊中选择；长按屏幕仍临时 2x，跨视频保持，退出重置。
- 方向切换位于底部设置胶囊：ROTATION（跟随系统方向）↔ FULLSCREEN（匹配视频方向）；跨视频保持，退出重置。
- 回收/恢复入口位于底部设置胶囊的末行；原有屏幕中下双击快捷回收和成功后的撤销 Snackbar 保持不变。
- 进度区的可见轨道为 4dp，但触摸区域至少 44dp；向下拖动按 1x、1/4、1/10 降低定位速度，精细定位显示缩略图、±30 秒标尺和当前增量。粗预览（手势/进度条 seek）水平跟随进度条滑块，并在视口安全边缘内约束（不遮出界；RTL 为已知残余风险，按 LTR 绝对坐标计算）。
- Overlays 仍是 icon+text pill（音量、亮度、速度、回收 IN/OUT、上一个/下一个、方向），改为渐变描边 Aurora 样式。
- 播放器内系统状态栏始终隐藏。`progressAlwaysVisible`：无 seek 且进度区未激活时显示 1dp 渐变进度线，任一 seek 进行时由 44dp 活动进度区替代（两种模式一致）。FULLSCREEN：顶行（退出+文件名）恒显；入场默认隐藏完整 controls（原子置 isFullscreen=true+showControls=false），单击画面切换完整底部控制区，controls 不自动隐藏。非 FULLSCREEN 态 controls 可见时显示完整进度区和圆钮，controls 隐藏时保留 1dp 进度线。

## Error Handling（错误处理一致性）

- `SyncException.code` 模式（18 SyncErrorCode + SyncOperation）：结构化、可测试、无文本泄漏。
- `ErrorMessageMapper`：`classifyRemoteError`（code）→ `syncErrorMessage`（R.string）——无文本散布。
- **播放器错误**：`PlayerUiState.hasPlayerError: Boolean`（不携带 `PlaybackException.message`）；UI 用固定 `R.string.player_error_generic`；日志保留底层异常供诊断。
- **Provider 错误**：统一安全 `FileNotFoundException`（资源文案），不泄漏 URI/id/path。

## Known Residual Risks（accepted, personal-use）

1. **Generic ViewModel catches show `sync_error_unknown`** for non-sync exceptions (by design — never leak `e.message`). Diagnostics via Log.
2. **`EncryptedMediaStore` `MediaExistence.IO_ERROR.reason`** reads `.message`（存储层——consumer 用 resource prefix 包装）。`PlayerScreen` 此前读 `.message` 已修复为 `hasPlayerError` 布尔标志。
3. **`security-crypto 1.1.0-alpha06`**：生产 app 用 alpha 依赖。功能正常，留意 API 破坏回归。
4. **`SyncEngineBoundaryTest` 重试用例真实退避耗时**（~1-3s），因 `Dispatchers.IO` 上 `delay` 无法被虚拟时间控制。
5. **`androidTest` DAO 测试未在真机执行**（编译验证通过，与 `SafVolumeTest` 同构）。

其余风险见 [sync.md](sync.md)（listDirectories/isNotFound/classifySmb/two-phase-commit）。
