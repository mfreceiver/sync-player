# Phase 0：基线勘察
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。

## 0.1 快照锚点（Phase 0 实测）

| 项 | 值 | 命令 | exit |
|---|---|---|---|
| git HEAD | `116dd4de17f99833d9e2490b15e9ff4af1270de7` | `git rev-parse HEAD` | 0 |
| git status --short | `?? docs/agent-code-audit-prompt.md` | `git status --short` | 0 |
| 采样哈希字节 | `e7`（十进制 231，铁律 8 用） | `git rev-parse HEAD \| grep -oE '[0-9a-fA-F]{2}$'` | 0 |

status 仅有 `?? docs/agent-code-audit-prompt.md`，为任务书允许项（reports/ 建立后另行出现）。

## 0.2 规模对账（B.1）

| 对象 | 实测文件数 | 实测行数 | B.1 锚点 | 偏差 |
|---|---|---|---|---|
| app/src/main（.kt） | 129 | 21416 | 129 / 21416 | 无 |
| app/src/test（.kt） | 28 | 6641 | 28 / 6641 | 无 |
| app/src/androidTest（.kt） | 3 | 647 | 3 / 647 | 无 |
| crypto/ 包 | 6 | 626 | 6 / 626 | 无 |
| sync/ 包 | 18 | 3716 | 18 / 3716 | 无 |
| SyncEngine.kt | 1 | 1025 | 1 / 1025 | 无 |
| FileOperationsUseCase.kt | 1 | 610 | 1 / 610 | 无 |
| MediaContentProvider.kt | 1 | 212 | 1 / 212 | 无 |

对账命令（均 exit=0）：
- `find app/src/main -name '*.kt' | wc -l` → 129
- `find app/src/main -name '*.kt' -exec cat {} + | wc -l` → 21416
- test/androidTest/crypto/sync 同法。

实测与锚点全部一致，无工具误用信号。

## 0.3 重点审阅对象（>400 行，共 10 个文件，后续 Phase 必查输入）

| 文件 | 行数 |
|---|---|
| app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt | 1140 |
| app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt | 1025 |
| app/src/main/java/cn/vectory/syncplayer/browser/BrowserContent.kt | 644 |
| app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt | 636 |
| app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt | 617 |
| app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt | 610 |
| app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt | 515 |
| app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt | 489 |
| app/src/main/java/cn/vectory/syncplayer/browser/SearchScreen.kt | 455 |
| app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisScreen.kt | 418 |

（>300 行 ViewModel 一并预标记，供 Phase 1.3 State Splitting 使用：BrowserViewModel.kt 617、PlayerViewModel.kt 371、SyncPreviewViewModel.kt 231——231<300 不入；实际 >300 的 ViewModel 为 BrowserViewModel.kt、PlayerViewModel.kt。）

## 0.4 包结构树

`find app/src/main/java -type d | sort`（exit=0），自 `cn/vectory/syncplayer` 起：

```
syncplayer/
  audio/  browser/  crypto/  debug/  di/  imageviewer/  navigation/
  player/  provider/  security/  settings/
  data/local/dao/  data/local/entity/  data/model/  data/repository/
  storage/volume/
  sync/sftp/  sync/smb/
  ui/components/  ui/theme/  ui/util/
```

注意（与任务书 B 提示一致）：Compose 界面分布在 `browser/ settings/ player/ imageviewer/ navigation/ ui/` 等多包，凡"UI 侧"检查须覆盖全包树。

## 0.5 主源码文件全清单（129 文件，`find app/src/main -name '*.kt' -exec wc -l {} \; | sort -rn`，exit=0）

| 行数 | 文件 |
|---|---|
| 1140 | app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt |
| 1025 | app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt |
| 644 | app/src/main/java/cn/vectory/syncplayer/browser/BrowserContent.kt |
| 636 | app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt |
| 617 | app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt |
| 610 | app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt |
| 515 | app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt |
| 489 | app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt |
| 455 | app/src/main/java/cn/vectory/syncplayer/browser/SearchScreen.kt |
| 418 | app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisScreen.kt |
| 393 | app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt |
| 376 | app/src/main/java/cn/vectory/syncplayer/browser/BrowserSortFilter.kt |
| 371 | app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt |
| 354 | app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt |
| 352 | app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt |
| 346 | app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt |
| 341 | app/src/main/java/cn/vectory/syncplayer/ui/components/VerticalScrollbar.kt |
| 326 | app/src/main/java/cn/vectory/syncplayer/browser/BrowseItemBuilder.kt |
| 314 | app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt |
| 300 | app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt |
| 300 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt |
| 299 | app/src/main/java/cn/vectory/syncplayer/ui/components/VideoGridItem.kt |
| 297 | app/src/main/java/cn/vectory/syncplayer/navigation/AppNavHost.kt |
| 288 | app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt |
| 277 | app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildScreen.kt |
| 274 | app/src/main/java/cn/vectory/syncplayer/crypto/CryptoScheme.kt |
| 272 | app/src/main/java/cn/vectory/syncplayer/ui/components/DirectoryTreeDialog.kt |
| 255 | app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt |
| 241 | app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt |
| 231 | app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt |
| 231 | app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt |
| 229 | app/src/main/java/cn/vectory/syncplayer/settings/SettingsScreen.kt |
| 225 | app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt |
| 220 | app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementScreen.kt |
| 220 | app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt |
| 213 | app/src/main/java/cn/vectory/syncplayer/ui/util/FormatUtils.kt |
| 212 | app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt |
| 207 | app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt |
| 193 | app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt |
| 190 | app/src/main/java/cn/vectory/syncplayer/settings/DebugLogScreen.kt |
| 190 | app/src/main/java/cn/vectory/syncplayer/settings/AboutScreen.kt |
| 190 | app/src/main/java/cn/vectory/syncplayer/data/repository/UserPreferencesRepository.kt |
| 187 | app/src/main/java/cn/vectory/syncplayer/player/GesturePlayerView.kt |
| 183 | app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt |
| 182 | app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt |
| 176 | app/src/main/java/cn/vectory/syncplayer/sync/SyncNotificationHelper.kt |
| 176 | app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt |
| 167 | app/src/main/java/cn/vectory/syncplayer/ui/theme/Theme.kt |
| 164 | app/src/main/java/cn/vectory/syncplayer/browser/FirstRunOnboardingCard.kt |
| 156 | app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt |
| 155 | app/src/main/java/cn/vectory/syncplayer/ui/components/FolderGridItem.kt |
| 152 | app/src/main/java/cn/vectory/syncplayer/audio/AudioGainAnalyzer.kt |
| 150 | app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt |
| 146 | app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt |
| 133 | app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt |
| 132 | app/src/main/java/cn/vectory/syncplayer/crypto/ChunkedEncryptSink.kt |
| 127 | app/src/main/java/cn/vectory/syncplayer/ui/components/ErrorDisplays.kt |
| 127 | app/src/main/java/cn/vectory/syncplayer/security/LockScreenOverlay.kt |
| 122 | app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt |
| 120 | app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationListScreen.kt |
| 117 | app/src/main/java/cn/vectory/syncplayer/sync/SyncSpaceChecker.kt |
| 116 | app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt |
| 114 | app/src/main/java/cn/vectory/syncplayer/browser/BrowserTopBar.kt |
| 112 | app/src/main/java/cn/vectory/syncplayer/data/repository/SyncRelationRepository.kt |
| 110 | app/src/main/java/cn/vectory/syncplayer/crypto/RangeDecryptor.kt |
| 107 | app/src/main/java/cn/vectory/syncplayer/data/repository/PlaybackRepository.kt |
| 104 | app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt |
| 101 | app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt |
| 100 | app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt |
| 96 | app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceListScreen.kt |
| 94 | app/src/main/java/cn/vectory/syncplayer/MainActivity.kt |
| 88 | app/src/main/java/cn/vectory/syncplayer/security/BiometricAuthManager.kt |
| 85 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/PlaybackRecordDao.kt |
| 84 | app/src/main/java/cn/vectory/syncplayer/sync/VideoMetadataExtractor.kt |
| 84 | app/src/main/java/cn/vectory/syncplayer/di/DatabaseModule.kt |
| 77 | app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt |
| 77 | app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt |
| 76 | app/src/main/java/cn/vectory/syncplayer/ui/components/StarRatingInput.kt |
| 76 | app/src/main/java/cn/vectory/syncplayer/ui/components/FileStatusIcon.kt |
| 72 | app/src/main/java/cn/vectory/syncplayer/crypto/MediaCryptoMaster.kt |
| 71 | app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt |
| 65 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncProgress.kt |
| 64 | app/src/main/java/cn/vectory/syncplayer/ui/components/Thumbnail.kt |
| 57 | app/src/main/java/cn/vectory/syncplayer/di/SyncModule.kt |
| 55 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt |
| 54 | app/src/main/java/cn/vectory/syncplayer/ui/components/ProgressRing.kt |
| 54 | app/src/main/java/cn/vectory/syncplayer/provider/MediaUris.kt |
| 52 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt |
| 50 | app/src/main/java/cn/vectory/syncplayer/sync/SyncException.kt |
| 49 | app/src/main/java/cn/vectory/syncplayer/ui/util/VideoUtils.kt |
| 48 | app/src/main/java/cn/vectory/syncplayer/navigation/Route.kt |
| 46 | app/src/main/java/cn/vectory/syncplayer/ui/components/FolderIcon.kt |
| 41 | app/src/main/java/cn/vectory/syncplayer/ui/theme/Color.kt |
| 40 | app/src/main/java/cn/vectory/syncplayer/browser/FirstRunState.kt |
| 38 | app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt |
| 37 | app/src/main/java/cn/vectory/syncplayer/data/model/BrowseItem.kt |
| 37 | app/src/main/java/cn/vectory/syncplayer/data/local/AppDatabase.kt |
| 36 | app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClient.kt |
| 36 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/VideoItemEntity.kt |
| 32 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncRelationEntity.kt |
| 30 | app/src/main/java/cn/vectory/syncplayer/data/repository/SyncLogRepository.kt |
| 29 | app/src/main/java/cn/vectory/syncplayer/sync/RemoteScanIncompleteException.kt |
| 29 | app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolumeProvider.kt |
| 28 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/PlaybackRecordEntity.kt |
| 28 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncSourceDao.kt |
| 26 | app/src/main/java/cn/vectory/syncplayer/di/DispatchersModule.kt |
| 26 | app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncLogDao.kt |
| 23 | app/src/main/java/cn/vectory/syncplayer/crypto/DecryptingFdCallback.kt |
| 22 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/PendingSyncActionEntity.kt |
| 21 | app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolume.kt |
| 21 | app/src/main/java/cn/vectory/syncplayer/navigation/VideoQueue.kt |
| 21 | app/src/main/java/cn/vectory/syncplayer/navigation/ImageQueue.kt |
| 21 | app/src/main/java/cn/vectory/syncplayer/data/model/OrientationPreference.kt |
| 19 | app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClientFactory.kt |
| 19 | app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceListViewModel.kt |
| 18 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncAction.kt |
| 17 | app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationListViewModel.kt |
| 17 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncLogEntity.kt |
| 16 | app/src/main/java/cn/vectory/syncplayer/data/local/entity/SyncSourceEntity.kt |
| 15 | app/src/main/java/cn/vectory/syncplayer/crypto/LegacyCiphertextException.kt |
| 14 | app/src/main/java/cn/vectory/syncplayer/sync/NetworkUtils.kt |
| 12 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncSource.kt |
| 12 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncRelation.kt |
| 12 | app/src/main/java/cn/vectory/syncplayer/data/model/RemoteFile.kt |
| 11 | app/src/main/java/cn/vectory/syncplayer/navigation/NavigationHelper.kt |
| 8 | app/src/main/java/cn/vectory/syncplayer/data/model/FolderStats.kt |
| 7 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncRelationWithSource.kt |
| 6 | app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaPaths.kt |
| 5 | app/src/main/java/cn/vectory/syncplayer/data/model/SyncProtocol.kt |

## 0.6 锚点扫描对账

| 命令 | scan_total | adjudicated | excluded_scope_or_FP | unknown |
|---|---|---|---|---|
| `find app/src/main -name '*.kt' \|| wc -l` | 129 | 129（全部入清单） | 0 | 0 |
| `find app/src/test -name '*.kt' \|| wc -l` | 28 | 28 | 0 | 0 |
| `find app/src/androidTest -name '*.kt' \|| wc -l` | 3 | 3 | 0 | 0 |

（Phase 0 无发现条目，无 U- 条目。）
