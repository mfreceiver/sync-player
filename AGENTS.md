# Sync Player — AGENTS.md

## Environment

- **JAVA_HOME**: `/home/mar/android-studio/jbr` (Android Studio bundled JBR, OpenJDK 21)
- **ANDROID_HOME**: `/home/mar/android-sdk` (build-tools 35.0.x, platform android-35)
- **Android Studio**: `/home/mar/android-studio`
- **Project root**: `/home/mar/sync-player`
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

### Tech Stack
Kotlin 2.1.0 | Jetpack Compose + Material 3 (BOM 2025.02.00) | MVVM + Repository | Hilt 2.54 | Room 2.7.0 (schema v6) | Media3/ExoPlayer 1.6.0 | jcifs-ng (SMB) | SSHJ (SFTP) | Coroutines + Flow

### Key Directories
```
app/src/main/java/cn/vectory/syncplayer/
├── MainActivity.kt          # Single Activity
├── player/                  # ExoPlayer + gesture controls + UI overlays
├── browser/                 # Video file browser (grid/list, thumbnails)
├── sync/                    # Sync engine (SMB/SFTP), foreground service
├── settings/                # Settings screens (sync sources, playback)
├── data/                    # Room DB, DAOs, repositories, models
├── navigation/              # Route sealed class + NavHost
└── ui/theme/                # Material 3 theme + extended colors
```

## Conventions

- **Architecture**: MVVM + Repository, Hilt DI, StateFlow for ViewModel → UI
- **Single Activity**: all screens are `@Composable`, navigated via Navigation Compose
- **Player**: Media3 ExoPlayer, gesture controls (swipe seek/volume/brightness, long-press speed, double-tap actions)
  - Top bar: filename row (exit/trash) + thumbless progress bar with inline time
  - Bottom toolbar: speed/rewind/play-pause/forward/rotation (non-immersive only)
  - Overlays: iOS-style icon+text pills (volume, brightness, speed, trash IN/OUT, previous/next, orientation)
  - Orientation toggle: ROTATION (system direction) ↔ FULLSCREEN (video-matched direction), persists across videos, resets on exit
  - Playback speed (1x/1.5x/2x): persists across videos, resets on exit
  - System status bar always hidden in player
  - `progressAlwaysVisible`: shows top bar in immersive mode without bottom toolbar
- **Sync**: mirror sync (remote → local), dry-run first, foreground service
- **Versioning**: bump versionCode + versionName in `app/build.gradle.kts` after every commit+push
- **Commit style**: `feat:` / `fix:` prefix
- **Release**: tag + push tag + Gitea API create release + upload APK
- **Icon library**: `material-icons-extended` — use Material Icons, never emoji
- **UI text**: user-facing strings in Chinese, code/variables in English
