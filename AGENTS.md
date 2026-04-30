# Sync Player — AGENTS.md

## Environment

- **JAVA_HOME**: `/home/mar/android-studio/jbr` (Android Studio bundled JBR, OpenJDK 21)
- **ANDROID_HOME**: `/home/mar/android-sdk` (build-tools 35.0.x, platform android-35)
- **Android Studio**: `/home/mar/android-studio`
- **Project root**: `/home/mar/sync-player`
- **Gradle**: 8.11.1
- **No Kotlin LSP installed** — use Gradle build for type/syntax verification

All Gradle commands require `JAVA_HOME=/home/mar/android-studio/jbr`. ANDROID_HOME is optional if already in env.

## Build Commands

```bash
# Fast compile check (~30s, no APK)
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin

# Debug APK
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug --no-daemon
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (signed, R8 minified, ~12MB, ~1.5min)
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk
```

Always run a build after code changes to verify compilation — there is no LSP fallback.

## Project

**Package**: `cn.vectory.syncplayer`
**Repo**: `https://git.vectory.cn:18443/mfreceiver/sync-player.git` (branch: `master`)
**Target**: Android 14+ (minSdk 34, targetSdk 35), personal use, sideloaded
**Current version**: see `versionName` in `app/build.gradle.kts`

### Tech Stack

| Category | Library | Version |
|---|---|---|
| Language | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2025.02.00 |
| Architecture | MVI (Player) / MVVM + Repository, Hilt DI | Hilt 2.54 |
| Database | Room | 2.7.0 (schema v14) |
| Player | Media3 / ExoPlayer | 1.6.0 |
| SMB | jcifs-ng | 2.1.10 |
| SFTP | SSHJ (with custom TOFU Verifier) | 0.39.0 |
| Testing | JUnit 4, MockK, Coroutines Test, Turbine | - |
| Crypto | BouncyCastle (MD4 for NTLM) | 1.80 |
| Security | androidx.security.crypto | 1.1.0-alpha06 |
| Biometric | androidx.biometric | 1.1.0 |
| Images | Coil Compose | 2.7.0 |
| Async | Coroutines + Flow | 1.10.1 |
| Preferences | DataStore Preferences | 1.1.4 |
| KSP | Kotlin Symbol Processing | 2.1.0-1.0.29 |

### Key Directories

```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt              # Single Activity — Compose host, app lock overlay, navigation
├── SyncPlayerApp.kt             # Application — Hilt, AppLockManager registration, BouncyCastle provider
│
├── audio/                       # Audio analysis
│   └── AudioGainAnalyzer.kt        # RMS/peak dB analysis via MediaExtractor+MediaCodec
│
├── browser/                     # File browser
│   ├── BrowserViewModel.kt         # Folder navigation, sort/filter, grid/list modes, thumbnails
│   ├── BrowserScreen.kt            # Main browse UI — grid/list, directory tree, item actions
│   ├── SearchViewModel.kt          # Search state across videos and folders
│   └── SearchScreen.kt             # Search results UI
│
├── data/                        # Data layer
│   ├── local/
│   │   ├── AppDatabase.kt          # Room DB (6 entities, schema v14, migrations 9→14)
│   │   ├── dao/                    # VideoItemDao, PlaybackRecordDao, SyncSourceDao,
│   │   │                           # SyncRelationDao, SyncLogDao, PendingSyncActionDao
│   │   └── entity/                 # VideoItemEntity, PlaybackRecordEntity, SyncSourceEntity,
│   │                                # SyncRelationEntity, SyncLogEntity, PendingSyncActionEntity
│   ├── model/                      # Domain models: BrowseItem, RemoteFile, SyncAction,
│   │                                # SyncProgress, SyncProtocol, SyncSource, SyncRelation,
│   │                                # SyncRelationWithSource, OrientationPreference
│   └── repository/                 # VideoRepository, PlaybackRepository, SyncSourceRepository,
│                                    # SyncRelationRepository, SyncLogRepository, UserPreferencesRepository
│
├── di/                          # Hilt DI modules
│   ├── DatabaseModule.kt           # Room DB, all DAOs, MasterKey, EncryptedSharedPreferences, migrations
│   └── SyncModule.kt               # SMB/SFTP client providers
│
├── navigation/                  # Navigation
│   ├── AppNavHost.kt               # Central NavHost wiring all routes/screens
│   ├── Route.kt                    # Route sealed class
│   ├── NavigationHelper.kt         # Hilt-injected navigation helper
│   └── VideoQueue.kt               # Shared video queue for player navigation
│
├── player/                      # Video player
│   ├── GesturePlayerView.kt        # ExoPlayer view with gesture controls (swipe seek/volume/brightness,
│   │                                # long-press speed, double-tap actions)
│   ├── PlayerViewModel.kt          # MVI state: unified PlayerUiState + separate currentPosition StateFlow
│   ├── PlayerScreen.kt             # Player UI — top bar, bottom toolbar, overlays
│   │                                #   PlaybackControlRow: play/pause + settings (low-frequency recompose)
│   │                                #   SeekProgressBarWithTime: progress bar + time (200ms polling, isolated)
│   └── SeekPreviewExtractor.kt     # Seek frame extraction with LRU cache, runs on Dispatchers.IO
│
├── provider/                    # SAF content provider
│   └── SyncDocumentsProvider.kt    # DocumentsProvider exposing synced videos to third-party apps
│
├── security/                    # App lock & biometric auth
│   ├── AppLockManager.kt           # Process lifecycle observer — auto-lock on background/foreground
│   ├── BiometricAuthManager.kt     # BiometricPrompt facade for unlock
│   └── LockScreenOverlay.kt        # Full-screen lock UI with retry button
│
├── settings/                    # Settings screens
│   ├── SettingsScreen.kt           # Main settings UI
│   ├── SettingsViewModel.kt        # Settings state (speed, volume, double-tap, SAF, secure lock)
│   ├── SyncSourceListScreen.kt     # Sync data source list
│   ├── SyncSourceEditScreen.kt     # Create/edit SMB/SFTP source + connection test
│   ├── SyncSourceEditViewModel.kt
│   ├── SyncRelationListScreen.kt   # Sync relation list
│   ├── SyncRelationEditScreen.kt   # Create/edit sync relation (source + path mapping)
│   ├── SyncRelationEditViewModel.kt
│   ├── MetadataRebuildScreen.kt    # Metadata rebuild UI
│   ├── MetadataRebuildViewModel.kt
│   ├── GainAnalysisScreen.kt       # Audio gain analysis per sync relation
│   ├── GainAnalysisViewModel.kt
│   └── AboutScreen.kt              # About page
│
├── storage/                     # Path management
│   └── StoragePaths.kt             # Centralized paths for user data, thumbnails, debug logs
│
├── sync/                        # Sync engine
│   ├── SyncService.kt              # Foreground service (dataSync type), orchestrates sync tasks
│   ├── SyncEngine.kt               # Core engine — mirror sync, download/delete, progress tracking
│   ├── RemoteFileClient.kt         # Remote file client abstraction
│   ├── smb/SmbFileClient.kt        # SMB implementation (jcifs-ng)
│   ├── sftp/SftpFileClient.kt      # SFTP implementation (SSHJ)
│   ├── sftp/SyncPlayerHostKeyVerifier.kt  # TOFU host key verifier (known_hosts.properties)
│   ├── VideoMetadataExtractor.kt   # Duration/width/height/bitrate extraction + thumbnail generation
│   ├── SyncNotificationHelper.kt   # Foreground notification with progress updates
│   ├── SyncPreviewScreen.kt        # Dry-run preview UI
│   ├── SyncPreviewViewModel.kt     # Preview state management
│   ├── NetworkUtils.kt             # Network availability check
│   └── ErrorMessageMapper.kt       # User-friendly error message mapping
│
└── ui/                          # Shared UI components & theme
    ├── components/
    │   ├── VideoGridItem.kt        # Grid tile for video entries
    │   ├── FolderGridItem.kt       # Grid tile for folder entries
    │   ├── FolderIcon.kt           # Folder icon with sync-base indicator
    │   ├── Thumbnail.kt            # Thumbnail rendering (Coil + video frames)
    │   ├── FileStatusIcon.kt       # Playback status overlay (completed/trashed)
    │   ├── ProgressRing.kt         # Ring progress indicator
    │   ├── StarRatingInput.kt      # Star rating widget
    │   ├── DirectoryTreeDialog.kt  # Directory tree picker dialog
    │   └── VerticalScrollbar.kt    # Custom scrollbar for LazyColumn
    ├── theme/
    │   ├── Theme.kt                # Material 3 theme, dynamic color, extended colors
    │   └── Color.kt                # Color palette definitions
    └── util/
        ├── FormatUtils.kt          # Duration/file-size formatters, sorting helpers
        └── VideoUtils.kt           # Video-related UI utilities
```

## Conventions

- **Architecture**: 
  - General: MVVM + Repository, Hilt DI.
  - Player: **MVI pattern** with unified `PlayerUiState`.
  - **State Splitting**: High-frequency updates (e.g., playback position) MUST be kept in a separate `StateFlow` from the main `UiState` to prevent full-screen recomposition.
  - **Recomposition Isolation**: Composables that subscribe to high-frequency state (e.g., progress bar) MUST be extracted into independent functions so that polling-driven recomposition does not propagate to sibling UI (buttons, icons). Low-frequency state (isPlaying, isFullscreen) MUST be passed as primitive parameters, not via StateFlow subscription inside child composables.
- **Testing**:
  - Unit tests located in `app/src/test`.
  - Framework: JUnit 4 + MockK + Coroutines Test + Turbine (for Flow verification).
  - Aim for high coverage on core logic like `SyncEngine` and `VideoUtils`.
- **Single Activity**: all screens are `@Composable`, navigated via Navigation Compose
- **Player**: Media3 ExoPlayer, gesture controls (swipe seek/volume/brightness, long-press speed, double-tap actions)
  - Top bar: filename row (exit/trash) + thumbless progress bar with inline time
  - Bottom toolbar: speed/rewind/play-pause/forward/rotation (non-immersive only)
  - Overlays: iOS-style icon+text pills (volume, brightness, speed, trash IN/OUT, previous/next, orientation)
  - Orientation toggle: ROTATION (system direction) ↔ FULLSCREEN (video-matched direction), persists across videos, resets on exit
  - Playback speed (1x/1.5x/2x): persists across videos, resets on exit
  - System status bar always hidden in player
  - `progressAlwaysVisible`: shows top bar in immersive mode without bottom toolbar
- **Sync**: mirror sync (remote → local), multi-source (SyncSource + SyncRelation), dry-run first, foreground service (`dataSync` type)
- **Security**: 
  - App lock via biometric (BIOMETRIC_WEAK), auto-locks on background transition.
  - SSH: **TOFU (Trust On First Use)** pattern for host key verification.
  - Credentials: EncryptedSharedPreferences.
- **SAF**: optional DocumentsProvider (`SyncDocumentsProvider`) to expose synced files to other apps
- **Audio**: gain analysis (RMS/peak dB) per sync relation, used for volume normalization hints
- **Database**: 
  - Room with schema migrations, version tracked in `DatabaseModule.kt`, schemas exported to `app/schemas/`.
  - **Optimization**: Use `parentPath` indexed column for high-speed folder browsing instead of `LIKE` substrings.
- **Performance**:
  - **Debounce**: Use at least 300ms debounce for heavy UI merging flows (e.g., file browsing).
  - **I/O Safety**: All blocking I/O (Retriever, File operations) MUST run in `Dispatchers.IO`.
- **Versioning**: bump versionCode + versionName in `app/build.gradle.kts` after every commit+push

## Release & Upload

Gitea API 用于创建 Release 并上传 APK。凭证存储在 `~/.git-credentials`（`credential.helper = store`）。

```bash
# 1. 提取 API token
TOKEN=$(cat ~/.git-credentials | grep 'git.vectory.cn' | sed 's|https://[^:]*:\([^@]*\)@.*|\1|')

# 2. 构建 Release APK
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk

# 3. 创建 Release（VERSION 替换为实际版本号，如 v10.9.5）
curl -s -X POST "https://git.vectory.cn:18443/api/v1/repos/mfreceiver/sync-player/releases" \
  -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tag_name": "VERSION",
    "target_commitish": "master",
    "name": "VERSION",
    "body": "## 修复\n- ...\n\n## 新功能\n- ...",
    "draft": false,
    "prerelease": false
  }'
# 返回 JSON 中 "id" 即 RELEASE_ID

# 4. 上传 APK 作为 Release Asset
curl -s -X POST "https://git.vectory.cn:18443/api/v1/repos/mfreceiver/sync-player/releases/RELEASE_ID/assets" \
  -H "Authorization: token $TOKEN" \
  -F "attachment=@app/build/outputs/apk/release/app-release.apk" \
  -F "name=app-release.apk"
```

Release 页面: `https://git.vectory.cn:18443/mfreceiver/sync-player/releases/tag/VERSION`
