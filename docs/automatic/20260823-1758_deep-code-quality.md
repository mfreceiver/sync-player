# Automatic Deep Code Quality Audit

**Run ID:** `20260823-1758_deep-code-quality`
**Audit date:** 2026-08-23
**Repository:** Sync Player
**Baseline:** `main` at `7dc00fc0eb862696dc93a16508580656dddf0152` (`v12.1.0`)
**Remediation status (2026-08-23):** RELEASED as `v12.2.0` at commit `3854bdc` — CQ-001 fixed, all remediation goals landed, Gitea Actions run `#35` succeeded, and the signed APK plus `SHA256SUMS` were published (see §13).

## 1. Executive Summary

The audited baseline builds, passes its complete JVM unit-test suite, and passes Android lint without errors. The investigated sync, encryption-storage, database, service-lifecycle, and UI-to-data paths generally contain explicit safety guards for cancellation, partial remote scans, encrypted-volume ownership, and structured errors.

One verified correctness issue remains: cancellation can be converted into a transport failure when a non-cooperative SMB/SFTP operation completes exceptionally after its coroutine has already been cancelled. The result is a user-visible FAILED/error outcome instead of cancellation on several core sync-preview and delete paths. No data corruption was demonstrated; the E8 scan-failure guard still clears derived actions.

> **Remediation status (2026-08-23):** the paragraph above and the table below describe the **audit baseline** (`7dc00fc`, `v12.1.0`); "remains" is to be read as "remains at the audited baseline". The single verified finding (CQ-001) was fixed by `31d9869`, and the completed remediation was released as `v12.2.0` at `3854bdc`; see §13.

| Severity | Count |
|---|---:|
| P0 Critical | 0 |
| P1 High | 0 |
| P2 Medium | 1 |
| P3 Low | 0 |

**Most urgent action:** make transport exception translation cancellation-aware by checking the coroutine job before wrapping a caught SMB/SFTP exception, then add a regression test for a blocking operation that fails after cancellation.

**Overall health:** good. The project has strong executable coverage for its size and recent history shows deliberate hardening. The main structural risk observed is that cancellation correctness is distributed across multiple client wrappers, engine catches, and incidental later suspension points rather than enforced at the transport boundary.

## 2. Repository & Git Baseline

- Branch: `main`
- HEAD: `7dc00fc0eb862696dc93a16508580656dddf0152`
- Tag: `v12.1.0`
- Initial worktree: clean (`git status --short` produced no entries)
- Remote: `origin` → `https://git.vectory.cn:18443/mfreceiver/sync-player.git`
- Module layout: single Android application module, `:app`
- Language/runtime: Kotlin 2.2.10, JVM target 17, minSdk 34, target/compileSdk 35
- Build: Gradle 8.11.1 / AGP 9.1.0 / KSP 2.3.6
- UI/application: Jetpack Compose, Material 3, Hilt, Single Activity
- Persistence/storage: Room schema v2, encrypted media store, internal-file and SAF volume implementations
- Remote protocols: SSHJ 0.39.0 and jcifs-ng 2.1.10
- Concurrency: kotlinx.coroutines 1.10.1, MVVM plus MVI for player state
- Tests: JUnit4, MockK, Coroutines Test, Turbine, Room testing

The initial baseline and read-only policy were recorded before exploration. No branch switch, reset, clean, restore, stash, rebase, commit, or push was performed.

## 3. Audit Scope

### Covered deeply

- Sync orchestration and failure/cancellation paths in `SyncEngine`
- SMB and SFTP client connection, listing, download, and delete boundaries
- Sync foreground-service restart and null-Intent behavior
- Progress publication, throttling, completion, cancellation, and aggregate counters
- Encrypted media-store cleanup and production `MediaVolume` implementations
- Room identity constraints and star-rating update call paths
- Browser/search item construction and file/folder context-menu routing
- Relevant application manifest security boundaries
- Project build configuration, dependency versions, lint policy, and CI-visible files
- Recent Git history and cancellation-hardening commit `801608c`

### Sampled

- Crypto and random-read contracts
- Content provider and storage ownership boundaries
- ViewModel exception handling outside the primary sync paths
- Database DAO consistency beyond candidate-triggered paths
- Player and image-viewer modules

### Not exhaustively covered

- Visual/UI design correctness
- Every Compose screen and accessibility state
- Generated KSP/Room/Hilt code
- Real-device behavior and Android instrumentation tests
- Live SMB/SFTP integration against controlled slow or failing servers
- Release signing and deployment execution

## 4. Commands & Tests Executed

All Gradle gates were run live with `--rerun-tasks` and `JAVA_HOME=/home/mar/android-studio/jbr`.

| Command | Result |
|---|---|
| `git status --short` | Clean at audit start |
| `git branch --show-current` | `main` |
| `git rev-parse HEAD` | `7dc00fc0eb862696dc93a16508580656dddf0152` |
| `git remote -v` | Recorded `origin` fetch/push URL |
| `git log --oneline --decorate -n 30` | Reviewed recent implementation and audit-fix history |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin --rerun-tasks` | Exit 0; BUILD SUCCESSFUL in 48s; 8 tasks executed |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --rerun-tasks` | Exit 0; BUILD SUCCESSFUL in 51s; 33 tasks executed; 31 suites / 352 tests / 0 failures / 0 errors / 0 skipped |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew lintDebug --rerun-tasks` | Exit 0; BUILD SUCCESSFUL in 2m20s; 32 tasks executed |
| Targeted temporary cancellation probe | Exit 0; two deterministic tests passed; BUILD SUCCESSFUL in 1m1s; probe removed after evidence collection |
| `git log --format= --name-only -n 100 \| sort \| uniq -c \| sort -nr` | Used to identify high-change audit hotspots |
| `git log` / `git show 801608c` on sync paths | Confirmed recent CE guards were added above, but not at transport exception boundaries |

Full baseline verification log: `/home/mar/personal_projects/sync-player/.ocmar/workflows/dcq-w1-tests/verify-1787479164.log`.

The compiler emitted only pre-existing test-stub parameter-name warnings at `app/src/test/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStoreTest.kt:239-245` (`relPath` / `dirRelPath`). Lint reported 91 warnings and 1 hint, with no errors. The warnings were principally unused resources, dependency/tooling suggestions, and an `Aligned16KB` warning for `io.mockk:mockk-agent-android:1.13.13`. A `TrustAllX509TrustManager` lint label could not be mapped to project source by repository search and was not promoted to a finding.

## 5. Verified Findings

### CQ-001 — Transport exceptions produced after cancellation are reported as sync failures

**Remediation status:** FIXED at `main` @ `d67882d` (commit `31d9869`, 2026-08-23) — see §13 for the fix and regression-test evidence. The text of this finding below is the original baseline evidence and is deliberately left unmodified (historical severity and trigger intact).

**Severity:** P2 Medium
**Confidence:** High
**State:** VERIFIED (`STATICALLY_PROVEN` control flow, framework behavior independently confirmed, generic failure-after-cancellation pattern reproduced)

#### Location

- `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:60-85`
- `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:63-97`
- Related translation sites:
  - `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:187-253`
  - `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:263-344`
- Deterministic caller outcomes:
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:256-378`
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:819-858`
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:863-1013`
- Additional failure accounting path:
  - `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:449-675`

#### Relevant Git Context

Commit `801608c` explicitly hardened `CancellationException` handling in ViewModels and `SyncEngine`, including `NonCancellable` cleanup and sibling-worker cancellation. It did not alter `SftpFileClient` or `SmbFileClient`. Its added cancellation tests use a suspending mock download and do not exercise a non-cooperative transport operation that produces an ordinary exception after the job is cancelled.

#### Trigger

1. A coroutine enters SMB/SFTP blocking I/O under `withContext(Dispatchers.IO)`.
2. The user cancels the sync, preview, browse, or delete operation while the blocking call is still running.
3. `Dispatchers.IO` does not interrupt the blocking thread; the call continues.
4. The transport subsequently times out or fails independently. Both connection implementations configure 5-second protocol/socket timeouts.
5. The client catches the resulting ordinary transport exception and wraps it as `SyncException` without first checking whether the coroutine job is cancelled.

#### Execution Path

For connection:

```text
cancel job
  → blocking SSHJ/jcifs operation continues
  → transport timeout/IOException
  → catch (Exception)
  → throw SyncException(..., cause = transport exception)
  → withContext completes exceptionally with SyncException
  → caller catch (CancellationException) does not match
  → generic failure/error branch executes
```

Kotlinx.coroutines 1.10.1 checks prompt cancellation at `withContext` entry and when a block completes normally. When a block completes exceptionally, that exception dominates the cancelled-job check and is propagated. Therefore the later transport exception is not automatically converted back into `CancellationException`.

Concrete outcomes:

- `SyncEngine.dryRunLocked`: clears derived lists as required by E8, but sets `FAILED` and returns a `DryRunResult` error rather than propagating cancellation.
- `SyncEngine.dryRunDelete`: returns an error message rather than cancellation.
- `SyncEngine.executeDeleteLocked`: sets `FAILED` and returns `false` when connection fails after cancellation.
- Download-worker connection: can increment failed-download accounting and write a FAILED sync-log entry rather than treating that worker as cancelled.
- Settings remote browse/test: CE-first ViewModel guards cannot recognize the translated `SyncException`, so a cancelled operation can be surfaced as a browse/test failure.

#### Evidence

1. **Code proof:** both transport `connect()` implementations execute blocking Java I/O in `withContext(Dispatchers.IO)` and translate every `Exception` into `SyncException`. No job-state check occurs before translation.
2. **Framework proof:** independent review of kotlinx.coroutines 1.10.1 behavior confirmed that exceptional completion is preserved; prompt cancellation does not replace an already-produced exception.
3. **Executable reproduction:** a temporary JVM test entered a non-cooperative blocking section, cancelled its parent while blocked, then released the block to throw `IOException("simulated transport timeout")`. The client-shaped catch translated it to `SyncException(TIMEOUT, CONNECT)`, and the observed outer exception was `SyncException` with the `IOException` cause. A second test reproduced direct CE wrapping. Both passed; the temporary test was removed.
4. **Caller proof:** the listed SyncEngine methods have CE-first catches followed by generic failure branches; the translated `SyncException` deterministically selects the generic branches.

#### Expected Behavior

After the user cancels an operation, a later transport exception from work that was already in flight must not convert the operation into a failure. Cancellation must remain observable as `CancellationException`, and caller cleanup should run under the established cancellation path.

#### Actual Behavior

The later transport exception is translated into `SyncException`; several callers report FAILED/error or increment failure counters. This violates the repository rule that `CancellationException` must propagate first.

#### Root Cause

Cancellation policy is enforced mainly by `catch (CancellationException)` clauses. Those clauses only help when the thrown object is itself a CE. A non-cooperative blocking operation can produce an ordinary timeout/transport exception after the job has already been cancelled. The transport boundary translates that exception without consulting the cancelled job state.

Adding only `catch (e: CancellationException) { throw e }` is useful but insufficient for this race because the caught exception can be an `IOException`, SSHJ exception, or SMB exception rather than CE.

#### Impact

- User sees an error/FAILED result for an operation they cancelled.
- Sync logs and per-file failure counters can be inaccurate.
- Core preview and delete flows can select the wrong terminal state.
- No local deletion or E8-derived remote deletion was shown to become unsafe; data-integrity guards remain intact.

#### Recommended Fix

At every transport exception-translation boundary, check coroutine activity before classifying or wrapping the exception:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    throw SyncException(..., cause = e)
}
```

Where cleanup is mandatory after cancellation, perform narrowly scoped cleanup under `NonCancellable`, then rethrow cancellation. Apply the policy consistently to connection, listing, download, and delete operations rather than relying on later incidental suspension points.

#### Recommended Regression Test

Introduce an injectable transport seam or fake blocking operation that:

1. signals that blocking I/O has started;
2. ignores cancellation while blocked;
3. allows the test to cancel the parent;
4. subsequently throws a transport exception;
5. asserts that the public client/engine operation finishes by cancellation, not `SyncException`, FAILED progress, or failed-action accounting.

Cover at least `connect()` through `dryRun`, `connect()` through `executeDelete`, and one per-file transport operation.

#### Adversarial Review

The finding was challenged twice:

- First challenge: whether `withContext` automatically restores CE. Official implementation review and a JVM probe disproved that for exceptional completion.
- Second challenge: whether cancellation itself interrupts Dispatchers.IO blocking I/O. It does not. The verified trigger was narrowed to a later, independent transport timeout/failure after cancellation; the report does not claim automatic thread interruption.

The download cleanup path was also traced separately. Current `store.cancel()` delegates to `FileVolume.delete()` or `SafVolume.delete()`, both of which enter another `withContext(IO)` and therefore currently restore cancellation at entry. That incidental recovery is excluded from the verified functional impact rather than overstated.

## 6. Architecture & Maintainability Observations

> **Remediation status (2026-08-23):** all four observations below were handled in the v12.2.0 remediation at `main` @ `d67882d`: 6.1/6.3 → cancellation now enforced at the transport boundary with explicit CE propagation (commit `31d9869`); 6.2 → `updateStarByPath` dead chain deleted, ID-scoped persistence retained (commit `3cdc672`); 6.4 → `SyncEngine` split into four lifecycle collaborators and `BrowserContentCoordinator` extracted from `BrowserViewModel` (commits `ec83906`, `c4f8052`). The observation text itself is unchanged baseline evidence; see §13.

### 6.1 Cancellation correctness is distributed across layers

The codebase recently added many CE-first catches, but transport clients can still erase the semantic distinction before those catches see the exception. Centralizing a helper such as “translate transport failure only if active” would reduce repeated policy and prevent future omissions.

### 6.2 `updateStarByPath` is an unsafe latent API even though its current branch is unreachable

`VideoItemDao.updateStarByPath(relativePath, star)` updates every relation sharing a path, while the schema only guarantees uniqueness for `(relativePath, syncRelationId)`. Current production item construction gives every file a database ID and routes folders to a separate menu, so the path-based file fallback was not reachable and was rejected as a bug. Removing the dead fallback/API, or adding `syncRelationId`, would prevent a future caller from activating cross-relation writes.

### 6.3 Some cancellation recovery is incidental

`downloadTo()` can catch and wrap a CE thrown by `yield()` or `ensureActive()`. The current engine then calls `store.cancel()`, whose production volume implementations enter another `withContext(IO)` and restore CE because the job is already cancelled. The final behavior is currently correct, but it depends on cleanup implementation details. Explicit CE handling at the client boundary would be easier to reason about and test.

### 6.4 `BrowserViewModel` and `SyncEngine` remain complexity hotspots

Git history and file size identify these as high-change, multi-responsibility components. No additional defect was verified from complexity alone, so this is not a finding. Future changes should prefer extracting independently testable policies rather than increasing branch density.

## 7. Rejected Hypotheses

### RH-001 — Star updates corrupt multiple sync relations

Initial suspicion: `VideoItemDao.updateStarByPath` lacks `syncRelationId`, and duplicate paths across relations are legal.

Further check: every production non-directory `BrowseItem` is constructed with a non-null `videoItemId`; folders alone have null IDs and are routed to a different context menu. The path-based fallback requires a file with null ID, which no current construction path produces.

**Verdict:** REJECTED as unreachable at current HEAD. Retained as a maintainability observation.

### RH-002 — A null restarted `SyncService` Intent starts relation 0

Initial suspicion: `intent == null` maps to `relationId=0L`.

Further check: the service returns `START_REDELIVER_INTENT`, internal start callers always pass the relation extra, the cancel action is handled before extraction, and relation ID 0 is an unused sentinel. Even the counterfactual path finds no pending actions and stops.

**Verdict:** REJECTED.

### RH-003 — Progress `tryLock` permanently loses download state

Initial suspicion: `SyncProgressPublisher.reportDownloadProgress` drops updates when its lock is busy and applies a roughly 500ms throttle.

Further check: only intermediate frames can be dropped. Completion uses a suspending lock and always publishes; final COMPLETED/PARTIAL_FAILED/CANCELLED/FAILED states are assigned directly; total bytes use atomic accounting.

**Verdict:** REJECTED. The UI may jump between intermediate percentages by design, but final state and totals remain consistent.

### RH-004 — Download CE wrapping always causes failure

Initial suspicion: SFTP/SMB `downloadTo` catches CE from `yield()`/`ensureActive()` and wraps it.

Further check: current SyncEngine generic cleanup calls `store.cancel()`, then production volume deletion enters `withContext(IO)` with an already-cancelled job and immediately rethrows CE. The worker cancellation path is restored.

**Verdict:** REJECTED as a current standalone functional defect; retained as an incidental-recovery maintainability concern.

## 8. Inconclusive Investigations

### IH-001 — Cancellation during an individual remote delete

The transport clients can translate an exception produced after cancellation, and the per-action delete loop can initially count that item as failed. Subsequent actions or repository suspensions are likely to re-observe cancellation, but final phase and accounting depend on exact timing and which action is last. This behavior is covered by CQ-001’s root cause, but a distinct additional impact was not promoted without a dedicated engine-level reproduction.

### IH-002 — Lint `TrustAllX509TrustManager` warning origin

Repository source search found no custom `TrustAllX509TrustManager` or `checkServerTrusted` implementation. The warning may originate in dependency/generated analysis. No source-level security finding was established.

## 9. Tests / Build / Tooling Results

- Kotlin debug compilation: passed.
- JVM debug unit tests: 352/352 passed across 31 suites.
- Android lint: passed with zero errors; 91 warnings and 1 hint.
- Targeted cancellation probes: two passed and were removed after use.
- No formatter was run.
- No production source file was modified.
- No dependency or build configuration was modified.

## 10. Coverage and Blind Spots

Strengths of the evidence:

- Multiple independent explorers covered architecture and correctness roles.
- Each serious candidate received a separate confirmation or adversarial pass.
- Framework semantics were checked independently rather than assumed.
- Candidate call paths were traced from UI/service entry to DAO/transport outcome.
- Baseline gates were re-run without cache reuse.

Blind spots:

- No Android device/emulator was attached, so `androidTest` and real service lifecycle execution were not run.
- No controlled live SMB/SFTP endpoint was used. CQ-001’s transport timing was established with configured timeout behavior, deterministic control flow, official coroutine semantics, and a client-shaped blocking probe rather than a real protocol session.
- Lint warnings inside dependencies/generated artifacts were not fully attributed.
- The audit sampled lower-risk UI/player code rather than exhaustively tracing every feature.

## 11. Prioritized Remediation Plan

> **Remediation status (2026-08-23):** items 1–5 below were executed in the v12.2.0 remediation (`31d9869`, `3cdc672`, `ec83906`, `c4f8052`, `d67882d`; implementation HEAD `d67882d`). The plan text is retained as baseline record; outcomes are in §13.

1. **Fix CQ-001 at the transport boundary.** Add CE-first handling plus `currentCoroutineContext().ensureActive()` before translating ordinary transport exceptions.
2. **Add blocking-failure-after-cancel regression tests.** Cover connection and delete paths through their public engine callers.
3. **Remove incidental cancellation dependencies.** Make `downloadTo` propagate CE explicitly instead of relying on `store.cancel()` entering another dispatcher context.
4. **Retire or scope `updateStarByPath`.** Prefer ID-based updates; otherwise require `syncRelationId` in DAO, repository, and use-case signatures.
5. **Triage lint debt separately.** Remove genuinely unused resources and identify the dependency source of the trust-manager label; do not mix that cleanup with correctness changes.

## 12. Audit Integrity

- Initial Git status: clean.
- Production code modified: no.
- Temporary test created: yes, solely to reproduce coroutine behavior.
- Temporary test retained: no; removed after successful execution.
- Global formatting: not run.
- Destructive Git operations: none.
- Commits/pushes: none.
- Persistent audit artifact: this report only.
- Audit workspace notes: removed after report consolidation.

Final `git status --short --untracked-files=all` contains only `?? docs/automatic/20260823-1758_deep-code-quality.md`. The requested report is therefore the sole worktree difference from the initial clean baseline; no temporary audit workspace or probe remains.

## 13. Remediation Outcome (2026-08-23)

> Added post-audit. Sections 1–12 above are the **unchanged audit baseline evidence** for `7dc00fc` (`v12.1.0`); severity levels, triggers, and evidence were not altered. This section is the centralized implementation record of the v12.2.0 remediation cycle (design: [`docs/ocmar/specs/2026-08-23-sync-player-12.2.0-remediation-design.md`](../ocmar/specs/2026-08-23-sync-player-12.2.0-remediation-design.md); plan: [`docs/ocmar/plans/2026-08-23-sync-player-12.2.0-remediation.md`](../ocmar/plans/2026-08-23-sync-player-12.2.0-remediation.md)).

**Status: RELEASED as `v12.2.0` at `3854bdc`** — code, docs, local gates, annotated tag, release CI, and signed APK publication are complete (see §13.5).

### 13.1 Baseline → implementation

- Audit baseline: `7dc00fc` (`v12.1.0`); implementation HEAD: `d67882d`; release/tag HEAD: `3854bdc`; design commit: `a80a7a8`; remediation outcome docs commit included in the tag: `3854bdc`.
- Remediation commits:
  - `31d9869` fix(sync): preserve cancellation across SMB and SFTP boundaries
  - `3cdc672` refactor(browser): remove unscoped star-by-path dead path
  - `ec83906` refactor(sync): split engine lifecycle collaborators
  - `c4f8052` refactor(browser): extract content coordinator
  - `d67882d` chore(android): clear project-owned lint findings

### 13.2 Finding / observation outcome

| Baseline item | Outcome at `d67882d` |
|---|---|
| CQ-001 (P2 Medium, §5) | **FIXED** (`31d9869`): 11 catch boundaries across `SftpFileClient`/`SmbFileClient` are CE-first + `currentCoroutineContext().ensureActive()` before wrapping/degrading; SFTP recursive traversal checks activity before blocking I/O; partial-connect ownership/cleanup established in the connect catch paths; public surface keeps only the `(Context)` constructors (test seams not public); new SFTP/SMB client and engine-level regression tests assert failure-after-cancellation terminates as `CancellationException` with no FAILED progress, failure accounting, or error sync-log. |
| §6.1 / §6.3 (distributed / incidental cancellation policy) | **ADDRESSED** (`31d9869`): cancellation enforced at the transport boundary itself; `downloadTo` propagates CE explicitly instead of relying on `store.cancel()` re-entering `withContext(IO)`. |
| §6.2 / RH-001 (`updateStarByPath` latent API) | **RESOLVED** (`3cdc672`): dead path-based chain deleted end-to-end (DAO → repository → use case → VM overrides → context-menu fallback); ID-scoped `updateStar` persistence retained. |
| §6.4 (complexity hotspots) | **ADDRESSED** (`ec83906`, `c4f8052`): `SyncEngine` split into `SyncPendingTaskManager`, `SyncDeleteExecutor`, `SyncRemoteScanner`, `SyncDownloadPipeline` — Engine retains shared progress publisher, mutexes, pending-task finalize, and `NonCancellable` orchestration; safety red lines unchanged. `BrowserContentCoordinator` extracted from `BrowserViewModel`; public API and Compose behavior preserved. |
| §11.5 / lint debt (91 warnings + 1 hint at baseline) | **DONE, project scope** (`d67882d`): project source/resource lint findings 67 → 0; the 25 remaining warnings are all toolchain/dependency/third-party (`OldTargetApi`, `AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`, `Aligned16KB`, `TrustAllX509TrustManager`); no dependency or toolchain upgrades were made. |

### 13.3 Phase reviews (during remediation)

Transport lane review: PASS. SyncEngine split review (rev-sgpt): PASS. Browser review (rev-sgpt): PASS. Phase 5 lint review (rev-sgpt): PASS.

### 13.4 Final independent local gate (HEAD `d67882d`)

Command: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin testDebugUnitTest lintDebug --rerun-tasks --no-daemon`

- BUILD SUCCESSFUL in 3m10s; 51/51 actionable tasks executed (live rerun, no cached evidence tasks).
- Unit tests: 39 suites / 394 tests / 0 failures / 0 errors / 0 skipped.
- Lint: 25 warnings / 0 errors — all located in `build.gradle.kts`, `gradle-wrapper.properties`, `libs.versions.toml`, and third-party artifacts; findings under `app/src/` = 0.
- `git diff --check`: clean.
- Log: `/home/mar/personal_projects/sync-player/.ocmar/workflows/v12.2.0-final-verify/verify-1787494440.log`.
- Supplementary integrity check: `git diff v12.1.0..HEAD --stat -- app/src/main/java/cn/vectory/syncplayer/crypto/` is empty (crypto/AEAD v2 domain untouched).

### 13.5 Release outcome

- Remediation docs were committed as `3854bdc` and `main` was pushed cleanly.
- `JAVA_HOME=/home/mar/android-studio/jbr ./scripts/release.sh 12.2.0` passed its local `compileDebugKotlin + testDebugUnitTest + lintDebug` gate (BUILD SUCCESSFUL in 2m17s), created annotated tag `v12.2.0`, and pushed it. The tag peels to `3854bdc`.
- Gitea Actions release run [`#35`](https://git.vectory.cn:18443/mfreceiver/sync-player/actions/runs/35) completed with conclusion **success** (`2026-08-23 22:36:42+08:00` → `22:45:49+08:00`).
- Release [`v12.2.0`](https://git.vectory.cn:18443/mfreceiver/sync-player/releases/tag/v12.2.0) is published (not draft/prerelease) with `sync-player-12.2.0-3854bdc.apk` (13,437,831 bytes) and `SHA256SUMS`.
