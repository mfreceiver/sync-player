# Architecture & Tech Stack

> 本文档是 Sync Player 架构与选型的权威来源。`AGENTS.md` 仅引用本文，不重复完整信息。

## Tech Stack

| Category | Library | Version |
|---|---|---|
| Language | Kotlin | 2.2.10 |
| Build | Android Gradle Plugin | 9.1.0 |
| Annotation Proc | KSP | 2.3.6 |
| UI | Jetpack Compose + Material 3 | BOM 2025.12.00 |
| Architecture | MVI (Player) / MVVM + Repository, Hilt DI | Hilt 2.59.2 |
| Database | Room (schema **v2**, `exportSchema=true`) | 2.7.0 |
| Player | Media3 / ExoPlayer | 1.6.0 |
| SMB | jcifs-ng | 2.1.10 |
| SFTP | SSHJ (with custom TOFU Verifier) | 0.39.0 |
| Testing | JUnit 4, MockK, Coroutines Test, Turbine | 4.13.2 / 1.13.13 / 1.2.0 |
| Crypto | BouncyCastle (`bcprov-jdk18on`, MD4 for NTLM) | 1.80 |
| Security | androidx.security.crypto | 1.1.0-alpha06 |
| Biometric | androidx.biometric | 1.1.0 |
| Images | Coil Compose | 2.7.0 |
| EXIF | androidx.exifinterface | 1.4.1 |
| Async | Coroutines + Flow | 1.10.1 |
| Preferences | DataStore Preferences | 1.1.4 |
| Navigation / Lifecycle / Activity | Navigation 2.9.0 / Lifecycle 2.9.0 / Activity-Compose 1.10.0 / Core-KTX 1.16.0 / AppCompat 1.7.0 | - |

Versions are centralized in `gradle/libs.versions.toml` (single source of truth). Do not pin versions inline in `app/build.gradle.kts`.

## Architecture Patterns

### 分层

```
Compose UI (Screen composables)
  ↓ StateFlow / callbacks / Navigation
ViewModel (MVVM) / PlayerViewModel (MVI)
  ↓
Use case / service (FileOperationsUseCase, UndoDeleteUseCase, SyncService, SeekPreviewExtractor)
  ↓
Engine / Repository / Storage (SyncEngine, Repositories, EncryptedMediaStore, MediaVolume)
  ↓
Room DAO / Crypto primitives / RemoteFileClient (SMB/SFTP)
```

### 通用规则
- **General**: MVVM + Repository, Hilt DI。
- **Player**: **MVI pattern** with unified `PlayerUiState`。
- **Single Activity**: 所有 screen 是 `@Composable`，Navigation Compose 导航。

### State Splitting（高频状态隔离）
- 高频更新（如播放位置）**必须**放在独立 `StateFlow`，与主 `UiState` 分离，防止全屏重组。
- `PlayerViewModel.currentPosition`（200ms 轮询）独立于低频 `PlayerUiState`。

### Recomposition Isolation（重组隔离）
- 订阅高频状态的 Composable（如进度条）**必须**提取为独立函数，使轮询驱动的重组不传播到兄弟 UI（按钮、图标）。
- 低频状态（isPlaying, isFullscreen）**必须**以 primitive 参数下传，不在子 Composable 内用 StateFlow 订阅。
- 顶层 Screen 收集的 StateFlow 订阅不应扩散到子 Composable；用稳定参数/`derivedStateOf` 隔离。

## Key Directories

```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt              # Single Activity — Compose host, app lock overlay, navigation
├── SyncPlayerApp.kt             # Application — Hilt, AppLockManager, BouncyCastle, crypto-v2 purge
│
├── audio/AudioGainAnalyzer.kt   # RMS/peak dB analysis via MediaExtractor+MediaCodec
│
├── browser/                     # File browser
│   ├── BrowserScreen.kt            # Entry composable: state wiring, dialogs, onboarding card
│   ├── BrowserTopBar.kt            # Top bar (folder, search, sync, sort/filter)
│   ├── BrowserContent.kt           # View-mode dispatcher (list/grid/staggered) + items + scrollbar
│   ├── BrowserSortFilter.kt        # Sort/filter dropdown sections
│   ├── BrowseItemBuilder.kt        # Pure data builder: filter/sort/star/folder → List<BrowseItem>
│   ├── BrowserViewModel.kt         # Folder nav, sort/filter, view modes, firstRunState, undo
│   ├── FirstRunState.kt            # Onboarding state enum + pure deriveFirstRunState()
│   ├── FirstRunOnboardingCard.kt   # Material 3 first-run guidance card (NEEDS_SOURCE/RELATION/SYNC)
│   ├── UndoDeleteUseCase.kt        # Cross-page undo (trash toggle restore + deferred hard delete)
│   ├── SearchViewModel.kt / SearchScreen.kt
│   └── FileOperationsUseCase.kt    # Delete/trash media + thumbs, volume-ownership fail-closed;
│                                   #   toggleTrashedChecked (suspend, CancellationException-safe)
│
├── crypto/                      # At-rest media encryption — see crypto.md
│   ├── CryptoScheme.kt / ChunkedEncryptSink.kt / RangeDecryptor.kt
│   ├── DecryptingFdCallback.kt / MediaCryptoMaster.kt / LegacyCiphertextException.kt
│
├── data/                        # Data layer
│   ├── local/{AppDatabase.kt, dao/, entity/}
│   ├── model/   # BrowseItem, SyncProgress, SyncRelationWithSource, OrientationPreference, …
│   └── repository/  # Video/Playback/SyncSource/SyncRelation/SyncLog/UserPreferences
│
├── di/                          # Hilt DI modules
│   ├── DatabaseModule.kt           # Room, DAOs, MasterKey, EncryptedSharedPreferences
│   ├── SyncModule.kt               # SMB/SFTP client providers, EncryptedMediaStore
│   └── DispatchersModule.kt        # @IoDispatcher qualifier → Dispatchers.IO (testable)
│
├── imageviewer/                 # Image viewer (encrypted image playback)
│   ├── ImageViewerScreen.kt / ImageViewerViewModel.kt
│
├── navigation/
│   ├── AppNavHost.kt               # NavHost (fade transitions; player/image instant)
│   ├── Route.kt / NavigationHelper.kt / VideoQueue.kt / ImageQueue.kt
│
├── player/                      # Video player (MVI)
│   ├── PlayerScreen.kt             # ExoPlayer UI + gestures + Snackbar undo
│   ├── PlayerViewModel.kt          # MVI: PlayerUiState + currentPosition; hasPlayerError flag;
│   │                                #   OrientationMode enum (FULLSCREEN/ROTATION overlay state)
│   └── SeekPreviewExtractor.kt     # Seek frame extraction with LRU cache
│
├── provider/                    # ContentProvider — see storage-security.md
│   ├── MediaContentProvider.kt     # Non-leaking exception boundary; volumeId per-access
│   └── MediaUris.kt
│
├── security/                    # App lock & biometric — see storage-security.md
│   ├── AppLockManager.kt / BiometricAuthManager.kt / LockScreenOverlay.kt
│
├── settings/                    # Settings screens (sources, relations, metadata rebuild, gain, storage, about)
│
├── storage/                     # Path + volume management — see storage-security.md
│   ├── StoragePaths.kt
│   └── volume/{EncryptedMediaStore, SafVolume, FileVolume, MediaVolume, MediaVolumeProvider, MediaPaths}.kt
│
├── sync/                        # Sync engine — see sync.md
│   ├── SyncEngine.kt              # Orchestrator (dryRun/executeSync/executeDelete/consumeAndExecute)
│   ├── SyncProgressPublisher.kt   # Progress StateFlow + throttle + active downloads map
│   ├── SyncSpaceChecker.kt        # cipherLengthFor precheck + overflow/unknown-space handling
│   ├── SyncService.kt             # Foreground service (dataSync type)
│   ├── SyncException.kt / RemoteScanIncompleteException.kt / ErrorMessageMapper.kt
│   ├── RemoteFileClient.kt / smb/SmbFileClient.kt / sftp/SftpFileClient.kt
│   ├── sftp/SyncPlayerHostKeyVerifier.kt
│   ├── VideoMetadataExtractor.kt / ImageMetadataExtractor.kt
│   ├── SyncNotificationHelper.kt / SyncPreviewScreen.kt / SyncPreviewViewModel.kt / NetworkUtils.kt
│
└── ui/                          # Shared UI components & theme
    ├── components/{VideoGridItem,FolderGridItem,FolderIcon,Thumbnail,FileStatusIcon,
    │              ProgressRing,StarRatingInput,DirectoryTreeDialog,VerticalScrollbar,
    │              ItemContextMenu,ErrorDisplays}.kt   # VerticalScrollbar: list/grid/staggered variants
    ├── theme/{Theme.kt,Color.kt}     # Material 3, dynamic color, extended colors
    └── util/{FormatUtils.kt,VideoUtils.kt}

app/schemas/cn.vectory.syncplayer.data.local.AppDatabase/2.json   # Exported Room schema
app/src/main/res/values/strings.xml                                # All user-facing Chinese text
```

## OrientationPreference vs OrientationMode

两个独立概念，不要混淆：

- **`data.model.OrientationPreference`**（FOLLOW_VIDEO / FOLLOW_SYSTEM）：**持久化**的播放器方向偏好，存于 DataStore（typed enum 读写，存储值仍为 enum.name）。进程重启保持。
- **`player.OrientationMode`**（FULLSCREEN / ROTATION）：播放器 overlay 的**瞬时**切换状态指示，仅用于 UI 显示当前方向模式。不持久化。
