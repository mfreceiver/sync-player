# Agent 1 Notes — Lifecycle / State / Navigation Audit (20260824-2303)

Scope per mission: Activity/Compose lifecycle, ViewModel state ownership, Navigation, collector
lifecycle, one-shot events, SyncService lifecycle, ProcessLifecycleOwner usage.
Baseline: main @ 5c7e419 (v12.2.0). Static reading only (no build toolchain). Line numbers from
current working tree.

Architecture facts verified up front (they gate several hypotheses):
- `MainActivity` manifest declares `android:configChanges="orientation|screenSize|smallestScreenSize|density|keyboardHidden|layoutDirection|locale"` (AndroidManifest.xml:25) → rotation/density/locale do NOT recreate the Activity. Recreation only via uiMode, fontScale, etc., or process death.
- Pure Compose UI; every screen collects VM state with `collectAsStateWithLifecycle` (grep across all screens — zero `collectAsState()` hits). No `repeatOnLifecycle` needed anywhere.
- `PlayerViewModel`/`ImageViewerViewModel`/`BrowserViewModel` are scoped to NavBackStackEntries (`hiltViewModel()` inside destinations).
- Prior audit (20260823-1758) covered sync-engine cancellation semantics; nothing there overlaps my lane. Its RH-002 (null restarted SyncService intent) remains fixed via START_REDELIVER_INTENT + persisted pending actions.

---

## Candidate Findings

### F1 — Player playback position is never persisted during playback or on ON_STOP; background process kill loses all progress — CONFIRMED
- **Mechanism**: `saveProgress` (DB write via PlaybackRepository) is only called from: PlayerScreen.kt:138 (composition `onDispose`), :192 (STATE_ENDED auto-switch), :342/:354 (manual prev/next switch). The 200 ms polling loop (PlayerScreen.kt:229-252) and the ON_STOP lifecycle observer (PlayerScreen.kt:256-267) only update in-memory `_currentPosition` / pause the player. Nothing writes to Room while playing or when the app backgrounds.
- **Lifecycle owner / scope**: Activity lifecycle (ON_STOP via DisposableEffect-registered observer), PlayerViewModel.viewModelScope for the DB write.
- **Trigger**: Play a video for N minutes → press Home (ON_STOP pauses player) → system kills the cached process (routine LMK behavior) → relaunch → Navigation restores the Player destination; `loadVideo` restores `playbackRecord.position`, which is the position saved at the *last dispose or video switch* — i.e., typically the position when this video was loaded. All N minutes of watch progress lost (video restarts near the beginning / shows stale resume point).
- **Expected**: periodic checkpoint save (the 200 ms loop is a natural place, throttled to e.g. every 5-10 s) and/or a save on ON_STOP/ON_PAUSE.
- **Actual**: only dispose/switch/ended saves.
- **Evidence**: /d/codeDev/sync-player/app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:138,192,229-252,256-267,342,354; PlayerViewModel.kt:192-203.
- **Runtime-or-static**: static (call-site enumeration complete — grep for saveProgress shows no other callers).
- **Existing guard that mitigates**: none for this path; nav-back and video-switch paths DO save, which masks the bug during normal use.
- **Strongest counterargument**: "Process death mid-playback is rare." Rebuttal: ON_STOP → killed-in-background is the *normal* lifecycle for a backgrounded media browser app; the user loses progress every time they background the app during playback and the process is reclaimed.
- **Sub-risk (PLAUSIBLE, same file)**: the dispose-time save launches into `viewModelScope` from `onDispose`. On the activity-destroy path (swipe from recents), the NavBackStackEntry ViewModelStore is cleared from `ComponentActivity.onDestroy` (LifecycleObserver on ON_DESTROY), while Compose composition disposal happens at decor-view detach, i.e., after onDestroy. If that ordering holds on a given device, `viewModelScope.launch` in onDispose is a silent no-op and even the swipe-away path loses progress. Nav-back path is safe (entry VM cleared after its composition leaves). Marked PLAUSIBLE because I cannot verify framework ordering statically with certainty.
- **Verdict**: CONFIRMED (main mechanism), severity **High** for a media player whose core feature is resume-where-you-left-off.

### F2 — Playlist hand-off (VideoQueue/ImageQueue) dies with the process; PlayerViewModel's SavedStateHandle fallback is dead code; ImageViewer has no fallback at all — CONFIRMED
- **Mechanism**: Browser/Search put the folder's id list into a `@Singleton` in-memory queue (`VideoQueue`/`ImageQueue`, AtomicReference) then navigate (AppNavHost.kt:66-74, 240-246). Player/ImageViewer ViewModels `consumeIds()` in `init` (PlayerViewModel.kt:120, ImageViewerViewModel.kt:67). Singletons die with the process.
- **Dead fallback**: PlayerViewModel.kt:99-100 declares `savedStateHandle.getStateFlow("videoIds", null)` and the collector (k.129-149) is documented as "reactive read of the id list passed via SavedStateHandle (e.g. when nav pushes a new folder context)". Grep proof: **no code anywhere writes `videoIds`/`imageIds` into any SavedStateHandle** (only readers exist; nav routes carry only `videoItemId`/`returnFolder`/`fromRoute`). The flow always emits `null` → the `else` branch falls back to `videoRepository.getAllIdsSorted()` = the ENTIRE library.
- **Trigger (player)**: process death while Player is on the back stack → restoration → queue empty, SavedStateHandle empty → `_allVideoIds` = whole library sorted → next/prev (and end-of-video auto-advance) traverse the entire library instead of the folder the user was in. Silently wrong scope, plus potentially surprising auto-advance to unrelated videos.
- **Trigger (image viewer)**: same scenario, but ImageViewerViewModel has NO fallback — `_allImageIds` stays empty → `getPrev/NextImageId` return null (indexOf == -1) → double-tap left/right shows NoPrevious/NoNext and swipe navigation is dead until user backs out and re-enters.
- **Expected**: queue ids persisted (SavedStateHandle write at navigate time, or route arg, or DB re-query scoped to `returnFolder`).
- **Actual**: player = wrong-scope fallback; viewer = feature loss.
- **Evidence**: navigation/VideoQueue.kt:8 (@Singleton), player/PlayerViewModel.kt:99-150 (esp. 141 `getAllIdsSorted()`), imageviewer/ImageViewerViewModel.kt:66-71, 94-106. Route.kt:10-15, 39-44 (no ids in args).
- **Runtime-or-static**: static.
- **Existing guard**: none; the "SAVED_VIDEO_IDS_KEY" mechanism was clearly *intended* to solve this (comment says so) but the writer side was never implemented.
- **Strongest counterargument**: "Process death restoration of a player screen is an edge case." Rebuttal: it is the exact scenario SavedStateHandle exists for, the code already pretends to handle it, and the player fallback produces *wrong* behavior (whole-library traversal) rather than just degraded.
- **Verdict**: CONFIRMED, severity **Medium**.

### F3 — Starting a second relation's sync while one is running: SyncService drops the start AND relation B's preview UI stays "syncing" forever — CONFIRMED
- **Mechanism**: `SyncService.onStartCommand` returns early when `syncJob?.isActive == true` (SyncService.kt:65-67) — the new relationId's command is dropped (its pending task was already persisted to `pending_sync_action` by `SyncPreviewViewModel.startSync`, SyncPreviewViewModel.kt:161-167, so data isn't lost, but nothing will execute it: after the running sync completes the service just `scheduleStop`s).
- **Stuck UI**: `startSync` sets `_isSyncing = true` (line 168) then `observeSyncCompletion()` (180-206) awaits `syncEngine.progress.first { (p.relationId == B || p.relationId == 0L) && terminal-phase }`. Relation A's terminal progress carries `relationId = A` (SyncDownloadPipeline.kt:474-506 sets relationId), so the filter never matches; the hot StateFlow holds A's terminal value; no future emission ever satisfies B's predicate → `_isSyncing` stays true indefinitely (spinner + Stop button; Start stays disabled since `SyncPreviewScreen` gates it on `!isSyncing`, SyncPreviewScreen.kt:143-146).
- **Trigger**: Start sync on relation A → switch relation via the dropdown (`onNavigateToRelation`, AppNavHost.kt:217-222, which replaces the entry) → tap Start on relation B while A is still downloading.
- **Expected**: B either queues (executed after A) or the UI shows a clear "busy" state; at minimum `_isSyncing` resolves.
- **Actual**: B never syncs this round; B's screen shows a perpetual sync-in-progress until the user leaves and re-enters the destination (new ViewModel, init re-checks `progress.value.isRunning && relationId == B` → false → clean), then manually taps Start again.
- **Evidence**: sync/SyncService.kt:63-67,91; sync/SyncPreviewViewModel.kt:157-206; sync/SyncDownloadPipeline.kt:474-506.
- **Runtime-or-static**: static (full emitter/collector chain traced; single global progress StateFlow).
- **Existing guard**: pending-task persistence means no data loss; manual retry works.
- **Strongest counterargument**: "Personal app, one relation usually." The dropdown switcher exists precisely to hop between relations, so the scenario is one tap away.
- **Verdict**: CONFIRMED, severity **Medium**.

### F4 — No duplicate-navigation guard: double-tap on a library item (or on a notification) pushes duplicate destinations — CONFIRMED mechanism
- **Mechanism**: Item click handlers are plain `combinedClickable`/`onClick` with no throttle (BrowserContent.kt:223-231, 329-332, 392-396; SearchScreen result rows) and `AppNavHost` calls `navController.navigate(...)` with **no `launchSingleTop`** for Player/ImageViewer (AppNavHost.kt:69, 74, 242, 246), Browser-from-notification (MainActivity.kt:86, 91), and SyncPreview-from-notification (MainActivity.kt:86). A fast double-tap fires two clicks before the first navigation's transition completes → two back-stack entries with identical routes.
- **Contrast inside the same file** proving the team knows the pattern: the SyncPreview relation switcher uses `launchSingleTop + popUpTo` (AppNavHost.kt:218-221), the settings drawer nav uses `launchSingleTop` (AppNavHost.kt:59-62), and PlayerScreen/ImageViewerScreen/SyncPreviewScreen all guard their *exit* with `isExiting`/`isNavigatingBack` flags. Entry paths are unguarded.
- **Trigger**: quick double-tap on a video/image card in Browser (or Search); or double-tap a sync notification (two `onNewIntent`s → two `handleNotificationNavigation` calls → two SyncPreview entries stacked).
- **Expected**: single destination.
- **Actual**: two identical destinations; pressing back once lands on the same screen again (must back twice). For Player this also creates two PlayerViewModels/ExoPlayer instances sequentially (first is only cleared after the second is composed — transient double player creation).
- **Evidence**: navigation/AppNavHost.kt:63-92, 227-250; MainActivity.kt:54-59, 80-94; browser/BrowserContent.kt:223-231.
- **Runtime-or-static**: mechanism static; trigger requires real double-tap timing (very reproducible with fast tapping on grid items).
- **Existing guard**: none on these paths.
- **Strongest counterargument**: "Compose click handling dedupes?" No — distinct physical taps within the ~200 ms fade transition each invoke onClick; `navigate()` unconditionally appends an entry when launchSingleTop is false.
- **Verdict**: CONFIRMED, severity **Medium**.

### F5 — Secure-lock cold-start race: `cachedSecureLockEnabled` may still be false when ProcessLifecycleOwner dispatches ON_START → app opens unlocked — PLAUSIBLE (security-adjacent)
- **Mechanism**: `AppLockManager.register()` (AppLockManager.kt:43-48) launches `userPrefs.secureLockEnabled.collect { cachedSecureLockEnabled = it }` on `Dispatchers.IO`. `secureLockEnabled` is a DataStore flow (UserPreferencesRepository.kt:96-98) — first emission requires a disk read of `player_prefs`. The `@Volatile private var cachedSecureLockEnabled = false` (AppLockManager.kt:28) is the *default-false* initial value. ON_START handler (AppLockManager.kt:60-67) locks only `if (cachedSecureLockEnabled)`. On cold start, ProcessLifecycleOwner's ON_START is dispatched right after MainActivity.onStart — there is no happens-before edge forcing the DataStore read to complete first.
- **Trigger**: secure lock enabled; cold launch where the DataStore first read (IO thread, competing with `purgeLegacyCiphertext` on the same dispatcher pool, SyncPlayerApp.kt:47-59) completes after the ON_START dispatch. Content is briefly fully browsable until the next ON_STOP→ON_START cycle re-locks.
- **Expected**: lock state available before the first ON_START decision (e.g., synchronous read via the existing `getSecureLockEnabled()` at ON_START, or block first frame on the pref).
- **Actual**: unlocked cold start, nondeterministic.
- **Evidence**: security/AppLockManager.kt:28,43-48,60-67; data/repository/UserPreferencesRepository.kt:96-98,125.
- **Runtime-or-static**: static race demonstration; cannot measure hit rate without runtime.
- **Existing guard**: FLAG_SECURE always on (no screenshot/recents leak), and any subsequent background→foreground transition locks correctly. The window is the first session only.
- **Strongest counterargument**: cold start from Application.onCreate to Activity.onStart usually exceeds a DataStore read (single small file), so in practice the cache is usually warm. True — this is why PLAUSIBLE, not CONFIRMED. But the correctness of a security gate should not depend on IO scheduling latency.
- **Verdict**: PLAUSIBLE, severity **Medium**.

### F6 — Notification navigation silently dropped after process-death restore — CONFIRMED logic / Low impact
- **Mechanism**: MainActivity.kt:43 `if (savedInstanceState == null) pendingIntent.value = intent`. If the process was previously killed while backgrounded (activity state saved) and the user then taps a sync notification, the recreated MainActivity receives `savedInstanceState != null` AND the notification Intent as `intent`, but the guard skips setting `pendingIntent` → `handleNotificationNavigation` never runs → user unlocks and lands on whatever screen was restored, not the SyncPreview/folder they tapped. Warm path (`onNewIntent`, MainActivity.kt:73-77) and fresh-launch path both work; only the restore path drops it. Also: any recreation (uiMode change) while locked discards a deferred pendingIntent (plain field, not SavedState).
- **Evidence**: MainActivity.kt:34,43,73-77,80-94.
- **Verdict**: CONFIRMED (static), severity **Low** (edge path; user can navigate manually).

### F7 — LockScreenOverlay cooldown/fail-count not saveable and auto re-prompts on recreation — PLAUSIBLE Low
- **Mechanism**: `authFailed/failCount/isCooldown/cooldownRemaining` are `remember` (LockScreenOverlay.kt:28-31), not `rememberSaveable`; `LaunchedEffect(Unit)` (LockScreenOverlay.kt:59-65) auto-launches `biometricAuthManager.authenticate` on every fresh composition of the overlay. Rotation is covered by manifest configChanges (no recreation), but uiMode (system dark-theme toggle), fontScale, or process death recreate the Activity → cooldown resets and a brand-new biometric prompt fires immediately.
- **Trigger**: fail biometric 5× → 30 s cooldown starts → pull down quick settings and toggle dark theme → prompt reappears instantly, cooldown gone.
- **Mitigation**: BiometricPrompt/device-credential enforce their own system-level lockout after repeated failures, so this is a UX-level throttle bypass only.
- **Evidence**: security/LockScreenOverlay.kt:28-31,44-65; AndroidManifest.xml:25.
- **Verdict**: PLAUSIBLE, severity **Low**.

### F8 — SyncPreviewScreen: toolbar back cancels a running sync, system back does not — CONFIRMED behavior asymmetry
- **Mechanism**: The TopAppBar back button (SyncPreviewScreen.kt:90-108) calls `viewModel.cancelSync()` when `isSyncing` before `onBack()`; there is **no `BackHandler`** in SyncPreviewScreen (grep across screens shows BackHandlers only in Player/ImageViewer/LockScreen/Browser/Search), so gesture/system back pops the screen and the sync keeps running in the foreground service (which is the designed behavior — sync is service-owned and progress is notification-visible). Two back affordances on the same screen do materially different things: one destroys an in-progress background transfer, the other preserves it.
- **Trigger**: start a large sync, tap the ← arrow in the toolbar → sync cancelled; relaunch, swipe back instead → sync continues. Users will hit both randomly.
- **Expected**: consistent semantics (typically: back never cancels a service-owned sync; cancellation belongs to the explicit Stop button at SyncPreviewScreen.kt:133-141, which exists).
- **Evidence**: sync/SyncPreviewScreen.kt:90-108,133-141.
- **Verdict**: CONFIRMED (static), severity **Low-Medium** (destructive action on an ambiguous affordance).

### F9 — In-VM navigation state (browser folder depth, search query) is not process-death restorable — CONFIRMED degradation, Low
- **Mechanism**: folder browsing mutates `_currentFolder` inside BrowserContentCoordinator (BrowserContentCoordinator.kt:59, 309-311) without pushing nav entries; the nav arg folder is only re-applied when the VM starts empty (BrowserScreen.kt:126-130). After process death the destination restores with its *original* nav arg (e.g., the folder passed from Search/notification or ""), not the subfolder the user drilled into. Search query likewise lives only in `_query` StateFlow (SearchViewModel.kt:32-33).
- **Trigger**: browse A → A/B → A/B/C → process death → restore lands on A (or root).
- **Counterpoint**: this is a deliberate architecture (single Browser destination + VM folder state + BackHandler-driven navigateUp, BrowserScreen.kt:161-167) that avoids stack growth; the cost is limited process-death restoration, standard for transient browse state.
- **Evidence**: browser/BrowserContentCoordinator.kt:59,309-325; browser/BrowserScreen.kt:126-130,158-167.
- **Verdict**: CONFIRMED (static), severity **Low**.

---

## Rejected Hypotheses

- **R1 — "somewhere collects with collectAsState() / collects beyond UI lifecycle"**: REJECTED. Grep across every screen: 100% `collectAsStateWithLifecycle`; no manual lifecycle collectors in composition. PlayerScreen's high-frequency position flow is deliberately subscribed only in narrow leaf components (PlayerScreen.kt:92-98 comment).
- **R2 — "notification PendingIntent spawns duplicate MainActivity instances"**: REJECTED. SyncNotificationHelper uses FLAG_ACTIVITY_SINGLE_TOP|CLEAR_TOP + FLAG_IMMUTABLE|UPDATE_CURRENT (SyncNotificationHelper.kt:145-173); MainActivity is single-instance-per-task via standard launchMode + those flags.
- **R3 — "NavigationHelper (ViewModel wrapping the queues) leaks across destinations / outlives its store"**: REJECTED as a bug. Queues are @Singleton by design; NavigationHelper is a per-NavBackStackEntry ViewModel cleared on pop — pointless indirection, not a leak (holder lifetime is shorter than the referenced singletons, which is the intended direction).
- **R4 — "SyncService violates Android 12+ FGS start timing or type rules"**: REJECTED. `startForegroundService` (SyncService.kt:147) + immediate `startForeground` in onCreate (k.36) and re-asserted in onStartCommand (k.52); single manifest FGS type dataSync covers the no-arg startForeground on API 34/35.
- **R5 — "SyncService.stop() uses startService → IllegalStateException from background"**: REJECTED. Only caller is SyncPreviewViewModel.cancelSync (SyncPreviewViewModel.kt:210) i.e., foreground UI; while the FGS runs the app is not in the cached/background state that triggers the restriction; the ongoing notification has no cancel action, so no background entry point exists.
- **R6 — "rotation resets the lock cooldown"**: PARTIALLY REJECTED — manifest configChanges covers orientation/screenSize; downgraded to the uiMode/fontScale recreation path (kept as F7).
- **R7 — "VideoQueue/ImageQueue double-consume race"**: REJECTED. AtomicReference `getAndSet` consume (VideoQueue.kt:20), with an inline comment documenting the earlier fix.
- **R8 — "folder taps push Browser entries and grow the back stack"**: REJECTED. Folder clicks mutate VM state (`onFolderClick = viewModel::setFolder`, BrowserScreen.kt:239); new Browser entries are pushed only by Search→folder and notification→browser paths.
- **R9 — "FLAG_SECURE applied too late → content flashes into recents screenshot"**: REJECTED. Set in onCreate before setContent (MainActivity.kt:38-41).
- **R10 — "locking on ON_PAUSE can deadlock with biometric prompt (OEM onPause)"**: REJECTED — deliberately avoided; code comments document the ON_STOP choice and its reasoning (AppLockManager.kt:52-59).
- **R11 — "ExoPlayer/GesturePlayerView leaked on exit"**: REJECTED. onDispose clears the view's player reference and releases the instance (PlayerScreen.kt:139-141); SeekPreviewExtractor released via DisposableEffect (k.400-404).
- **R12 — "pending notification navigation lost while locked"**: REJECTED for the locked-but-alive case (LaunchedEffect defers until unlock, MainActivity.kt:54-59); the *recreation* loss case is real and captured as F6.
- **R13 — "Player ON_STOP fails to pause playback (audio keeps playing in background)"**: REJECTED. LifecycleEventObserver pauses on ON_STOP (PlayerScreen.kt:256-267).
- **R14 — "AppLockManager scope (SupervisorJob+IO in a @Singleton) leaks"**: REJECTED for practical impact — singleton lifetime == process lifetime by intent; the forever-collector is appropriate.

## Coverage Notes

Read fully: MainActivity, AppNavHost, Route, NavigationHelper, ImageQueue, VideoQueue, AppLockManager, BiometricAuthManager, LockScreenOverlay, SyncPlayerApp, AndroidManifest, SyncService, SyncPendingTaskManager, SyncNotificationHelper, SyncProgressPublisher, SyncEngine (head 180), SyncPreviewViewModel, PlayerViewModel, ImageViewerViewModel, BrowserViewModel, BrowserContentCoordinator, BrowserScreen, SearchViewModel, SearchScreen (partial), SyncPreviewScreen (partial), ImageViewerScreen (partial), PlayerScreen (~lines 60-520), UserPreferencesRepository (prefs section). Grepped: all collectAsState sites, all BackHandler sites, all saveProgress/consumeIds/SavedStateHandle-videoIds sites, all SyncService.start/stop callers, all click handlers in Browser/Video/FolderGridItem.

Not verifiable statically / not done:
- Android 15 dataSync FGS 6-hour quota: `onTimeout` not overridden in SyncService — behavior on timeout is system-stopped service with CE-cleanup path intact; likely acceptable for personal use, flagged informational.
- Predictive back: manifest lacks `android:enableOnBackInvokedCallback` → no system predictive-back animation on 14+/15; BackHandler-based in-app backs unaffected. Informational, not a runtime defect.
- Exact framework ordering in F1's sub-risk (VM clear vs compose disposal on activity destroy) and F5's DataStore latency — both need a runtime probe (adb or instrumentation) to grade beyond PLAUSIBLE.
- Ongoing notification exposes no cancel action (only the preview screen's Stop) — UX observation, out of my lane.
