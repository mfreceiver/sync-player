# Agent 4 Notes — Data / Build / Adversarial Verification (20260824-2303)

Baseline main @ 5c7e419 (v12.2.0). Static reading + git history only; no gradle. Domain: Room, DataStore/prefs, sync-engine data invariants, manifest/permissions, serialization, R8/build, networking (sshj/jcifs/BC). Phase B: adversarial verification of Agents 1–3.

Read first: AGENTS.md, docs/specs/{crypto,storage-security,sync}.md, docs/release.md, prior audit docs/automatic/20260823-1758_deep-code-quality.md (CQ-001 fixed by 31d9869; all remediation landed in v12.2.0). Git log since prior audit = remediation commits only (31d9869, 3cdc672, ec83906, c4f8052, d67882d, 3854bdc, 5c7e419) — no new feature risk introduced since.

---

## Phase A Candidate Findings

### A4-1 · volumeId written to logcat AND in-app debug-log UI — CONFIRMED (Low; red-line 6 consistency violation)
- Mechanism: `DebugLogger.i(TAG, "当前卷: ${store.volumeId}")` — `SyncDownloadPipeline.kt:115`. `DebugLogger` (debug/DebugLogger.kt:39-56) forwards unconditionally to `android.util.Log` AND stores the message in a ring buffer rendered by the user-navigable `DebugLogScreen` (`Route.DebugLog`, AppNavHost.kt:291-295).
- `SafVolume.volumeId` = `"saf-" + treeUri` (SafVolume.kt:24) — i.e. the full SAF tree URI incl. storage volume UUID. AGENTS.md red line 6 and storage-security.md ("卷标识…不进入…普通日志") name 卷标识 explicitly; the spec's path-logging clarification extends only to relativePath in logcat, not volumeId.
- Inconsistency proof inside the codebase: `SyncDeleteExecutor.kt:94-98` and `SyncDownloadPipeline.kt:411-415` deliberately log volume mismatch WITHOUT the volumeId values ("stored volumeId differs from active volume"). Single offending site: `grep -rn "volumeId" | grep -i log` → SyncDownloadPipeline.kt:115 only.
- Trigger: every `executeSyncLocked` run (release builds too — DebugLogger is not BuildConfig-gated).
- Expected: log "active volume switched/resolved" without the identifier, or hash it. Actual: raw volume identity in logcat + UI.
- Counterargument: the tree URI is not a credential, and knowing which volume is active is a legitimate diagnostic need; severity stays Low. Verdict: CONFIRMED (spec-consistency), Low.

### A4-2 · SyncService does not override `onTimeout(int)` for dataSync FGS budget — PLAUSIBLE (Low)
- Mechanism: manifest declares `foregroundServiceType="dataSync"` (AndroidManifest.xml:33-36), FOREGROUND_SERVICE_DATA_SYNC permission present (line 7). On Android 14+ the dataSync type has a 6-hour cumulative-per-24h budget; on Android 15 (API 35 = targetSdk) the system calls `Service.onTimeout(startId)` and expects the service to stop promptly, else the app crashes (ForegroundServiceDidNotStopInTimeException). `grep -rn onTimeout app/src/main` → zero hits.
- Trigger: >6h cumulative dataSync FGS within 24h (very large first syncs on slow NAS, or many re-syncs across a day) on an Android 15 device.
- Expected: override onTimeout → cancel sync job (CE path already exists: pendingTaskManager.cancelCleanup) + stopSelf. Actual: default no-op → system-initiated crash after grace.
- Framework guard: none in-app. Mitigation: Semaphore(3) personal library typically finishes well under 6h/day; Android 14 devices are stopped by the system without the crash.
- Counterargument: personal-use scale makes 6h/day unlikely; prior audit and Agent 1 (notes line 129) both flagged it informational. Verdict: PLAUSIBLE, Low.

### A4-3 · Exception-path download cleanup not under NonCancellable — narrow ghost-file race — NOT PROMOTED (observation)
- `SyncDownloadPipeline.kt:302-305`: generic `catch (e: Exception)` runs `store.cancel(assignedId)` then `videoRepository.deleteById(assignedId)` WITHOUT NonCancellable. If cancellation lands inside that suspend cleanup, CE escapes to the outer worker catch (line 355) which does no cleanup → leftover `.tmp`/`.enc` + placeholder row.
- Self-healing: placeholder rows carry `fileSize=0, remoteModifiedTime=null` (lines 232-244) → next dryRun re-downloads; `openEncryptSink` overwrites the tmp path. Prior audit RH-004 already classified incidental-recovery concerns as non-defects; eb5ec59 fixed the main ghost-file path. Rejected as a standalone finding; recorded for completeness.

### A4-4 · Verified invariants (no findings — positive assurance)
1. **Room schema vs entities**: app/schemas/...AppDatabase/2.json matches all six @Entity definitions field-for-field (video_item incl. unique (relativePath, syncRelationId), parentPath + volumeId indices; playback_record FK CASCADE + unique videoItemId; sync_relation FK RESTRICT + unique localBasePath; pending_sync_action relationId index). DB version=2 = schema file 2 = AppDatabase version=2.
2. **E8**: `SyncRemoteScanner.dryRunLocked` catch(Exception) clears toDownload AND toDelete, sets scanError, phase=FAILED (SyncRemoteScanner.kt:153-172); CE rethrown first (153-154). Both transports throw `RemoteScanIncompleteException` on MAX_REMOTE_FILES=10000 and on per-entry attribute failure (SftpFileClient.kt:199-206, SmbFileClient.kt:286-312). `SyncEngineDryRunTest` locks it.
3. **Volume ownership fail-closed**: mismatch (incl. empty "") skips ciphertext deletion and counts FAILED in BOTH SyncEngine paths — DELETE_LOCAL tail `SyncDownloadPipeline.kt:405-425` (failedDeleteCount++), DELETE_BOTH `SyncDeleteExecutor.kt:92-108` (failedActions++) — completedFiles+failedFiles == totalFiles holds in both. `store.volumeId` never "" (FileVolume="internal", SafVolume="saf-<uri>"). Browser/manual paths use index-only delete by documented design (FileOperationsUseCase KDoc decision table; storage-security.md) — correctly NOT flagged.
4. **Retry whitelist**: RETRYABLE_CODES = {TIMEOUT, CONNECTION_REFUSED, UNKNOWN_HOST, NETWORK_UNAVAILABLE, DOWNLOAD_FAILED, UNKNOWN} (SyncDownloadPipeline.kt:534-541) — byte-identical to sync.md contract; terminal codes break out of the 3-attempt loop at line 309.
5. **Space checker**: uses `CryptoScheme.cipherLengthFor` everywhere (SyncSpaceChecker.kt:98, pipeline per-file recheck :207-227); formula 16 + ceil(n)*16 + plainLen verified against crypto.md; overflow → -1 → skip-precheck (never false "insufficient"); usableSpace<0 (SAF unknown) → skip, per-file fallback. Math checked with Math.multiplyExact/addExact and overflow-free numChunks.
6. **AEAD v2 immutability**: CryptoScheme constants (SPM2 magic, 64KiB chunk, 16B header, nonce=iv(8)+index(4), AAD=header+index, GCM-128 tag) all match crypto.md; decodeHeader strict (reserved byte enforced); ChunkedEncryptSink close-time length check; verifyReadable probe throws on any failure (rollback path verified pipeline:279-292). Thumbnail format untouched.
7. **CE-first**: every transport boundary is CE-catch + `currentCoroutineContext().ensureActive()` before wrapping (SftpFileClient connect/list/download/delete; SmbFileClient same) — CQ-001 fix 31d9869 confirmed present at all 11 boundaries.
8. **Structured errors**: `syncErrorMessage` resolves code→R.string only; UNKNOWN never touches e.message (ErrorMessageMapper.kt:65-130). Classification reads code/exception type, never message prose (except documented jcifs English status keywords = sync.md residual risk 3).
9. **Credentials**: passwords live ONLY in EncryptedSharedPreferences keyed pw_<id>; DB stores "ENCRYPTED" placeholder; save() orderings (existing: prefs-commit→DB with restore-rollback; new: DB→prefs with row-rollback) under saveMutex shared with delete() (SyncSourceRepository.kt:49-118). Master key AES-256 KEK-wrapped in ESP; MediaCryptoMaster double-checked-locking + @Volatile correct.
10. **Backup**: allowBackup=false + data_extraction_rules.xml excluding every domain for both cloud-backup and device-transfer (defense in depth). No backup of ESP/DB/DataStore possible.
11. **Provider**: MediaContentProvider exported=true but guarded by signature-level `cn.vectory.syncplayer.permission.READ_MEDIA`; FileProvider exported=false, cacheDir/share/ only. Exception boundary maps everything to safe FileNotFoundException per storage-security.md.
12. **Pending-task durability**: RUNNING→PENDING reset on consume (process death re-executes); cancelCleanup under NonCancellable with per-statement swallow; finalize marks then deletes. `SyncPendingTaskManagerTest` locks.
13. **TOFU**: process-wide lock, reload-under-lock, atomic tmp+rename, save-failure → rollback in-memory + rethrow (never silently trusts) — SyncPlayerHostKeyVerifier.kt. Mismatch → false (fail-closed).
14. **R8**: BC full-keep `{ *; }` with documented reflective $Mappings rationale; jcifs/sshj class-name keeps documented as release-verified; `-assumenosideeffects` strips Log.v/d/i/w in release (Log.e kept) — DebugLogger's ring buffer unaffected (in-memory), so DebugLogScreen still works in release; no behavioral dependency on stripped logs found.
15. **Timeouts**: sshj connect+socket 5s; jcifs responseTimeout+soTimeout 5s; no unbounded waits; zero-read stall guard (5 retries) + truncation check (`offset < totalSize` → throw, never short success).

### A4-5 · Minor observations (not promoted)
- `SyncSourceRepository.delete()` removes the DB row BEFORE the prefs key; process death between leaves an orphaned (encrypted) password in ESP. Harmless residue, no retrievable row. INFO.
- `SyncEngine.dryRunDelete` takes only the relation mutex while `executeDelete` takes both mutexes — stale-snapshot risk between dry-run and execute is re-guarded by execution-time volume re-check; acceptable.
- `VideoItemEntity` is a data class containing `iv: ByteArray` → structural equality uses array identity. No current diffing path compares whole entities (dry-run compares size/mtime; upsert keyed by (relativePath, syncRelationId)); latent pitfall only. INFO.
- Destructive migration (dropAllTables=true) wipes the index DB on every schema bump while ciphertext stays → orphaned ciphertext until re-sync writes NEW ids (old files never reclaimed by any path — purgeLegacyIfNeeded only removes confirmed non-SPM2 files). Documented accepted trade-off (crypto.md, conventions.md). Not a finding; worth a "storage reclaim" backlog item someday.
- `EncryptedSharedPreferences.commit()` on caller thread inside `save()` — callers are ViewModels on viewModelScope (Main); commit() is a blocking disk write on Main (StrictMode-detectable). Personal app, rare path (source edit); not promoted.

---

## Phase B Adversarial Verdicts

### Agent 1 (lifecycle/state/navigation)
| ID | Verdict | Reason |
|---|---|---|
| F1 save-progress only on dispose/switch | **UPHELD (severity → Medium, not High)** | Call-site enumeration independently reproduced (saveProgress: PlayerScreen.kt:138,192,342,354; PlayerViewModel.kt:192-196 is the only DB writer; 200ms loop and ON_STOP only pause/update memory; doExit comment covers navigation, not background death). Mechanism certain. But loss is bounded to watch-position accuracy in a personal app whose media/index are untouched — "High" overstates it. Sub-risk (viewModelScope no-op after VM clear on swipe-away) correctly marked PLAUSIBLE. |
| F2 queue dead fallback / whole-library restore | **UPHELD** | Verified: `savedStateHandle.getStateFlow(SAVED_VIDEO_IDS_KEY, null)` reader exists (PlayerViewModel.kt:99-101,127-149); grep for any writer of `videoIds`/`imageIds` into SavedStateHandle → zero hits; fallback `getAllIdsSorted()` = whole library confirmed at :141. ImageViewer has no fallback (empty nav). Medium agreed. |
| F3 second-sync dropped + stuck spinner | **DOWNGRADED (core mechanism refuted; residual real)** | The drop at SyncService.kt:65-67 requires B's onStartCommand while A's syncJob is active. But B's only caller path is SyncPreviewViewModel.startSync:161-170, which awaits `setPendingTask` → `storageOperationMutex` — held by A's `consumeAndExecute` for A's ENTIRE run (SyncEngine.kt:112-128). So SyncService.start(B) fires only after A released the mutex; A's syncJob completes in microseconds after that, making the isActive-drop practically unreachable; normally B then executes (pendingStopJob is cancelled at SyncService.kt:70). Agent missed the mutex serialization. RESIDUAL (real, low): (a) confirming a sync whose pending lists are both EMPTY → consumePendingTaskLocked returns null → no terminal progress ever emitted with relationId=B → observeSyncCompletion's `first{}` (SyncPreviewViewModel.kt:181-188, filter requires terminal phase) never matches → isSyncing stuck; (b) the microscopic drop window has the same stuck-UI consequence. Downgrade to Low-Medium. |
| F4 double-tap duplicate navigation | **UPHELD** | launchSingleTop present only at AppNavHost.kt:61 (drawer) and :220 (relation switch); Player/ImageViewer/SyncPreview entry navigations unguarded (verified by grep). Mechanism standard-certain. Medium agreed (annoyance, transient double player). |
| F5 cold-start lock race | **UPHELD (PLAUSIBLE, Low-Med)** | AppLockManager.kt:28 @Volatile default-false, :43-48 async DataStore collect on Dispatchers.IO, :60-67 ON_START gates on cache — no happens-before edge. Guarded by FLAG_SECURE + subsequent-cycle locking, exactly as agent says. |
| F6 notification nav dropped after restore | **UPHELD (Low)** | MainActivity.kt:43 `if (savedInstanceState == null)` verified; process-death + notification-tap recreation carries both — guard skips. Low agreed. |
| F7 lock cooldown reset on recreation | **UPHELD (PLAUSIBLE, Low)** | LockScreenOverlay.kt:28-31 plain remember verified; uiMode/fontScale not in configChanges; framework biometric lockout mitigates. (Same finding as Agent 3 F8 — dedupe.) |
| F8 toolbar-back cancels sync, system-back doesn't | **UPHELD (Low-Med)** | SyncPreviewScreen.kt:92-101 verified: back icon cancels when isSyncing; no BackHandler in this screen; destructive asymmetry on ambiguous affordance. |
| F9 in-VM folder state not process-death restorable | **UPHELD (Low, by-design trade-off)** | Coordinator-held folder state verified via agent evidence + BrowserScreen.kt:126-130 re-apply only on empty; deliberate architecture. |

### Agent 2 (coroutines/resources/performance)
| ID | Verdict | Reason |
|---|---|---|
| A2-01 per-file remote client, SMB root listing per connect | **UPHELD (Medium perf)** | Reproduced at SyncDownloadPipeline.kt:165-174 (new client + connect per action), SmbFileClient.kt:103-121 (fresh BaseContext + `listFiles()` root enumeration as connect probe), factory always news up (RemoteFileClientFactory.kt:15-18). MAX_CONCURRENCY=3 and 10000-file cap bound it. No test pins client lifetime (SyncEngineTest stubs factory — consistent with my read of tests). |
| A2-02 full-res bitmap decode for dimensions/thumb | **UPHELD (Low-Med)** | ImageMetadataExtractor.kt:44-62 verified verbatim: `BitmapFactory.decodeStream(input)` full decode, no inJustDecodeBounds/inSampleSize, second stream for EXIF. |
| A2-03 LruCache recycle race at dispose | **UPHELD (PLAUSIBLE, Low)** | SeekPreviewExtractor.kt:46 (`entryRemoved` recycle-on-evict) and :131 (`evictAll` in release) verified; steady-state victim analysis (MRU display frame never evicted) is sound; residual = one-frame dispose race. Agree Low/PLAUSIBLE. Side note (prefetchScope no SupervisorJob) is latent-only — agreed. |
| A2-04 completions bypass 500ms throttle; O(n) set copy per completion | **UPHELD (Low)** | SyncProgressPublisher.kt:160-175 (completeDownload unconditional publish + `completedPathsSet.toSet()`), :191-196 (markDeleted same), SyncService.kt:41-47 (notify per emission) all verified. Bounded by StateFlow conflation + DELETING-tail pacing. Low agreed. |
| A2-05 RangeDecryptor re-validates header per readRange | Not promoted by agent — agreed, documented security trade-off (fresh AAD binding); coverage note only. |

### Agent 3 (compose/a11y)
| ID | Verdict | Reason |
|---|---|---|
| F1 gesture-seek display vs landing divergence | **UPHELD (P3)** | GesturePlayerView.kt:104/111 (frozen base) vs PlayerScreen.kt:309-313 (`exoPlayer.currentPosition + offsetMs` live base) + :244-246 (freeze feed during gesture) all verified verbatim. Display uses frozen base, action uses live position → lands ahead by gesture duration while playing. Solid static proof. |
| F2 seek bar no semantics | **UPHELD (P3 a11y)** | PlayerScreen.kt:874+ seek bar = Box + raw pointerInput; zero `semantics` usage in file (grep). |
| F3 player chips unlabeled/no selected state | **UPHELD (P3 a11y)** | PlayerScreen.kt:1031-1075: `selected` only drives colors; no Role/semantics; contrast with BrowserSortFilter pattern confirmed. |
| F4 filter dialog unlabeled mode button / value-less stars | **UPHELD (P3 a11y)** | Evidence consistent with file reads; icon-only 36dp mode button OFF-state and 28dp star targets verified in agent's quoted code. |
| F5 identical consecutive errors dropped | **UPHELD (PLAUSIBLE P3)** | `LaunchedEffect(it)` at BrowserScreen.kt:346, SearchScreen.kt:306, PlayerScreen.kt:505 verified; identical-string second error while snackbar suspended → no restart → clearError wipes it. Real Compose behavior. |
| F6 zoom state persists across image switch | **UPHELD (P3)** | Plain remember transform state + edge-swipe-while-zoomed permitted; no reset on entity change; double-tap recovery only. |
| F7 playback position not saved on ON_STOP | **UPHELD — duplicate of Agent 1 F1** | Same mechanism; merge into one finding (Medium per my Agent-1 verdict). |
| F8 lock cooldown reset | **UPHELD — duplicate of Agent 1 F7** | Merge. |
| F9 no gesture-exclusion rects | **UPHELD (PLAUSIBLE P3)** | `grep ExclusionRect|setSystemGesture` → zero hits across main sources; device-dependent impact (200dp edge cap) correctly framed. |

---

## Rejected Hypotheses (mine, with reasons)

1. **Relation deletion leaves orphan video_item rows / dangling syncRelationId as a BUG** — REJECTED: user-facing confirm text explicitly promises "已同步的本地文件不会被删除" (strings.xml:195); behavior is documented intent. Residual storage-leak angle (re-created relation re-downloads under new ids, old ciphertext never reclaimed) noted under A4-5 as accepted-design observation.
2. **FK violation crash on source delete with live relations** — REJECTED: repository exposes `getRelationCount` and UI flow checks it before delete; FK RESTRICT is a backstop, not the guard. (Not statically reachable as a crash from the settings flow.)
3. **Room schema drift between entities and 2.json** — REJECTED: field-by-field comparison identical (A4-4.1).
4. **cipherLengthFor overflow/negative bypass of cap** — REJECTED: overflow-free numChunks + multiplyExact/addExact + requireWithinCap at all entry points (CryptoScheme.kt:96-148).
5. **EncryptedSharedPreferences backup exposure** — REJECTED: allowBackup=false + full-exclusion data_extraction_rules.xml (both domains, all storage domains).
6. **SMB `deleteFile` false (already gone) mis-flagged as failure** — REJECTED: executor treats `false` as idempotent success by design (SyncDeleteExecutor.kt:131-135 comment + code path).
7. **`stop()` startService-from-background IllegalStateException** — REJECTED (agrees with Agent 1 R5): sole caller is foreground UI; app is not in cached state while FGS runs.
8. **MD4/BC provider missing under R8 full mode** — NOT CONFIRMABLE statically, evidence against: bcprov 1.80 on classpath + full `-keep` + proguard comment says "SMB verified working in release"; sshj DefaultSecurityProviderConfig registers BC when present; SMB is the primary production feature of released v12.x builds. No finding; runtime-only residual.
9. **DebugLogger logs stripped in release break DebugLogScreen** — REJECTED: only the android.util.Log calls are stripped (`-assumenosideeffects` on Log); the ring-buffer `add()` is app code and survives.
10. **Progress `tryLock` drops final state** — REJECTED (prior audit RH-003): completion paths use suspending withLock and always publish (SyncProgressPublisher.kt:160-196).

## Coverage Notes

- Fully read: AppDatabase, all 6 entities, all 6 DAOs, DatabaseModule, SyncModule, UserPreferencesRepository, SyncSourceRepository (+password flow), SyncRelationRepository, PlaybackRepository/SyncLogRepository (heads), SyncEngine, SyncRemoteScanner, SyncDownloadPipeline, SyncDeleteExecutor, SyncPendingTaskManager, SyncSpaceChecker, SyncService, SyncException/ErrorMessageMapper, SftpFileClient, SmbFileClient, SyncPlayerHostKeyVerifier, RemoteFileClient(Factory), CryptoScheme, EncryptedMediaStore (full incl. purge), MediaVolumeProvider, SafVolume/FileVolume (volumeId/paths), MediaCryptoMaster, DebugLogger, AndroidManifest, data_extraction_rules.xml, build.gradle.kts, proguard-rules.pro, app/schemas 2.json.
- Test inventory consulted: 39 test files; invariant-pinning suites verified to exist (SyncEngineDryRunTest, SyncDeleteExecutorTest, SyncPendingTaskManagerTest, SyncEngineBoundaryTest, CryptoSchemeTest, MediaCryptoMasterTest). No test contradicts any Phase A or Phase B verdict.
- Spot-checked: FileOperationsUseCase (share + volume guards), UndoDeleteUseCase volume check, MetadataRebuildViewModel volume check, MainActivity, SyncPreviewViewModel/Screen (for F3/F8), PlayerScreen/PlayerViewModel (for Agents 1/3 dupes), LockScreenOverlay, AppLockManager, SeekPreviewExtractor, ImageMetadataExtractor, SyncProgressPublisher, AppNavHost.
- Not covered: androidTest, res/values beyond cited keys, scripts/CI (out of domain), runtime behavior (no device).
- Phase B files: agent1-notes.md, agent2-notes.md, agent3-notes.md all present and read in full; every CONFIRMED/PLAUSIBLE item re-verified at cited file:line except where noted (Agent 3 F4/F6 spot-verified via quoted code + pattern grep).
- No files other than this one were modified.
