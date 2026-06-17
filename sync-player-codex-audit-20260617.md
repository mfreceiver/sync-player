# Sync Player Code Quality Audit - 2026-06-17

## Executive Summary

Overall health: the codebase is understandable and mostly organized around clear feature boundaries, with good use of Hilt, repositories, Room DAOs, foreground service sync, and separated high-frequency player position state. The largest risks are not style or architecture; they are safety issues in the file/sync boundary where incorrect paths, partial downloads, process death, or provider modes can damage or expose user media.

Top 3 risks:

1. Path inputs are not normalized or confined to `userdataRoot`, so sync/local file operations can escape the intended storage root.
2. Sync can report success after failed or truncated downloads, and mirror deletion can remove DB state before physical deletion succeeds.
3. The foreground service consumes approved pending sync actions before execution and may auto-run a new dry run after restart, weakening the "dry-run first" safety model.

## Critical Findings

### Critical - Local path traversal can write/delete outside `userdataRoot`

Location: `app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:41`, `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:264`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:419`

What's wrong: `StoragePaths.resolveUserData()` only strips leading `/` and then constructs `File(userdataRoot, trimmed)`. `SyncRelationEditViewModel.save()` persists `localBasePath` with only `.trim()`. Sync then writes `File(localRoot, action.relativePath)` and browser operations delete `File(storagePaths.userdataRoot, relativePath)` without canonical-path containment checks.

Why it matters: a manually typed local base path or local folder name containing `..` can escape `filesDir/userdata`. A malicious or malformed remote path should also never be trusted at the point where local files are created. For a mirror-sync app that deletes and overwrites files, this is a data-loss boundary.

Suggested fix: centralize safe path resolution in `StoragePaths`, using `canonicalFile` or `Path.normalize()` and rejecting any resolved path that is not under `userdataRoot.canonicalFile`. Apply it to local base paths, remote relative paths before download, manual folder creation, metadata rebuild, SAF open paths, and delete/share paths. Add tests for `../`, absolute paths, empty path, repeated slashes, and base-prefix lookalikes.

### Critical - SAF provider may open exported documents with write/truncate modes

Location: `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:305`

What's wrong: `openDocument()` passes the caller-supplied `mode` directly to `ParcelFileDescriptor.parseMode(mode)`. The provider is exported via `android.permission.MANAGE_DOCUMENTS`; document rows advertise no write flags, but the provider itself still accepts write-capable modes if called.

Why it matters: this provider exposes synced media to third-party apps. A write or truncate open could corrupt local synced files while the database still records the remote size and metadata. Even if the DocumentsUI normally requests read-only modes, providers should enforce their own access contract.

Suggested fix: reject every mode except read-only (`"r"`). Prefer `ParcelFileDescriptor.MODE_READ_ONLY` explicitly. If write support is ever added, implement it deliberately with DB updates, file-size refresh, metadata invalidation, and sync conflict handling.

## High Findings

### High - Parallel downloads share one mutable SMB/SFTP client instance

Location: `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:413`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:417`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:435`, `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:26`, `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:32`

What's wrong: `executeSync()` creates one `RemoteFileClient`, connects it once, then launches up to three `Dispatchers.IO` coroutines that all call `client.downloadFile(...)`. Both concrete clients store mutable connection/session state (`sshClient`, `sftpClient`, `cifsContext`, `shareName`, `subPath`) and no synchronization protects the underlying SSHJ `SFTPClient` or jcifs `SmbFile` access.

Why it matters: SFTP clients in particular are rarely safe for concurrent reads through the same session/channel. This can cause intermittent corrupt reads, protocol errors, stuck downloads, or progress state that looks correct while files are incomplete.

Suggested fix: either make downloads sequential per connection, or create one independent client connection per worker/download. If keeping concurrency, move connection state into a short-lived worker object and validate library thread-safety explicitly. Add stress tests with multiple large files and forced cancellation.

### High - Partial downloads can be committed as successful files

Location: `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:170`, `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:172`, `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:183`, `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:267`, `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:282`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:477`

What's wrong: SFTP returns `true` after the read loop even if `rf.read(...)` returns `< 0` before `offset == totalSize`. SMB also returns `true` without checking that `transferred == totalSize`. `SyncEngine` then renames/copies the temp file into place, upserts the DB with the remote size, and extracts metadata.

Why it matters: network interruptions or server-side short reads can produce truncated videos that the app records as complete. Future dry runs may not repair them if the DB says the expected remote size and timestamp are present.

Suggested fix: after every download, require `tmpFile.length() == action.fileSize` and ideally compare the bytes transferred reported by the client. In each client, throw if EOF arrives before the advertised length. Only upsert DB records after the file is durably moved and size-verified.

### High - Sync reports `COMPLETED` and returns success despite failed downloads

Location: `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:518`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:597`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:610`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:616`

What's wrong: individual download failures increment `failedCount`, but the final progress is always `SyncPhase.COMPLETED`, `completedFiles = totalActions`, and `executeSync()` returns `true`. The last-sync message includes a failed part, but callers and notifications see a successful completion state.

Why it matters: this hides partial sync failures in the main control flow. Users can think the library is mirrored when some files are missing or stale, and retry/alert behavior cannot distinguish partial success from full success.

Suggested fix: introduce a `PARTIAL_FAILED` or use `FAILED` when `failedCount > 0`. Return `false` for partial failure unless the UI explicitly models partial success. Keep `completedFiles` as processed count, but add `failedFiles` to `SyncProgress` so the notification and preview screen can show the real outcome.

### High - Pending sync actions are deleted before execution and service restart can bypass confirmation

Location: `app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:27`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:56`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:76`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:94`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:151`

What's wrong: the preview screen stores approved actions, then the service calls `getAndDeleteByRelationId()` before executing them. If the process dies after that point, the approved action list is gone. Because the service returns `START_REDELIVER_INTENT`, a restarted service with no pending task falls into the `dryRun(...)` + `executeSync(...)` path, executing a fresh mirror plan without the user's prior preview contents.

Why it matters: the app's sync model says dry-run first. Process death, low memory, or service restart can turn a confirmed plan into either a lost job or an unconfirmed current-state mirror operation.

Suggested fix: add an explicit pending-task state machine: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`. Mark the task running atomically at execution start; delete only after terminal success or explicit cancellation. If the service starts without an approved task, stop and notify instead of dry-running and executing automatically.

### High - Mirror deletion removes DB records before confirming file deletion

Location: `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:576`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:579`, `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:583`

What's wrong: for `DELETE_LOCAL`, the sync engine deletes the `video_item` row first, then attempts to delete the physical file. If file deletion fails, it only logs a warning and still writes a successful sync log entry.

Why it matters: the database can say a remote-deleted file is gone while the file remains on disk. Future mirror dry runs compare remote files to DB records, so the orphaned physical file may not be cleaned by sync and can reappear as a disk-only item in the browser.

Suggested fix: delete the file first, verify it no longer exists, then remove playback/DB state in one ordered operation. If deletion fails, keep the DB row, mark the action failed, and surface it in progress/last-sync status. Also delete associated thumbnails when local media is removed.

## Medium Findings

### Medium - Local base-path conflict detection misses exact matches

Location: `app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:136`

What's wrong: `findConflictingBasePaths()` checks only parent/child relationships using `LIKE ... '/%'`; exact duplicates are not returned as conflicts. A unique index later rejects duplicates, but the UI-level validation misses them.

Why it matters: users get a generic save failure path rather than the intended base-directory conflict message. This is especially confusing around sync roots, where exact and nested conflicts both matter.

Suggested fix: include `localBasePath = :checkPath` in the conflict predicate, while still excluding the current relation by id rather than by path. Use normalized paths for this check.

### Medium - SAF provider blocks binder threads with multiple `runBlocking` database calls

Location: `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:188`, `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:213`, `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:259`, `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:326`

What's wrong: provider query methods use `runBlocking(Dispatchers.IO)` repeatedly, sometimes making two separate Flow `.first()` calls per directory query. These methods run under external callers' binder interactions.

Why it matters: large folders or slow Room invalidation can stall file picker/player clients. Repeated blocking calls can make third-party browsing feel frozen or trigger ANRs in the caller path.

Suggested fix: expose synchronous DAO queries specifically for provider use, combine folder items/subfolders in fewer queries, and keep provider methods short. Avoid Flow collection in `DocumentsProvider`; use `suspend` internally only behind a bounded blocking adapter if necessary.

### Medium - Seek preview extractor cancels its prefetch scope permanently

Location: `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:47`, `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:65`, `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:72`, `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt:127`

What's wrong: `endSession()` calls `prefetchScope.cancel()`. The scope is created once as a property, so after the first seek session ends, later `schedulePrefetch()` launches into a cancelled scope.

Why it matters: seek previews still extract on demand, but prefetch silently stops after the first gesture, increasing latency and making performance inconsistent during long player sessions.

Suggested fix: cancel only the current `prefetchJob` in `endSession()`, and cancel the whole scope only from a final `release()` called by `DisposableEffect`. Alternatively create a fresh scope per session.

### Medium - Notification navigation does not reliably handle `onNewIntent`

Location: `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:47`, `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:49`, `app/src/main/java/cn/vectory/syncplayer/MainActivity.kt:72`

What's wrong: Compose state captures the initial `intent`, and `onNewIntent()` only calls `setIntent(intent)`. That does not itself update `notificationIntent` state or force recomposition.

Why it matters: tapping sync notifications while the activity is already alive may fail to navigate to the sync preview or browser folder, which makes foreground sync recovery less predictable.

Suggested fix: hold a `MutableStateFlow<Intent?>` or `mutableStateOf<Intent?>` as an Activity property and update it directly in `onNewIntent()`. Collect/read that state inside `setContent`.

## Low Findings

### Low - DocumentsProvider advertises only `video/*` despite image support

Location: `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:86`, `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt:348`

What's wrong: the root MIME types are `"video/*"`, and provider helper MIME detection only handles video extensions. The rest of the app now supports images and stores `mediaType = "IMAGE"`.

Why it matters: images synced by the app may be hidden or misrepresented to external document clients.

Suggested fix: advertise both `video/*` and `image/*`, and reuse the shared MIME utility or extend provider MIME mapping for image types.

### Low - Malformed document IDs can throw unchecked exceptions from provider queries

Location: `app/src/main/java/cn/vectory/syncplayer/provider/DocumentId.kt:17`, `app/src/main/java/cn/vectory/syncplayer/provider/DocumentId.kt:22`

What's wrong: `DocumentId.decode()` assumes valid formats and can throw `NumberFormatException`, `StringIndexOutOfBoundsException`, or `IllegalArgumentException`. Provider query methods do not consistently catch these.

Why it matters: external document clients and system components can pass stale or malformed IDs. The provider should fail with an empty cursor or `FileNotFoundException`, not crash the provider call.

Suggested fix: make decode return `DocumentId?` or throw a provider-specific checked failure that each provider method converts into an empty cursor or `FileNotFoundException`.

## Prioritized Action List

1. Add canonical path confinement to `StoragePaths` and route every local file operation through it.
2. Make the SAF provider read-only at `openDocument()` and harden document ID parsing.
3. Fix download integrity: per-download size checks, client EOF validation, and no DB upsert until verified.
4. Fix sync outcome semantics so partial failures are not reported as `COMPLETED` success.
5. Replace the pending-sync consume/delete flow with a durable task state machine and remove service auto-execute without approved actions.
6. Rework parallel downloads to use one client/session per worker or sequential downloads for non-thread-safe protocols.
7. Change mirror deletion ordering: file first, then DB/playback/thumbnail state, with failed delete surfaced.
8. Add focused tests for path normalization, pending task restart behavior, partial download handling, and Room base-path conflict detection.
9. Replace provider `runBlocking` Flow reads with direct synchronous DAO queries optimized for DocumentsProvider access.
10. Fix player seek preview scope lifetime and notification `onNewIntent` state propagation.
