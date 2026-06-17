# Sync Player Follow-up Review 2 - 2026-06-17

## Executive Verdict

Not safe to ship a release APK yet.

Several original findings are fixed or substantially improved, especially read-only SAF opens, per-worker remote clients, size checks before DB upsert, synchronous provider DAO reads, seek preview scope lifetime, notification `onNewIntent`, image MIME support, and nullable document ID decoding. However, there are still blocking issues:

- Path confinement is only partially applied. The new safe API exists, but live write/delete/create paths still use raw `File(userdataRoot, path)` or `File(localRoot, action.relativePath)`.
- Pending sync durability is still incomplete. A service/process death after rows are marked `RUNNING` can strand the approved task and the redelivered service will not resume it.
- Partial failure notification handling regressed: `PARTIAL_FAILED` keeps the foreground notification ongoing instead of producing a terminal failure/partial notification.
- Some delete flows still remove DB state even when local deletion fails.

Build verification was attempted but did not complete in this sandbox. `./gradlew compileDebugKotlin` first failed because `/home/mar/.gradle/...gradle-8.11.1-bin.zip.lck` is on a read-only filesystem; retrying with `GRADLE_USER_HOME=/tmp/sync-player-gradle` failed because network access to download Gradle is blocked.

## Original Findings

### 1. CRITICAL: Local path traversal / escape from `userdataRoot`

Status: PARTIALLY RESOLVED.

Evidence:

- Safe APIs were added and the core canonical prefix check is correct for normal filesystem cases: [StoragePaths.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:69), [StoragePaths.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:84).
- Relation save now normalizes and rejects unsafe `localBasePath`: [SyncRelationEditViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:250).
- But the old unsafe resolver remains and is still used to choose the sync root: [StoragePaths.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:41), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:151).
- Download/reconcile/delete still build child paths directly from remote/action paths: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:363), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:418), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:419), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:583).
- Browser/manual file operations also bypass `safeResolveUserData`: [FileOperationsUseCase.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:112), [FileOperationsUseCase.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:170), [BrowserViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:601), [BrowserViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:656), [SyncRelationEditViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:145).

Assessment: the safe API itself is good, and new relation base paths are safer. The fix is incomplete because the API is not the only route to local write/delete sites. Existing DB rows, pending rows, disk-only browser items, or malformed remote/action relative paths can still reach raw `File(...)` construction.

### 2. CRITICAL: SAF provider `openDocument` accepting write/truncate

Status: RESOLVED for write/truncate enforcement.

Evidence:

- `openDocument` rejects every mode except `"r"` before opening a file: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:303).
- It opens with `ParcelFileDescriptor.MODE_READ_ONLY` instead of `parseMode(mode)`: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:319).

Residual risk: the provider still trusts `video.localAbsolutePath` from the DB for read access: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:316). That is not a write/truncate bypass, but it remains coupled to the incomplete path-confinement fix above.

### 3. HIGH: Parallel downloads sharing one client

Status: RESOLVED.

Evidence:

- Each download coroutine creates its own client: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:415).
- The factory returns fresh concrete instances: [RemoteFileClientFactory.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClientFactory.kt:15).
- Each worker connects independently and closes in `finally`: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:422), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:563).

### 4. HIGH: Partial downloads committed as success

Status: RESOLVED for normal download flow.

Evidence:

- SFTP throws on EOF before advertised size: [SftpFileClient.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:185).
- SMB throws if transferred bytes are less than remote size: [SmbFileClient.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:284).
- `SyncEngine` verifies temp file size before rename/upsert: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:478), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:492).

### 5. HIGH: Sync reports `COMPLETED` despite failures

Status: PARTIALLY RESOLVED.

Evidence:

- `SyncProgress` now has `failedFiles` and `PARTIAL_FAILED`: [SyncProgress.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/model/SyncProgress.kt:17), [SyncProgress.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/model/SyncProgress.kt:62).
- `executeSync` sets `PARTIAL_FAILED` and returns `false` when failures occurred: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:643), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:664).
- `SyncPreviewViewModel` observes `PARTIAL_FAILED`: [SyncPreviewViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:171).
- But notifications only treat `COMPLETED` as terminal. `PARTIAL_FAILED` falls into `createOngoingNotification`, remains ongoing, and is not cancelled: [SyncNotificationHelper.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncNotificationHelper.kt:95).

Assessment: core engine semantics are fixed, but one consumer still mishandles the new phase.

### 6. HIGH: Pending actions deleted before execution + auto-dry-run on restart

Status: PARTIALLY RESOLVED.

Evidence:

- `status` was added to `PendingSyncActionEntity`: [PendingSyncActionEntity.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/entity/PendingSyncActionEntity.kt:70).
- Migration 16→17 adds the status column: [DatabaseModule.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/di/DatabaseModule.kt:216).
- `consumePendingTask` marks rows `RUNNING` instead of deleting first: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:119).
- `SyncService` no longer auto-dry-runs and executes when no pending task exists; it stops: [SyncService.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:128).
- However, `consumePendingTask` only selects `PENDING` rows. If the service or process dies after `markRunning` but before terminal cleanup, redelivery calls `consumePendingTask`, finds no `PENDING` rows, and stops. The approved task is now stranded in `RUNNING`: [PendingSyncActionDao.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:35), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:119), [SyncService.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:128).
- Terminal rows are immediately deleted after marking `COMPLETED`, `FAILED`, or `CANCELLED`, so the state machine is not actually durable beyond execution: [SyncService.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:156), [SyncService.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:164).

Assessment: the dangerous auto-dry-run bypass is fixed, but crash/restart recovery is still incomplete.

### 7. HIGH: Mirror deletion removes DB before file

Status: RESOLVED for mirror `DELETE_LOCAL`; still present in delete-both flow.

Evidence:

- Mirror deletion now deletes the physical file first, verifies non-existence, logs failure, increments `failedCount`, and skips DB deletion on failure: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:581), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:596), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:627).
- It also deletes thumbnails after local file deletion succeeds: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:612).

Residual/new issue: `executeDelete` for `DELETE_BOTH` still logs local delete failure and deletes the DB row anyway: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:793), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:807).

### 8. MEDIUM: Base-path conflict detection misses exact match

Status: RESOLVED for exact-match detection; minor edit-case caveat.

Evidence:

- DAO SQL now includes exact match: [SyncRelationDao.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:31).
- Repository excludes the current relation by resolving its current path: [SyncRelationRepository.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/repository/SyncRelationRepository.kt:67).

Caveat: exclusion is by old path, not by id. If two pre-existing bad rows somehow share the same `localBasePath`, editing one could exclude all rows at that path. The normal unique-index path should make this unlikely, but excluding by id would be cleaner.

### 9. MEDIUM/LOW group: Provider blocking, seek preview, notification intent, MIME, document IDs

Status: MOSTLY RESOLVED.

Evidence:

- Provider `runBlocking` Flow reads were replaced with synchronous DAO calls: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:116), [VideoItemDao.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:12), [SyncRelationDao.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:17).
- Seek preview no longer cancels the long-lived prefetch scope in `endSession`; final cancellation moved to `release`: [SeekPreviewExtractor.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:64), [SeekPreviewExtractor.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:76).
- `MainActivity.onNewIntent` updates a `MutableStateFlow` observed by Compose: [MainActivity.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:33), [MainActivity.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:66).
- SAF root advertises image MIME types and `getMimeType` covers common image extensions: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:82), [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:352).
- `DocumentId.decode` is null-safe and provider query methods handle null decode without throwing: [DocumentId.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/DocumentId.kt:17), [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:96), [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:183).

Residual: provider methods still block binder threads while executing synchronous Room queries. This removes `runBlocking`/Flow overhead, but large folders/searches can still block binder calls. The original concern is reduced, not eliminated.

## New Issues / Regressions

### Critical: Safe path API is not wired into most live filesystem operations

This is also the incomplete resolution of original finding 1. The fix added `safeResolveUserData`, but the sync engine, browser file operations, manual folder creation, and relation directory browsing still construct files directly under `userdataRoot` or `localRoot`. Blocking because this is the app's data-loss boundary.

Key sites: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:418), [FileOperationsUseCase.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:170), [BrowserViewModel.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:601).

### High: `RUNNING` pending tasks are not recoverable after service/process death

Rows marked `RUNNING` are excluded from restart consumption, and the service stops when no `PENDING` task is found. This avoids the old unapproved dry-run behavior, but it can still lose an approved sync after the exact crash window the state machine was meant to address.

Key sites: [PendingSyncActionDao.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:35), [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:122), [SyncService.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:128).

### High: Partial failure notifications remain ongoing

`SyncNotificationHelper.updateNotification` only handles `COMPLETED` as terminal. `PARTIAL_FAILED`, `FAILED`, and `CANCELLED` continue through the ongoing notification path. For partial failures this undermines the new outcome semantics and can leave a sticky foreground-style notification.

Key site: [SyncNotificationHelper.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncNotificationHelper.kt:95).

### High: `executeDelete` still deletes DB state after local delete failure

The mirror delete path was fixed, but the separate delete-both flow still logs local file delete failure and then deletes the DB row. If local deletion fails and remote deletion succeeds, the app loses DB tracking while the local file remains.

Key site: [SyncEngine.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:793).

### Medium: Provider read access still trusts absolute DB paths

`openDocument` is read-only now, but it opens `video.localAbsolutePath` directly. If previous traversal bugs or a corrupted DB left an out-of-root absolute path, SAF can expose it read-only to external clients.

Key site: [SyncDocumentsProvider.kt](/home/mar/personal_projects/sync-player/app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:316).

### Medium: Test coverage does not exercise the critical remaining path call sites

There are useful tests for `StoragePaths` and partial sync failure, but current sync tests mock `storagePaths.resolveUserData(any())`, which hides the fact that production still uses the unsafe resolver and raw child `File` construction.

Key site: [SyncEngineTest.kt](/home/mar/personal_projects/sync-player/app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt:138).

## Recommended Blocking Fixes Before Release

1. Replace or remove `StoragePaths.resolveUserData`; route all user-data path construction through `safeResolveUserData`.
2. Validate every remote/action relative path before temp/final file creation, delete, reconcile, metadata rebuild, disk-only record creation, browser folder creation, and local folder browsing.
3. Make pending-task recovery consume resumable `RUNNING` tasks on service redelivery, or atomically reset stale `RUNNING` rows to `PENDING` with a started-at timestamp policy.
4. Treat `PARTIAL_FAILED`, `FAILED`, and `CANCELLED` as terminal notification phases.
5. Apply the file-first/verify-before-DB-delete rule to `executeDelete` as well as mirror `DELETE_LOCAL`.
6. Re-run `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` in an environment where Gradle can use its distribution cache or network.
