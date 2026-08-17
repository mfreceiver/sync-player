# Phase 2：安全红线逐条核查
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账（详见文末"最终对账"节）:
  - R2 删除候选: `grep -rnE '(delete|remove|purge|clear|unlink)' --include='*.kt' app/src/main/java/cn/vectory/syncplayer` | scan_total=363 | adjudicated=86（LOCAL_MEDIA 26 + LOCAL_THUMBNAIL 9 + LOCAL_DB 51，守卫判定见 2.2） | excluded_scope_or_FP=277（REMOTE 5 + TEMP 5 + FALSE_POSITIVE 267） | unknown=0
- 唯一发现: P0=10, P1=17, P2=4（其中 CONFIRMED=13, CANDIDATE=18）；引用他 Phase 的 REF: 0 条（不计入）
- U 条目: 0

## 2.1 R1（E8 扫描失败保护）

### 扫描
`grep -rn "E8" --include='*.kt' app/src`（exit=0，命中 19 行）：定义/守卫位于 `sync/RemoteFileClient.kt:15`、`sync/RemoteScanIncompleteException.kt:6`、`sync/SyncEngine.kt:57,343,357,364`、`sftp/SftpFileClient.kt:154,158`、`smb/SmbFileClient.kt:221,225,233`；测试位于 `SyncEngineDryRunTest.kt:36,145,189,242`。

### CHECK-2-1 [PASS] [R1/E8]
- 期望: 红线 1（E8 扫描失败保护）；sync.md:49 "部分远端快照**绝不**推导 `DELETE_LOCAL`"
- 检查范围: `SyncEngine.dryRunLocked` 全函数（app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:249-372）
- 证据: 完整分支/出口清单——
  1. 251-252：relation/source 不存在 → `return DryRunResult(emptyList(), emptyList())`（空列表，安全）
  2. 262 try 起，264 `client.listFiles`（唯一远端快照来源）
  3. 268-316：toDownload 推导（try 内）
  4. 318-337：**DELETE_LOCAL 推导唯一发生地**（`localVideo.relativePath !in remoteLocalPaths` → toDelete.add，全部在 try 内、快照完整之后）
  5. 340-341：`catch (e: CancellationException) { throw e }`（取消先传播）
  6. 342-359：`catch (e: Exception)` → **守卫本体**（逐字引用 347-348）：
     ```
     toDownload.clear()
     toDelete.clear()
     ```
     并置 `phase=FAILED`、log SCAN FAILED、`scanError = wrapSyncException(e)`
  7. 360-362 finally：`client.disconnect()`
  8. 366-371：`return DryRunResult(toDownload, toDelete, scanError)`——注释明确"到达这里的唯一「非空」路径是 try 块完整跑完"
- 判定: PASS。所有出口均不可能携带残缺快照派生的 toDelete：早退出口为空列表，异常出口清空两列表，唯一非空路径为完整扫描。辅助证据：`grep -n "toDelete" app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt` 显示 toDelete 添加仅发生在 328（try 内）与函数入参传递。

### CHECK-2-2 [PASS] [R1/E8-测试存在]
- 期望: 任务书 Phase 2 R1"查『扫描失败→不产生删除』测试"
- 检查范围: app/src/test
- 证据: `grep -rn "E8" app/src/test`（exit=0）→ `SyncEngineDryRunTest.kt:189`：`assertEquals("scan failure must NEVER produce DELETE_LOCAL — this is the E8 guard", 0, result.toDelete.size)`
- 判定: PASS（E8 失败不删测试存在；行为覆盖的完整判定在 Phase 7 CHECK 复核）。

R1 结论：守卫存在且完备，测试存在 → 无 Finding。

## 2.2 R2（卷所有权 fail-closed）

### 候选全清单分类
扫描命令（exit=0）：`grep -rnE '(delete|remove|purge|clear|unlink)' --include='*.kt' app/src/main/java/cn/vectory/syncplayer`，scan_total=**363**。逐行分类见附录 A（行号|分类|grep 原文）。对账：LOCAL_MEDIA 26 + LOCAL_THUMBNAIL 9 + LOCAL_DB 51 = 86 adjudicated；REMOTE 5 + TEMP 5 + FALSE_POSITIVE 267 = 277 excluded；classification_unknown = 0；86+277=363 ✓。

### 卷校验裁定（仅 LOCAL_* 三类 + DELETE_BOTH 本地半段）

| 删除执行点 | 删前卷比对 | 不匹配处置 | 判定 |
|---|---|---|---|
| SyncEngine DELETE_LOCAL（SyncEngine.kt:663-686） | `videoItem.volumeId != store.volumeId`（663） | 不删 + `failedDeleteCount++`（668）+ log SKIPPED | PASS（fail-closed 且计 FAILED） |
| SyncEngine DELETE_BOTH 本地半段（SyncEngine.kt:867-886） | 同上（867） | 不删本地不删远端 + `failedActions++`（868）+ log SKIPPED | PASS |
| SyncEngine 下载失败/取消清理（SyncEngine.kt:563-575） | 无需——`assignedId` 为本次下载在活跃 store 上自建（`existing == null` 才删行） | 自有产物回滚 | PASS |
| FileOperationsUseCase.deleteMediaByIdLocked（FileOperationsUseCase.kt:469-475） | `video.volumeId != currentVolumeId`（含空/未知，470） | 密文/缩略图不动，转 `deleteOtherVolumeIndexLocked`（474，仅删 DB 行） | PASS（不删文件）；计 FAILED 维度见 F-2-1 CANDIDATE |
| SyncEngine.deleteStoreMedia/deleteStoreThumb（993-1017） | 调用点（688/889）均在上述守卫之后 | — | PASS |
| EncryptedMediaStore.delete/deleteThumb（123/173） | facade 原语，仅经上述受守卫路径调用 | — | PASS |
| EncryptedMediaStore v1 purge（219-328） | 非实体驱动：按文件名模式清理当前卷 v1 遗留密文（crypto 契约，marker 幂等） | fail-closed（不可读即保留，336） | PASS（契约清理，Phase 6 C-CR 复核） |
| StorageManagementViewModel.resetStorage（114-157） | 非实体驱动：用户主动整卷重置，持 `storageOperationMutex`（123），仅作用于 `mediaVolumeProvider.current()` 活跃卷 | 删除失败计数 `failed++` 并告警（149） | PASS（功能语义即卷级擦除） |
| SafVolume.openWrite 覆盖删除（SafVolume.kt:79）/ delete 原语（127-132）/ FileVolume.delete（31） | 卷内原语：只能操作本卷（treeUri/resolve 内限定） | — | PASS（越卷不可能由构造保证） |
| LOCAL_DB 独立删除（日志清理/关系/源/pending 清理，共 51 行） | DB 行非卷持有对象，无卷语义；与文件耦合的 DB 删除（deleteMediaRecord 等）均随上述文件守卫 | — | PASS |

### CHECK-2-3 [PASS] [R2]
- 期望: 红线 2；sync.md:51-52（跳过 + 计 FAILED + log SKIPPED）
- 检查范围: 上表全部 LOCAL 删除执行点（文件:行见表）
- 证据: SyncEngine.kt:663-668 逐字引用：
  ```
  if (videoItem != null && videoItem.volumeId != store.volumeId) {
  ```
  ```
  failedDeleteCount.incrementAndGet()
  ```
  以及 FileOperationsUseCase.kt:470-474 守卫引用见上表。
- 判定: PASS——所有 LOCAL_MEDIA/LOCAL_THUMBNAIL 实体驱动删除均有删前卷比对且不匹配时不删文件；sync 路径同时计 FAILED。

### F-2-1 [P2] [CANDIDATE] [R2]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:592
- 代码: `DeleteMediaResult.Success(video.id)`
- 问题: 浏览器删除路径卷不匹配时执行 index-only 删除后返回 `Success`，红色线 2 字面要求"计 FAILED"。文件安全（不删）已满足；但"Success"会掩盖"密文仍留在另一卷"的事实（sync.md:51-52 的"计 FAILED"条款字面上仅限定 sync 引擎流，浏览器路径是否适用 spec 存在歧义，故 CANDIDATE 供人工裁决）。
- 复现: grep -n 'DeleteMediaResult.Success' app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt （exit=0，命中 2 行）
- 建议: 引入专用结果类型（如 SkippedVolumeMismatch）或在 UI 提示"密文保留于其他卷"，与红色线 2 对齐。

R2 结论：无 P0；1 条 P2 CANDIDATE。

（R3-R7 见后续章节）

## 附录 A：R2 候选全清单分类（363 行；格式 行号|分类|grep 原文；扫描命令见 2.2）

```
1|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:35:        purgeLegacyCiphertext()
2|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:42:     * [EncryptedMediaStore.purgeLegacyIfNeeded] 通过卷根 marker 保证每个卷只执行一次，
3|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:46:    private fun purgeLegacyCiphertext() {
4|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:50:                    encryptedMediaStore.get().purgeLegacyIfNeeded()
5|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:53:                .onSuccess { if (it > 0) Log.i(TAG, "legacy v1 ciphertext purge: removed $it file(s)") }
6|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:54:                .onFailure { Log.w(TAG, "legacy v1 ciphertext purge skipped", it) }
7|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncLogRepository.kt:26:    suspend fun clearOldLogs() {
8|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncLogRepository.kt:28:        syncLogDao.deleteOlderThan(cutoff)
9|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:74:    suspend fun deleteByRelativePath(relativePath: String) =
10|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:75:        videoItemDao.deleteByRelativePath(relativePath)
11|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:77:    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long) =
12|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:78:        videoItemDao.deleteByRelativePathAndRelationId(relativePath, syncRelationId)
13|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:80:    suspend fun deleteById(id: Long) = videoItemDao.deleteById(id)
14|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:82:    /** Atomically remove playback row + video_item row for [id]. */
15|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:83:    suspend fun deleteMediaRecord(id: Long) {
16|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:85:            appDatabase.playbackRecordDao().deleteByVideoId(id)
17|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:86:            videoItemDao.deleteById(id)
18|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt:90:    suspend fun deleteAll() = videoItemDao.deleteAll()
19|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:80:    suspend fun deleteById(id: Long)
20|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:83:    suspend fun deleteByRelativePath(relativePath: String)
21|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:86:    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long)
22|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:89:    suspend fun deleteAll()
23|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt:99:    /** Relation-scoped path prefix query for deleteFolder isolation. */
24|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncRelationRepository.kt:98:    suspend fun delete(id: Long) {
25|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncRelationRepository.kt:99:        syncRelationDao.deleteById(id)
26|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/navigation/ImageQueue.kt:9:    // AtomicReference makes consumeIds() an atomic read-and-clear. The previous
27|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/navigation/ImageQueue.kt:10:    // read-then-clear two-step could race across coroutines and double-consume
28|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:18:    suspend fun deleteByRelationId(relationId: Long)
29|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:22:        deleteByRelationId(relationId)
30|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:30:            deleteByRelationId(relationId)
31|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PendingSyncActionDao.kt:54:    suspend fun deleteAll()
32|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PlaybackRecordDao.kt:31:    suspend fun deleteByVideoId(videoItemId: Long)
33|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/PlaybackRecordDao.kt:84:    suspend fun deleteAll()
34|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:108:    fun clearScrollPosition(folder: String) {
35|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:109:        _scrollPositions.remove(folder)
36|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:192:    // private val playbackRecords removed in favor of per-folder queries
37|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:485:    override fun deleteFolder(path: String) {
38|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:486:        fileOps.deleteFolder(path, basePaths.value, viewModelScope, _error) { _refreshTrigger.value++ }
39|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:489:    fun clearError() {
40|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:535:    override fun deleteFile(videoItemId: Long) {
41|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:540:     * Deferred hard-delete with an [onSuccess] callback invoked ONLY after the use-case has
42|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:541:     * recorded the pending delete and bumped the refresh trigger. The caller defers the undo
43|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:542:     * Snackbar until then, so a delete that fails before recording state never shows a
44|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:543:     * "deleted" Snackbar.
45|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:545:    fun deleteFileWithUndo(videoItemId: Long, onSuccess: () -> Unit) {
46|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:553:     * Undo the most recent delete operation (trash toggle or deferred hard delete).
47|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:560:     * Commit the deferred hard delete when the undo snackbar expires without action.
48|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:578:     * deferred hard delete is never silently lost when the BrowserScreen composition disposes
49|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:581:     * [expectedId] scopes the commit to the specific pending delete observed at dispose time,
50|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:601:     * On ViewModel clear (page leave / fragment destroy), commit any pending deferred
51|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:602:     * hard delete so it is never silently lost.
52|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:606:     * hard delete would leak forever). [UndoDeleteUseCase.commitPendingSafely] uses its
53|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt:31:    override suspend fun delete(relPath: String): Boolean = resolve(relPath).delete()
54|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchScreen.kt:117:                                contentDescription = stringResource(R.string.search_clear)
55|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchScreen.kt:308:            viewModel.clearError()
56|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserSortFilter.kt:296:                    stringResource(R.string.browser_clear_filter),
57|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:57:            // 关键：先快照旧密码。DB 写入失败时必须**恢复旧值**而不是 remove ——
58|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:72:                    else remove("pw_${source.id}")
59|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:85:                syncSourceDao.deleteById(savedId)
60|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:94:     * save/delete interleaving can leave the DB row alive with its password removed (delete
61|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:95:     * wipes prefs between save's prefs-write and DB-upsert), or resurrect a deleted source.
62|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:98:    suspend fun delete(id: Long) = saveMutex.withLock {
63|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:99:        syncSourceDao.deleteById(id)
64|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/SyncSourceRepository.kt:100:        encryptedPrefs.edit().remove("pw_$id").commit()
65|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/PlaybackRepository.kt:82:    suspend fun deleteByVideoId(videoItemId: Long) =
66|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/repository/PlaybackRepository.kt:83:        playbackRecordDao.deleteByVideoId(videoItemId)
67|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:135:                        path = fullPath.removePrefix(path).removePrefix("/"),
68|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:173:                val relPath = fullPath.removePrefix(rootPath).removePrefix("/")
69|REMOTE|app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:242:    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
70|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:30:/** The kind of delete operation that can be undone. */
71|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:36:     * Toggle trashed flag (soft delete).
72|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:43:     * Hard delete (cipher + thumb + DB).
73|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:58: * Encapsulates undo support for delete operations across Browser, Player, and ImageViewer.
74|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:66: *   Undo cancels the pending deletion (the file was never actually deleted).
75|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:72: * - Sequential hard deletes auto-commit the old pending delete before setting the new one,
76|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:73: *   so no pending delete is ever silently lost.
77|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:75: * Only the most recent delete operation is retained (latest-first semantics per task requirement 6).
78|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:101:     * which would leak a pending hard delete forever).
79|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:187:     * Perform a hard delete with undo support (deferred execution).
80|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:191:     * Undo cancels the pending deletion (the file was never actually deleted).
81|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:193:     * If there is already a pending hard delete for a DIFFERENT id, it is auto-committed
82|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:194:     * (executed) before the new one is recorded — ensuring no pending delete is silently lost.
83|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:206:                    // Auto-commit the old pending delete before recording the new one.
84|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:210:                            fileOps.deleteMediaById(existing.id)
85|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:216:                                // Old pending delete failed (e.g. IO/permission). Do NOT silently
86|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:218:                                Log.e(TAG, "Auto-commit of old pending delete failed id=${existing.id}: ${result.error.code}", result.error)
87|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:225:                        Log.e(TAG, "Failed to auto-commit old pending delete id=${existing.id}", e)
88|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:236:     * Undo the most recent delete operation.
89|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:241:     * - [UndoableDelete.HardDelete]: cancel the pending deletion (file was never actually deleted).
90|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:257:                // Deferred delete was never executed — just cancel it (idempotent)
91|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:264:     * Commit the deferred hard delete (called when Snackbar expires without undo).
92|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:280:        // Clear the state before executing (so a concurrent undo won't double-clear)
93|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:284:                fileOps.deleteMediaById(state.id)
94|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:293:            Log.e(TAG, "Failed to commit deferred delete id=${state.id}", e)
95|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:303:     * Commit any pending hard delete using the internal [safetyScope], with a [NonCancellable]
96|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:309:     * delete would never execute and be silently lost.
97|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:316:     *   Prevents a stale dispose (from an earlier delete) from committing a newer operation.
98|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:335:    fun clearUndoState() {
99|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt:364:                // Entity gone (e.g., already deleted by another operation) — nothing to restore.
100|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:32:    /** Directory for auto-generated video thumbnails. Self-healing: auto-creates if deleted. */
101|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:36:    /** Directory for debug log files. Self-healing: auto-creates if deleted. */
102|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/StoragePaths.kt:52:            localRelPath.removePrefix("$basePath/")
103|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:193:    fun clearBrowseError() {
104|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:293:    fun deleteRelation() {
105|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:296:            syncRelationRepository.delete(relationId)
106|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:297:            _saveResult.value = context.getString(R.string.relation_delete_success)
107|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:186:    fun deleteFile(
108|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:195:                    syncEngine.storageOperationMutex.withLock { deleteMediaByIdLocked(videoItemId) }
109|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:206:                Log.e(TAG, "Failed to delete file id=$videoItemId", e)
110|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:290:     *  - If anything throws after [outFile] is opened, [outFile] is deleted in the cleanup
111|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:292:     *  - [CancellationException] propagates verbatim (the cleanup `runCatching { outFile.delete() }`
112|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:329:                    // Never leave a partial file behind: delete unconditionally, then rethrow.
113|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:330:                    // CancellationException still propagates (runCatching swallows only the delete's
114|TEMP|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:332:                    runCatching { outFile.delete() }
115|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:360:    fun deleteFolder(
116|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:371:                    errorFlow.value = context.getString(R.string.error_cannot_delete_base_dir)
117|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:383:                    errorFlow.value = context.getString(R.string.error_cannot_delete_sync_dir)
118|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:387:                    syncEngine.storageOperationMutex.withLock { deleteFolderContentsLocked(path) }
119|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:393:                        R.string.error_folder_delete_partial,
120|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:402:                Log.e(TAG, "Failed to delete folder", e)
121|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:411:     * [deleteMediaById] — mismatched volumes use index-only deletion instead of ciphertext access.
122|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:413:    private suspend fun deleteFolderContentsLocked(path: String): FolderDeleteSummary {
123|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:425:            when (val r = deleteMediaByIdLocked(item.id)) {
124|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:428:                    // Already cleaned ciphertext; DB removed as part of skip path.
125|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:445:     * - Missing ciphertext (NotFound / !exists): treat as success and still remove DB.
126|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:450:     *   (pre-tracking record) — routes through [deleteOtherVolumeIndex] (index-only delete,
127|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:455:    suspend fun deleteMediaById(id: Long): DeleteMediaResult {
128|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:456:        return syncEngine.storageOperationMutex.withLock { deleteMediaByIdLocked(id) }
129|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:460:    internal suspend fun deleteMediaByIdLocked(id: Long): DeleteMediaResult {
130|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:464:        // N3: volume ownership guard — fail-closed. Only delete ciphertext/thumb on the active
131|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:468:        // never clean via the app. Index-only delete is the honest, recoverable choice.
132|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:471:            Log.w(TAG, "deleteMediaById: id=$id volume='${video.volumeId}' not confirmed on " +
133|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:472:                "active='$currentVolumeId' (different or empty/unknown); index-only delete, " +
134|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:474:            return deleteOtherVolumeIndexLocked(id)
135|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:491:                // FileVolume/SafVolume: false = missing OR delete failed (cannot tell alone).
136|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:492:                val deleted = mediaStore.delete(id)
137|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:493:                if (!deleted) {
138|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:499:                        Log.w(TAG, "exists() after delete failed for id=$id", e)
139|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:505:                        Log.e(TAG, "ciphertext still present after delete returned false for id=$id")
140|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:521:                    Log.e(TAG, "Failed to delete ciphertext for id=$id", e)
141|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:529:        // M2: Boolean false from deleteThumb is ambiguous (missing vs fail). Re-check existence.
142|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:531:            val deleted = mediaStore.deleteThumb(id)
143|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:532:            if (!deleted) {
144|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:538:                    Log.w(TAG, "thumbExists() after delete failed for id=$id", e)
145|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:544:                    Log.e(TAG, "thumb still present after delete returned false for id=$id")
146|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:557:                Log.e(TAG, "Failed to delete thumb for id=$id", e)
147|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:565:            videoRepository.deleteMediaRecord(id)
148|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:571:            Log.e(TAG, "Failed to delete DB rows for id=$id", e)
149|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:580:     * (ids on inactive volumes must not drive mediaStore.delete on the active store).
150|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:582:    suspend fun deleteOtherVolumeIndex(videoItemId: Long): DeleteMediaResult {
151|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:583:        return syncEngine.storageOperationMutex.withLock { deleteOtherVolumeIndexLocked(videoItemId) }
152|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:587:    internal suspend fun deleteOtherVolumeIndexLocked(videoItemId: Long): DeleteMediaResult {
153|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:591:            videoRepository.deleteMediaRecord(video.id)
154|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:596:            Log.w(TAG, "deleteOtherVolumeIndex failed for id=$videoItemId", e)
155|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt:197:    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
156|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncRelationDao.kt:51:    suspend fun deleteById(id: Long)
157|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/data/repository/UserPreferencesRepository.kt:109:        dataStore.edit { if (uri != null) it[Keys.MEDIA_STORAGE_TREE_URI] = uri else it.remove(Keys.MEDIA_STORAGE_TREE_URI) }
158|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt:79:            context.contentResolver.delete(
159|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt:127:    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
160|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt:131:            context.contentResolver.delete(uri, null) > 0
161|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncSourceDao.kt:27:    suspend fun deleteById(id: Long)
162|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt:62:            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
163|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt:76:    fun clear() = synchronized(lock) { entries.clear() }
164|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/navigation/VideoQueue.kt:9:    // AtomicReference makes consumeIds() an atomic read-and-clear. The previous
165|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/navigation/VideoQueue.kt:10:    // read-then-clear two-step could race across coroutines and double-consume
166|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:131:    fun clearError() { _error.value = null }
167|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:157:    override fun deleteFile(videoItemId: Long) {
168|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:158:        fileOps.deleteFile(videoItemId, viewModelScope, _error)
169|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:172:    override fun deleteFolder(path: String) {
170|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/SearchViewModel.kt:173:        fileOps.deleteFolder(path, basePaths.value, viewModelScope, _error)
171|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolume.kt:17:    suspend fun delete(relPath: String): Boolean
172|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/data/local/dao/SyncLogDao.kt:25:    suspend fun deleteOlderThan(beforeTimestamp: Long)
173|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:96:    // Tracks the id of a file currently pending deferred hard delete, so the context
174|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:97:    // menu can disable its delete action and prevent duplicate deferred deletions.
175|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:99:    // The most recent id for which a deferred hard delete was initiated. Captured so the
176|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:103:    // True while this composition is active. The deleteFileWithUndo onSuccess callback reads
177|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:106:    // path is unreachable and would silently lose the deferred delete).
178|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:113:            // Fallback: commit any still-pending deferred hard delete via the safety path so
179|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:116:            // (state cleared) or a newer operation now holds the slot.
180|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:253:    val undoDeleteMsg = stringResource(R.string.undo_delete_message)
181|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:281:                    viewModel.deleteFileWithUndo(id) {
182|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:282:                        // onSuccess: the use-case recorded the pending delete and refreshed the
183|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:296:                            // deferred delete via the safety path so it is not silently lost.
184|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:336:                    viewModel.clearScrollPosition(path)
185|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:348:            viewModel.clearError()
186|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:394: * Handle hard delete in browser: the delete is deferred by UndoDeleteUseCase (UI already
187|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:396: * If the Snackbar is dismissed without undo, we commit the deferred delete.
188|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:399: * @param onResolved Callback to clear the pending-delete guard in the UI.
189|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/browser/BrowserScreen.kt:400: * @param message Resolved "deleted" message (pre-resolved to avoid @Composable context).
190|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt:94:            if (path != null) context.getString(R.string.sync_error_remote_delete_failed_path, path)
191|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt:95:            else context.getString(R.string.sync_error_remote_delete_failed)
192|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:70:            activeDownloadsMap.clear()
193|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:71:            completedPathsSet.clear()
194|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:169:            activeDownloadsMap.remove(relativePath)
195|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:180:    suspend fun removeActiveDownload(relativePath: String) {
196|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:182:            activeDownloadsMap.remove(relativePath)
197|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:71:        props.clear()
198|TEMP|app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:91:            tmpFile.delete()
199|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:36:    fun deleteFile(videoItemId: Long)
200|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:46:    fun deleteFolder(path: String)
201|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:71: * Dispatch a file-menu delete tap. Same fallback contract as [dispatchFileTrashAction], but for
202|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:72: * hard delete via [FileContextMenuActions.deleteFile].
203|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:79:    if (callback != null) callback(id) else actions.deleteFile(id)
204|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:88: * @param onDeleted Called with the video item id when the user taps the delete action.
205|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:89: *   The host is responsible for performing the deferred delete (with undo support) and showing a snackbar.
206|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:223:                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
207|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:291:                            actions.deleteFolder(item.relativePath)
208|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/components/ItemContextMenu.kt:295:                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
209|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:212:                pendingSyncActionDao.deleteByRelationId(relationId)
210|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:218:        pendingSyncActionDao.deleteByRelationId(relationId)
211|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:347:                toDownload.clear()
212|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:348:                toDelete.clear()
213|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:563:                                                try { store.delete(assignedId) } catch (_: Exception) {}
214|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:564:                                                videoRepository.deleteById(assignedId)
215|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:570:                                            if (existing == null) videoRepository.deleteById(assignedId)
216|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:575:                                            if (existing == null) videoRepository.deleteById(assignedId)
217|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:586:                                    progressPublisher.removeActiveDownload(action.relativePath)
218|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:627:                                    progressPublisher.removeActiveDownload(action.relativePath)
219|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:688:                    val mediaGone = deleteStoreMedia(store, videoItem.id)
220|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:689:                    val thumbGone = deleteStoreThumb(store, videoItem.id)
221|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:691:                        playbackRepository.deleteByVideoId(videoItem.id)
222|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:692:                        videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
223|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:712:                                R.string.sync_error_local_delete_incomplete, mediaGone, thumbGone
224|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:752:            syncLogRepository.clearOldLogs()
225|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:888:                    val mediaGone = deleteStoreMedia(store, videoItem.id)
226|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:889:                    val thumbGone = deleteStoreThumb(store, videoItem.id)
227|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:900:                                R.string.sync_error_local_delete_incomplete, mediaGone, thumbGone
228|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:905:                    playbackRepository.deleteByVideoId(videoItem.id)
229|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:906:                    videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
230|REMOTE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:912:                    client.deleteFile(remoteRelPath)
231|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:913:                    // true = deleted; false (SMB !exists) = already gone → both OK
232|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:955:                    context.getString(R.string.sync_delete_success, completedActions)
233|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:960:                    context.getString(R.string.sync_delete_failed, "local/remote incomplete: ok=$completedActions fail=$failedActions")
234|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:963:            syncLogRepository.clearOldLogs()
235|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:991:     * @return true if media is gone (deleted or never present); false if still present after failed delete.
236|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:993:    private suspend fun deleteStoreMedia(store: EncryptedMediaStore, id: Long): Boolean {
237|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:995:            val deleted = store.delete(id)
238|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:996:            if (deleted) true
239|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:999:            Log.w(TAG, "Failed to delete media id=$id", e)
240|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:1011:    private suspend fun deleteStoreThumb(store: EncryptedMediaStore, id: Long): Boolean {
241|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:1013:            val deleted = store.deleteThumb(id)
242|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:1014:            if (deleted) true
243|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:1017:            Log.w(TAG, "Failed to delete thumb id=$id", e)
244|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:119:                        text = { Text(stringResource(R.string.sync_preview_delete_label)) }
245|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:190:                                Text(stringResource(R.string.sync_preview_confirm_delete))
246|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:250:                            onDismiss = { viewModel.clearError() }
247|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:359:                                    deleteCount = remainingToDelete.size,
248|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:360:                                    deleteSize = remainingToDelete.sumOf { it.fileSize }
249|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:404:    deleteCount: Int,
250|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:405:    deleteSize: Long
251|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:417:                text = stringResource(R.string.sync_preview_download_summary, downloadCount, formatFileSize(downloadSize), deleteCount, formatFileSize(deleteSize)),
252|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:439:                text = stringResource(R.string.sync_preview_delete_summary, count, formatFileSize(totalSize)),
253|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:467:                text = stringResource(R.string.sync_preview_delete_warning),
254|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewScreen.kt:501:                contentDescription = if (isDownload) stringResource(R.string.sync_preview_content_desc_download) else stringResource(R.string.sync_preview_content_desc_delete),
255|REMOTE|app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:327:    override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
256|REMOTE|app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:337:            smbFile.delete()
257|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:18: *  2. **Disk growth** — every share leaves a temp file behind (we cannot delete it before the
258|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:43:     * currently being handed off is never deleted mid-read. [DEFAULT_MAX_AGE_MS] (1h) is plenty.
259|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:55:        var deleted = 0
260|TEMP|app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:60:                if (f.delete()) deleted++
261|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:63:        return deleted
262|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:188:                                        activeFiles.remove(video.id)
263|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:117:    fun clearMediaStorageTreeUri() {
264|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:135:    fun clearLogs() {
265|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:138:                syncLogRepository.clearOldLogs()
266|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:140:                Log.e(TAG, "Failed to clear logs", e)
267|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:75:        viewModel.clearBrowseError()
268|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:236:                        contentDescription = stringResource(R.string.common_delete),
269|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:240:                    Text(stringResource(R.string.common_delete))
270|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:265:            title = { Text(stringResource(R.string.relation_delete_confirm_title)) },
271|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:266:            text = { Text(stringResource(R.string.relation_delete_confirm_text)) },
272|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:269:                    viewModel.deleteRelation()
273|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditScreen.kt:272:                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
274|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/AboutScreen.kt:117:                                onClick = viewModel::clearLogs,
275|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/AboutScreen.kt:120:                                Text(stringResource(R.string.about_clear_logs))
276|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:72:            // (CancellationException would otherwise skip the suspend delete, leaving a 0-byte file).
277|TEMP|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:73:            try { withContext(NonCancellable) { vol.delete(tmpMedia(id)) } } catch (_: Exception) {}
278|TEMP|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:92:        volume().delete(tmpMedia(id))
279|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:123:    suspend fun delete(id: Long): Boolean = volume().delete(finalMedia(id))
280|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:173:    suspend fun deleteThumb(id: Long): Boolean = volume().delete(MediaPaths.thumbRelPath(id))
281|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:175:    // ---- legacy v1 ciphertext purge (one-shot, post-v2-upgrade) ----
282|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:177:    /** Marker file written at the current volume root once [purgeLegacyIfNeeded] has run there. */
283|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:178:    private val purgeMarker = ".spm2_purged"
284|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:193:     * **Deliberately NOT deleted** (fail-closed):
285|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:194:     *  - `*.enc.tmp` partial downloads. [purgeLegacyIfNeeded] runs concurrently with active
286|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:196:     *    between purge and the sync service). A leftover `.tmp` is harmlessly
287|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:198:     *    nothing but reintroduces a delete-while-writing race (the very race that made the
288|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:209:     * [.spm2_purged] at the volume root guards re-scanning.
289|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:213:     * other user data is enumerated or deleted.
290|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:217:     * startup code outside this module. Returns the number of files deleted.
291|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:219:    suspend fun purgeLegacyIfNeeded(): Int {
292|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:221:        if (vol.exists(purgeMarker)) return 0
293|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:222:        var deleted = 0
294|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:233:                deleted += purgeBucket(vol, bucket)
295|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:235:                deleted += purgeThumbs(vol)
296|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:240:        runCatching { vol.openWrite(purgeMarker).use { it.write(byteArrayOf(1)) } }
297|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:241:        return deleted
298|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:248:     * Tmp files are intentionally *not* deleted here (see [purgeLegacyIfNeeded]); they are
299|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:251:    private suspend fun purgeBucket(vol: MediaVolume, bucket: Long): Int {
300|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:253:        var deleted = 0
301|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:258:            // Current-format .enc.tmp files are intentionally NOT purged here (overwritten on
302|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:264:                    if (legacyId != null && legacyId / 1000 == bucket && vol.delete("$bucketDir/$name")) deleted++
303|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:269:            // anything else are skipped (not deleted).
304|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:275:                if (vol.delete(rel)) deleted++
305|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:278:        return deleted
306|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:308:     * Rule: delete a thumbnail only when **no v2 media file exists for its id**. That keeps
307|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:311:     * Names that are not exactly `<id>.enc` are skipped, mirroring [purgeBucket].
308|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:313:    private suspend fun purgeThumbs(vol: MediaVolume): Int {
309|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:314:        var deleted = 0
310|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:326:            if (vol.delete("thumbs/$name")) deleted++
311|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:328:        return deleted
312|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:335:     * Returns false — meaning "do NOT delete, preserve" — in every other case, deliberately
313|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:336:     * fail-closed so a temporarily-unreadable v2 file is never purged:
314|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:226:        onDispose { exoPlayer.removeListener(playerListener) }
315|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:237:                        viewModel.clearSeekTarget()
316|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:265:            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
317|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:502:    // Surface toggle/delete failures (resolved message already localized by the ViewModel;
318|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:507:            viewModel.clearError()
319|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:751:                            onClick = { viewModel.clearPlayerError() }
320|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt:757:                                viewModel.clearPlayerError()
321|REMOTE|app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClient.kt:35:    suspend fun deleteFile(remotePath: String): Boolean
322|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:64:        if (initialTab == "delete") SyncMode.DELETE else SyncMode.DOWNLOAD
323|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:68:    private var deleteJob: kotlinx.coroutines.Job? = null
324|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:201:        deleteJob?.cancel()
325|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:202:        deleteJob = null
326|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:205:    fun clearError() {
327|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:210:        deleteJob = viewModelScope.launch {
328|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:222:                    _error.value = context.getString(R.string.sync_preview_delete_failed)
329|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:172:                            when (val r = fileOps.deleteMediaByIdLocked(record.id)) {
330|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:206:                // Keep otherVolumeRecords visible; only clear processed buckets by re-discovering
331|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:248:                        // B1: index-only delete — never mediaStore.delete on active volume.
332|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:249:                            when (val r = fileOps.deleteOtherVolumeIndexLocked(record.id)) {
333|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/DebugLogScreen.kt:77:                    IconButton(onClick = { DebugLogger.clear() }) {
334|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/DebugLogScreen.kt:80:                            contentDescription = stringResource(R.string.debug_log_clear)
335|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:117:    fun deleteSource() {
336|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:126:                    syncSourceRepository.delete(sourceId)
337|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:127:                    _saveResult.value = context.getString(R.string.source_delete_success)
338|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:130:                _saveResult.value = context.getString(R.string.source_delete_failed, syncErrorMessage(e, context))
339|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt:142:    // Surface toggle/delete failures (resolved message already localized by the ViewModel).
340|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt:146:            viewModel.clearError()
341|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:185:                                contentDescription = stringResource(R.string.common_delete),
342|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:189:                            Text(stringResource(R.string.source_delete_source))
343|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:208:            title = { Text(stringResource(R.string.source_delete_confirm_title)) },
344|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:209:            text = { Text(stringResource(R.string.source_delete_confirm_text)) },
345|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:212:                    viewModel.deleteSource()
346|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditScreen.kt:215:                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
347|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:118:                var deleted = 0
348|LOCAL_MEDIA|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:131:                                        if (vol.delete("$entry/$name")) deleted++ else failed++
349|LOCAL_THUMBNAIL|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:137:                                        if (vol.delete("thumbs/$name")) deleted++ else failed++
350|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:142:                        if (failed > 0) Log.w(TAG, "Reset: $deleted deleted, $failed failed")
351|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:143:                        videoItemDao.deleteAll()
352|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:144:                        pendingSyncActionDao.deleteAll()
353|LOCAL_DB|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:145:                        playbackRecordDao.deleteAll()
354|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:148:                // 🔴 Failure feedback: show warning if some files couldn't be deleted
355|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:149:                val warning = if (failed > 0) context.getString(R.string.storage_reset_partial, deleted, failed) else null
356|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:53:    /** Error flow for undo/delete operations — surfaced via Snackbar in the UI. */
357|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:60:    fun clearError() {
358|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:146:     * Undo the most recent delete operation (trash toggle).
359|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:82:    /** Error flow for undo/delete operations — surfaced via Snackbar in the UI. */
360|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:89:    fun clearError() {
361|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:237:     * Undo the most recent delete operation (trash toggle).
362|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:345:    fun clearSeekTarget() {
363|FALSE_POSITIVE|app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:359:    fun clearPlayerError() {
```

## 2.3 R3（CancellationException 先传播）

### 扫描与锚点对账
- `grep -rnE 'catch[[:space:]]*\(' --include='*.kt' app/src/main` → **202**（=B.2 锚点，exit=0）
- `grep -rnE 'catch[[:space:]]*\([[:space:]]*[^:()]+:[[:space:]]*(Exception|Throwable)[[:space:]]*\)' --include='*.kt' app/src/main` → **148**（=锚点，exit=0）
- `grep -rn 'runCatching' --include='*.kt' app/src/main` → **5**（=锚点，exit=0；其中 2 行为注释，指向 FileOperationsUseCase.kt:332 真实捕获点）
- **对账：R3_PASS 90 + R3_FAIL 35 + R3_CANDIDATE 28 + R3_UNKNOWN 0 = 153** ✓

### 裁定量规（用于逐点分类，全表见附录 B）
- **R3_PASS**：(a) 相邻兄层 `catch (e: CancellationException) { throw e }` 守卫；(b) try 块内无可达挂起点且无非挂起 CE 源（纯阻塞调用/枚举解析/内存操作）→ CE 不可能进入该 catch；(c) 捕获后以原异常 rethrow（含清理后 rethrow）。
- **R3_FAIL**：挂起上下文 catch(Exception/Throwable)，try 内含 suspend 调用（CE 可达），无 CE 守卫，吞掉后协程**正常完成**（无后继挂起点恢复）——红线 3 字面被破坏。按任务书映射立 **P0 Finding**。同文件同型站点聚合为一个 canonical Finding（枚举全部行号）。
- **R3_CANDIDATE**：同 FAIL 模式但吞掉后**必然**经过后继挂起点（含 withContext 退出恢复 / sticky cancellation 重抛）恢复传播——红线 3 瞬态破坏、最终传播保留。立 CANDIDATE Finding（P1）。
- 同型规律：**全部 17 处 FileOperationsUseCase/UndoDeleteUseCase/MediaContentProvider/SyncEngine 外层 catch 均有 CE 守卫**（引擎/数据层规范良好）；FAIL 集中在 ViewModel 层 fire-and-forget 模式。

### R3 逐点裁定汇总
| 文件 | PASS | FAIL（行） | CANDIDATE（行） |
|---|---|---|---|
| sync/SyncEngine.kt | 9（167,175,342,568,572,625,763,823,916,967 中 10 处；167/175 非挂起点，其余守卫） | 0 | 480,562,563,603,642,998,1002,1016,1020 |
| browser/FileOperationsUseCase.kt | 17（全部：CE 守卫或非挂起清理或 rethrow） | 0 | 0 |
| browser/BrowserViewModel.kt | 0 | 132,174,185,398,409,420,431,451 | 116,121,126 |
| browser/UndoDeleteUseCase.kt | 5（全部 CE 守卫） | 0 | 0 |
| settings/SettingsViewModel.kt | 0 | 71,81,91,101,111,139,150 | 162 |
| sync/SyncPreviewViewModel.kt | 0 | 85,93,142,165,190,224 | 0 |
| settings/SyncRelationEditViewModel.kt | 3（142,188,225） | 183,217,285 | 0 |
| player/PlayerViewModel.kt | 1（226 守卫） | 111,140,169,191 | 0 |
| imageviewer/ImageViewerViewModel.kt | 1（135 守卫） | 86,174 | 0 |
| settings/SyncSourceEditViewModel.kt | 0 | 94,109,129 | 0 |
| settings/GainAnalysisViewModel.kt | 2（176,201 守卫） | 93 | 0 |
| settings/MetadataRebuildViewModel.kt | 3（147,220,266 守卫） | 0 | 188 |
| settings/StorageManagementViewModel.kt | 1（196 非 suspend） | 152 | 66,75 |
| sync/sftp/SftpFileClient.kt | 7（阻塞调用无 CE 源/包装再抛） | 0 | 0 |
| sync/smb/SmbFileClient.kt | 8（同上） | 0 | 0 |
| provider/MediaContentProvider.kt | 4（85,121,151,190 全守卫） | 0 | 0 |
| storage/volume/EncryptedMediaStore.kt | 5（69 rethrow,70,73,119,349） | 0 | 136,321（+runCatching 240） |
| storage/volume/SafVolume.kt | 2（185,191 非 suspend） | 0 | 132,156 |
| audio/AudioGainAnalyzer.kt | 5（全部非 suspend 阻塞） | 0 | 0 |
| sync/VideoMetadataExtractor.kt | 0 | 0 | 45,65,80 |
| sync/ImageMetadataExtractor.kt | 3（59,93,96） | 0 | 61 |
| player/SeekPreviewExtractor.kt | 3（try 内无挂起点） | 0 | 0 |
| crypto/ChunkedEncryptSink.kt | 4（非协程 OutputStream；rethrow） | 0 | 0 |
| sync/sftp/SyncPlayerHostKeyVerifier.kt | 2（非 suspend） | 0 | 0 |
| storage/StoragePaths.kt | 1（非 suspend） | 0 | 0 |
| data/repository/SyncSourceRepository.kt | 1（恢复后 rethrow） | 0 | 0 |
| runCatching（5 行） | 3（FOUC:332 真实点+292,330 注释行） | 0 | SyncPlayerApp:48、EncryptedMediaStore:240 |
| **合计** | **90** | **35** | **28** |

### CHECK-2-4 [FAIL] [R3]
- 期望: 红线 3（"CancellationException 始终先传播，不被 catch(Exception) 吞"）；sync.md:54-55
- 检查范围: 153 个通用捕获点（148 catch + 5 runCatching，全表见上）
- 证据: 35 处 R3_FAIL 站点（上表 FAIL 列），逐点已读 ±15 行；代表证据 BrowserViewModel.kt:398-401 逐字：
  ```
  } catch (e: Exception) {
  ```
  ```
  Log.e(TAG, "Failed to save sort mode", e)
  ```
  try 内为 suspend `userPrefs.setDefaultSortMode(mode.name)`（UserPreferencesRepository.kt:137 `suspend fun setDefaultSortMode`），无 CE 守卫，吞后协程正常结束。
- 判定: FAIL → 转 PRESENT Findings F-2-2 … F-2-11。

### R3 发现（P0，CONFIRMED，均 PRESENT；同文件同型站点聚合、行号全枚举）

### F-2-2 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:224
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: `cancelSync()`（:201 `deleteJob?.cancel()`）**主动取消**本协程时，`executeDelete` 挂起点抛出的 CancellationException 被 catch(Exception) 捕获并当作同步失败渲染成用户可见错误（假错误提示），取消未按红线 3 先传播。用户可稳定触发（删除中点取消）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt （exit=0，命中 6 行）
- 建议: 在 :224 前增加 `catch (e: CancellationException) { throw e }`（同文件 :142/:165/:190 同改）。

### F-2-3 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:398
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to save sort mode", e)`
- 问题: 8 处同型（132,174,185,398,409,420,431,451）：try 内 suspend 调用（userPrefs 读写，UserPreferencesRepository.kt:106-188 全 suspend），catch(Exception) 无 CE 守卫，CE 被吞、协程正常完成。可达路径为 viewModelScope 取消（VM 销毁期），实际影响有限但违反红线 3 字面。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt （exit=0，命中 11 行；8 行属本 Finding，另 3 行为 F-2-17 瞬态站点）
- 建议: 统一加 CE 先行重抛（或封装 safeLaunch 工具统一处置）。

### F-2-4 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:71
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load settings", e)`
- 问题: 7 处同型（71,81,91,101,111,139,150）：init collect 与 6 个 setter 均 suspend-in-try + 吞 CE 无守卫（:71 collect 长驻挂起、CE 可达性最高）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt （exit=0，命中 8 行；7 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-5 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:85
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: 5 处同型（85,93,142,165,190）：init 加载/loadPreview/startSync/observeSyncCompletion 均 suspend-in-try + 吞 CE 为 _error 状态，无守卫（:224 另立 F-2-2）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt （exit=0，命中 6 行；5 行属本 Finding）
- 建议: 同 F-2-2。

### F-2-6 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt:183
- 代码: `} catch (e: Exception) {` / `_browseError.value = classifyRemoteError(e)`
- 问题: 3 处同型（183,217,285）：browseRemote/testRemotePath/saveRelation 的 try 含 suspend（client.connect/listDirectories、syncRelationRepository.save），吞 CE 为 UI 状态且 finally 的 disconnect 无挂起检查、CE 不再恢复。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt （exit=0，命中 6 行；3 行属本 Finding，另 3 行为清理/PASS 站点）
- 建议: 同 F-2-2。

### F-2-7 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:111
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load preferences", e)`
- 问题: 4 处同型（111,140,169,191）：偏好加载/DB 回退/视频加载/进度保存均 suspend-in-try + 吞 CE 无守卫（:191 saveProgress 高频调用、可达性最高）。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt （exit=0，命中 5 行；4 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-8 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt:86
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to load image $imageItemId", e)`
- 问题: 2 处同型（86,174）：图片加载与 markCompleted 均 suspend-in-try + 吞 CE 无守卫。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerViewModel.kt （exit=0，命中 3 行；2 行属本 Finding）
- 建议: 同 F-2-3。

### F-2-9 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt:94
- 代码: `} catch (e: Exception) {` / `_testResult.value = context.getString(R.string.source_connect_failed, syncErrorMessage(e, context))`
- 问题: 3 处同型（94,109,129）：testConnection/save/deleteSource 均 suspend-in-try + 吞 CE 为 _testResult/_saveResult，无守卫。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/SyncSourceEditViewModel.kt （exit=0，命中 3 行）
- 建议: 同 F-2-2。

### F-2-10 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt:93
- 代码: `} catch (e: Exception) {` / `_error.value = syncErrorMessage(e, context)`
- 问题: discover（:93）suspend-in-try（videoRepository.getAll）+ 吞 CE 为 _error，无守卫。对比：同文件 :174/:199 内层已正确加 CE 守卫，仅外层 discover 遗漏。
- 复现: grep -nE 'catch \(e: (kotlinx\.coroutines\.)?(Exception|CancellationException)' app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt （exit=0，命中 5 行；:93 属本 Finding，:174/:199 为 CE 守卫、:176/:201 为已守卫 PASS 站点）
- 建议: discover 外层补 CE 守卫。

### F-2-11 [P0] [CONFIRMED] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:152
- 代码: `} catch (e: Exception) {` / `Log.e(TAG, "Failed to reset storage", e)`
- 问题: resetStorage（:117-155）try 内含 withContext(ioDispatcher)+mutex+suspend DAO 删除，catch(Exception) 吞 CE（含用户取消场景）后置 isResetting=false 正常收尾，红线 3 破坏。
- 复现: grep -nE 'catch \([_e]+: Exception\)' app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt （exit=0，命中 4 行；:152 属本 Finding，另 3 行见 F-2-19/PASS）
- 建议: 补 CE 守卫后再 catch(Exception)。

### R3 发现（P1，CANDIDATE——瞬态吞、后继挂起点/withContext 退出必然恢复传播）

### F-2-12 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:480
- 代码: `try { workerClient.disconnect() } catch (_: Exception) {}`
- 问题: disconnect/close 为 suspend（RemoteFileClient.kt:22-23），取消期 CE 可被吞；但紧邻的 connect/后续挂起点必重抛（sticky cancellation），传播最终保留——红线 3 瞬态破坏。同型 :642。
- 复现: grep -nE 'try \{ workerClient\.(disconnect|close)' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0，命中 2 行）
- 建议: 清理段用 runCatching+CE 重抛或 NonCancellable。

### F-2-13 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:562
- 代码: `} catch (e: Exception) {`
- 问题: verifyReadable（suspend，EncryptedMediaStore.kt:107）失败处理把**所有**异常（含 CE）包装成 SyncException(STORAGE_ERROR) 再抛——CE 不再以 CE 形式先传播，取消被记作下载失败（accounting 污染）；后继挂起点会恢复取消。内层 :563 清理同型。
- 复现: sed -n '560,566p' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0）
- 建议: 包装前先 `if (e is CancellationException) throw e`。

### F-2-14 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:603
- 代码: `} catch (e: Exception) {` / `Log.w(TAG, "Metadata extraction failed for ${withMeta.fileName}", e)`
- 问题: extractMetadata（suspend，SyncEngine.kt:120）异常（含 CE）被吞且随后记录 DOWNLOAD SUCCESS 日志——取消场景下产生误导性成功日志；后继挂起点恢复取消。
- 复现: sed -n '601,605p' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0）
- 建议: 同 F-2-13。

### F-2-15 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:998
- 代码: `} catch (e: Exception) {` / `Log.w(TAG, "Failed to delete media id=$id", e)`
- 问题: deleteStoreMedia 的 store.delete/exists（suspend）CE 被吞后返回 false / 内层 :1002 再吞返回 false——取消被记作删除失败（failedDeleteCount 污染）；调用方后继挂起点恢复。deleteStoreThumb（:1016,:1020）同型。
- 复现: grep -nE 'catch \((_\: )?e?: Exception\)' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0，命中 20 行；998,1002,1016,1020 属本条与 F-2-16）
- 建议: 同 F-2-13。

### F-2-16 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:1016
- 代码: `} catch (e: Exception) {` / `Log.w(TAG, "Failed to delete thumb id=$id", e)`
- 问题: 同 F-2-15（deleteStoreThumb，含内层 :1020）。
- 复现: 同 F-2-15 命令（1016,1020 行属本条）
- 建议: 同 F-2-13。

### F-2-17 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt:116
- 代码: `} catch (_: Exception) {` / `SortMode.FILENAME_ASC`
- 问题: init 四连 try（116,121,126）内 suspend getter 的 CE 被吞后取默认枚举；紧随其后的裸挂起点（:130 getGridColumns）必重抛 CE，传播最终保留——瞬态破坏。尾位 :132 无后继挂起点，已计入 F-2-3。
- 复现: sed -n '112,133p' app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt （exit=0）
- 建议: init 块整体一个 try + CE 守卫。

### F-2-18 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:162
- 代码: `} catch (e: Exception) {` / `"—"`
- 问题: refreshUsableSpace 在 withContext(IO) 内吞 CE（mediaVolumeProvider.current() 为 suspend，MediaVolumeProvider.kt:23）；withContext 退出时对已取消协程必重抛 CE——瞬态破坏。
- 复现: sed -n '158,166p' app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt （exit=0）
- 建议: 同 F-2-13。

### F-2-19 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:66
- 代码: `try { videoItemDao.getTotalItemCount() } catch (e: Exception) {`
- 问题: :66（Room suspend 查询）与 :75（mediaVolumeProvider.current() suspend）在 withContext(ioDispatcher) 内吞 CE；withContext 退出恢复——瞬态破坏（:152 已入 F-2-11）。
- 复现: sed -n '64,78p' app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt （exit=0）
- 建议: 同 F-2-13。

### F-2-20 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/VideoMetadataExtractor.kt:45
- 代码: `} catch (e: Exception) {` / `return videoItem`
- 问题: 3 处同型（45,65,80）：try 内含 store.writeThumb（suspend，EncryptedMediaStore.kt:142），CE 被吞后按"提取失败"正常返回；调用方（SyncEngine:603/MetadataRebuild）后继挂起点恢复——瞬态破坏。
- 复现: grep -nE 'catch \(e: Exception\)' app/src/main/java/cn/vectory/syncplayer/sync/VideoMetadataExtractor.kt （exit=0，命中 3 行）
- 建议: 同 F-2-13。

### F-2-21 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt:61
- 代码: `} catch (e: Exception) {` / `return item`
- 问题: try 内含 store.writeThumb（suspend，:47），CE 被吞后正常返回；后继 upsert（suspend）恢复——瞬态破坏。
- 复现: sed -n '40,62p' app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt （exit=0）
- 建议: 同 F-2-13。

### F-2-22 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt:188
- 代码: `} catch (e: Exception) {` / `fail++`
- 问题: 循环内 extractAndSave（suspend）CE 被吞计为该文件失败；循环下一轮挂起点恢复（末轮则直达外层 :220 前的守卫外）——瞬态破坏（外层 :218 已有 CE 守卫）。
- 复现: sed -n '184,192p' app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt （exit=0）
- 建议: 同 F-2-13。

### F-2-23 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:137
- 代码: `MediaExistence.IO_ERROR(e.message ?: e.javaClass.simpleName)`
- 问题: 3 处同型（136,321 及 runCatching :240）：suspend 卷操作 CE 被吞（:136 还把 e.message 带入 IO_ERROR 值，见 Phase 3 R7 关联）；调用方后继挂起点恢复——瞬态破坏。
- 复现: grep -nE 'catch \((_\: )?e?: Exception\)|runCatching' app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt （exit=0，命中 8 行；136,321,240 属本条）
- 建议: 同 F-2-13。

### F-2-24 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt:132
- 代码: `} catch (_: Exception) { false }`
- 问题: :132（delete）与 :156（rename）：try 内 resolveDocId（suspend，SafVolume.kt:26）CE 被吞返回 false；withContext 退出恢复——瞬态破坏。
- 复现: grep -nE 'catch \(_\: Exception\)' app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt （exit=0，命中 4 行；132,156 属本条）
- 建议: 同 F-2-13。

### F-2-25 [P1] [CANDIDATE] [R3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt:48
- 代码: `runCatching {`
- 问题: runCatching 捕获 withLock/purgeLegacyIfNeeded（suspend）一切 Throwable 含 CE，onFailure 把取消记作 "purge skipped"；appScope 为应用级通常不取消，CE 可达性低，故 CANDIDATE。
- 复现: sed -n '46,55p' app/src/main/java/cn/vectory/syncplayer/SyncPlayerApp.kt （exit=0）
- 建议: runCatching 前置 CE 重抛。

（R4-R7 见后续章节）

## 2.4 R4（AEAD v2 契约静态核对）

crypto/ 包 6 文件 626 行**全部完整读**（铁律 9 允许）。对照 docs/specs/crypto.md 逐项：

### CHECK-2-5 [PASS] [R4]（逐项 spec↔代码对照）
- 期望: crypto.md 全部格式条款 + 红线 4（AEAD v2 格式不变）
- 检查范围: crypto/ 6 文件（CryptoScheme 274 行 / ChunkedEncryptSink 132 / RangeDecryptor 110 / DecryptingFdCallback 23 / MediaCryptoMaster 72 / LegacyCiphertextException 15）
- 证据: （spec 原文 ↔ 代码常量对照表，全部逐字核对）

| spec 条款 | spec 行 | 代码实现 | 判定 |
|---|---|---|---|
| magic "SPM2" | crypto.md:19 | CryptoScheme.kt:37 `byteArrayOf(0x53,0x50,0x4D,0x32)` | PASS |
| formatVersion 2 | :20 | CryptoScheme.kt:40 `FORMAT_VERSION = 2` | PASS |
| reserved 0 | :21 | 写 :158 `put(0.toByte())`；读 :186 拒绝非 0（防 0 字节文件 header 可变） | PASS |
| chunkSizeKiB 64 BE u16 | :22 | :49 `CHUNK_SIZE_KIB=64`；:159 putShort；:190 读侧强校验 | PASS |
| plaintextLength BE i64 | :23 | :160 putLong（BIG_ENDIAN） | PASS |
| Header 16B 绑入每 chunk AAD | :15 | :43 `HEADER_SIZE=16`；:211-218 `aadForChunk=header(16)‖i(4BE)` | PASS |
| nonce = iv(8)‖i(4BE) | :28 | :201-208 `nonceForChunk`；:58 `NONCE_LEN=12` | PASS |
| AES/GCM/NoPadding | :7 | :63 `TRANSFORM_AEAD`；tag 128bit（:52） | PASS |
| chunk 64KiB、尾块可短 | :32 | :46 `CHUNK_SIZE=65536`；ChunkedEncryptSink.kt:110-114 尾块 flush | PASS |
| iv 8 字节每文件随机 | :37 | :34 `IV_LEN=8`；:84 `newIv()` SecureRandom | PASS |
| fileSize=明文字节 | :38 | SyncEngine.kt:527-531 以 `action.fileSize`（远端明文大小）开 sink | PASS |
| cipherLen=16+ceil(n/65536)*16+plainLen | :43 | :128-136（HEADER+N*TAG+plainLen，checked arithmetic，防溢出改写自 :99-112） | PASS |
| 明文坐标随机读 | :50 | RangeDecryptor.kt:36（offset/len 为明文坐标，密文偏移 :62 封装） | PASS |
| Master AES-256、KEK blob 于 EncryptedSharedPreferences | :54 | MediaCryptoMaster.kt:33 `init(256)`；SyncModule.kt:30-44 loadBlob/storeBlob→`media_master_wrapped`（EncryptedSharedPreferences，DatabaseModule.kt:72-82 AES256_SIV/GCM） | PASS |
| KekProvider 抽象 | :55 | MediaCryptoMaster.kt:15-18 | PASS |
| HKDF(master,"syncplayer/content") | :56 | CryptoScheme.kt:65-81（HmacSHA256 extract+expand，info="syncplayer/content"；thumb 独立 info :66） | PASS |
| v1 缺 magic 抛 LegacyCiphertextException | :61 | CryptoScheme.kt:175-177；RangeDecryptor.kt:92-97（短 header 也抛） | PASS |
| 缩略图 full-file GCM 不分块 | :67 | CryptoScheme.kt:257-273（版本字节1+iv12+ct‖tag） | PASS |

- 判定: PASS——格式契约（红线 4）未发现破坏。

### F-2-26 [P2] [CANDIDATE] [R4/C-CR]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:178
- 代码: `private val purgeMarker = ".spm2_purged"`
- 问题: crypto.md:63 规定 v2 首启 purge 幂等标志为「DataStore flag `crypto_v2_purged`」，代码实际使用**每卷 marker 文件** `.spm2_purged`（:221 exists 检查、:240 写入）。全库 grep 无 `crypto_v2_purged`（`grep -rn 'crypto_v2_purged' --include='*.kt' app/src` exit=1 零命中）。机制差异使多卷场景每卷各 purge 一次（行为更优），但 spec 文字与实现漂移——文档失真属契约维护问题。CANDIDATE 供人工裁决是否按"实现优、文档旧"处理。
- 复现: grep -rn 'crypto_v2_purged\|spm2_purged' --include='*.kt' app/src （exit=0，命中 2 行，全部为 spm2_purged）
- 建议: 更新 crypto.md:63 描述为卷根 marker 机制（或反之）。

附带观察（不入 Finding）：CryptoScheme.kt:18、RangeDecryptor.kt:10、ChunkedEncryptSink.kt:8 注释引用 `/tmp/opencode/sync-player-aead-spec.md`（易失路径），Phase 9 卫生项记录。

## 2.5 R5（随机读不返回脏明文）

`grep -rln "RangeDecryptor" --include='*.kt' app/src/main`（exit=0，4 文件）→ RangeDecryptor.kt、DecryptingFdCallback.kt、FileOperationsUseCase.kt（decryptWholeTo :300-339）、EncryptedMediaStore.kt（仅注释 :104，实际读路径 readThumb :146-169）全部完整核阅。

### CHECK-2-6 [PASS] [R5]
- 期望: 红线 5（随机读失败必须抛异常，不返回脏明文）；crypto.md:33/:50
- 检查范围: RangeDecryptor.readRange（:36-76）全部分支 + 全部消费方读路径
- 证据: readRange 出口清单——
  1. `offset >= size || requestedLen == 0` → 空数组（空区间语义，v1 兼容，非脏数据）
  2. header 短读/缺 magic → `LegacyCiphertextException`（:96）
  3. header 字段非法 → IOException（CryptoScheme.decodeHeader :180-194）
  4. header.plaintextLength ≠ size → IOException（:46）
  5. chunk 截断 → `EOFException`（readFully :106）
  6. GCM tag/AAD 不匹配 → AEADBadTagException（decryptChunk :241）
  输出仅在整 chunk 解密成功后写出（:68-71）。消费方：DecryptingFdCallback.onRead 直接透传异常（Provider 层统一转 FileNotFoundException）；decryptWholeTo catch(Throwable) 删除半成品文件后 **rethrow**（FOUC:328-333）；readThumb 短读抛 EOFException（:160-162）、GCM 失败上抛、仅文件缺失返回 null（非脏数据）。
- 判定: PASS——不存在返回部分填充 buffer/默认值/脏明文的分支。

## 2.6 R6（敏感信息进日志/UI）

期望依据：**storage-security.md:50**（"密码、密钥、完整远端凭据、卷标识**不进入**用户可见错误或普通日志"）、**:51**（"用户界面不显示异常原文、主机路径、卷标识或凭据相关信息"）、红线 6。

### 扫描与对账
- `grep -rnE 'Log\.[diwev]\(' --include='*.kt' app/src/main` → **101**（=B.2 锚点，exit=0）| adjudicated=101（4 行 volumeId→F-2-27、2 行 fingerprint→F-2-29、95 行 PASS） | excluded=0 | unknown=0
- 补充扫描 `grep -rnE 'DebugLogger\.[diwev]\(' --include='*.kt' app/src/main` → **13** | adjudicated=13（:399 volumeId + :369/:764 带栈→F-2-27/F-2-28，10 PASS） | excluded=0 | unknown=0
- UI 侧 `grep -rnE '\.message|throwable\?\.message|it\.message' --include='*.kt' <全包树>` → **7** | adjudicated=7（EncryptedMediaStore.kt:137→UI=F-2-30、DebugLogScreen.kt:184=F-2-28 展示点、SmbFileClient.kt:47 诊断匹配=PASS、其余 3 行注释+1 行类注释=PASS） | excluded=0 | unknown=0

### 关键事实链（DebugLogger 是"普通日志+用户可见 UI"双通道）
`debug/DebugLogger.kt:41-57`：每个 d/i/w/e **同时**转发 `android.util.Log` 并写入内存环形缓冲；`settings/DebugLogScreen.kt:184` `text = entry.message` 将缓冲渲染给用户。故 DebugLogger 调用等价于"日志+UI"双曝光；`:52/:57` 带 Throwable 时把 `t.stackTraceToString()` 整段写入缓冲（异常原文/底层 message 进 UI）。

### 发现

### F-2-27 [P1] [CONFIRMED] [R6/C-ST-日志契约]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:399
- 代码: `DebugLogger.i(TAG, "当前卷: ${store.volumeId}")`
- 问题: 卷标识（volumeId，格式 `saf-<treeUri>`，含 SAF treeUri）进入普通日志与用户可见 DebugLogScreen，违反 storage-security.md:50/51。同型 6 处：SyncEngine.kt:671-672、871-872（Log.w entity/store volumeId）、FileOperationsUseCase.kt:471-473、UndoDeleteUseCase.kt:372、以及 **UI 侧** SyncEngine.kt:680-683 与 880-883 把两个 volumeId 填入 `R.string.sync_error_volume_mismatch`（"卷不一致跳过删除 (entity=%1$s, current=%2$s)"，strings.xml:368）作为 syncLog errorMessage / lastSync 文案展示给用户。
- 复现: grep -rnE 'volumeId|volume=' --include='*.kt' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0，命中含 :399/:671/:682/:871/:882 等行）
- 建议: 日志与文案中去掉 volumeId 原文（用哈希/掩码或"另一个卷"措辞）。

### F-2-28 [P1] [CONFIRMED] [R6/R7]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt:57
- 代码: `add(Level.ERROR, tag, if (t != null) "$msg\n${t.stackTraceToString()}" else msg)`
- 问题: DebugLogger.w/e（:52 同型）带 Throwable 时把完整堆栈（含各级 cause 的底层 message，可能含 host/path/协议细节）写入 DebugLogScreen 用户可见缓冲——"异常原文进用户界面"，违反 storage-security.md:51 与红线 6"底层 message 不暴露给用户"。现有调用点：SyncEngine.kt:369、764。
- 复现: grep -rn 'stackTraceToString' app/src/main/java/cn/vectory/syncplayer/debug/DebugLogger.kt （exit=0，命中 2 行）
- 建议: 入缓冲前剥离 throwable（logcat 保留完整栈，UI 缓冲只存 msg），或对 stackTrace 做脱敏。

### F-2-29 [P2] [CANDIDATE] [R6]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt:59
- 代码: `Log.e("HostKeyVerifier", "Host key mismatch for $keyLabel! Saved: $savedFingerprint, Current: $fingerprint")`
- 问题: hostname（keyLabel）+ host-key fingerprint 进 logcat（另 :49 首连记录）。任务书裁定：TOFU 主机名/指纹属公开元数据、storage-security.md 未禁止——单独出现不自动立项，按威胁模型记 P2 CANDIDATE（歧义点：fingerprint 可辅助中间人检测绕过的侦察；个人应用威胁模型下影响有限）。
- 复现: grep -n 'fingerprint' app/src/main/java/cn/vectory/syncplayer/sync/sftp/SyncPlayerHostKeyVerifier.kt （exit=0，命中 2 行）
- 建议: 维持现状可接受；如需收紧仅保留 fingerprint 前缀。

### F-2-30 [P1] [CONFIRMED] [R6/R7]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:137
- 代码: `MediaExistence.IO_ERROR(e.message ?: e.javaClass.simpleName)`
- 问题: 底层异常 message 被装入 IO_ERROR.reason，经 MetadataRebuildViewModel.kt:127 `_error.value = context.getString(R.string.metadata_scan_io_error, existence.reason)`（"扫描失败：无法访问外部卷 (%1$s)"，strings.xml:406）**直接展示给用户**——违反红线 6"底层 message 不暴露给用户"与 storage-security.md:51"用户界面不显示异常原文"。
- 复现: grep -rn 'metadata_scan_io_error' --include='*.kt' app/src/main （exit=0，命中 1 行）
- 建议: IO_ERROR 只携带类型枚举/错误码，文案由资源解析。

### F-2-31 [P2] [CANDIDATE] [R6]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt:89
- 代码: `if (path != null) context.getString(R.string.sync_error_download_failed_path, path)`
- 问题: relativePath 插入用户错误文案（"下载失败：%1$s" strings.xml:358；:94 删除远程同型）。歧义点：红线 6 列"path 不暴露给用户"，但 storage-security.md:51 用词为"**主机路径**"；relativePath 是用户库内逻辑路径（浏览器 UI 本身即展示文件名/路径，属产品语义），且"哪个文件失败"是用户刚需。规范两处措辞不一致，故 CANDIDATE 供人工裁决。
- 复现: grep -n 'failed_path' app/src/main/java/cn/vectory/syncplayer/sync/ErrorMessageMapper.kt （exit=0，命中 2 行）
- 建议: 若按红线 6 严格执行则改为仅展示文件名或数量；建议同时澄清 spec 用词。

其余 95 行 Log + 10 行 DebugLogger 判定 PASS（依据：消息仅含 id/数量/文件名/大小/类名 simpleName，或中文诊断文本[conventions 允许日志不本地化]，不含密码/密钥/凭据/卷标识/指纹；throwable 三参仅进 logcat 不进 UI 缓冲——F-2-28 例外已单列）。

## 2.7 R7（结构化错误不回退 message）

### 步骤 1：SyncException 类定义 ✓
`SyncException.kt:50`：`) : Exception(null, cause)` —— 固定继承 `Exception(null, cause)`，构造器**无 message 参数**（仅 code/operation/path/host/字节计数/cause），与 sync.md:41 "message 刻意为 null" 一致。子类 `RemoteScanIncompleteException`（:24-29）同样只传 code=SCAN_INCOMPLETE/operation/path/cause。

### 步骤 2：SyncErrorCode 枚举与映射覆盖 ✓
`SyncException.kt:11-30` 枚举 **18 码**，与 sync.md:42 清单**逐码一致**：NETWORK_UNAVAILABLE, UNKNOWN_HOST, CONNECTION_REFUSED, TIMEOUT, AUTHENTICATION_FAILED, ACCESS_DENIED, PATH_NOT_FOUND, NOT_CONNECTED, SCAN_INCOMPLETE, DOWNLOAD_FAILED, REMOTE_DELETE_FAILED, INVALID_FILE_SIZE, INSUFFICIENT_SPACE, FINALIZE_FAILED, STORAGE_ERROR, THUMBNAIL_ERROR, DATABASE_ERROR, UNKNOWN。
`ErrorMessageMapper.syncErrorMessage`（:60-116）`when(code)` **18 分支全覆盖**（Kotlin 穷尽 when，无 else 兜底泄漏）；非 SyncException 的 stdlib 异常按类型映射（:61-66）；UNKNOWN 永不取 `e.message`（:113-114 用 `sync_error_unknown` 资源）——满足 sync.md:44。运行时兜底：UI 拿到的都是 `context.getString(R.string.*)`。

### CHECK-2-7 [PASS] [R7]
- 期望: 红线 7；sync.md:41-44
- 检查范围: SyncException.kt 全文（50 行）、ErrorMessageMapper.kt 全文（116 行）、全 UI 错误渲染链
- 证据: 见步骤 1/2；UI 侧异常文案入口均经 syncErrorMessage/getString（Phase 8 复核字符串字面量）。例外已在 F-2-30（e.message→UI）与 F-2-28（栈→UI）立项。
- 判定: PASS（结构化机制完备；两处旁路已单独立项）。

### 步骤 3：构造点抽查（铁律 8）
清单 `grep -rn "SyncException(" --include='*.kt' app/src/main`（49 行，exit=0；宽式 `: SyncException|SyncException\(` 56 行含声明/签名误匹配）。L=49，effectiveN=15，hashByte=231，offset=231 mod 49=35，step=3；索引=(35+3k) mod 49 → 排序后 {1,4,7,10,13,16,19,22,25,28,35,38,41,44,47}。
15/15 样本全部合规：仅传 code/operation(/path/host/bytes/cause)，无 display 文本（样本含 FOUC:181,507,546,598；UndoDeleteUseCase:386；RemoteScanIncompleteException:24；SyncEngine:501,565；SftpFileClient:115,249；SmbFileClient:131,185,291,328）。其中 SyncEngine:242 为函数签名误匹配（isRetryableSyncException 声明），非构造点，记 FP。

## 最终对账（Phase 2 汇总）

| 扫描 | scan_total | adjudicated | excluded_scope_or_FP | unknown |
|---|---|---|---|---|
| R2 删除候选 | 363 | 86 | 277 | 0 |
| R3 全量 catch（S1） | 202 | 202（其中通用 148+runCatching 5 由 R3 四桶覆盖；专项 49 见 Phase 3） | 0 | 0 |
| R3 通用捕获四桶（S2+S3） | 153 | 153（PASS 90 + FAIL 35 + CANDIDATE 28） | 0 | 0 |
| R6 Log 全量 | 101 | 101 | 0 | 0 |
| R6 DebugLogger 补充 | 13 | 13 | 0 | 0 |
| R6 UI .message | 7 | 7 | 0 | 0 |
| R7 SyncException 构造宽式 | 56 | 15（样本）+41 未逐一 | 0 | 41（未入样本，非异常判定） |
| R4 crypto 对照 | 全部条款 | 全 PASS + 1 CANDIDATE(F-2-26) | 0 | 0 |
| R5 读路径出口 | 全部出口 | 全 PASS（CHECK-2-6） | 0 | 0 |

- 唯一发现: **P0=10, P1=17, P2=4**（其中 CONFIRMED=13[10×P0+3×P1]，CANDIDATE=18[14×P1+4×P2]）；引用他 Phase 的 REF: 0
- U 条目: 0
