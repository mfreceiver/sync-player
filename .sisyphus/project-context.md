# Sync Player — Project Context for AI Sessions

> Last updated: 2026-04-08
> Status: Active development, v6.1.0, all P0 features implemented

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
| DB | Room | 2.7.0, schema v6 |
| Video Player | Media3 / ExoPlayer | 1.6.0 |
| SMB Client | jcifs-ng | 2.1.10 |
| SFTP Client | SSHJ | 0.39.0 |
| Async | Kotlin Coroutines + Flow | 1.10.1 |
| Crypto | EncryptedSharedPreferences + Android Keystore | 1.1.0-alpha06 |
| Build | Gradle Kotlin DSL, AGP 8.8.0, KSP | — |
| JDK | 17 (targetCompatibility/sourceCompatibility) | — |

---

## 3. Build & Run

### Environment Setup
```bash
export JAVA_HOME=/home/mar/android-studio/jbr   # Android Studio bundled JBR (OpenJDK 21)
export ANDROID_HOME=/home/mar/android-sdk   # SDK: build-tools 35.0.x, platform android-35
```

### Quick Compile Check (fast, no APK output)
```bash
# From project root /home/mar/sync-player
JAVA_HOME=/home/mar/android-studio/jbr ANDROID_HOME=/home/mar/android-sdk ./gradlew compileDebugKotlin
```

### Build Debug APK
```bash
# From project root /home/mar/sync-player
JAVA_HOME=/home/mar/android-studio/jbr ANDROID_HOME=/home/mar/android-sdk ./gradlew assembleDebug --no-daemon
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK (signed, minified with R8)
```bash
JAVA_HOME=/home/mar/android-studio/jbr ANDROID_HOME=/home/mar/android-sdk ./gradlew assembleRelease --no-daemon
# Output: app/build/outputs/apk/release/app-release.apk (~12MB, R8 minified)
# Signing: automatically signed via release keystore configured in local.properties
#   release.storeFile=release.keystore (at app/release.keystore)
#   release.keyAlias=release
```

**No Kotlin LSP is installed in this environment.** The Gradle build IS the syntax/type verification — always run `compileDebugKotlin` (fast, ~30s) or `assembleRelease` (full APK, ~1.5min) after code changes.

---

## 4. Project Structure

```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt                    # Single Activity, setContent
├── SyncPlayerApp.kt                   # @HiltAndroidApp Application, triggers StoragePaths migration
├── storage/
│   └── StoragePaths.kt                # Centralized path management (userdata/, thumbnails/, debug-logs/)
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt             # Room DB v6, includes MIGRATION_5_6
│   │   ├── dao/
│   │   │   ├── SyncSourceDao.kt       # Sync source CRUD (SMB/SFTP servers)
│   │   │   ├── SyncRelationDao.kt     # Sync relations (source + local/remote path pairs)
│   │   │   ├── VideoItemDao.kt        # Video file metadata queries
│   │   │   ├── PlaybackRecordDao.kt   # Playback progress, favorites
│   │   │   ├── SyncLogDao.kt          # Sync operation logs
│   │   │   └── VideoMetadataCacheDao.kt # Metadata cache (path+name+size keyed)
│   │   └── entity/
│   │       ├── SyncSourceEntity.kt    # SMB/SFTP connection config
│   │       ├── SyncRelationEntity.kt  # Sync relation (source + localBasePath + remotePath)
│   │       ├── VideoItemEntity.kt     # Synced video file record
│   │       ├── PlaybackRecordEntity.kt # Play position, isCompleted, isFavorite
│   │       ├── SyncLogEntity.kt       # Sync operation history
│   │       └── VideoMetadataCacheEntity.kt # Cached metadata (duration, resolution, frameRate)
│   ├── model/
│   │   ├── BrowseItem.kt              # UI model for browser items
│   │   ├── SyncAction.kt              # DOWNLOAD, DELETE_LOCAL, DELETE_BOTH
│   │   ├── SyncProgress.kt            # Sync progress state
│   │   ├── SyncProtocol.kt            # SMB / SFTP enum
│   │   ├── SyncRelation.kt            # Domain model for sync relation
│   │   ├── SyncSource.kt              # Domain model for sync source
│   │   ├── SyncRelationWithSource.kt  # Joined model for UI
│   │   ├── RemoteFile.kt              # Remote file info model
│   │   └── OrientationPreference.kt   # Player orientation settings
│   └── repository/
│       ├── VideoRepository.kt         # Video item CRUD + folder queries
│       ├── PlaybackRepository.kt      # Playback records, favorites
│       ├── SyncSourceRepository.kt    # Sync source management (encrypted passwords)
│       ├── SyncRelationRepository.kt  # Sync relation management, basePath conflict detection
│       └── SyncLogRepository.kt      # Sync log queries
├── debug/
│   ├── DebugLogger.kt                 # Debug logging with file output, session management
│   └── DebugLogScreen.kt             # Debug log viewer UI
├── di/
│   ├── DatabaseModule.kt              # Room DB + all DAO/repo providers + EncryptedSharedPreferences
│   └── SyncModule.kt                  # RemoteFileClient binding (SMB/SFTP)
├── navigation/
│   ├── Route.kt                       # Sealed class: Browser, BrowserFolder, Player, Settings, SyncPreview, DeletePreview, SyncSourceList, SyncRelationList, SyncRelationEdit, About
│   └── AppNavHost.kt                  # NavHost with all composable routes
├── browser/
│   ├── BrowserScreen.kt               # File browser UI (grid/list, sort, filter, progress rings, thumbnails)
│   └── BrowserViewModel.kt            # Folder navigation, video list, thumbnail auto-repair
├── player/
│   ├── GesturePlayerView.kt           # Custom ExoPlayer view with gesture handling
│   ├── PlayerScreen.kt                # Full-screen player UI
│   └── PlayerViewModel.kt             # Playback state, progress save/restore
├── settings/
│   ├── SettingsScreen.kt              # Main settings hub
│   ├── SettingsViewModel.kt           # Settings state, debug mode toggle
│   ├── PlaybackSettingsScreen.kt      # Playback speed, debug log export
│   ├── SyncSourceListScreen.kt        # Sync source list management
│   ├── SyncRelationListScreen.kt      # Sync relation list management
│   ├── SyncRelationEditScreen.kt      # Sync relation create/edit with local/remote browser
│   ├── SyncRelationEditViewModel.kt   # Sync relation state, local directory browsing
│   └── AboutScreen.kt                # App info
├── sync/
│   ├── SyncEngine.kt                  # Core sync logic: dryRun(), executeSync(), dryRunDelete(), executeDelete()
│   ├── SyncPreviewScreen.kt           # Sync preview UI with download/delete mode tabs
│   ├── SyncPreviewViewModel.kt        # Preview state (toDownload, toDelete lists)
│   ├── SyncService.kt                 # Foreground service for sync operations
│   ├── SyncNotificationHelper.kt      # Notification management
│   ├── RemoteFileClient.kt            # Interface: listFiles(), downloadFile(), deleteFile()
│   ├── VideoMetadataExtractor.kt      # Cache-first metadata extraction, thumbnail generation + repair
│   ├── smb/SmbFileClient.kt           # SMB implementation (jcifs-ng)
│   └── sftp/SftpFileClient.kt         # SFTP implementation (SSHJ)
└── ui/
    └── theme/                         # Material 3 theme (Color, Theme, Type)

app/src/main/res/
├── xml/filepaths.xml                  # FileProvider paths: userdata/, cache/
└── ...
```

---

## 5. Navigation Routes

```kotlin
sealed class Route(val route: String) {
    data object Browser : Route("browser")
    data object BrowserFolder : Route("browser/{folderPath}") {
        fun create(folderPath: String): String =
            "browser/${android.net.Uri.encode(folderPath)}"
    }
    data object Player : Route("player/{videoItemId}") {
        fun create(videoItemId: Long): String = "player/$videoItemId"
    }
    data object Settings : Route("settings")
    data object SyncPreview : Route("sync_preview")
    data object DeletePreview : Route("delete_preview")
}
```

**Navigation flow**:
- `Browser` → `BrowserFolder` (folder tap, pushes to nav stack)
- `Browser`/`BrowserFolder` → `Player` (video tap)
- `Browser`/`BrowserFolder` → `SyncPreview` (sync button)
- All screens → `Settings` (settings button)

---

## 6. Database Schema (Room v6)

| Entity | PK | Key Fields | Notes |
|---|---|---|---|
| SyncSourceEntity | id (auto) | name, protocol, host, port, username, encryptedPassword | Multiple sync sources supported |
| SyncRelationEntity | id (auto) | name, syncSourceId (FK), remotePath, localBasePath (relative to userdata), lastSyncTime, lastSyncResult | localBasePath is unique, nesting conflict checked |
| VideoItemEntity | id (auto) | relativePath, fileName, localAbsolutePath, fileSize, duration, frameRate, thumbnailPath, syncRelationId | One per synced file |
| PlaybackRecordEntity | id (auto) | videoItemId (FK, UNIQUE), position, duration, lastPlayedTime, isCompleted (>90%), isFavorite | 1:1 with VideoItem |
| SyncLogEntity | id (auto) | timestamp, dryRun, action, relativePath, fileSize, status, errorMessage | Sync history |
| VideoMetadataCacheEntity | id (auto) | localAbsolutePath, fileName, fileSize, duration, width, height, frameRate, bitrate, thumbnailPath | Cache keyed by path+name+size |

**Migration History**:
- v5→v6: Updated `video_item.localAbsolutePath` to include `userdata/` segment; cleared `video_metadata_cache` for rebuild

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
- **Sync mode toggle**: SyncPreviewScreen has TabRow for Download/Delete mode
- **Delete flow**: SyncPreview (delete mode) → inline delete confirmation
- **Foreground Service** with `dataSync` type for sync operations

### Player Design
- **Media3 ExoPlayer** with hardware decoding
- **Gesture controls**: long-press speed up, horizontal swipe seek, left=brightness, right=volume
- **Auto-resume**: saved position restored on open; completed videos (isCompleted) play from beginning
- **Progress saving**: on exit, video switch, and periodically during playback
- **isCompleted**: position > 90% of duration
- **Auto-advance on end-of-stream**: next video in folder, or exit if last
- **Orientation**: configurable (landscape default)
- **Folder-scoped playback**: switch between videos in current folder only (via PlayerNavigationStore)

### Browser Design
- **Folder navigation**: each folder is a separate nav stack entry (BrowserFolder route)
- **Path encoding**: `Uri.encode(folderPath)` — full encoding (no `/` preservation), decoded with `Uri.decode()` in AppNavHost
- **Back navigation**: from player returns to the folder the video was opened from (nav stack)
- **Completed videos**: show full progress ring (progress=1f) instead of checkmark
- **Video metadata line**: shows extension, resolution, frame rate
- **Sort**: dropdown menu (filename, duration) with direction toggle
- **Filter**: "全部", "仅视频", "仅预删除" filters
- **Thumbnails**: generated via MediaMetadataRetriever, stored in `filesDir/thumbnails/`, auto-repaired when missing
- **Non-video files**: show file icon, toast "该文件无法播放" on click
- **Pre-delete (favorite)**: "喜欢" repurposed as "预删除" marker — files marked for deletion are visually distinct with delete icon

### Storage
All paths managed centrally by `StoragePaths` (singleton, injected via Hilt).

```
context.filesDir/
├── userdata/          ← User-visible data (sync folders, manually created folders)
│   ├── movies/        ← Synced via SyncRelation.localBasePath="movies"
│   └── drama/         ← Manually created by user
├── thumbnails/        ← Auto-generated video thumbnails (self-healing)
├── debug-logs/        ← Debug session logs (self-healing)
└── sync-player.db     ← Room database
```

- User data root: `StoragePaths.userdataRoot` = `filesDir/userdata/`
- Thumbnails: `StoragePaths.thumbnailsDir` = `filesDir/thumbnails/` (auto-mkdirs)
- Debug logs: `StoragePaths.debugLogsDir` = `filesDir/debug-logs/` (auto-mkdirs)
- Thumbnail auto-repair: `BrowserViewModel.repairMissingThumbnails()` detects missing thumbnail files and re-extracts asynchronously
- FileProvider: only exposes `userdata/` and `cache/` (see `res/xml/filepaths.xml`)
- Passwords: EncryptedSharedPreferences (`secure_prefs`) via Android Keystore
- One-time migration: `StoragePaths.migrateToUserdataLayout()` moves pre-existing user dirs from `filesDir` root into `userdata/` (idempotent, marker file `.userdata_migrated`)

---

## 8. Git Conventions

### Workflow (after each commit+push)

1. **Bump version** in `app/build.gradle.kts` (`versionCode += 1`, `versionName` major +1, e.g. `3.0.0` → `4.0.0`)
2. **Build**: `./gradlew assembleDebug --no-daemon`
3. **Commit**: `git add -A && git commit -m "fix: ..."`
4. **Push**: `git push origin master`
5. **Tag**: `git tag vN.0.0 && git push origin vN.0.0`
6. **Release**: Create Gitea release with APK (see below)

### Commit Message Style
```
feat: <short description>
fix: <short description>
```
Examples:
```
feat: metadata cache, delete feature, file list UI, sort/filter enhancements
fix: favorite button, sync progress UI, seek clamp, swipe direction
```

### Gitea Release & APK Upload
```bash
# Credentials (from ~/.git-credentials)
TOKEN=$(grep 'git.vectory' ~/.git-credentials | head -1 | sed 's|https://||' | sed 's|@.*||' | cut -d: -f2)
GITEA_API="https://git.vectory.cn:18443/api/v1"
REPO="mfreceiver/sync-player"
VERSION="v6.1.0"

# Step 1: Create release (if not exists)
RELEASE_ID=$(curl -s "${GITEA_API}/repos/${REPO}/releases/tags/${VERSION}" \
    -H "Authorization: token $TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))")

if [ -z "$RELEASE_ID" ]; then
    RELEASE_ID=$(curl -s -X POST "${GITEA_API}/repos/${REPO}/releases" \
        -H "Authorization: token $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"tag_name\":\"${VERSION}\",\"target_commitish\":\"master\",\"name\":\"${VERSION}\",\"body\":\"release description\",\"draft\":false,\"prerelease\":false}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
fi

# Step 2: Upload Release APK (~12MB, R8 minified)
curl -X POST "${GITEA_API}/repos/${REPO}/releases/${RELEASE_ID}/assets?name=app-release.apk" \
    -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @app/build/outputs/apk/release/app-release.apk \
    --max-time 300
```
**Note**: Release APK is ~12MB (R8 minified). Use `--max-time 300`. For Chinese body text, avoid `curl -d` with inline Chinese — use Python `json.dumps()` instead.

### Versioning Rule
**After every `git commit` + `git push`, increment the app version number by +1 (major version).**
- Version is defined in `app/build.gradle.kts` as `versionCode` and `versionName`
- After push: bump `versionCode += 1` and update `versionName` accordingly (e.g. `3.0.0` → `4.0.0`)
- Also tag the release with the new version (e.g. `v4.0.0`)

---

## 9. Known Issues & Limitations
- No Kotlin LSP in dev environment — rely on `./gradlew assembleRelease` for verification
- No test suite currently
- `fallbackToDestructiveMigration()` still present but protected by explicit migrations (v5→v6)
- Sync runs via foreground service (not in WorkManager)
- No background/periodic sync (intentional — manual trigger only)
- Thumbnail repair runs during browsing (not proactive on app start)
- APK upload to Gitea can timeout for large files — use `--max-time 600` or tmux session

---

## 10. UI Language
All user-facing strings are in **Chinese (zh-CN)**:
- "同步预览", "下载", "删除", "确认下载", "确认删除"
- "取消", "返回", "重试"
- "本地文件已是最新", "同步摘要"
- "全部", "仅视频", "仅预删除" filters
- "预删除" for favorite/marked-for-delete
- "该文件无法播放" for non-video files

Code (variable names, comments) is in **English**.
