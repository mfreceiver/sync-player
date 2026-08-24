# Agent 2 Notes — Coroutines / Threads / Resources / Performance

Run ID: 20260824-2303 · Baseline main @ 5c7e419 · Static reading + git history only (no build toolchain).

Prior audit (docs/automatic/20260823-1758_deep-code-quality.md) covered transport-boundary
cancellation (CQ-001, fixed in v12.2.0 @ 31d9869). This pass avoided re-litigating that and
focused on scope ownership, dispatchers, resource lifetimes, Flow semantics, startup,
recomposition.

---

## Candidate Findings

### A2-01 · Per-file remote client creation in the download pipeline (no connection reuse; SMB connect enumerates the sync root per file)

- Mechanism: `SyncDownloadPipeline.executeSyncLocked` launches one worker per `SyncAction`; each
  worker constructs a brand-new `RemoteFileClient` (`getClient(source.protocol)` inside
  `launch(Dispatchers.IO)`) and calls `connect(source, relation.remotePath)` before downloading.
  `SmbFileClient.connect` builds a fresh jcifs `BaseContext` + credentials and probes connectivity
  with `smbFileFactory.create(smbUrl, creds).listFiles()` — a FULL enumeration of the sync-root
  directory — before every file download. `SftpFileClient.connect` performs a full TCP + SSH KEX +
  password auth + `newSFTPClient()` per file. `disconnect()`/`close()` merely null the fields
  (SMB) or disconnect the per-client socket (SFTP); no client/session pooling exists
  (`RemoteFileClientFactory.create` always constructs a new instance).
- Scope owner / trigger: SyncService.syncJob → SyncEngine.consumeAndExecute → pipeline
  coroutineScope workers. Triggered by every confirmed sync with N files.
- Expected: a per-relation or per-worker client reused across files (connect once per lane), or at
  minimum a cheap connectivity probe; observed per-file handshake + (SMB) O(E) directory
  enumeration where E = entries in the sync root.
- Actual: N × (session setup [+ root listing]) extra network round trips. For a flat root with
  E=2000 entries and N=2000 files, that is on the order of 10^6 directory-entry transfers; for
  SFTP, N SSH handshakes (~100–300 ms LAN each) ≈ minutes of pure handshake time across 3 lanes.
- Evidence: app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:165-174
  (`for (action in toActuallyDownload) { semaphore.acquire(); launch(Dispatchers.IO) {
  val workerClient = getClient(source.protocol) … workerClient.connect(source, relation.remotePath) }`),
  :197-200 (reconnect per retry — this one documented as E5 deliberate);
  app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:103-121 (fresh BaseContext +
  `listFiles()` probe per connect); app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:89-102
  (full handshake + auth per connect); app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClientFactory.kt:15-18
  (factory always news up a client).
- Runtime-or-static: code-proven mechanism (static); magnitude depends on library layout (runtime).
- Framework guard that mitigates: `MAX_CONCURRENCY = 3` bounds simultaneous handshakes;
  `RemoteScanIncompleteException` cap of 10000 files bounds N; 5 s connect timeouts cap worst-case
  per-file stall.
- Strongest counterargument: the refactor KDoc claims "逐字符等价" port from the original
  SyncEngine — this is inherited legacy structure, not a tested contract: SyncEngineTest stubs the
  factory with a relaxed mockk and asserts nothing about client lifetime. No comment marks
  per-file clients as deliberate (the only deliberate reconnect is the E5 per-retry one).
- Verdict: CONFIRMED (performance; no correctness impact). Severity: Medium for large libraries.

### A2-02 · ImageMetadataExtractor decodes full-resolution bitmaps just to read dimensions and build a 640 px thumbnail

- Mechanism: `extractAndSave` opens the decrypted content stream and calls
  `BitmapFactory.decodeStream(input)` with default options — full ARGB allocation of the source
  image — only to read `bitmap.width/height` and then `sampleThumbnail` (scale to ≤640 px) for the
  encrypted thumbnail. No `inJustDecodeBounds` pass for dimensions and no `inSampleSize` for the
  thumb decode. A second full `openInputStream` re-read decrypts the file again for EXIF.
- Trigger: every synced image (pipeline worker, Dispatchers.IO) and every metadata rebuild
  (MetadataRebuildViewModel, ioDispatcher). Three concurrent workers can decode three full-size
  photos simultaneously.
- Expected: bounds-only decode for width/height + sampled decode for the thumb (~1–2 MB peak);
  Actual: e.g. a 48 MP photo allocates ~192 MB of (native-backed) bitmap memory and burns full
  decode CPU per image, ×3 lanes.
- Evidence: app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt:44-62
  (`BitmapFactory.decodeStream(input)` then `sampleThumbnail`), :57-62 (second stream for EXIF).
- Runtime-or-static: static code; impact runtime-dependent on photo sizes.
- Mitigations: since Android O bitmap pixels live on the native heap, so a hard Java-heap OOM is
  unlikely; images on a personal media share are usually ≤ 12–50 MP. Dispatchers.IO keeps it off
  Main. OutOfMemoryError (an Error) would escape the `catch (Exception)` handlers, but native-heap
  behaviour makes that improbable.
- Counterargument: personal-use image sets are typically phone photos where the extra decode is
  ~100–300 ms each — noticeable only on bulk first syncs.
- Verdict: CONFIRMED (performance/memory pressure). Severity: Low-Medium.

### A2-03 · SeekPreviewExtractor LruCache recycles bitmaps the Compose layer may still reference

- Mechanism: `cache.entryRemoved(evicted = true)` calls `oldValue.recycle()`, and
  `release()` calls `cache.evictAll()` (recycling every entry) from `PlayerScreen`'s
  `DisposableEffect.onDispose`. `PlayerScreen` stores returned bitmaps in
  `seekPreviewBitmap` (mutableStateOf) and renders them via `Image(bitmap.asImageBitmap())`.
  The Compose layer has no ownership protocol: a bitmap handed to the UI stays in the cache and
  can be recycled underneath a live `Image` painter or an in-flight recorded frame.
- Scope owner: `prefetchScope` (plain CoroutineScope(Dispatchers.IO), no SupervisorJob) in the
  extractor, `remember`-owned extractor in PlayerScreen.
- Trigger windows analysed:
  1. Steady-state scrub: the displayed frame is always the MRU cache entry (each displayed frame
     is written to the cache and becomes MRU at put/get time; budget ~18 frames). Eviction victims
     are ≥18 accesses older, so the displayed frame effectively cannot be the victim — REJECTED in
     practice.
  2. `release()` on player exit while the final frame's display list is recorded but not yet
     drawn by the RenderThread: recycle racing the draw → classic
     "Canvas: trying to use a recycled bitmap" crash. Window ≈ one frame (~16 ms) at dispose.
- Evidence: app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:35-48
  (LruCache + entryRemoved recycle), :121-133 (release → evictAll + recycle),
  app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:399-405 (extractor +
  DisposableEffect), :450-459 (bitmaps stored into state), :662-666 (Image(bitmap)).
- Runtime-or-static: static mechanism; crash reproduction is timing-dependent (runtime).
- Mitigations: preview `Image` is gated on `isSeekingActive && seekPreviewBitmap != null`;
  `endSession()` (does NOT evict) runs 100 ms before the state is nulled, so the common exit path
  stops drawing before any teardown; the dispose race is the residual window.
- Side note: `prefetchScope` lacks SupervisorJob — an uncaught child failure would silently kill
  the shared scope Job (subsequent `schedulePrefetch` launches become no-ops). Current body has
  catch-alls around the only throwing ops and no suspension points, so this is latent, not live.
- Verdict: PLAUSIBLE (low frequency, high impact when it hits — RenderThread crash). Severity: Low.

### A2-04 · Per-file progress emissions bypass the 500 ms throttle; SyncService forwards each to NotificationManager.notify

- Mechanism: `SyncProgressPublisher.reportDownloadProgress` throttles writes to 500 ms, but
  `completeDownload` / `markDeleted` / `beginDownload` always publish
  (`updateActiveDownloadsState` unconditional). SyncService's collector calls
  `SyncNotificationHelper.updateNotification` on EVERY emission (NotificationManager.notify =
  binder + RemoteViews rebuild), and every emission also carries
  `completedPaths = completedPathsSet.toSet()` — an O(n) copy per completion, O(n²) total over a
  run, plus StateFlow's equality check comparing growing Sets.
- Trigger: DELETE_LOCAL tail (DB-bound, can complete tens–~100 files/s on internal volume) and
  rapid small-file download completions.
- Expected: ~500 ms pacing per conventions ("进度节流：同步进度 ~500ms 节流"); Actual: one
  state emission + notify() per completed/deleted file, throttled only by StateFlow conflation
  and binder call pacing in the collector.
- Evidence: app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:160-175
  (completeDownload always publishes), :191-196 (markDeleted publishes), :168-173 (per-completion
  Set copy), app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:41-47 (collector →
  updateNotification per emission), app/src/main/java/cn/vectory/syncplayer/sync/SyncNotificationHelper.kt:97-108.
- Mitigations: StateFlow conflation collapses bursts when the collector lags; download-phase
  completions are network-paced (rare); completedFiles accuracy on completion is visibly intended
  (commit 4989375 lineage kept per-completion publish, only removed the speed recompute);
  SyncEngineProgressTest asserts completion semantics.
- Verdict: PLAUSIBLE (minor; bounded, brief, mostly the DELETING tail + completedPaths O(n²)
  copying at 10k-file scale). Severity: Low.

### A2-05 (observation, not promoted) · RangeDecryptor re-reads and validates the header on every readRange

- Documented security decision (fresh header bytes bound as AAD per call). Cost: one extra
  seek+read per 64 KiB playback read; on SAF volumes every handle op is a binder round trip, and
  all proxied files share the single `media-provider-io` HandlerThread. Measured-style estimate:
  ~2× read call count at ≤ 5 MB/s playback ≈ well below thread saturation. Kept as coverage note
  (design trade-off, not a defect). Evidence: crypto/RangeDecryptor.kt:42-47, 83-99;
  provider/MediaContentProvider.kt:60-68.

---

## Rejected Hypotheses

1. **Stale `uiState` snapshot in the seek-preview LaunchedEffect loop** (PlayerScreen.kt:422-462
   reads `uiState.*` inside a long-lived effect). `val uiState by
   viewModel.uiState.collectAsStateWithLifecycle()` (line 84) delegates through the *remembered*
   State instance, so closure reads are fresh; the team explicitly uses the same pattern for
   gestureListener via `rememberUpdatedState` (line 291). REJECTED.
2. **`observeSyncCompletion` completing instantly from a stale terminal SyncProgress value.**
   `loadPreview()` calls `syncEngine.resetProgress()` (SyncPreviewViewModel.kt:120) before any
   plan exists; the init path guards with `isRunning` (line 77-82). No reachable path subscribes
   against a stale terminal state for the same relation. REJECTED.
3. **Main-thread blocking via MediaContentProvider's runBlocking.** Traced all consumers of the
   content URIs: Media3/ExoPlayer (ContentDataSource on its Loader threads), Coil AsyncImage
   (fetcher dispatcher = IO), `openPipeHelper` (framework pool thread), MediaMetadataRetriever
   (callers verified on Dispatchers.IO: SyncDownloadPipeline workers, MetadataRebuildViewModel
   withContext(ioDispatcher), SeekPreviewExtractor withContext(Dispatchers.IO)),
   FileOperationsUseCase.buildShareIntent (withContext(Dispatchers.IO)). No main-thread caller
   found. REJECTED.
4. **CancellationException swallowed by catch(Exception)/catch(Throwable).** Swept all 112 catch
   sites; triaged the 37 without an adjacent CE guard: every one either (a) has a CE-first sibling
   just outside the 8-line window, (b) rethrows `e` (FileOperationsUseCase.kt:309,
   SyncSourceRepository.kt:73, ChunkedEncryptSink), (c) runs in a non-suspend context with no
   suspension points (SafVolume, AudioGainAnalyzer, SyncPlayerHostKeyVerifier, date parsing), or
   (d) is the documented NonCancellable DAO-cleanup swallow (SyncPendingTaskManager.kt:100-111).
   REJECTED — cancellation hygiene is excellent post 801608c/31d9869.
5. **Orphaned/leaked CoroutineScopes.** Inventory: SyncPlayerApp.appScope (process lifetime,
   launches only startup purge), AppLockManager.scope (singleton + infinite pref collector —
   process lifetime, by design), UndoDeleteUseCase.safetyScope (documented must-complete scope +
   NonCancellable commit, SupervisorJob), SyncService.serviceScope (cancelled in onDestroy with
   syncJob/collectorJob/pendingStopJob), SeekPreviewExtractor.prefetchScope (cancelled in
   release()), all ViewModels on viewModelScope, BrowserContentCoordinator on injected
   viewModelScope. No GlobalScope, no runBlocking in ViewModels, no callbackFlow. REJECTED.
6. **ExoPlayer/PlayerView leak or cross-config retention.** Player created in
   `remember` (PlayerScreen.kt:105-114), released + `player = null` in
   DisposableEffect.onDispose (:125-142); ViewModel holds no player. Position persisted on
   dispose. REJECTED (design).
7. **Per-recomposition sorting/filtering of large browser lists.** No sortedWith/filter in
   BrowserScreen/BrowserContent/BrowserSortFilter; sorting/filtering happens once per folder/filter
   emission inside `mapLatest` on ioDispatcher (BrowserContentCoordinator.kt:183-215, 230-307).
   REJECTED.
8. **Whole-screen recomposition from the 200 ms position flow.** PlayerScreen intentionally does
   NOT subscribe; narrow components do (PlayerScreen.kt:92-98 comment). REJECTED (already fixed).
9. **Progress tryLock losing final state** — prior audit RH-003; unchanged (completion paths use
   suspending withLock and always publish). REJECTED.
10. **Notification flooding during the download phase** — 500 ms throttle holds for intermediate
    frames; only completions bypass (see A2-04). REJECTED as standalone download-phase issue.
11. **Retry-after-cancellation.** Transport clients are CE-first + ensureActive (CQ-001 fix);
    backoff `delay(1000L * attempts)` is cancellable; retry loop rethrows CE first
    (SyncDownloadPipeline.kt:192-313). REJECTED.
12. **NonCancellable misuse.** All five NonCancellable sites are narrow cleanup blocks that rethrow
    the original CE afterwards (SyncEngine.consumeAndExecute, SyncPendingTaskManager.cancelCleanup,
    pipeline worker cleanup, EncryptedMediaStore.openEncryptSink stub cleanup, UndoDeleteUseCase
    commit). REJECTED.

## Coverage Notes

Read fully: SyncPlayerApp, SyncService, SyncEngine, SyncDownloadPipeline, SyncProgressPublisher,
SyncPendingTaskManager, RemoteFileClient(Factory), SmbFileClient, SftpFileClient,
MediaContentProvider, EncryptedMediaStore, MediaVolumeProvider, FileVolume, SafVolume (key parts),
RangeDecryptor, DecryptingFdCallback, CryptoScheme (decryptChunk sites), ChunkedEncryptSink (close
paths), PlayerScreen (~700 of ~1000 lines incl. all LaunchedEffects), PlayerViewModel,
SeekPreviewExtractor, ImageViewerViewModel, BrowserContentCoordinator, BrowserViewModel (state
areas), SyncPreviewViewModel, MetadataRebuildViewModel, GainAnalysisViewModel (launchAnalysis),
AudioGainAnalyzer, AppLockManager, BiometricAuthManager (grep-level), MainActivity,
SyncNotificationHelper, DebugLogger, ShareTempFiles, Thumbnail, Video/ImageMetadataExtractor,
UndoDeleteUseCase (scope areas), FileOperationsUseCase (catch sites + share path).

Sampled via targeted grep: SyncRemoteScanner/SyncDeleteExecutor (single-client-per-operation with
finally disconnect confirmed), StorageManagementViewModel, SearchViewModel/Screen, settings VMs,
GesturePlayerView (structure — no Handler/Thread; listener set once), ImageViewerScreen (Coil),
navigation queues, DAO layer (Room suspend DAOs throughout; only provider uses getByIdSync on
non-main callers).

Not covered: androidTest sources, GesturePlayerView gesture math, theme/components, manifest,
res. Tests consulted: SyncEngineTest (factory stubbing confirms per-file client creation is NOT a
tested contract), transport cancellation tests (already covered by prior audit).

Startup check: Application.onCreate does provider-alias registration (cheap), AppLockManager
register (addObserver + background pref collect), and defers the legacy-v1 purge to
appScope.launch behind Hilt Provider — no first-frame blockers. EncryptedMediaStore /
MediaCryptoMaster / Keystore construction is deferred off the startup path via Lazy/Provider.
