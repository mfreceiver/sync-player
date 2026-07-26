# Sync Player — AGENTS.md

## Environment

- **JAVA_HOME**: `/home/mar/android-studio/jbr` (Android Studio bundled JBR, OpenJDK 21)
- **ANDROID_HOME**: `/home/mar/android-sdk` (build-tools 35.0.x, platform android-35)
- **Android Studio**: `/home/mar/android-studio`
- **Project root**: `/home/mar/personal_projects/sync-player`
- **Gradle**: 8.11.1
- **Kotlin LSP disabled** (`opencode.json: lsp=false`). Reason: this project uses Hilt (KSP) + Room (KSP) + Compose Compiler; as of 2026-07 no LSP can resolve KSP/Compose generated code (`build/generated/ksp/`, `Hilt_xxx`, `xxxDao_Impl`), so any LSP would flood the session with stale/unresolved-reference false positives. JetBrains official Kotlin LSP is pre-alpha JVM-only; fwcd/kotlin-language-server is deprecated. **Gradle compile is the single source of truth** for type/syntax verification — always run `compileDebugKotlin` after code changes; never trust LSP-style diagnostics if any tool re-enables them.

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

# Unit tests
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

Always run a build after code changes to verify compilation — there is no LSP fallback.

## Project

**Package**: `cn.vectory.syncplayer`
**Repo**: `https://git.vectory.cn:18443/mfreceiver/sync-player.git` (branch: `master`)
**Target**: Android 14+ (minSdk 34, targetSdk 35, compileSdk 35), personal use, sideloaded
**JVM target**: 17
**Current version**: `git describe --tags`（git 派生，见 Conventions · Versioning）

### Tech Stack

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

### Key Directories

```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt              # Single Activity — Compose host, app lock overlay, navigation
├── SyncPlayerApp.kt             # Application — Hilt, AppLockManager registration, BouncyCastle provider, crypto-v2 one-shot purge
│
├── audio/AudioGainAnalyzer.kt   # RMS/peak dB analysis via MediaExtractor+MediaCodec
│
├── browser/                     # File browser
│   ├── BrowserViewModel.kt         # Folder navigation, sort/filter, grid/list modes, thumbnails
│   ├── BrowserScreen.kt            # Main browse UI — grid/list, directory tree, item actions
│   ├── SearchViewModel.kt
│   ├── SearchScreen.kt
│   └── FileOperationsUseCase.kt    # Delete/trash media + thumbnails, volume-ownership fail-closed,
│                                   #   DeleteMediaResult (Failed now carries SyncException, not String)
│
├── crypto/                      # At-rest media encryption (AEAD v2 — see Conventions · Crypto)
│   ├── CryptoScheme.kt             # Format constants + key derivation + chunked GCM primitives + cipherLengthFor()
│   ├── ChunkedEncryptSink.kt       # Streaming encrypt OutputStream (writes header + 64KiB GCM chunks)
│   ├── RangeDecryptor.kt           # Plaintext-offset random read (per-chunk seek + GCM decrypt)
│   ├── DecryptingFdCallback.kt     # ParcelFileDescriptor-backed decrypt pipe for ContentProvider
│   ├── MediaCryptoMaster.kt        # KEK-wrapped master key (KekProvider abstraction for testability)
│   └── LegacyCiphertextException.k # Thrown on missing SPM2 magic (v1 ciphertext after v2 upgrade)
│
├── data/                        # Data layer
│   ├── local/
│   │   ├── AppDatabase.kt          # Room DB (6 entities, schema v2, exportSchema=true,
│   │   │                           #   fallbackToDestructiveMigration(dropAllTables=true))
│   │   ├── dao/                    # VideoItemDao, PlaybackRecordDao, SyncSourceDao,
│   │   │                           # SyncRelationDao, SyncLogDao, PendingSyncActionDao
│   │   └── entity/                 # VideoItemEntity, PlaybackRecordEntity, SyncSourceEntity,
│   │                                SyncRelationEntity, SyncLogEntity, PendingSyncActionEntity
│   ├── model/                      # BrowseItem, RemoteFile, SyncAction, SyncProgress, SyncProtocol,
│   │                                SyncSource, SyncRelation, SyncRelationWithSource,
│   │                                OrientationPreference, FolderStats
│   └── repository/                 # VideoRepository, PlaybackRepository, SyncSourceRepository,
│                                    SyncRelationRepository, SyncLogRepository, UserPreferencesRepository
│
├── di/                          # Hilt DI modules
│   ├── DatabaseModule.kt           # Room DB, all DAOs, MasterKey, EncryptedSharedPreferences, destructive migration
│   └── SyncModule.kt               # SMB/SFTP client providers
│
├── imageviewer/                 # Image viewer (encrypted image playback)
│   ├── ImageViewerScreen.kt
│   └── ImageViewerViewModel.kt
│
├── navigation/
│   ├── AppNavHost.kt               # Central NavHost wiring all routes/screens
│   ├── Route.kt                    # Route sealed class
│   ├── NavigationHelper.kt         # Hilt-injected navigation helper
│   ├── VideoQueue.kt               # Shared video queue for player navigation
│   └── ImageQueue.kt               # Shared image queue
│
├── player/                      # Video player (MVI)
│   ├── PlayerScreen.kt             # ExoPlayer UI + gesture controls (swipe seek/volume/brightness,
│   │                                #   long-press speed, double-tap actions), top bar, bottom toolbar, overlays
│   ├── PlayerViewModel.kt          # MVI: unified PlayerUiState + separate currentPosition StateFlow
│   └── SeekPreviewExtractor.kt     # Seek frame extraction with LRU cache, runs on Dispatchers.IO
│
├── provider/                    # ContentProvider exposing encrypted media to ExoPlayer
│   ├── MediaContentProvider.kt     # ContentProvider (not DocumentsProvider) — serves encrypted media +
│   │                                #   thumbnails via DecryptingFdCallback; resident HandlerThread;
│   │                                #   volumeId resolved per-access via runBlocking (not cached)
│   └── MediaUris.kt                # Uri builders for media/thumbnail lookups
│
├── security/                    # App lock & biometric auth
│   ├── AppLockManager.kt           # Process lifecycle observer — auto-lock on background/foreground
│   ├── BiometricAuthManager.kt     # BiometricPrompt facade for unlock
│   └── LockScreenOverlay.kt        # Full-screen lock UI with retry button + cooldown
│
├── settings/                    # Settings screens
│   ├── SettingsScreen.kt / SettingsViewModel.kt
│   ├── SyncSourceListScreen.kt / SyncSourceEditScreen.kt / SyncSourceEditViewModel.kt
│   ├── SyncRelationListScreen.kt / SyncRelationListScreen.kt / SyncRelationEditViewModel.kt
│   ├── MetadataRebuildScreen.kt / MetadataRebuildViewModel.kt   # Old-volume index cleanup
│   ├── GainAnalysisScreen.kt / GainAnalysisViewModel.kt         # Audio gain analysis per sync relation
│   └── AboutScreen.kt
│
├── storage/                     # Path + volume management
│   ├── StoragePaths.kt             # Centralized paths for user data, thumbnails, debug logs
│   └── volume/
│       ├── EncryptedMediaStore.kt     # Encrypt sink / decrypt read / purge / space query on the active volume
│       ├── SafVolume.kt               # SAF (treeUri) volume — usableSpace() parses real volume, -1L = unknown
│       ├── FileVolume.kt / MediaVolume.kt / MediaVolumeProvider.kt
│       └── MediaPaths.kt              # Path layout for media/ + thumb/ products
│
├── sync/                        # Sync engine (see Conventions · Sync Error Model)
│   ├── SyncService.kt              # Foreground service (dataSync type), orchestrates sync tasks
│   ├── SyncEngine.kt               # Core engine — dryRun (returns DryRunResult) / executeSync / executeDelete,
│   │                                #   E8 scan-failure guard, volume-mismatch fail-closed, retry whitelist
│   ├── SyncException.kt            # Typed exception: SyncErrorCode (18) + SyncOperation + structured fields,
│   │                                #   message deliberately null (no display text carried)
│   ├── RemoteScanIncompleteException.kt  # Extends SyncException(SCAN_INCOMPLETE); triggers E8 guard
│   ├── RemoteFileClient.kt         # Remote file client abstraction (SMB/SFTP contract)
│   ├── ErrorMessageMapper.kt       # classifyRemoteError(by code) + syncErrorMessage(→ R.string.*) resolver
│   ├── smb/SmbFileClient.kt        # SMB (jcifs-ng); classifySmbException on jcifs English status keys
│   ├── sftp/SftpFileClient.kt      # SFTP (SSHJ)
│   ├── sftp/SyncPlayerHostKeyVerifier.kt  # TOFU host key verifier (known_hosts.properties)
│   ├── VideoMetadataExtractor.kt / ImageMetadataExtractor.kt  # Duration/dimensions/bitrate + thumbnail gen
│   ├── SyncNotificationHelper.kt   # Foreground notification; all text via context.getString(R.string.*)
│   ├── SyncPreviewScreen.kt / SyncPreviewViewModel.kt  # Dry-run preview UI; consumes DryRunResult.error
│   └── NetworkUtils.kt             # Network availability check (throws SyncException)
│
└── ui/                          # Shared UI components & theme
    ├── components/{VideoGridItem,FolderGridItem,FolderIcon,Thumbnail,FileStatusIcon,
    │              ProgressRing,StarRatingInput,DirectoryTreeDialog,VerticalScrollbar,
    │              ItemContextMenu,ErrorDisplays}.kt
    ├── theme/{Theme.kt,Color.kt}     # Material 3, dynamic color, extended colors
    └── util/{FormatUtils.kt,VideoUtils.kt}

app/schemas/cn.vectory.syncplayer.data.local.AppDatabase/2.json   # Exported Room schema (gitignored build artifacts except this)
app/src/main/res/values/strings.xml                                # All user-facing Chinese text (single source)
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
  - Assert on enums, collection sizes, and MockK call counts — **never on user-facing message text** (strings move to `strings.xml`; text assertions break on i18n changes).
  - Aim for high coverage on core logic like `SyncEngine`, `CryptoScheme`, `RangeDecryptor`, `ErrorMessageMapper`.
- **Single Activity**: all screens are `@Composable`, navigated via Navigation Compose.
- **i18n / strings**: ALL user-facing Chinese text lives in `app/src/main/res/values/strings.xml` (single source). Resolved via `stringResource` (Composables), `context.getString` (Notifications), or `syncErrorMessage(e, context)` (ViewModels). **Internal exception/log diagnostic text is NOT a localization target** — sync/data-layer exceptions carry a typed `SyncErrorCode` (not display text); `Log.w/d/i` Chinese diagnostics stay hardcoded (developer-facing, debuggability > i18n).
- **Player**: Media3 ExoPlayer, gesture controls (swipe seek/volume/brightness, long-press speed, double-tap actions)
  - Top bar: filename row (exit/trash) + thumbless progress bar with inline time
  - Bottom toolbar: speed/rewind/play-pause/forward/rotation (non-immersive only)
  - Overlays: iOS-style icon+text pills (volume, brightness, speed, trash IN/OUT, previous/next, orientation)
  - Orientation toggle: ROTATION (system direction) ↔ FULLSCREEN (video-matched direction), persists across videos, resets on exit
  - Playback speed (1x/1.5x/2x): persists across videos, resets on exit
  - System status bar always hidden in player
  - `progressAlwaysVisible`: shows top bar in immersive mode without bottom toolbar
- **Sync**: mirror sync (remote → local), multi-source (SyncSource + SyncRelation), dry-run first, foreground service (`dataSync` type).
- **Crypto (AEAD v2 — authoritative format)**:
  - Media at rest uses **per-file chunked AEAD** (`AES/GCM/NoPadding`), NOT the legacy AES/CTR stream.
  - Layout: `[Header 16B][Chunk 0]…[Chunk N-1]`.
    - Header (plaintext, bound into every chunk's AAD): magic `"SPM2"` (4) | formatVersion `2` (1) | reserved `0` (1) | chunkSizeKiB `64` (2, BE u16) | plaintextLength (8, BE i64).
    - Chunk i: `AES/GCM(key, nonce = iv(8)‖i(4 BE), aad = header(16)‖i(4 BE))` → `ciphertext‖tag(16)`. Chunk plaintext size = 64 KiB; last chunk may be short.
  - `VideoItemEntity.iv` is the **8-byte** per-file random nonce prefix (unchanged from v1). `VideoItemEntity.fileSize` is the **plaintext** byte count (= remote source size).
  - `cipherLen(plainLen) = 16 + ceil(plainLen/65536)*16 + plainLen` — use `CryptoScheme.cipherLengthFor()` for space pre-checks; never hand-compute.
  - Random read: `RangeDecryptor.readRange(handle, offset, len)` takes **plaintext** coords; seeks per chunk, GCM-decrypts (failure MUST throw, never return dirty plaintext).
  - Master key: `MediaCryptoMaster` — AES-256, KEK-wrapped blob in EncryptedSharedPreferences (`KekProvider` abstraction for testability).
  - **Destructive v1→v2 break**: v1 ciphertext (AES/CTR, no magic) is unreadable after upgrade. `RangeDecryptor` throws `LegacyCiphertextException` on missing `SPM2` magic. Migration is **destructive** (`fallbackToDestructiveMigration(dropAllTables=true)`): DB wipes on schema change, user re-syncs to produce v2 ciphertext. `SyncPlayerApp` runs a one-shot purge of legacy `media/`+`thumb/` files on first v2 launch (DataStore flag `crypto_v2_purged`).
  - Thumbnail format is **unchanged** (separate GCM blob; not chunked).
- **Sync Error Model**:
  - All sync/data-layer failures throw **`SyncException(code, operation, path?, host?, expectedBytes?/actualBytes?/availableBytes?, cause)`** — `message` is deliberately null (no display text carried).
  - `SyncErrorCode` (18): NETWORK_UNAVAILABLE, UNKNOWN_HOST, CONNECTION_REFUSED, TIMEOUT, AUTHENTICATION_FAILED, ACCESS_DENIED, PATH_NOT_FOUND, NOT_CONNECTED, SCAN_INCOMPLETE, DOWNLOAD_FAILED, REMOTE_DELETE_FAILED, INVALID_FILE_SIZE, INSUFFICIENT_SPACE, FINALIZE_FAILED, STORAGE_ERROR, THUMBNAIL_ERROR, DATABASE_ERROR, UNKNOWN.
  - `ErrorMessageMapper.classifyRemoteError(e)` reads the **code** (not substring of message) → `RemoteBrowseError`. `syncErrorMessage(e, context)` resolves code → `R.string.*` (UNKNOWN never leaks `e.message`).
  - **`DryRunResult(toDownload, toDelete, error: SyncException?)`**: `SyncEngine.dryRun()` returns the structured error so `SyncPreviewViewModel` renders the real cause (AUTH/PATH_NOT_FOUND/SCAN_INCOMPLETE) instead of a generic "connect failed".
  - **E8 scan-failure guard**: `RemoteScanIncompleteException` (or any scan exception) → `dryRun` clears BOTH `toDownload` and `toDelete` (a partial remote snapshot MUST NEVER derive `DELETE_LOCAL` actions — they delete real local ciphertext + DB rows, unrecoverable). Locked by `SyncEngineDryRunTest`.
  - **Retry whitelist**: download retry only retries transient codes `{TIMEOUT, CONNECTION_REFUSED, UNKNOWN_HOST, NETWORK_UNAVAILABLE, DOWNLOAD_FAILED, UNKNOWN}`. Terminal codes (AUTH/ACCESS/PATH_NOT_FOUND/SPACE/FINALIZE/…) throw immediately after cleanup. `CancellationException` always propagates first.
  - **Volume-mismatch DELETE**: if `entity.volumeId != store.volumeId`, the delete is skipped (ciphertext belongs to another volume — don't delete what isn't ours) BUT counted as **FAILED** (so `completedFiles+failedFiles == totalFiles` and the relation shows PARTIAL_FAILED, not false SUCCESS). Status log = `SKIPPED`. **To unblock a stuck relation**: re-sync once on the old volume, or use `FileOperationsUseCase` to index-delete the orphaned entries.
- **Security**:
  - App lock via biometric (BIOMETRIC_WEAK), auto-locks on background transition.
  - FLAG_SECURE always on (prevents screenshot/screen-recording).
  - SSH: **TOFU (Trust On First Use)** for host key verification (`known_hosts.properties`, fail-closed on mismatch).
  - Credentials: EncryptedSharedPreferences (`secure_prefs`, AES256_SIV keys / AES256_GCM values).
- **ContentProvider**: `MediaContentProvider` (a `ContentProvider`, **not** DocumentsProvider) exposes encrypted media + thumbnails to ExoPlayer via `DecryptingFdCallback`. Uses a resident `HandlerThread` (ContentProvider has no destroy lifecycle). `volumeId` is resolved per-access via `runBlocking` (not cached) to stay correct across volume remounts.
- **Database**:
  - Room, schema **v2**, `exportSchema=true` (schemas exported to `app/schemas/`). KSP arg `room.schemaLocation=$projectDir/schemas`.
  - **Destructive migration**: `fallbackToDestructiveMigration(dropAllTables=true)` — this is a v1 clean-start app; breaking schema changes rebuild the DB (personal use, user reinstalls/re-syncs). Do not add non-destructive migrations without a deliberate decision.
  - **Optimization**: Use `parentPath` indexed column for high-speed folder browsing instead of `LIKE` substrings.
- **Performance**:
  - **Debounce**: ≥300ms debounce for heavy UI merging flows (e.g., file browsing).
  - **I/O Safety**: All blocking I/O (Retriever, File operations) MUST run in `Dispatchers.IO`.
  - **Versioning**: git 派生 —— `versionCode` = `git rev-list --count HEAD`，`versionName` = `git describe`（见 `app/build.gradle.kts` 的 `gitVersionCode` / `gitVersionName`）。发版用 `./scripts/release.sh <x.y.z>`（打 tag + push → `release.yml`）。不要手改 versionCode/versionName。

### Known Residual Risks (accepted, personal-use scope)

These are deliberately deferred — safe for a single-user app, documented so the next session doesn't re-litigate:

1. **`listDirectories()` swallows exceptions** → returns empty list (browse UI may show "empty dir" instead of an access error). The mirror-scan `listFiles()` path still throws (E8 holds). Defer: convert to structured error return.
2. **`isNotFound()` is type-only** (`FileNotFoundException`). SAF providers that signal not-found via plain `IOException` message text will be treated as real failures (DB row kept) — conservative, never mis-deletes.
3. **`classifySmbException` keys on jcifs English status phrases**. Unknown variants safely degrade to `UNKNOWN`→`DOWNLOAD_FAILED` (less specific, still safe).
4. **Generic ViewModel catches show `sync_error_unknown`** for non-sync exceptions (by design — never leak `e.message`). Diagnostics available via Log.
5. **Out-of-scope `.message` reads**: `PlayerScreen` (ExoPlayer) and `EncryptedMediaStore` (`MediaExistence.IO_ERROR.reason`, storage layer — consumer wraps in resource prefix).
6. **AEAD v2 iv is 8 bytes** → birthday-bound nonce collision at ~2^32 files per key (acceptable; far beyond personal library size).
7. **No two-phase commit** across Room + EncryptedSharedPreferences + filesystem; append-only writes aren't integrity-checked post-crash (best-effort purge marker is idempotent → harmless).

## Release & Upload

> **完整发版规范见 [`docs/release.md`](docs/release.md)**（版本模型、changelog 规范、签名凭据链、故障排查、与 ocdroid/x-liker 三项目对比）。本节是速查。

**自动发版（推荐）**：`./scripts/release.sh <x.y.z>` → 校验 + **本地门禁**（`compileDebugKotlin + testDebugUnitTest`）+ 自动生成**分类 changelog**（上次 tag 以来的 conventional commits 按 feat/fix/… 分节）→ 打 annotated tag → push → `release.yml` CI 出签名 APK。版本号 git 派生（见 Conventions · Versioning），无需手动 bump。

```bash
# 一次性凭据就绪（pass 为 source of truth）：
./scripts/setup-signing.sh        # pass(GPG) → local.properties（开发机签名）
./scripts/gitea/setup-secrets.sh  # local.properties → Gitea Secrets（CI 签名；keytool 校验；轮换时重跑）

# 每次发版（改动已在 main）：
./scripts/release.sh 12.1.0
# → 校验 + 本地门禁 + 分类 changelog → annotated tag + push
# → release.yml 自动 clean + lintRelease + testDebugUnitTest + assembleRelease（签名）
#   → publish-release.sh 上传 APK/mapping/SHA256SUMS + 回填 changelog 到 release body
```

`release.sh` requires a clean working tree (or `--allow-dirty`, which suffixes versionName with `-dirty`). It runs the local gate itself before tagging — you do not need to pre-run tests.

Actions: `https://git.vectory.cn:18443/mfreceiver/sync-player/actions`
Release: `https://git.vectory.cn:18443/mfreceiver/sync-player/releases/tag/v<VERSION>`

---

**手动发版（fallback，CI 不可用时）**：本地构建 + Gitea API 建 Release + 上传 asset。凭证在 `~/.git-credentials`（`credential.helper = store`）。

```bash
TOKEN=$(cat ~/.git-credentials | grep 'git.vectory.cn' | sed 's|https://[^:]*:\([^@]*\)@.*|\1|')
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk

VERSION=12.1.0
curl -s -X POST "https://git.vectory.cn:18443/api/v1/repos/mfreceiver/sync-player/releases" \
  -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
  -d "{\"tag_name\":\"v$VERSION\",\"target_commitish\":\"main\",\"name\":\"v$VERSION\",\"body\":\"## 修复\n- ...\n\n## 新功能\n- ...\",\"draft\":false,\"prerelease\":false}"
# 返回 JSON 中 "id" 即 RELEASE_ID

curl -s -X POST "https://git.vectory.cn:18443/api/v1/repos/mfreceiver/sync-player/releases/RELEASE_ID/assets" \
  -H "Authorization: token $TOKEN" \
  -F "attachment=@app/build/outputs/apk/release/app-release.apk" -F "name=app-release.apk"
```
