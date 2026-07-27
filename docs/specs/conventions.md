# Conventions: Testing, i18n, Database, Performance, Versioning

> 本文档是 Sync Player 工程约定的权威来源。`AGENTS.md` 仅引用本文。

## Testing

- 单测位于 `app/src/main/test`；androidTest（真实 in-memory Room）位于 `app/src/androidTest`。
- 框架：JUnit 4 + MockK + Coroutines Test + Turbine。
- **断言枚举、集合大小、MockK 调用次数——绝不断言用户可见文案**（strings 进 `strings.xml`，文案断言在 i18n 变更时脆裂）。
- 高覆盖目标：`SyncEngine`、`CryptoScheme`、`RangeDecryptor`、`ErrorMessageMapper`、`BrowseItemBuilder`、`UndoDeleteUseCase`、`FileOperationsUseCase`。
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
- 6 实体：VideoItemEntity（17 列，parentPath/volumeId/syncRelationId/relativePath+syncRelationId unique 索引）、PlaybackRecordEntity（FK CASCADE, videoItemId unique）、SyncSourceEntity、SyncRelationEntity（FK RESTRICT）、SyncLogEntity、PendingSyncActionEntity。

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

- Media3 ExoPlayer，手势控制（滑动 seek/音量/亮度、长按加速、双击动作）。
- Top bar：文件名行（退出/回收）+ 无缩略图进度条内联时间。
- Bottom toolbar：速度/快退/播放暂停/快进/旋转（仅非沉浸）。
- Overlays：iOS 风格 icon+text pill（音量、亮度、速度、回收 IN/OUT、上一个/下一个、方向）。
- 方向切换：ROTATION（系统方向）↔ FULLSCREEN（视频匹配方向），跨视频保持，退出重置。
- 播放速度（1x/1.5x/2x）：跨视频保持，退出重置。
- 播放器内系统状态栏始终隐藏。
- `progressAlwaysVisible`：沉浸模式显示顶栏但不显示底栏。

## Error Handling（错误处理一致性）

- `SyncException.code` 模式（18 SyncErrorCode + SyncOperation）：结构化、可测试、无文本泄漏。
- `ErrorMessageMapper`：`classifyRemoteError`（code）→ `syncErrorMessage`（R.string）——无文本散布。
- **播放器错误**：`PlayerUiState.hasPlayerError: Boolean`（不携带 `PlaybackException.message`）；UI 用固定 `R.string.player_error_generic`；日志保留底层异常供诊断。
- **Provider 错误**：统一安全 `FileNotFoundException`（资源文案），不泄漏 URI/id/path/volumeId。

## Known Residual Risks（accepted, personal-use）

1. **Generic ViewModel catches show `sync_error_unknown`** for non-sync exceptions (by design — never leak `e.message`). Diagnostics via Log.
2. **`EncryptedMediaStore` `MediaExistence.IO_ERROR.reason`** reads `.message`（存储层——consumer 用 resource prefix 包装）。`PlayerScreen` 此前读 `.message` 已修复为 `hasPlayerError` 布尔标志。
3. **`security-crypto 1.1.0-alpha06`**：生产 app 用 alpha 依赖。功能正常，留意 API 破坏回归。
4. **`SyncEngineBoundaryTest` 重试用例真实退避耗时**（~1-3s），因 `Dispatchers.IO` 上 `delay` 无法被虚拟时间控制。
5. **`androidTest` DAO 测试未在真机执行**（编译验证通过，与 `SafVolumeTest` 同构）。

其余风险见 [crypto.md](crypto.md)（iv 8 字节）与 [sync.md](sync.md)（listDirectories/isNotFound/classifySmb/two-phase-commit）。
