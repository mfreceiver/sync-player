# Sync Player — Project Context for AI Sessions

> Last updated: 2026-04-03
> Status: Active development, all P0 features implemented

---

## 1. Project Overview

**Name**: Sync Player
**Package**: `cn.vectory.syncplayer`
**Repo**: `https://git.vectory.cn:18443/mfreceiver/sync-player.git` (branch: `master`)
**Purpose**: Android video player that mirrors video files from remote SMB/SFTP servers to local storage, then plays them with hardware decoding and gesture controls.

**Target**: Personal use only, Android 14+ devices (minSdk 34, targetSdk 35), sideloaded (no app store).

---

## 2. Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2025.02.00 |
| Architecture | MVVM + Repository, Single Activity | — |
| Navigation | Navigation Compose | 2.9.0 |
| DI | Hilt | 2.54 |
| DB | Room | 2.7.0, schema v3 |
| Video Player | Media3 / ExoPlayer | 1.6.0 |
| SMB Client | jcifs-ng | 2.1.10 |
| SFTP Client | SSHJ | 0.39.0 |
| Async | Kotlin Coroutines + Flow | 1.10.1 |
| Crypto | EncryptedSharedPreferences + Android Keystore | 1.1.0-alpha06 |
| Build | Gradle Kotlin DSL, AGP 8.8.0, KSP | — |
| JDK | 17 (targetCompatibility/sourceCompatibility) | — |

---

## 3. Build & Run

```bash
# Environment
export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk

# Build debug APK
./gradlew assembleDebug --no-daemon

# Output APK
# app/build/outputs/apk/debug/app-debug.apk
```

**No Kotlin LSP is installed in this environment.** The Gradle build IS the syntax/type verification — always run `assembleDebug` after code changes.

---

## 4. Project Structure

```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt                    # Single Activity, setContent
├── SyncPlayerApp.kt                   # @HiltAndroidApp Application
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt             # Room DB v3 (exportSchema=false)
│   │   ├── dao/
│   │   │   ├── ServerConfigDao.kt     # Server config CRUD (single row)
│   │   │   ├── VideoItemDao.kt        # Video file metadata queries
│   │   │   ├── PlaybackRecordDao.kt   # Playback progress, favorites, getFavorites()
│   │   │   ├── SyncLogDao.kt          # Sync operation logs
│   │   │   └── VideoMetadataCacheDao.kt # Metadata cache (path+name+size keyed)
│   │   └── entity/
│   │       ├── ServerConfigEntity.kt  # SMB/SFTP connection config
│   │       ├── VideoItemEntity.kt     # Synced video file record (has frameRate)
│   │       ├── PlaybackRecordEntity.kt # Play position, isCompleted, isFavorite
│   │       ├── SyncLogEntity.kt       # Sync operation history
│   │       └── VideoMetadataCacheEntity.kt # Cached metadata (duration, resolution, frameRate)
│   ├── model/
│   │   ├── BrowseItem.kt              # UI model for browser items (has frameRate)
│   │   ├── SyncAction.kt              # DOWNLOAD, DELETE_LOCAL, DELETE_BOTH
│   │   ├── SyncProgress.kt            # Sync progress state
│   │   ├── SyncProtocol.kt            # SMB / SFTP enum
│   │   ├── ServerConfig.kt            # Domain model for server config
│   │   ├── RemoteFile.kt              # Remote file info model
│   │   └── OrientationPreference.kt   # Player orientation settings
│   └── repository/
│       ├── VideoRepository.kt         # Video item CRUD + folder queries
│       ├── PlaybackRepository.kt      # Playback records, getFavorites()
│       ├── ServerConfigRepository.kt  # Server config management
│       └── SyncLogRepository.kt       # Sync log queries
├── di/
│   ├── DatabaseModule.kt              # Room DB + all DAO providers
│   └── SyncModule.kt                  # RemoteFileClient binding (SMB/SFTP)
├── navigation/
│   ├── Route.kt                       # Sealed class: Browser, BrowserFolder, Player, Settings, SyncPreview, DeletePreview
│   └── AppNavHost.kt                  # NavHost with all composable routes
├── browser/
│   ├── BrowserScreen.kt               # File browser UI (grid/list, sort, filter, progress rings)
│   └── BrowserViewModel.kt            # Folder navigation, video list state
├── player/
│   ├── GesturePlayerView.kt           # Custom ExoPlayer view with gesture handling
│   ├── PlayerScreen.kt                # Full-screen player UI
│   └── PlayerViewModel.kt             # Playback state, progress save/restore
├── settings/
│   ├── SettingsScreen.kt              # Server config, sync path, speed multiplier, debug log viewer
│   └── SettingsViewModel.kt           # Settings state management
├── sync/
│   ├── SyncEngine.kt                  # Core sync logic: dryRun(), executeSync(), dryRunDelete(), executeDelete()
│   ├── SyncPreviewScreen.kt           # Sync preview UI with download/delete mode tabs
│   ├── SyncPreviewViewModel.kt        # Preview state (toDownload, toDelete lists)
│   ├── DeletePreviewScreen.kt         # Delete confirmation UI
│   ├── DeletePreviewViewModel.kt      # Delete preview state
│   ├── SyncService.kt                 # Foreground service for sync operations
│   ├── SyncNotificationHelper.kt      # Notification management
│   ├── RemoteFileClient.kt            # Interface: listFiles(), downloadFile(), deleteFile()
│   ├── VideoMetadataExtractor.kt      # Cache-first metadata extraction
│   ├── smb/SmbFileClient.kt           # SMB implementation (jcifs-ng)
│   ├── sftp/SftpFileClient.kt         # SFTP implementation (SSHJ)
│   └── SyncMode.kt (in SyncPreviewScreen.kt) # enum DOWNLOAD, DELETE
└── ui/
    └── theme/                         # Material 3 theme (Color, Theme, Type)

app/src/main/res/
├── xml/filepaths.xml                  # FileProvider paths: synced/, thumbnails/
└── ...
```

---

## 5. Navigation Routes

```kotlin
sealed class Route(val route: String) {
    Browser         // "browser" — Root file browser
    BrowserFolder   // "browser/{folderPath}" — Sub-folder browser (URL-encoded path)
    Player          // "player/{videoItemId}" — Video player (Long ID)
    Settings        // "settings" — Settings page
    SyncPreview     // "sync_preview" — Download sync preview + mode toggle
    DeletePreview   // "delete_preview" — Delete sync confirmation
}
```

**Navigation flow**:
- `Browser` → `BrowserFolder` (folder tap, pushes to nav stack)
- `Browser`/`BrowserFolder` → `Player` (video tap)
- `Browser`/`BrowserFolder` → `SyncPreview` (sync button)
- `SyncPreview` → `DeletePreview` (when in delete mode, "去删除预览" button)
- All screens → `Settings` (settings button)

---

## 6. Database Schema (Room v3)

| Entity | PK | Key Fields | Notes |
|---|---|---|---|
| ServerConfigEntity | id (fixed=1) | protocol, host, port, username, encryptedPassword, remotePath | Single row |
| VideoItemEntity | id (auto) | relativePath, fileName, localAbsolutePath, fileSize, duration, frameRate, dateSynced, remoteModifiedTime | One per synced file |
| PlaybackRecordEntity | id (auto) | videoItemId (FK, UNIQUE), position, duration, lastPlayedTime, isCompleted (>90%), isFavorite | 1:1 with VideoItem |
| SyncLogEntity | id (auto) | timestamp, dryRun, action, relativePath, fileSize, status, errorMessage | Sync history |
| VideoMetadataCacheEntity | id (auto) | filePath, fileName, fileSize, duration, width, height, frameRate, extractedAt | Cache keyed by path+name+size |

---

## 7. Development Conventions

### Architecture
- **MVVM + Repository** pattern throughout
- **Hilt** for all DI — ViewModels use `@HiltViewModel`, repositories injected via constructor
- **StateFlow** for ViewModel → UI state (collect with `collectAsState()`)
- **Single Activity** — all screens are `@Composable` functions navigated via Navigation Compose
- **Sealed class** for routes (Route.kt)

### Kotlin Style
- `kotlinOptions.jvmTarget = "17"`
- No `as any`, `@ts-ignore` equivalents — no `@Suppress("UNCHECKED_CAST")` unless absolutely unavoidable
- Null safety: prefer `?.let {}` over `!!`
- Coroutines: `viewModelScope.launch` for VM work, `withContext(Dispatchers.IO)` for IO

### Compose Style
- `@OptIn(ExperimentalMaterial3Api::class)` on screens using TopAppBar, etc.
- Custom composables for reusable UI: SummaryCard, WarningCard, SyncActionItem, etc.
- Private composables for screen-internal components
- `Scaffold` with `topBar` + optional `bottomBar` for each screen

### Sync Design
- **Mirror sync** direction: always remote → local
- **Dry run first**: `dryRun()` returns `(downloads, deletions)` before execution
- **Delete feature**: `dryRunDelete()` / `executeDelete()` for favorite files (local + remote)
- **Sync mode toggle**: SyncPreviewScreen has FilterChips for Download/Delete mode
- **Delete flow**: SyncPreview (delete mode) → DeletePreviewScreen for final confirmation
- **Foreground Service** with `dataSync` type for sync operations

### Player Design
- **Media3 ExoPlayer** with hardware decoding
- **Gesture controls**: long-press speed up, horizontal swipe seek, left=brightness, right=volume
- **Auto-resume**: saved position restored on open; completed videos (isCompleted) play from beginning
- **Progress saving**: on exit, video switch, and periodically during playback
- **isCompleted**: position > 90% of duration
- **Orientation**: configurable (landscape default)

### Browser Design
- **Folder navigation**: each folder is a separate nav stack entry (BrowserFolder route)
- **Back navigation**: from player returns to the folder the video was opened from (nav stack)
- **Completed videos**: show full progress ring (progress=1f) instead of checkmark
- **Video metadata line**: shows extension, resolution, frame rate
- **Sort**: dropdown menu (name, date, size, duration) with direction toggle
- **Filter**: "仅视频" (video-only) filter
- **Thumbnails**: generated via MediaMetadataRetriever, cached in cache/thumbnails/

### Storage
- Synced files: `context.getExternalFilesDir(null)/synced/`
- Thumbnails: `context.cacheDir/thumbnails/`
- Passwords: EncryptedSharedPreferences (Android Keystore)

---

## 8. Git Conventions

```bash
# Remote
origin = https://git.vectory.cn:18443/mfreceiver/sync-player.git
branch = master

# Commit message style
feat: <short description>

# Examples from history:
# feat: metadata cache, delete feature, file list UI, sort/filter enhancements
# feat: browser back nav, immersive mode, sort/filter dropdown, UI refinements
# feat: implement player gestures, orientation control, auto-resume, and immersive mode
# feat: rename package to cn.vectory.syncplayer, fix sync path/SQL/browser/debug
```

### Versioning Rule

**After every `git commit` + `git push`, increment the app version number by +1 (major version).**

- Version is defined in `app/build.gradle.kts` as `versionCode` and `versionName`
- After push: bump `versionCode += 1` and update `versionName` accordingly (e.g. `1.0.0` → `2.0.0`)
- Also tag the release with the new version (e.g. `v2.0.0`)

---

## 9. Known Issues & Limitations

- No Kotlin LSP in dev environment — rely on `./gradlew assembleDebug` for verification
- No test suite currently
- `Icons.Filled.Sort` deprecation warning — should migrate to `Icons.AutoMirrored.Filled.Sort`
- Sync runs on main thread via foreground service (not in WorkManager)
- No background/periodic sync (intentional — manual trigger only)
- Thumbnails generated on-demand, no pre-generation during sync

---

## 10. UI Language

All user-facing strings are in **Chinese (zh-CN)**:
- "同步预览", "下载", "删除", "去删除预览", "确认下载", "确认删除"
- "取消", "返回", "重试"
- "本地文件已是最新", "同步摘要"
- "仅视频" filter
- "喜欢" for favorites

Code (variable names, comments) is in **English**.
