# Android Quality Audit

- **Run ID**: 20260916-0843 (round 5 of the android-quality lane)
- **Baseline**: `main @ ea623dbfc74296c8365ff466c03162e2fd7c0570`, working tree clean, up to date with `origin/main`
- **Method**: main agent (profile/git/gates/main-agent recheck of every candidate) + 4 sub-agents (Lifecycle/State/Navigation; Coroutines/Threads/Resources/Performance; Compose/View/A11y; Data/Build + adversarial). Candidate gate:

```text
DISCOVERED → ANDROID_MECHANISM_CONFIRMED → STATICALLY_PROVEN
→ ADVERSARIALLY_REVIEWED → MAIN_AGENT_RECHECKED → VERIFIED
```

Numbering continues the project convention: this round produces **AND-056..090** (prior rounds: AND-001..022 fixed, AND-023..038 tracked, AND-039..055 fixed — see Prior Findings Status).

## Executive Summary

Fifth deep audit pass. The codebase is heavily pre-hardened — all 33 prior findings are fixed or mitigated (verified individually against HEAD). This round found **one HIGH** and a set of mostly MEDIUM/LOW residuals concentrated at seam surfaces (overlay input routing, nav-callback semantics, gesture-loop termination, DataStore failure paths, one-shot storage migration):

- **AND-056 (HIGH)** — the app-lock overlay is a plain `Box` that consumes no input: while the UI reads "locked", touches fall through to `AppNavHost` (playback gestures, long-press delete menus) and TalkBack can traverse + activate everything underneath. Same defect class as fixed AND-048, but on the security boundary.
- **AND-057 (MEDIUM)** — `popBackStack(list, inclusive=true)` after save pops *past* the list onto Settings; on the onboarding entry path the list isn't in the stack at all, navigation silently no-ops and the re-enabled Save button inserts **duplicate rows** per tap (`@Upsert` + `id=0`).
- **AND-058 (MEDIUM)** — ImageViewer gesture loop never terminates when the first-down pointer lifts while a second is still pressed: swipe/zoom permanently dead for the composition (~half of natural pinch endings).
- **AND-059 (MEDIUM)** — no DataStore flow or read in `UserPreferencesRepository`/coordinator has error handling (except the deliberately guarded `secureLockEnabled`): a corrupted `player_prefs` crashes the app wherever prefs are collected/read.
- **AND-061 (MEDIUM)** — the one-time v4 orphan purge writes its done-marker *best-effort after* `deleteAllMedia()`; a failed marker write makes the next launch re-wipe the entire media store, including DB-referenced files downloaded meanwhile.
- **AND-060 (MEDIUM)** — Search-context-menu "Delete" is an immediate irreversible hard delete (file + thumb + DB row), no confirm/undo — asymmetric with Browser.
- **AND-062 (MEDIUM)** — `launchMode=standard` lets a launcher re-tap stack a second `MainActivity` (fresh NavHost/VMs) on the task.
- Remaining ~28 findings are LOW/INFO (lifecycle gaps, UX polish, hygiene).

No crash-on-normal-path or silent-corruption findings beyond the above; all six security red lines re-verified intact.

## Project Profile

| Item | Value |
|---|---|
| Language | Kotlin 2.2.10 (K2 + compose compiler), zero Java sources |
| UI | 100% Compose (Material3, BOM 2025.12.00); no Fragments/XML layouts |
| Build | AGP 9.1.0 / Gradle 9.3.1 / KSP 2.3.6; jvmTarget 17 |
| SDK | minSdk 34 / targetSdk 35 / compileSdk 35 |
| Modules | single `:app` (`cn.vectory.syncplayer`), 216 main + 84 test Kotlin files |
| Architecture | MVVM + Repository (player = MVI-ish multi-StateFlow), Hilt 2.59.2, Single Activity |
| Navigation | Navigation-Compose 2.9.0; startDestination Browser; shared `VideoQueue`/`ImageQueue` singletons + SavedStateHandle mirror for id lists |
| Networking | no HTTP stack; jcifs-ng 2.1.10 (SMB) + sshj 0.39.0 (SFTP), blocking I/O, ~5s timeouts, TOFU host-key verifier |
| Persistence | Room 2.7.0 schema v4 (destructive migration convention) + DataStore-Preferences; EncryptedSharedPreferences for source credentials |
| Media | Media3/ExoPlayer 1.6.0 via `GesturePlayerView` AndroidView; Coil 2.7.0; MediaMetadataRetriever seek previews |
| Coroutines | 1.10.1; no GlobalScope; owned scopes: `appScope`, `serviceScope`, `coordinatorScope` (Main.immediate confined), `progressSaveScope` (player + imageviewer), `prefetchScope`, undo safety scope |
| Background | `SyncService` (dataSync FGS, `onTimeout` both overloads) + `SyncServiceDrainCoordinator`; no WorkManager; `MediaContentProvider` exported w/ signature permission |
| Security | Biometric app lock (ProcessLifecycleOwner), FLAG_SECURE, EncryptedSharedPreferences, SSH TOFU |
| Tests | 84 JVM test classes (JUnit4 + MockK + Turbine + Robolectric Compose), 4 androidTest classes |
| Manifest highlights | `configChanges=orientation|screenSize|smallestScreenSize|density|keyboardHidden|layoutDirection|locale` (rotation does **not** recreate; `uiMode`/`fontScale` **do**); no `launchMode` (standard); `allowBackup=false`; POST_NOTIFICATIONS requested at runtime (AND-029 fix) |

## Git Baseline

```text
branch: main (synced with origin/main)
HEAD:   ea623db test(storage): skip real-FS permission-denied probe when EACCES unenforceable
status: clean
```

Recent history is dominated by landing the previous audit rounds: `lane A–D audit fixes`, `F-001..F-005`, `N-001/2`, `R1..R5` player revisions. Highest-churn files: `PlayerScreen.kt` (18), `PlayerViewModel.kt` (11), `AuroraPlayerUi.kt` (9) — consistent with this round's remaining findings clustering around gesture/state seams.

## Build / Test / Lint Results

```text
env:  JAVA_HOME=D:\codeDev\.audit-tools\jdk-21.0.2  ANDROID_HOME=D:\codeDev\.audit-tools\android-sdk
      (AGENTS.md's Linux paths are stale for this machine; local.properties sdk.dir is the effective one)
./gradlew compileDebugKotlin   → BUILD OK (exit 0)
./gradlew testDebugUnitTest    → BUILD OK — 84 test result classes, 0 failures
./gradlew lintDebug            → BUILD OK — 0 errors (abortOnError gate passes); ~10 informational warnings
not run: assembleRelease (signing creds out of scope), androidTest (no device)
```

## Runtime Validation Environment

```text
No device/emulator runtime validation available.
```

No AVD/emulator components or attached devices on this host. Every finding is a **static mechanism proof** (call-path tracing + framework-semantics checks); items needing device confirmation are marked `RUNTIME-GAP`. No scenario (rotation, dark-mode toggle, process death, TalkBack, slow network) was physically executed.

## Verified Findings

### AND-056 — App-lock overlay consumes no input: touches and the accessibility tree reach the live app underneath

**Severity**: High
**Confidence**: 0.9
**Affected Screen / Component**: LockScreenOverlay (app lock)
**Source Location**: `security/LockScreenOverlay.kt:85-92` (root Box), `:83` (BackHandler); mount site `MainActivity.kt:107-113`

**Android Mechanism**: In Compose, hit testing only collects nodes carrying pointer-input handlers. The overlay root is `fillMaxSize().background().statusBarsPadding().navigationBarsPadding()` — **no `pointerInput`/`clickable`**. It draws opaquely on top of `AppNavHost` but is not a hit target, so touches in its dead area dispatch to whatever lies below: browser `combinedClickable` items, `GesturePlayerView` (tap/double-tap/seek/volume/brightness — including prev/next media switch and the trash context menu), player controls. The semantics tree is likewise merged — TalkBack can read and activate all underlying nodes, a usable interface while "locked". `BackHandler` blocks only back; `FLAG_SECURE` blocks capture, not interaction. BiometricPrompt is modal only while displayed; between failure/cooldown states the underlying UI is fully operable.

**Lifecycle / State Ownership**: `AppLockManager.isLocked` (process scope) gates a sibling conditional composition, not a separate window/dialog.

**Trigger**: any locked state (cold start with lock enabled, background→foreground, manual lock) over interactive content — worst while a video is playing or mid-gesture.

**Evidence** (adversarially reviewed — C1 CONFIRMED): root Box carries no pointer modifier; the only barriers are `BackHandler(true)` and the opaque background. Compare `PlayerScreen.kt:713-715` where the error overlay *does* add `pointerInput { detectTapGestures {} }` (fixed AND-048) — the identical defect was left on the lock.

**Expected**: locked state intercepts all input and hides underlying content from a11y.
**Actual**: visual occlusion only; full interactive + a11y access underneath. Playback audio also keeps playing under the lock (player pauses only on ON_STOP — `PlayerScreen.kt:332-352`).

**User Impact**: the app lock is functionally bypassable for interaction (blind taps on known layouts; full control via TalkBack). Confidentiality still holds for the eyes — but a lock that accepts input is not a lock.

**Root Cause**: overlay implemented as sibling composable rather than a modality boundary (Dialog/Popup with its own semantics pane, or a consuming pointer barrier).

**Git Context**: overlay predates the AND-048 fix pattern (file last touched `1c1e236`/`178ef61` — those fixed cooldown/state, not input).

**Recommended Fix**: (a) add a consuming gesture barrier on the root — `pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); /* consume all changes */ } }` or `clickable(enabled=false){}`-style; (b) hide the underlying tree: wrap `AppNavHost` in `Modifier.semantics { hideFromAccessibility() }` while `isLocked`, or mount the lock in a real `Dialog`/popup (own semantics pane + input isolation for free). Consider pausing playback on lock.

**Regression Test**: Compose test — place a clickable counter under the overlay, inject tap, assert counter unchanged; assert `SemanticsNodeInteraction` for underlying nodes not found when locked.

**Adversarial Review**: "BiometricPrompt is modal" — refuted as mitigation: it is modal only while displayed; on error/cooldown it dismisses and the overlay remains with input pass-through. "FLAG_SECURE" — capture-only. Confirmed.

---

### AND-057 — `popBackStack(list.route, inclusive=true)` overshoots the list; onboarding path saves duplicate rows

**Severity**: Medium
**Confidence**: 0.9
**Affected Screen / Component**: SyncSourceEdit / SyncRelationEdit save navigation
**Source Location**: `navigation/AppNavHost.kt:185,214`; `settings/SyncSourceEditScreen.kt:240-245,170-177`; `settings/SyncSourceEditViewModel.kt:140-155,179-187`; `data/local/dao/SyncSourceDao.kt:23` (`@Upsert`); `data/local/entity/SyncSourceEntity.kt:8` (`autoGenerate`)

**Android Mechanism**: `popBackStack(route, inclusive=true)` pops entries up to **and including** the matching destination.

**Trigger A**: Settings → SyncSourceList → Edit → Save → pops SourceEdit *and* SourceList → lands on **Settings**, not the list the callback name promises. Same for relation edit (`:214`).

**Trigger B** (worse): onboarding card → `Route.SyncSourceEdit.createNew()` directly (`AppNavHost.kt:98-103`, `FirstRunOnboardingCard` primary CTA). Stack `[Browser, SourceEdit]` has no SourceList → `popBackStack` returns false → no navigation. The user remains on a "saved" form; `isSaving` resets in `finally`, the button re-enables (`enabled = !isSaving && name.isNotBlank() && host.isNotBlank()`), `buildSource()` keeps `id=0`, and `@Upsert` with `autoGenerate` id=0 is an INSERT each tap → **duplicate source/relation rows**, each with its own stored credential.

**Expected**: land on the list; no duplicate persistence on repeat save.
**Actual**: overshoot to Settings (path A); stuck screen + duplicate rows (path B).

**User Impact**: confusing navigation; duplicate sync sources (each carrying an encrypted password entry) created silently on a first-run path.

**Root Cause**: `inclusive=true` chosen for "return to list" semantics without accounting for the no-list stack; no post-save UI lock.

**Recommended Fix**: `inclusive=false` (or plain `popBackStack()`), and disable Save while `saveResult is Saved` (or `viewModelScope`-guard on a saved flag).

**Regression Test**: nav test — stack `[Browser, SourceEdit]` → save → assert pop attempt result/landing destination; unit test — two consecutive `save()` calls do not double-insert (or assert button disabled after Saved).

**Adversarial Review**: "Upsert with id=0 updates" — refuted: autoGenerate PK means 0 matches no row → insert. "Button stays disabled" — refuted: `isSaving=false` in `finally`, no saved-gate. Confirmed.

---

### AND-058 — ImageViewer gesture loop never terminates when the first-down pointer lifts while another is down

**Severity**: Medium
**Confidence**: 0.85 (mechanism static-certain; frequency RUNTIME-GAP)
**Affected Screen / Component**: ImageViewerScreen swipe/pinch-zoom
**Source Location**: `imageviewer/ImageViewerScreen.kt:262-341`

**Android Mechanism**: `awaitEachGesture` inner `do{ awaitPointerEvent() }while(true)` exits **only** via the `pointerCount==1` branch finding `firstDown.id` with `pressed=false` (`:306-311`). A pointer-up event's `changes` list contains **all** tracked pointers (lifted one `pressed=false` plus still-pressed ones). When the first-down finger lifts while a second finger remains down: `changes.size==2` → `>=2` branch → `ptrs` (pressed-only) size 1 → pinch skipped, release never observed. Thereafter every event has `size==1` with an id ≠ `firstDown.id` → `firstOrNull{} ?: continue` spins forever. `pointerInput(Unit)` never restarts → swipe and zoom dead for the composition's lifetime; only the separate `detectTapGestures` survives, so exit remains reachable.

**Trigger**: pinch ending where the first-placed finger lifts first (~half of natural pinch terminations), or any multi-finger gesture with that lift order.

**Expected**: loop exits on "all pointers up" (`changes.none { it.pressed }`) and `awaitEachGesture` re-arms.
**Actual**: permanent dead-loop; zoom/swipe silently stop working.

**Recommended Fix**: terminate on all-pointers-up instead of matching `firstDown.id`; also reset `previousCenter/previousDistance` when pressed count drops below 2.

**Regression Test**: Compose touch-injection — down(1), down(2), up(1), up(2), then a swipe — assert `onSwipeLeft/Right` fires.

**Adversarial Review**: "changes may exclude the lifted pointer" — refuted (standard Compose semantics: the up event carries all tracked pointers). Confirmed.

---

### AND-059 — Unguarded DataStore reads: corrupted `player_prefs` crashes the app on multiple paths

**Severity**: Medium
**Confidence**: 0.8
**Affected Screen / Component**: Browser (init hydration + collected preference flows), any screen collecting prefs
**Source Location**: `browser/BrowserContentCoordinator.kt:120` (`_gridColumns.value = userPrefs.getGridColumns()` — the only unguarded read in the init block, all siblings try/catch'd); `data/repository/UserPreferencesRepository.kt:20` (`preferencesDataStore` without `corruptionHandler`) and `:46-101` (every flow is bare `dataStore.data.map{}` — zero `.catch`)

**Android Mechanism**: `DataStore.data` throws `CorruptionException`/`IOException` on a corrupt/unreadable preferences file. With no `corruptionHandler` and no `.catch`, the exception propagates: (a) through `getGridColumns()` inside the coordinator's init `scope.launch` → uncaught in `viewModelScope` (SupervisorJob → platform handler) → crash; (b) through `stateIn`-collected flows (`BrowserViewModel.hasConfig`, `firstRunState`, `onboardingDismissed`, `libraryEmpty`) → collecting coroutine failure → crash on Browser open.

**Trigger**: DataStore file corruption (interrupted write, disk fault) — rare but this is exactly the population a personal-use media app hits after unclean shutdowns.

**Expected**: fail to defaults (the codebase's own precedent: `AppLockManager` wraps `secureLockEnabled` in `retryWhen` + fail-closed, `SettingsViewModel` guards similarly).
**Actual**: process crash wherever prefs are touched.

**Recommended Fix**: add `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }` to `preferencesDataStore` (single-point fix for all flows), and try/catch `getGridColumns()` like its siblings.

**Regression Test**: instrumented/JVM — corrupt prefs file → assert flows emit defaults; coordinator init test with throwing prefs mock.

**Adversarial Review**: "stateIn catches upstream errors" — refuted: `stateIn`/`launchSharing` does not swallow flow exceptions; the collecting coroutine fails into the scope's CEH. Confirmed.

---

### AND-060 — Search-result "Delete" is an immediate irreversible hard delete — no confirmation, no undo

**Severity**: Medium
**Confidence**: 0.9
**Affected Screen / Component**: SearchScreen file context menu
**Source Location**: `browser/SearchScreen.kt:286-295` (no `onTrashed`/`onDeleted` passed); `ui/components/ItemContextMenu.kt:71-77` (fallback dispatch), `:204-216` (delete button, no confirm step); `browser/SearchViewModel.kt:167-169` → `browser/FileOperationsUseCase.kt:196-220` → `deleteMediaByIdLocked`

**Android Mechanism**: `FileContextMenuDialog`'s documented fallback calls `actions.deleteFile(id)` when the host supplies no undo-aware callback — which SearchScreen doesn't. One tap deletes the media file + thumbnail + DB row under `storageOperationMutex`, immediately. Browser routes the identical menu item through `UndoDeleteUseCase.performHardDelete` (deferred + undo + dispose safety commit).

**Trigger**: long-press a search-result file (outside a sync base dir — the menu only renders delete for `!isFileInBaseDir`) → tap Delete.

**Expected**: consistent destructive-action UX — the same affordance shouldn't be undoable in one screen and instant-irreversible in another.
**Actual**: permanent deletion in one tap.

**Recommended Fix**: pass `onTrashed`/`onDeleted` in SearchScreen reusing the browser snackbar helpers, or add a confirm step to the fallback path.

**Regression Test**: `ItemContextMenuDispatchTest`-style unit test asserting the fallback is never used by SearchScreen; or host-level test asserting `onDeleted` non-null.

**Adversarial Review**: "deliberate documented fallback" — the fallback is deliberate, but no user-facing consequence was documented; asymmetry is the defect. Confirmed.

---

### AND-061 — Failed purge marker write → next launch re-wipes the entire media store, including live content

**Severity**: Medium (low trigger probability, data-loss consequence)
**Confidence**: 0.85
**Affected Screen / Component**: one-time v4 upgrade orphan purge
**Source Location**: `storage/MediaFileStore.kt:227-239` (`purgeOrphansIfNeeded`), `:214-220` (`deleteAllMedia` — name-pattern wipe, **no DB-reference check**), `:242-248` (`isOwnedDirectory`); caller `SyncPlayerApp.kt:46-59` (every cold start until marker exists)

**Mechanism**: `purgeOrphansIfNeeded` = `if (marker.exists()) return` → `deleteAllMedia()` → best-effort `marker.writeBytes` (failure swallowed). If the marker write fails (disk-full, transient I/O), the marker never exists → next `onCreate` re-runs `deleteAllMedia()` — which deletes **every** numeric-bucket dir + `thumbs`, i.e. all media including files downloaded after the first purge that are still referenced by DB rows → broken library (rows point to missing files; subsequent sync may not re-download because diff keys off DB state).

**Trigger**: marker write fails on the launch that performs the purge; user syncs/downloads before next launch.

**Expected**: purge idempotent w.r.t. legit content, or marker-first ordering.
**Actual**: unbounded re-purge scope.

**Recommended Fix**: write the marker **before** `deleteAllMedia()` (worst case then is leftover orphan bytes — benign), or scope deletion to DB-unreferenced files, or gate on a migration flag rather than filesystem marker.

**Regression Test**: fake marker-write failure → assert second `purgeOrphansIfNeeded()` doesn't delete a file referenced by a DB row (or doesn't run).

**Adversarial Review**: "marker write can't fail after successful delete" — possible (ENOSPC cleared by the delete itself helps, but transient FS/SELinux errors exist); consequence asymmetry favors marker-first. Confirmed (LOW trigger / real impact).

---

### AND-062 — `launchMode=standard` allows duplicate MainActivity instances on launcher re-launch

**Severity**: Medium
**Confidence**: 0.8 (mechanism standard; OEM variance RUNTIME-GAP)
**Affected Screen / Component**: MainActivity / task structure
**Source Location**: `AndroidManifest.xml` activity declaration (no `launchMode`)

**Mechanism**: with default `standard` launchMode, tapping the launcher icon while the task exists pushes a **new** MainActivity on top — fresh NavHost at startDestination Browser, fresh ViewModels — while the old instance (potentially a paused Player screen) sits below. Back-press reveals the stale instance. Notification intents are clean (`FLAG_ACTIVITY_SINGLE_TOP|CLEAR_TOP` → `onNewIntent`), so only the launcher path duplicates.

**Expected**: single instance (`singleTask` for a single-activity app, or `singleTop`).
**Actual**: duplicate instance per launcher re-tap.

**Recommended Fix**: `android:launchMode="singleTask"` (or `singleTop` + existing onNewIntent handling which already tolerates reuse).

**Regression Test**: none practical on JVM; adb `am start` twice → `dumpsys activity` count check.

**Adversarial Review**: the notification path was specifically checked and is clean; this is launcher-only. Confirmed.

---

### AND-063 — Position poll loop keeps running while the app is stopped (background wakeups + 30s Room writes)

**Severity**: Medium-Low
**Confidence**: 0.85
**Source Location**: `player/PlayerScreen.kt:298-328`

**Mechanism**: `LaunchedEffect(uiState.isPlaying)` is bound to composition, **not** lifecycle. On ON_STOP: the observer pauses ExoPlayer (`:334-336`) → `onIsPlayingChanged(false)` → VM `_uiState` updates — but `collectAsStateWithLifecycle` froze the composition's snapshot at `isPlaying=true`, so the effect never sees the key change and the `while(true)` 200ms loop runs for the whole background stint: `exoPlayer.currentPosition` polls, StateFlow writes (conflated), and a `saveProgress` Room write every 30s — until ON_START or process death.

**Expected**: polling stops when paused/stopped (e.g. collect a lifecycle-unfrozen source, or check `exoPlayer.isPlaying` live each tick and break).
**Actual**: unbounded background polling + periodic DB writes. Harmless data-wise; battery/thermal waste.

**Recommended Fix**: inside the loop, `if (!exoPlayer.isPlaying) break`, or key the effect on a lifecycle-aware state.

---

### AND-064 — Gesture-seek base goes stale while paused; first gesture seek after a paused scrub anchors at the pre-pause position

**Severity**: Medium-Low
**Confidence**: 0.85
**Source Location**: `player/PlayerScreen.kt:315` (sole `gestureView.currentPosition` write, inside the isPlaying-only loop); `player/GesturePlayerView.kt:180` (`seekBasePositionMs = currentPosition`)

**Mechanism**: `GesturePlayerView.currentPosition` is only refreshed by the 200ms poller while playing. Paused seeks — progress-bar scrub (`onProgressScrubFinished`→`seekTo`), ±10s (`seekBy`), error-retry — move ExoPlayer but not the view field. Next horizontal gesture captures the stale base → preview and commit are offset from the *old* position, jumping the playback head back.

**Expected**: anchor at actual position — sync `gestureView.currentPosition` in `onPositionDiscontinuity`/scrub-finish.
**Actual**: stale base on the pause→scrub→gesture sequence.

---

### AND-065 — `searchByFileName` LIKE patterns don't escape user-typed `%`/`_`

**Severity**: Low-Medium
**Confidence**: 0.9
**Source Location**: `data/local/dao/VideoItemDao.kt:166,179` (`LIKE '%'||:query||'%'` unescaped)

**Mechanism**: user input is bound (no injection) but `%`/`_` act as LIKE wildcards → wrong search results for queries containing them (common in media names: `one_piece`, `100%`). The codebase's own fix pattern exists at `SyncRelationDao.kt:36-37` (`ESCAPE '\'` + REPLACE escaping).

**Recommended Fix**: same ESCAPE+REPLACE treatment.

---

### AND-066 — App lock is a dead end when no authenticator is enrolled

**Severity**: Low-Medium (fail-closed, but zero recovery UX)
**Confidence**: 0.8
**Source Location**: `security/LockScreenOverlay.kt:72` (auto-auth only `if canAuthenticate`), `:115` (button only `if authFailed || isCooldown` — unreachable when auth never ran); `security/AppLockManager.kt` fail-closed design

**Mechanism**: `secureLockEnabled=true` + `BiometricManager.canAuthenticate()!=SUCCESS` (user removed all biometrics + device credential, or `BIOMETRIC_ERROR_HW_UNAVAILABLE`) → overlay shows lock text, no button, back blocked → permanent lockout; recovery only via clearing app data. Deliberately fail-closed, but no explanation or escape (e.g. link to system security settings, or a visible "no authenticator" state).

---

### AND-067 — Browser folder position and scroll memory lost on process death

**Severity**: Low
**Confidence**: 0.85
**Source Location**: `browser/BrowserContentCoordinator.kt:59` (`_currentFolder` plain StateFlow); `browser/BrowserViewModel.kt:113` (scroll map); `browser/BrowserScreen.kt:130-134` (re-seeds from nav arg only when VM folder empty)

**Mechanism**: drill-in lives only in the VM (no SavedStateHandle); after process death the restored entry re-seeds from its original `folder` arg — user returns to that folder (or root), not where they were; scroll memory empty. Rotation is unaffected (configChanges). LOW because process death is the only trigger and the cost is navigation steps.

---

### AND-068 — `clearError()` in a cancelled effect's `finally` can wipe a newer queued error

**Severity**: Low
**Confidence**: 0.8
**Source Location**: `browser/BrowserScreen.kt:412-419`, `player/PlayerScreen.kt:560-568`, `imageviewer/ImageViewerScreen.kt:~145-153`

**Mechanism**: `LaunchedEffect(ev.seq) { try { showSnackbar } finally { clearError() } }` — error B emitted while A shows → recomposition cancels A → A's `finally` unconditionally clears `_error` → B's effect is removed/cancelled mid-display → B lost or flashed. The AND-047 fix added `try/finally` (correct for replay) but the unconditional clear now races the seq chain.

**Recommended Fix**: `clearError(expectedSeq = ev.seq)` — only clear if still current.

---

### AND-069 — Older sync-completion notifications navigate to the newest synced folder

**Severity**: Low
**Confidence**: 0.9
**Source Location**: `sync/SyncNotificationHelper.kt:160-173`

**Mechanism**: folder PendingIntent uses fixed `requestCode=COMPLETE_NOTIFICATION_ID` + same action + `FLAG_UPDATE_CURRENT`; extras aren't PI identity → each new completion overwrites the shared PI's extras → tapping an older completion notification opens the newest folder. (The sync-preview PI correctly varies requestCode by relationId.)

**Recommended Fix**: vary requestCode by a folder hash, or accept as cosmetic.

---

### AND-070 — SyncPreview observer gaps: post-init sync starts unobserved; `startSync` throw leaves `_isSyncing` stuck

**Severity**: Low
**Confidence**: 0.75 (RUNTIME-GAP for the redelivery path)
**Source Location**: `sync/SyncPreviewViewModel.kt:78-84` (observer armed only if already-running at init), `:162-187` (`_isSyncing=true` at `:177` before `SyncService.start` at `:179` — a throw leaves it true with no observer; the catch sets `_error` but not `_isSyncing=false`)

**Mechanism**: (a) process-death restore where `START_REDELIVER_INTENT` re-runs the sync after the restored VM already initialized → `_isSyncing=false`, no observer → completion never picked up until re-entry; (b) `SyncService.start` throwing (e.g. transaction failure) leaves the button in a stuck "syncing" state; (c) small double-tap window before `_isSyncing` is set inside the launched coroutine (harmless — drain dedupes).

---

### AND-071 — Shared-queue vs SavedStateHandle ordering race on rapid double navigation

**Severity**: Low
**Confidence**: 0.7 (RUNTIME-GAP — sub-frame window)
**Source Location**: `navigation/AppNavHost.kt:66-76,252-261`; `player/PlayerViewModel.kt:237-241` (prefers `videoQueue.consumeIds()`)

**Mechanism**: `queue.setIds(B)` can land after `navigate(A)` but before VM-A construction → VM-A consumes B's list while showing video A → prev/next walks the wrong list. Two rapid different-video taps defeat `launchSingleTop`. ImageViewer path identical (no DB fallback, but benign — empty prev/next).

**Recommended Fix**: carry ids only via SavedStateHandle, or key the queue payload to the target videoItemId and drop mismatches.

---

### AND-072 — ImageViewer swipe threshold is 60 **px**, not dp, with no velocity gate

**Severity**: Low (RUNTIME-GAP for perceived sensitivity)
**Source Location**: `imageviewer/ImageViewerScreen.kt:263,331-335`

**Mechanism**: `swipeThreshold=60f` compares against accumulated px; at ~3x density ≈20dp — small drags at `scale<=1f` (or zoomed pan edge with `canSwipeHorizontal`) switch image on release. Recommended: `with(density){60.dp.toPx()}` + optional fling velocity.

---

### AND-073 — `SimpleDateFormat` allocated per list-item composition in image metadata lines

**Severity**: Low
**Source Location**: `ui/util/FormatUtils.kt:147,170` (constructed inside per-item calls)

**Mechanism**: per-visible-row, per-recomposition allocation of a nontrivial formatter during scroll. Fix: hoist/remember (composition is main-thread; a file-level or remembered instance is safe — precedent `SyncRelationListScreen.kt:56`).

---

### AND-074 — Staggered-view-mode glyph draws hardcoded white — invisible on light theme

**Severity**: Low
**Source Location**: `browser/BrowserSortFilter.kt:249-255` (`Canvas` draws `Color(0xFFFFFFFF)` bars); `ui/theme/Theme.kt:40-69` (a real light scheme exists — reachable)

**Recommended Fix**: draw with `LocalContentColor.current` (the chip already sets it for `Icon` children).

---

### AND-075 — Scrollbar right-edge drag strip and lazy-container drag can co-drive scroll; one coroutine per move event

**Severity**: Low-Medium (RUNTIME-GAP for perceived jank)
**Source Location**: `ui/components/VerticalScrollbar.kt:122-143` (three isomorphic variants)

**Mechanism**: `pointerInput(state)` on the container modifier: down in rightmost 16dp → `down.consume()` + `scope.launch { state.scrollToIndex(progress) }`, then per move event another launch (`:138`). Main-pass ordering lets the lazy container's own drag detector also engage the same `LazyListState` → absolute index-jumps fighting incremental drag deltas → jumpy scrolling in the strip; plus per-event coroutine churn (scroll mutex serializes them). Whether the container drag actually co-drives depends on consumption semantics — RUNTIME-GAP; the per-event `scrollToIndex` launches are static-certain.

**Recommended Fix**: drive scroll via a single `scroll{}`-mutual-exclusion drag channel, or `PointerEventPass.Initial` handling.

---

### AND-076 — Settings volume-balance row: row-tap navigates while its Switch toggles — ambiguous affordance

**Severity**: Low
**Source Location**: `settings/SettingsScreen.kt:147-158` (`Modifier.clickable { onNavigateToGainAnalysis() }` + trailing `Switch` → `setVolumeBalance`)

**Mechanism**: two unrelated actions on one row; TalkBack presents a clickable row + unrelated switch. Fix: remove row-level clickable (navigate via dedicated affordance) or make the row toggle the switch.

---

### AND-077 — DirectoryTreeDialog: shared `loadingPath` races concurrent expands; card fixed at 360dp

**Severity**: Low
**Source Location**: `ui/components/DirectoryTreeDialog.kt:69-86` (`loadingPath` nulled by whichever load finishes first → other's spinner disappears early), `:108` (`width(360.dp)` — not `widthIn(max=…)`, can clip on narrow windows/multi-window)

---

### AND-078 — DebugLogScreen auto-scroll fights user scrollback while logs stream

**Severity**: Low
**Source Location**: `settings/DebugLogScreen.kt:52-57` (500ms poll), `:135-138` (`LaunchedEffect(filteredEntries.size) { animateScrollToItem(lastIndex) }` unconditional)

**Recommended Fix**: follow-only-when-at-bottom (`canScrollForward == false`).

---

### AND-079 — `AboutScreen` (incl. its sync-log viewer) is unreachable dead UI

**Severity**: Low (hygiene)
**Source Location**: `settings/AboutScreen.kt`; no route in `Route.kt`/`AppNavHost.kt`; `SettingsViewModel.syncLogs`/`clearLogs` consumed only there

**Recommended Fix**: wire a route/entry or delete the screen + VM plumbing.

---

### AND-080 — SearchScreen error snackbar lacks `try/finally` → replays on recreation

**Severity**: Low
**Source Location**: `browser/SearchScreen.kt:305-310` (`showSnackbar` then `clearError`, no finally)

**Mechanism**: residual of fixed AND-047 — recreation during display cancels the effect before `clearError` → the StateFlow event persists → replayed in new composition. Fix: same `try/finally` as siblings.

---

### AND-081 — `EncryptedSharedPreferences.commit()` executes on the caller (main) dispatcher

**Severity**: Low
**Source Location**: `data/repository/SyncSourceRepository.kt:65-67,85-87,108`; callers `SyncSourceEditViewModel.kt:141,158` (`viewModelScope` = Main)

**Mechanism**: synchronous XML write + AES + fsync on main per save/delete. `commit()` is correctly required for durability ordering (adversarial pass confirmed both failure directions roll back properly — C28 REFUTED); only the dispatcher is wrong. Fix: wrap repo bodies in `withContext(ioDispatcher)`.

---

### AND-082 — Shared companion `SimpleDateFormat`s raced by concurrent download lanes

**Severity**: Low
**Source Location**: `sync/ImageMetadataExtractor.kt:68-69,141-151`; reached concurrently via `SyncDownloadPipeline.kt:217-218` (`repeat(maxConcurrency){ launch(ioDispatcher) }`, ≤3 lanes)

**Mechanism**: `SimpleDateFormat.parse` is not thread-safe → corrupted calendar state → wrong `dateTaken` or ParseException (caught → null). Bounded impact; fix = per-call formatter or `java.time`.

---

### AND-083 — `dryRun`/`setPendingTask` block for a running sync's full duration → open-ended spinner

**Severity**: Low
**Source Location**: `sync/SyncEngine.kt:149-151,101-105` (both under `storageOperationMutex`); `sync/SyncPreviewViewModel.kt:115-160` (`loadPreview` shows only `_isLoading`)

**Mechanism**: opening preview while a long sync runs suspends until it finishes — indefinite spinner, no "sync in progress" state. Serialization is correct (TOCTOU guard); only UX is absent. Fix: surface `progress.isRunning` as a queued/disabled state.

---

### AND-084 — Cold-start `runBlocking` DataStore read on main (≤2s, fail-closed)

**Severity**: Low — documented tradeoff, kept for completeness
**Source Location**: `security/AppLockManager.kt:153-171`

Bounded by `withTimeoutOrNull(2000)`, warm path non-blocking, deliberately fail-closed + tested. Below ANR threshold.

---

### AND-085 — Logcat hygiene: fileName/URI/Throwable reach logcat at several sites (incl. release — `Log.e` is not stripped)

**Severity**: Low
**Source Location**: `sync/VideoMetadataExtractor.kt:49,71,88`; `sync/ImageMetadataExtractor.kt:112`; `audio/AudioGainAnalyzer.kt:63` (URI); `settings/GainAnalysisViewModel.kt:201-205`; `settings/MetadataRebuildViewModel.kt:183`; `imageviewer/ImageViewerViewModel.kt:152`; `sync/sftp/SyncPlayerHostKeyVerifier.kt:49,67` (hostname + fingerprints); `player/PlayerScreen.kt:285` (raw `PlaybackException` — may carry URI/provider internals)

**Mechanism**: the `SyncSafeLog` convention bars handing `Throwable`/paths to logs; these sites bypass it. The DebugLogger ring itself is sanitized; `proguard-rules.pro` strips only `Log.v/d/i/w` in release — `Log.e` (and throwable arg) survive to release logcat. User-facing surfaces remain clean (structured errors → resources); this is diagnostics-hygiene inconsistency.

---

### AND-086 — First `EncryptedSharedPreferences`/`MasterKey` construction on whichever thread first injects `SyncSourceRepository` (main, via BrowserViewModel)

**Severity**: Low (RUNTIME-GAP for magnitude)
**Source Location**: `di/DatabaseModule.kt:65-84`; injection chain `BrowserViewModel` → constructed at first frame

**Mechanism**: keystore key load/gen + prefs read on main at first composition; typically tens of ms, slower on first-ever launch.

---

### AND-087 — `SeekPreviewExtractor` leaks one `MediaMetadataRetriever` when join times out

**Severity**: Low — documented tradeoff
**Source Location**: `player/SeekPreviewExtractor.kt:113-133,144-174` (bounded 500ms join; release deliberately skipped on timeout to avoid concurrent native teardown; bounded to one instance)

---

### AND-088 — `SyncService.stop()` cold-starts the service if it isn't running

**Severity**: Low (cosmetic)
**Source Location**: `sync/SyncService.kt:193-197`; sole caller gated on `_isSyncing` (`SyncPreviewViewModel.kt:229-235`)

A stale-true `isSyncing` spins up onCreate→startForeground→ACTION_CANCEL→forced-stop ~1s later. Foreground-only caller → no background-start violation.

---

### AND-089 — `OrientationPreference` is dead code / spec drift

**Severity**: Info
**Source Location**: `data/model/OrientationPreference.kt`; `data/repository/UserPreferencesRepository.kt:48,104,124`; spec `docs/specs/architecture.md` (OrientationPreference↔OrientationMode)

**Mechanism**: repository + model + spec exist; zero consumers — `PlayerViewModel.resolveOrientation` (`:509-516`) uses only `isFullscreen`. Either unwired feature or stale API; fix = wire it or delete it + spec.

---

### AND-090 — Residual hardcoded `Dispatchers.IO` inside SFTP/SMB client `withContext` bodies

**Severity**: Info (AND-055 residual)
**Source Location**: `sync/sftp/SftpFileClient.kt:92,129,156,163,240,295`; `sync/smb/SmbFileClient.kt:96,155,180,203,242` (and siblings) — call sites, not default-params; clients don't inject dispatchers, so they're not TestDispatcher-substitutable. No runtime behavior difference.

---

## Lifecycle & State Review

Strong overall: all 100+ UI collections use `collectAsStateWithLifecycle` (zero raw `collectAsState`); notification-intent consumption is a tested identity-keyed state machine (`MainActivity.kt:189-229` + `MainActivityNavigationTest`); the player progress-save channel/scope protocol (AND-001) and load-generation gates (BUG-015) hold; `AppLockManager` cold-start read is bounded + fail-closed + generation-guarded.

Residual concentration at **seam surfaces**: overlay-as-sibling modality (AND-056), nav-callback inclusive-pop semantics (AND-057), VM-internal navigation state vs SavedStateHandle (AND-067, AND-071), and one-shot-effect bookkeeping (AND-068, AND-080).

## Coroutine / Threading Review

Excellent: CE-first catches throughout; `ensureActive()` in blocking loops (including `AudioGainAnalyzer.kt:100` — closes the old AND-051 residual); both remote clients disconnect under `IO + NonCancellable`; two-phase commit + three-state probes intact; drain coordinator is a textbook confined-domain design; 500ms progress throttle + terminal flush.

Residuals: AND-063 (lifecycle-blind poll loop), AND-082 (thread-unsafe shared formatter), AND-081/AND-086 (dispatcher placement), AND-090 (injection drift), AND-083 (mutex-held UX stall).

## Compose / View Review

State splitting genuinely effective (13 separate player StateFlows; 200ms position updates narrowly consumed). Prior Compose fixes all verified in place (graphicsLayer lambda form, scrollbar guard, DisposableEffect view-writes, try/finally errors, heightIn/FlowRow font-scale fixes, 48dp targets, a11y prev/next).

Residuals: AND-056 (lock overlay), AND-058 (gesture termination), AND-064 (stale view-field base), AND-072/073/074/075/076/077/078/079/080 (polish/hygiene), AND-068 (clear ordering).

## Navigation Review

Graph itself sound (arg handling, singleTop, popUpTo deep-link normalization, SavedStateHandle id mirrors). Residuals: AND-057 (inclusive pop overshoot + no-list stack), AND-062 (standard launchMode), AND-069 (PI extras identity), AND-067/071 (state durability/ordering).

## Persistence / Network Review

Red lines all re-verified: E8 partial-scan → no DELETE_LOCAL; two-phase download commit (`.tmp` → length-verified close → atomic rename → `verifyReadable` → 3-state `probePendingTmp`); Provider exception boundary sanitizes everything (id-only derivation, mode rejected, no path/URI/message leaks); fail-closed three-state deletion probes; credential save ordering sound both directions (only residual: unchecked `remove("pw_$id")` → possible orphaned key, harmless); indexes/FK/transaction coverage clean.

New gaps: AND-059 (DataStore corruption → crash), AND-061 (purge marker ordering), AND-065 (search LIKE escaping), AND-085 (logcat hygiene).

## Performance Review

No new hot-path defects beyond: AND-063 (background polling), AND-075 (per-event scroll launches), AND-073 (per-item formatter alloc), AND-081/086 (main-thread crypto/disk at known points). Prior performance fixes (EXISTS flow, debounces, narrow consumption, lambda graphicsLayer) verified.

## Accessibility Review

Real investment verified (48dp targets, contentDescriptions, progress semantics, resume-refresh auth capability). Residuals: AND-056 (TalkBack reaches locked content — the top a11y defect), AND-066 (no-authenticator dead end), AND-074 (invisible light-theme glyph), AND-076 (ambiguous dual-action row), AND-045 residual (fixed 360dp dialog width folded into AND-077).

## Gradle / Build Review

Signing fails loudly without creds; R8 keeps cover the actual reflection paths (BouncyCastle `{*;}`, jcifs/schmizz name keeps — `proguard-rules.pro:8,13-14`); `Log.v/d/i/w` stripped in release, `Log.e/wtf` kept (relevant to AND-085); shrinkResources safe (zero `getIdentifier`); git-derived versioning intact; `debugImplementation(compose-ui-test-manifest)` adds no launcher entry. Lint gate green.

## Rejected Hypotheses

1. **Credential ordering inconsistency (C28)** — REFUTED: `SyncSourceRepository.save` writes prefs first for edits (rolls back to previous password on DB failure) and deletes the row on prefs failure for creates; `saveMutex` serializes save/delete. No source-without-password state reachable. Residual: unchecked `remove("pw_$id").commit()` → orphaned key (harmless).
2. **`isLongPressing` never reset on ACTION_DOWN (C25)** — WEAKENED to non-issue: while `isLongPressing`, `requestDisallowInterceptTouchEvent(true)` is held; the only un-recovered stream end is view detach mid-gesture, and the view is discarded with the composition anyway.
3. **Notification intent replay/duplicate navigation** — handled by the identity-keyed consumption state machine + `removeExtra` + tests.
4. **`VideoQueue.consumeIds` double-consume** — `AtomicReference.getAndSet` (documented prior fix).
5. **VM-cleared progress-save loss** — `progressSaveScope` + closed-channel drain protocol; composition dispose precedes ViewModelStore clear.
6. **`progressSaveScope`/`prefetchScope`/coordinator scope leaks** — scopes rooted only by their owners; die with them.
7. **`SyncService.stop` via `startService` background-start violation** — callers foreground-only; service self-terminates ~1s.
8. **`Channel.UNLIMITED` growth** — bounded by start-command rate / 30s checkpoints / action counts.
9. **Metadata extractors on main** — all callers dispatch to IO.
10. **Scrollbar strip eats item taps** — Main pass reaches children first; clicks unaffected (AND-075 keeps only the dual-drag aspect).
11. **`staticCompositionLocalOf` for extended colors** — changes only on theme flip which recreates composition anyway.
12. **Save-button-after-save double-submit race** — subsumed into AND-057's larger finding (navigation no-op is what exposes the duplicate insert).
13. **`context as? FragmentActivity ?: return` fail-open in LockScreenOverlay** — currently unreachable (MainActivity is AppCompatActivity); noted as hardening nit within AND-056's fix scope.
14. **Player `onExit` popBackStack target missing → stuck** — unreachable: any popUpTo that removes Browser/Search also removes Player above them.
15. **jcifs/sshj executor thread leaks** — `cifsContext?.close()` / `sshClient?.disconnect()` under `IO + NonCancellable` exercised on all exit paths.
16. **MediaContentProvider leaks PFD/Cursor** — caller-owned per contract; exceptions sanitized.

## Inconclusive Investigations

1. **AND-075 perceived jank** — dual-drive mechanics need a device.
2. **AND-058 trigger frequency** — deadlock is certain; how often lift-order produces the shared event needs device stats.
3. **AND-072 swipe sensitivity** at high density.
4. **AND-062** actual launcher behavior on the target OEM build.
5. **AND-070(a)** redelivery timing window (process death mid-sync + restored preview).
6. **AND-086** keystore/ESP first-init cost on target hardware.
7. **Scroll-restore out-of-bounds** (`BrowserContent.kt:458-468`) — `scrollToItem` clamps OOB indices per LazyList semantics; likely non-issue, listed for completeness.
8. **jcifs transport-thread termination on failed-connection close** (minor, from agent-2 notes).

## Coverage / Device Limitations

- No emulator/device: rotation, dark-mode/uiMode toggle, fontScale, process death, recents-swipe, TalkBack, slow/offline network, permission denial flows — all static only.
- androidTest (4 classes) not run.
- Runtime perf (recomposition counts, jank, battery) unquantified — all perf findings are mechanism-derived.

## Prior Findings Status (AND-023..055 @ HEAD ea623db)

| ID | Status now |
|---|---|
| AND-023 (2-arg onTimeout) | FIXED — `SyncService.kt:138-140` delegates |
| AND-024 (intent replay) | FIXED — identity-keyed consumption + tests |
| AND-025 (prefetch/release race) | FIXED — bounded 500ms join; residual = AND-087 (documented) |
| AND-026 (SFTP disconnect dispatcher) | FIXED — `IO + NonCancellable` |
| AND-027 (audio focus/noisy) | FIXED — `setAudioAttributes(attrs,true)` + `setHandleAudioBecomingNoisy` |
| AND-028 (gesture state in main UiState) | FIXED — `gestureSeekState`/`progressScrubState`/`overlayState` split |
| AND-029 (POST_NOTIFICATIONS) | FIXED — runtime request after unlock |
| AND-030/031 (ImageViewer error seq / save scope) | FIXED |
| AND-032 (auth capability snapshot) | FIXED — `LifecycleResumeEffect` refresh |
| AND-033/040 (cooldown state) | FIXED — saveable + resume + event-driven re-auth |
| AND-034 (a11y prev/next) | FIXED — control-row buttons |
| AND-035 (36dp chip) | RESIDUAL-INFO — visual 40dp; M3 enforcement gives ≥48dp effective |
| AND-036 (composition-phase view writes) | FIXED — DisposableEffect + equality guard |
| AND-037 (delete-chain Main dispatcher) | FIXED |
| AND-038 (cold-start runBlocking) | mitigated (AND-084 documents the residual) |
| AND-039..053 | all FIXED per git + source verification |
| AND-054 (pill contrast) | FIXED — alpha 0.88 |
| AND-055 (hardcoded Dispatchers.IO) | RESIDUAL — see AND-090 |

## Prioritized Remediation Plan

1. **AND-056** — lock overlay: consuming pointer barrier + `hideFromAccessibility` on `AppNavHost` while locked (or remount as Dialog). Highest priority — security feature that doesn't secure input.
2. **AND-057** — `inclusive=false` + post-save disable; kills both the overshoot and duplicate inserts.
3. **AND-058** — all-pointers-up termination in the ImageViewer gesture loop.
4. **AND-059** — `ReplaceFileCorruptionHandler` on `preferencesDataStore` + guard `getGridColumns`.
5. **AND-061** — marker-first ordering in `purgeOrphansIfNeeded`.
6. **AND-060** — route SearchScreen delete/trash through the undo path (or confirm).
7. **AND-062** — `launchMode="singleTask"`.
8. Batch: AND-063/064 (player loop lifecycle + stale base), AND-065 (LIKE escape), AND-068/080 (error-clear ordering), AND-069/070/071 (notification/preview/queue seams).
9. Hygiene batch: AND-072..AND-090.

## Workspace Integrity

- Workspace: `docs/automatic/.work/audit-20260916/` held consolidated findings notes (`findings-notes.md`); **deleted** at run end per the prior lane's use-then-delete convention — all evidence is inlined above.
- No tracked files modified; no signing config, manifest behavior, endpoints, or versioning touched; no temporary tests created (all findings verified statically; no reproduction tests were needed to establish the mechanisms).
- Sub-agents were read-only; the shared workspace contains only this lane's notes.
- Baseline unchanged: `main @ ea623db`, tree clean.
