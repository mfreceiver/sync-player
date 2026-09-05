# 修复计划：Sync Pipeline / Engine Lane

> 范围：BUG-001、BUG-007、BUG-008、BUG-009、BUG-010、BUG-018（共 6 项）。
> 权威来源：BUG-001/007/008/009/010 以 `todo.md` > `docs/automatic/20260905-0012_bug-hunting.md`；**BUG-018 不在 todo.md 内**（v2 更正：已逐行核实 todo.md 无 BUG-018 条目、无「第 10 项」与 engine-only 约束的记载），其来源为**用户指定修复范围 + `docs/automatic/20260905-0114_bug-hunting.md`**。
> 编号说明：报告 0012 中 connect 重试与 SFTP 幂等的编号（009/010）与 todo.md 互换，本文以 **todo.md 为准**：009 = SFTP NO_SUCH_FILE 幂等，010 = initial connect 重试。
> 状态：**已完成**（6 BUG 全部章节 + 总体验收/红线核查/报告不符清单落盘）。**v2（2026-09-05）：按门禁评审 8 项意见原地修订**。**v3（同日复审）：按复审 8 项意见（2 Critical + 5 Major + 1 Minor）原地修订**——事件 actor 重设计（v2）改为单线程串行域 + 到期事件不入队，`hasPendingTmp`（v2）改为三阶段状态 + 三态探测；首轮 #6/#7/#8（来源标注/SFTP harness 复用/红线三栏）经复审确认保持不动。仅本文件被写入，生产代码/测试零改动。**v3.1（同日 Oracle 复审 FAIL 8.0/10）：按 7 项意见 + 2 附注原地增量修订**——#1 LAZY 注册序拆三条独立语句 + #16 换 UnconfinedTestDispatcher；#2 `onSyncRequested` 入队前即作废令牌/取消计时器 + 终局性论证补前提；#3 SyncService cancelSync/onTimeout/onDestroy 全量接线（用户取消延迟停服并入 confined 域，新增 FORCED 停服种类）；#4 `existsStrict` 收窄为全路径 strict traversal（EXTRA_LOADING/File 属性语义）+ §2.1 提交推断支持边界；#5 `cleanUpAttempt` 探测调用单独保护；#6 修正 S2/#10/#13/#14/#17 假绿或自相矛盾断言；#7 删 START_REDELIVER 自愈主张、术语统一（NOT_FINALIZED/删 AMBIGUOUS 措辞）。v3 骨架（confined 单线程串行域、请求队列与到期事件分离、三阶段+三态探测、恰一次回滚、cancelActiveDrain 三步原子、Harness 规则、孤儿/索引分开验收、SFTP 包装契约断言）全部保留不动。

## 目录

- [BUG-001：DELETE_BOTH remote-first 重构 + 卷保护](#bug-001)
- [BUG-007：finalize 后取消的清理缺口](#bug-007)
- [BUG-008：终态进度写入清零计数器](#bug-008)
- [BUG-009：SFTP deleteFile NO_SUCH_FILE 幂等](#bug-009)
- [BUG-010：initial connect 纳入白名单重试](#bug-010)
- [BUG-018：服务尾部 pending race（consumeAndExecute drain 竞态）](#bug-018)
- [总体验收与守卫](#总体验收与守卫)

---

## BUG-001 <a id="bug-001"></a>

**DELETE_BOTH 改为 remote-first，消除「部分完成删除 → 下次 dryRun 复活」循环**（todo.md #1 / 报告 0012 BUG-001，Severity High）

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncDeleteExecutor.kt:88-150`** —— DELETE_BOTH 按 local-first 拆成两个不可回滚半步：

```kotlin
// :88-128（本地先删）
// 1) Local first — never touch remote if local ciphertext/thumb still present.
val videoItem = videoRepository.getByRelativePathAndRelationId(action.relativePath, relationId)
if (videoItem != null && videoItem.volumeId != store.volumeId) { /* 卷不匹配 → 跳过整个 action（不变） */ }
if (videoItem != null) {
    val mediaGone = deleteStoreMedia(store, videoItem.id)
    val thumbGone = deleteStoreThumb(store, videoItem.id)
    if (!(mediaGone && thumbGone)) { failedActions++; ...; continue }
    playbackRepository.deleteByVideoId(videoItem.id)
    videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)  // ← 行已删
}
// :131-150（远端后删）
val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
try {
    client.deleteFile(remoteRelPath)
} catch (e: CancellationException) { throw e }
catch (e: Exception) { failedActions++; ...; continue }   // ← 本地已删，远端未删
```

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncRemoteScanner.kt:97-99`** —— dryRun 推导只看快照差集：

```kotlin
val actionType = if (local != null) { /* mtime/size 比较 */ } else {
    SyncActionType.DOWNLOAD          // 远端在 + 本地无行 → 重新下载
}
```

**根因链**：本地行删除成功 → 远端 `deleteFile` 抛异常（网络闪断）→ `failedActions++` 但行已不存在 → 下次 mirror dryRun 看到「远端存在 + 本地无行」→ 推导 `DOWNLOAD` → 已删除文件以全新条目复活；远端删除持续失败则循环往复。删除意图无持久化记录，扫描推导只认当前差集。**方向性根因**：镜像同步的自愈语义是「远端不在 → DELETE_LOCAL」（可重试收敛），而 local-first 把失败残局推向「远端在 → DOWNLOAD」（反向放大）。

### 2. 修复方案（remote-first 重排，todo.md 选定方向 B）

**函数级改动：`SyncDeleteExecutor.executeDeleteLocked` 的 action 循环体重排**（唯一生产改动点），半步顺序变为：卷检查 → **远端删除** → 本地删除：

```kotlin
for (action in actions) {
    progressPublisher.value = progressPublisher.value.copy(...)          // 不变
    val videoItem = videoRepository.getByRelativePathAndRelationId(...)  // 不变
    // 卷不匹配检查保持在最前且跳过整个 action（红线 2，语义不变，代码原样保留）
    if (videoItem != null && videoItem.volumeId != store.volumeId) { ...; continue }

    // 1) Remote first —— 远端未 ack 前不动任何本地状态。
    val remoteRelPath = storagePaths.toPathUnderBase(relation.localBasePath, action.relativePath)
    try {
        client.deleteFile(remoteRelPath)   // false（SMB 已不存在）= 幂等成功，语义不变
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { failedActions++; /* FAILED 日志 */; continue }   // 本地全保留

    // 2) Local —— 远端 ack 后才执行；此处失败交由下次镜像 dryRun 推导 DELETE_LOCAL 自愈。
    if (videoItem != null) {
        val mediaGone = deleteStoreMedia(store, videoItem.id)
        val thumbGone = deleteStoreThumb(store, videoItem.id)
        if (!(mediaGone && thumbGone)) { failedActions++; /* FAILED 日志 */; continue }
        playbackRepository.deleteByVideoId(videoItem.id)
        videoRepository.deleteByRelativePathAndRelationId(action.relativePath, relationId)
    }
    /* SUCCESS 日志 + completedActions++（不变） */
}
```

失败模式对照（修复后）：

| 失败点 | 修复前残局 | 修复后残局 | 下次 dryRun 推导 |
|---|---|---|---|
| 远端删除失败 | 行已删+密文已删+缩略图已删，远端仍在 | 本地全保留（行仍在回收站标记下） | 无 DOWNLOAD（local≠null 且 size/mtime 相等 → 无动作）；下次回收站清理重新推导 DELETE_BOTH 重试删除意图 |
| 远端成功、本地删除失败 | 不可达 | 远端已删，行/密文残留（仍 trashed） | `DELETE_LOCAL`（本地不在远端快照）→ 正常确认流删除，自愈收敛 |

配套文档改动：类 KDoc「迁移不变量 2：local-first：本地密文/缩略图未删净不触远端」改写为「remote-first：远端 ack 前不动本地；远端失败保留全部本地状态防复活；远端成功后本地失败由下次镜像同步推导 DELETE_LOCAL 自愈」；`:88` 行内注释同步改写。

**不采用方向 A（dryRun 排除 pending 队列）**：DELETE_BOTH 走 `executeDelete`，不落 `PendingSyncActionDao`（只有 mirror sync 的 DOWNLOAD/DELETE_LOCAL 落 pending 表），方向 A 需要为 DELETE_BOTH 新增一类持久化意图并让扫描器查询它，改动面更大且引入「pending 表与回收站状态双真相源」；方向 B 以零新增状态消除残局。

### 3. TDD 测试计划

测试文件：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDeleteExecutorTest.kt`（既有类，追加用例；MockK 注入方式沿用该文件既有 `stubBasics()` 模式）

1. `remote delete failure preserves local row, ciphertext and playback record`
   - Arrange：`stubBasics()`；`getByRelativePathAndRelationId` 返回 volumeId 匹配的 entity(id=7)；`client.deleteFile(any())` 抛 `SyncException(REMOTE_DELETE_FAILED, DELETE)`。
   - Act：`sut.executeDeleteLocked(1L, listOf(action))`。
   - Assert：返回 false；`coVerify(exactly = 0) { store.delete(any()) }`；`coVerify(exactly = 0) { store.deleteThumb(any()) }`；`coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }`；`coVerify(exactly = 0) { playbackRepository.deleteByVideoId(any()) }`。
2. `remote ack precedes local deletion`
   - Arrange：全成功路径（`deleteFile` returns true；`store.delete`/`store.exists` 使 mediaGone=true；thumb 同）。
   - Act：同上。
   - Assert：`io.mockk.coVerifyOrder { client.deleteFile(any()); store.delete(7L) }`。
3. `remote success with local delete failure keeps row for mirror DELETE_LOCAL self-heal`
   - Arrange：`deleteFile` returns true；`store.delete(7L)` returns false 且 `store.exists(7L)` returns true（mediaGone=false）。
   - Assert：返回 false；`coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(any(), any()) }`（行保留=自愈前提）；`coVerify(exactly = 1) { client.deleteFile(any()) }`。
4. 既有三个用例（volume mismatch / remote failure / success）必须保持绿——卷检查仍在最前、`videoItem==null` 时行为不变。

复活回归（两阶段序列，报告 BH_TrashResurrectionTest 重建）：新文件 `app/src/test/java/cn/vectory/syncplayer/sync/SyncTrashResurrectionRegressionTest.kt`。**v2 修订（TDD 有效性）**：必须用**有状态 fake repository**——复活的因果链是「旧实现把本地行真实删除 → 下次扫描看到 local==null」。若把本地 stub 成静态「行仍在」，旧实现下 `local != null` 同样推导不出 DOWNLOAD，`toDownload.isEmpty()` 恒真，测试永远不会红。

5. `DELETE_BOTH with remote failure is not resurrected by next dryRun`（红测试）
   - Arrange：MockK 构造**有状态** `videoRepository`：内部 `MutableMap<String, VideoItemEntity>` 以 relativePath 为键；`getByRelativePathAndRelationId` 读 map、`deleteByRelativePathAndRelationId` **真实移除**键、`getBySyncRelationId` 返回 `map.values`。预置 `a.mp4` 行（size/mtime 与远端快照一致）。真实 `SyncDeleteExecutor`：`client.deleteFile` 抛 `SyncException(REMOTE_DELETE_FAILED, DELETE)`。随后真实 `SyncRemoteScanner.dryRunLocked`：`client.listFiles` 返回仍含 `a.mp4` 的远端快照。
   - Act：先 `executeDeleteLocked`（旧实现执行中已把行从 map 移除——这正是复活机制本身），再 `dryRunLocked`。
   - Assert：`toDownload.isEmpty()`（旧实现：map 无行 → local==null → DOWNLOAD 复活 → **红**；修复后：行仍在且 size/mtime 相等 → 无动作）；`coVerify(exactly = 0) { videoRepository.deleteByRelativePathAndRelationId(...) }`（行保留的直接证据，保留该断言）。
6. `remote success with local delete failure self-heals as DELETE_LOCAL`（回归锁，非红测试）
   - Arrange：同一有状态 fake，预置 `a.mp4` 行；`client.deleteFile` 返回 true；`store.delete(7L)` 返回 false 且 `store.exists(7L)` true（mediaGone=false → 行保留在 map）。`dryRunLocked` 的 `listFiles` 返回**不含** `a.mp4` 的远端快照。
   - Act：先 `executeDeleteLocked`，再 `dryRunLocked`。
   - Assert：`toDelete` 含 `a.mp4` 的 DELETE_LOCAL 动作（远端已 ack + 行残留 → 镜像推导删除意图，自愈收敛闭环被验证）。注：该变体在旧实现下同样成立（旧 local-first 在 mediaGone=false 时也保留行），定位为**修复后残局契约的回归锁**（对应 §2 失败模式表第 2 行），与测试 5（红）分工。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncDeleteExecutorTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncTrashResurrectionRegressionTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncRemoteScannerTest' --tests 'cn.vectory.syncplayer.sync.SyncEngineDryRunTest' --tests 'cn.vectory.syncplayer.sync.SyncEngineBoundaryTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

期望：上述全部 BUILD SUCCESSFUL；`SyncEngineBoundaryTest` 中 `executeDelete DELETE_BOTH with mismatched volumeId is counted as FAILED and deletes nothing`（该文件 :370）保持绿（卷 fail-closed 不回退）。

### 5. 风险与守卫

- **卷归属 fail-closed（红线 2）**：卷不匹配分支代码原样保留且仍在最前——不删本地、不删远端、计 FAILED。remote-first 不触碰该检查的相对位置与语义；测试 4 锁定。
- **E8（红线 1）**：不涉及扫描异常路径；`dryRunLocked` catch 清空逻辑零改动。
- **CancellationException（红线 3）**：远端删除的 CE rethrow 分支保留在新位置最前。
- **删除意图不丢失**：远端失败时行保留在回收站标记下（`getTrashed()` 仍返回），下次清理重推 DELETE_BOTH——意图可重试而非复活。
- **新风险**：远端成功+本地失败窗口内行仍可见于浏览器（带回收站标记），与既有「本地删除失败」行为一致，非新增暴露。
- 不改 `RemoteFileClient` 契约、不改 `SyncDownloadPipeline` 的 DELETE_LOCAL tail（该路径本来就是「行在才删」，语义与 remote-first 残局正交）。

### 6. 涉及文件清单（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/sync/SyncDeleteExecutor.kt`（executeDeleteLocked 循环体重排 + KDoc）
- 测试：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDeleteExecutorTest.kt`（+3 用例）、`app/src/test/java/cn/vectory/syncplayer/sync/SyncTrashResurrectionRegressionTest.kt`（新建，2 用例：复活红测试 + DELETE_LOCAL 自愈回归锁）

## BUG-007 <a id="bug-007"></a>

**finalize 后收到 CancellationException → 清理作用在错误产物上（幽灵密文 / 行-密文 iv 错配）**（todo.md #2 / 报告 0012 BUG-007，Severity High）

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:265-330`** —— 单文件下载的 try/catch 结构：

```kotlin
var sink: java.io.OutputStream? = null
try {
    sink = store.openEncryptSink(assignedId, iv, action.fileSize)
    downloadSuccess = client.downloadTo(action.relativePath, sink) { ... }
    sink.close()
    if (!downloadSuccess) throw SyncException(DOWNLOAD_FAILED, ...)
    if (!store.finalize(assignedId)) throw SyncException(FINALIZE_FAILED, ...)   // :288 ← rename tmp→final
    try { store.verifyReadable(assignedId) }                                      // :296-309 挂起点
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        try { store.delete(assignedId) } catch ... {}                              // generic：完整回滚
        videoRepository.deleteById(assignedId)
        throw SyncException(STORAGE_ERROR, ..., cause = e)
    }
} catch (e: CancellationException) {                                              // :310-318 ← BUG
    try { sink?.close() } catch (_: Exception) {}
    withContext(NonCancellable) {
        try { store.cancel(assignedId) } catch (_: Exception) {}                   // 只删 .enc.tmp
        if (existing == null) { try { videoRepository.deleteById(assignedId) } catch (_: Exception) {} }
    }
    throw e
} catch (e: Exception) {                                                          // :319-330
    try { sink?.close() } catch (_: Exception) {}
    store.cancel(assignedId)
    if (existing == null) videoRepository.deleteById(assignedId)
    ...
}
```

**存储语义（`app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt`）**：
`finalize(id)` = `volume().rename(tmpMedia(id), finalMedia(id))`（:90）；`cancel(id)` = `volume().delete(tmpMedia(id))`（:92-94）；`delete(id)` = `volume().delete(finalMedia(id))`（:124）；`verifyReadable` 打开 final 密文校验 SPM2 magic（:108-122）。

**根因**：CE 分支与 generic 分支清理不对称。取消落在 `finalize` 已成功（rename 已完成，final `.enc` 已就位、existing 行的旧密文已被覆盖）之后、元数据写入（:338-347，try 块之外）之前时：
- `store.cancel` 删 tmp → no-op（tmp 已被 rename 消费）；
- `existing != null` 变体：行保留**旧 iv/旧 fileSize** 指向**已被替换**的密文 → 播放 GCM 校验失败；
- `existing == null` 变体：占位行被删但 final 密文无人清理 → 幽灵密文（eb5ec59 修复过的 generic 路径同款回归）。
另外 worker 外层 CE catch（:372-374）只 `workerScope.cancel + rethrow`，完全无清理——CE 落在元数据写入窗口（:333-371，循环外 try 之外）时 finalized 产物同样无人回滚。

### 2. 修复方案（三阶段提交状态机 + per-action 局部清理 helper + 三态 tmp 探测协调）

**函数级改动：`SyncDownloadPipeline.executeSyncLocked` 的 per-action 下载块**。v3 重写。v2 草案经复审暴露四类缺陷（全部修正依据见 §2.1/规则）：

- **推断缺陷（评审 #3，Critical）**：v2 的「tmp 缺失 ⇒ rename 已提交」把「**从未进入 finalize**」的情形也卷入探测分支——`openEncryptSink` 可在 tmp 创建前失败（`openWrite` IOException）、加密包装失败后还会**自删 tmp**（EncryptedMediaStore.kt:52-77）——此时旧 final 完好、tmp 不存在，v2 会误删旧 final + existing 行（数据丢失授权）。且 Boolean 探测不可实现为可靠磁盘证明：`SafVolume.findChildDocId` 把 **null cursor 与「未找到」合并**为 null（SafVolume.kt:39-51、:142-144），`storageOperationMutex` 排他引擎内写者、不使 SAF 查询本身可靠。
- **控制流缺陷（评审 #4）**：finalize 返回 false 后无显式失败分支（会继续 verify/元数据提交）；attempt 级 CE 清理后 rethrow 再入 :372 补偿 catch 造成**双重回滚**；:203-207 声明在 per-action try（:182）之内，:372 catch 不可见；`guarded` 的 `catch (_: Exception)` 实含 CE，与「只吞非 CE」描述不一致。
- **收敛承诺缺陷（评审 #7）**：「删行后下次重下覆盖同名孤儿密文」错误——删行后下次 dryRun `local == null` → 占位 upsert 分配**新 id**（SyncDownloadPipeline.kt:250-262 无 id 复用）→ 新 id 的 `openWrite` 只触碰新 id 的 tmp，**不触碰旧 id 的孤儿密文**（SafVolume.openWrite 先删同名仅作用于自身路径，:82-87）。
- **`uncleanFinalizedId` 清零点单薄**：仅「元数据 upsert 成功」一处，不足以与 attempt 级清理互斥（见规则 4）。

**v3.1（Oracle 复审 FAIL 8.0/10）再修订点**（逐处以下文「v3.1」标注；v3 骨架不动）：

- 复审 #5：`cleanUpAttempt` 的 `FINALIZE_UNCERTAIN` 分支对 `probePendingTmp` 的调用**单独保护**——探测异常（含 CE）按「原异常优先」收敛 UNKNOWN 后继续清理，不再裸调（原「helper 内部不可能抛出」不成立：`probePendingTmp` 明确 rethrow CE，NonCancellable 不约束实现方/mock 行为）。
- 复审 #4：`existsStrict` 契约收窄为「全路径 strict traversal + 完整结果」——SAF 中间目录段 null cursor/`DocumentsContract.EXTRA_LOADING` 加载中、FileVolume 访问错误均**不得**折算 ABSENT；§2.1 前提链显式给出支持边界（应用侧不可证明的卷/provider 前提 → 保守分支，不称普遍磁盘证明）。
- 复审 #6：修正 S2 输入（非法 IV 在 EncryptedMediaStore.kt:53 入口 require 即抛、尚未 openWrite，走不到自删路径）与管线 #14 断言切片（第一轮已记录 delete(42)，全局「绝不含 42」自相矛盾）。
- 复审 #7（术语）：`NOT_FINALIZED`（此前误写 NOT_FINALISHED）；删「AMIGUOUS 结果」措辞（易误读为第四阶段）。

**变量/函数作用域表（v3 唯一权威定义）**：

| 名称 | 作用域 | 职责 |
|---|---|---|
| `store`（既有 :115） | `executeSyncLocked` 局部 | 清理 helper 以 **per-action 局部 suspend 函数**闭包捕获——不做类方法（按签名不可访问该局部） |
| `var existing: VideoItemEntity? = null`、`var assignedId = 0L` | **声明提升至 per-action try（:182）之前**，try 内赋值（原 :203/:206 的 `val` 改为 try 外 `var` 声明 + try 内赋值）——v2 的「与 :203-207 同级」仍把声明留在 try **内**，:372 catch 照样不可见；v3 明确提升到 `try {` 之前 | :372 补偿清理与 cleanUpAttempt 均需读（existing 判条件删行、assignedId 定位密文） |
| `var uncleanFinalizedId = 0L` | per-action try（:182）之前（与上同级） | 「已提交未登记」登记簿；**两处清零点**：① 元数据 upsert 成功后（成功侧提交点）② `cleanUpAttempt` 完成时（清理侧，堵双重回滚——见规则 4） |
| `var attemptPhase` | while 循环体顶部，每次尝试重置 | 本尝试所处**三阶段**之一（`NOT_FINALIZED` / `FINALIZE_UNCERTAIN` / `COMMITTED`，见 §2.1） |
| `cleanUpAttempt(...)` | per-action 局部 suspend 函数（声明于提升声明之后、per-action try 之前；闭包捕获 `store`/`existing`/`assignedId`/`uncleanFinalizedId`） | 唯一清理入口（attempt 级 catch 与 :372 补偿共用），内部守卫内联（非伪码） |

单次尝试的状态转移与落点（替换 v2 伪码；`AttemptPhase` 为类级 `private enum class AttemptPhase { NOT_FINALIZED, FINALIZE_UNCERTAIN, COMMITTED }`）：

```kotlin
for (action in actionChannel) {                                          // :180
    var client = laneClient
    var existing: VideoItemEntity? = null        // v3：提升声明（:372 catch 可见性）
    var assignedId = 0L
    var uncleanFinalizedId = 0L
    suspend fun cleanUpAttempt(phase: AttemptPhase, id: Long) { /* 见下，唯一清理入口 */ }
    try {                                                                // :182 per-action try
        existing = videoRepository.getByRelativePathAndRelationId(localRelPath, relationId)
        var downloadSuccess = false; var attempts = 0; var lastIv = ByteArray(0)
        while (attempts < 3 && !downloadSuccess) {
            attempts++
            var attemptPhase = AttemptPhase.NOT_FINALIZED                 // 每次尝试重置
            var sink: java.io.OutputStream? = null
            try {
                ... downloadTo / sink.close / downloadSuccess 检查（不变） ...
                attemptPhase = AttemptPhase.FINALIZE_UNCERTAIN            // 进入 finalize：此后磁盘提交面不确定
                val finalizedOk = try {
                    store.finalize(assignedId)
                } catch (e: CancellationException) { throw e }            // 保持 FINALIZE_UNCERTAIN
                catch (e: Exception) {
                    throw SyncException(FINALIZE_FAILED, DOWNLOAD, path = action.relativePath, cause = e)  // 同上
                }
                if (!finalizedOk)                                         // v3 修复：false 显式失败
                    throw SyncException(FINALIZE_FAILED, DOWNLOAD, path = action.relativePath)
                attemptPhase = AttemptPhase.COMMITTED                     // 仅返回 true 才确认
                uncleanFinalizedId = assignedId                           // 登记与判定间无挂起点
                try { store.verifyReadable(assignedId) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw SyncException(STORAGE_ERROR, DOWNLOAD, path = action.relativePath, cause = e) }
            } catch (e: CancellationException) {                          // attempt 级 CE
                try { sink?.close() } catch (_: Exception) {}
                withContext(NonCancellable) { cleanUpAttempt(attemptPhase, assignedId) }   // 清理内置 uncleanFinalizedId=0
                throw e
            } catch (e: Exception) {                                      // attempt 级 generic（重试判定前先清理）
                try { sink?.close() } catch (_: Exception) {}
                withContext(NonCancellable) { cleanUpAttempt(attemptPhase, assignedId) }
                if (!isRetryableSyncException(e)) throw e
                ...
            }
        }
        ... :333-342 元数据窗口（getById → copy → upsert(withMeta)）不变 ...
        uncleanFinalizedId = 0L            // 成功侧唯一提交点：元数据 upsert 正常返回后**立即**清零
                                           // （在 extractMetadata :348 之前——upsert 成功即行/密文一致，
                                           //  其后的取消是干净中断，不回滚）
        ... :348-371 extractMetadata / syncLog / completedCount++ 不变 ...
    } catch (e: CancellationException) {                                   // :372 per-action 外层 CE catch
        val id = uncleanFinalizedId               // 提升声明后此处可见（v2 不可见——评审 #4）
        if (id != 0L) withContext(NonCancellable) { cleanUpAttempt(AttemptPhase.COMMITTED, id) }
        workerScope.cancel(e); throw e
    } catch (e: Exception) { ... :375-399 不变 ... }                       // finally lane close 不变
}
```

**`cleanUpAttempt`（per-action 局部 suspend 函数，v3 最终形态，守卫内联非伪码）**：

```kotlin
suspend fun cleanUpAttempt(phase: AttemptPhase, id: Long) {
    suspend fun guarded(op: suspend () -> Unit) {
        // v3 措辞更正：吞并一切 Exception（含防御性 CE）——NonCancellable 内框架不交付 CE，
        // 此兜底只防自定义实现/mock 抛 CE；吞并理由是「原异常优先」：清理中的任何异常
        // （含 CE）都不得替换已在传播的原异常（规则 3），也不得短路后续清理步骤（规则 1）。
        try { op() } catch (_: Exception) { /* 可 DebugLogger 计数 */ }
    }
    when (phase) {
        AttemptPhase.NOT_FINALIZED -> {            // 本尝试从未进入 finalize ⇒ 磁盘提交面未被触碰，
            guarded { store.cancel(id) }           // 旧 final **必然完好**——绝不删（v2 在此语境也探测 tmp，
            if (existing == null)                  //  tmp 缺失即删 final——评审 #3 的数据丢失授权点，v3 移除）
                guarded { videoRepository.deleteById(id) }
        }
        AttemptPhase.FINALIZE_UNCERTAIN -> {
            // v3.1（复审 #5）：探测调用**单独保护**——probePendingTmp 对普通调用方 rethrow CE
            // （契约不变），但 NonCancellable 不约束自定义实现/mock 的行为，清理语境裸调时探测
            // 抛出的 CE 会替换正在传播的原异常、跳过删行与末尾清零。按「原异常优先」把探测自身
            // 的任何异常（含 CE）收敛 UNKNOWN，随后继续清理。此处 catch CE 安全：cleanUpAttempt
            // 的两个调用点均在 withContext(NonCancellable) 内，框架真取消不会经此丢失。
            val probe = try {
                store.probePendingTmp(id)
            } catch (_: Exception) {
                TmpProbeState.UNKNOWN
            }
            when (probe) {                                                    // 三态，见下
                TmpProbeState.PRESENT -> {             // tmp 未被消费 ⇒ rename 未发生，旧 final 完好
                    guarded { store.cancel(id) }
                    if (existing == null) guarded { videoRepository.deleteById(id) }
                }
                TmpProbeState.ABSENT -> {              // 进入过 finalize 且 tmp **严格确认**缺失 ⇒ 被 rename 消费
                    guarded { store.delete(id) }       // （前提链及支持边界见 §2.1）——与 COMMITTED 分支同构
                    guarded { videoRepository.deleteById(id) }
                }
                TmpProbeState.UNKNOWN -> {             // 探测自身失败/结果不完整：删行（行不得指向可能已换的密文），
                    guarded { videoRepository.deleteById(id) }   // **不删 final**（可能仍为旧版本——保守）
                }
            }
        }
        AttemptPhase.COMMITTED -> {                // finalize 返回 true（改名后双检通过，SafVolume.kt:162）
            guarded { store.delete(id) }           // final = 新内容，可安全删
            guarded { videoRepository.deleteById(id) }   // 无条件：旧行 iv 指向已覆盖密文
        }
    }
    uncleanFinalizedId = 0L      // 清理侧清零点（v3 新增）：与 :372 补偿清理互斥——
                                 // v2 缺此步时，attempt 级 CE 清理后 rethrow 会再入 :372 补偿 catch，
                                 // delete/deleteById 各执行**两次**（评审 #4 双重回滚）；重试路径若再次
                                 // finalize 成功会重新登记，语义不变。
}
```

配套生产改动一（`EncryptedMediaStore`：**三态**探测，替换 v2 的 Boolean `hasPendingTmp`）：

```kotlin
enum class TmpProbeState { PRESENT, ABSENT, UNKNOWN }

/** BUG-007：tmp 密文存在性三态探测。ABSENT 仅在「已进入过 finalize」语境下**且**底层
 *  existsStrict 返回了完整、严格的「确认未命中」时可解读为「rename 已消费 tmp」（进入
 *  前提链与支持边界见计划 §2.1）；UNKNOWN = 探测自身失败/结果不完整（查询异常 / null
 *  cursor / SAF 加载中），调用方必须保守处理（只删行、不删 final）。与既有
 *  checkExistence（:135-141 sealed EXISTS/NOT_FOUND/IO_ERROR）同构的三态契约，非新发明。
 *  CE 传播契约：对普通调用方 rethrow CE 不变（不全局吞取消）；**清理语境（cleanUpAttempt）
 *  的调用点由调用方单独 try/catch 收敛 UNKNOWN**（v3.1，复审 #5）——本函数不因清理场景改契约。 */
suspend fun probePendingTmp(id: Long): TmpProbeState = try {
    if (volume().existsStrict(tmpMedia(id))) TmpProbeState.PRESENT else TmpProbeState.ABSENT
} catch (e: CancellationException) { throw e }
catch (_: Exception) { TmpProbeState.UNKNOWN }
```

配套生产改动二（`MediaVolume` 接口新增 `existsStrict`，`FileVolume` / `SafVolume` 两实现同步——目录内确有两个实现）：

```kotlin
/** 严格存在性（v3.1 收窄，复审 #4）：仅「**全路径完整查询且确认未命中**」返回 false；
 *  一切「查询不可信/不完整」形态**上抛** IOException（由 probePendingTmp 收敛 UNKNOWN），
 *  绝不与「不存在」合并——「误报缺失」在本调用语境 = 误授权删旧 final（数据丢失）。
 *
 *  SAF 实现要求（全路径 strict traversal，**不得复用** resolveDocId/findChildDocId 的宽松语义）：
 *  - 逐段解析：任一**中间目录段** query 返回 null cursor 或抛异常 ⇒ 抛 IOException
 *    （现行 findChildDocId 的 `?.use` 把 null cursor 吞成「未找到」、resolveDocId 随之把
 *    中间目录失败吞成整路径不存在，SafVolume.kt:39-51/:28-36——对 existsStrict 是毒药）；
 *  - **结果完整性**：cursor extras 的 DocumentsContract.EXTRA_LOADING=true 表示 provider 仍在
 *    异步加载子文档、当前结果可能不完整（SAF 异步 provider 契约）⇒ 抛 IOException——非 null
 *    cursor 的空/部分列表**不构成**「确认缺失」；
 *  - 全部目录段 + 文件名完整遍历结束未命中 ⇒ false。
 *  FileVolume 实现要求：**不得用 File.exists()**（false 混淆「不存在」与「访问失败」——
 *  v3.1 更正「File 语义天然三态可靠」的不实说法）——经 Files.readAttributes 读文件属性：
 *  NoSuchFileException ⇒ false；其余 IOException（如访问拒绝/ENOTDIR 类）上抛。
 */
suspend fun existsStrict(relPath: String): Boolean
```

**§2.1 finalize 提交/取消边界（v3：三阶段 + 前提链 + 三态探测；v3.1 补前提支持边界）**：

- **三阶段定义**：`NOT_FINALIZED`（本尝试未进入 finalize——磁盘提交面未被本尝试触碰）／`FINALIZE_UNCERTAIN`（已进入 finalize、未取得 true 确认）／`COMMITTED`（finalize 返回 true）。
- **关键前提链（v2 缺失，v3 补齐——评审 #3 的核心）**：到达 `store.finalize` 的必要条件是 `openEncryptSink` 已成功（:267 之后无回退路径），而 `openEncryptSink` 成功 ⇒ tmp 必已存在于磁盘。它的两条失败路径——`openWrite` 抛 IOException（**tmp 从未创建**）与加密包装失败（**NonCancellable 内自删 tmp**，EncryptedMediaStore.kt:52-77）——都使流程在进入 finalize **之前**抛出，phase 停在 `NOT_FINALIZED`。因此：`FINALIZE_UNCERTAIN` 语境下「tmp **严格确认**缺失（ABSENT）」在本应用视角下的唯一可能写者是 rename 的消费 ⇒ ABSENT 等价于已提交。**该等价的支持边界（v3.1 显式化，复审 #4）**：① provider 查询完整一致——`EXTRA_LOADING` 加载中的空/部分列表不得判缺失（`existsStrict` 拒收为 IOException→UNKNOWN）；② 探测窗口内 tmp 无其它消失原因——`storageOperationMutex` 只排他**应用内**写者，外部 app/系统侧对同一文档的写/删不被排除。①②是应用侧**无法普遍证明**的卷/provider 前提：`existsStrict` 把「查询不可信」显式上抛覆盖①的检测面，②作为残余如实列入 §5——本修复声称的是「约定前提下的最强推断 + 不可证明即走保守分支」，**不是普遍磁盘证明**。**`NOT_FINALIZED` 语境下对 tmp 缺失不作任何推断**（tmp 可能从未创建或已自删，旧 final 必然完好）——v2 在该语境也探测并据 ABSENT 删 final，正是被评审否定的数据丢失授权。
- 「finalize **返回 true** ⇒ 已改名」**成立**（保留 v2 结论）：`SafVolume.rename` 在 `renameDocument` 后做 `isDocumentExists(newUri) && !isDocumentExists(fromUri)` 双检（SafVolume.kt:162）。
- 「finalize **抛 CE / 异常 / 返回 false** ⇒ 未改名」**不成立**（保留 v2 结论）：`rename` 整体在 `withContext(Dispatchers.IO)` 内，CE 可在 `renameDocument` 完成后于返回边界交付；尾部双检自身异常被 `catch (_: Exception) { false }` 误报为 false（SafVolume.kt:150-171）。
- **为何三态而非 Boolean**（v2 的 `hasPendingTmp: Boolean` 被否）：`SafVolume.exists` 经 `resolveDocId` 把 null cursor（provider 拒绝/瞬断）与「未找到」**合并**为 false（SafVolume.kt:39-51、:142-144）——Boolean 无法把「探测不可信」传回调用方，会把查询失败误当 ABSENT 走删除分支。`storageOperationMutex` 排他的是 SyncEngine 内的写者，**不使 SAF 查询本身成为可靠磁盘证明**。`existsStrict` 拆开两者：null cursor / 查询异常 ⇒ 抛 ⇒ `probePendingTmp` 收敛 `UNKNOWN` ⇒ 保守分支（只删行不删密文）。**v3.1 补（复审 #4）**：除 null cursor/异常外，**非 null cursor 也不保证结果完整**——`DocumentsContract.EXTRA_LOADING=true` 表示 provider 仍在异步加载、遍历结束≠全集，空/部分列表不得折算 ABSENT；且「严格」必须是**全路径**的——若复用 `resolveDocId`/`findChildDocId`，中间目录段的 null cursor 仍被吞成「路径不存在」，误判 ABSENT 同样误授权删旧 final（测试 S8/S9 锁定）。
- **登记窗口**：`uncleanFinalizedId = assignedId` 赋值紧跟 `attemptPhase = COMMITTED`，二者与 finalize 返回之间无挂起点（登记簿不可能领先于磁盘确认）；`FINALIZE_UNCERTAIN` 阶段的不确定结果由 `cleanUpAttempt` 的探测收束（v3.1 措辞更正：探测针对的是 FINALIZE_UNCERTAIN 这**既有三阶段之一**的不确定性，AMBIGUOUS 并非第四阶段）——探测运行于 `NonCancellable`，不受取消窗口影响，且从不确定结果到探测之间 tmp 无**应用内**写者（同 action 串行、`storageOperationMutex` 排他——外部写者残余见 §5 v3.1）。

**清理失败 / false 返回 / 原异常保留 / 恰一次回滚规则（显式枚举，规则即契约）**：

1. `store.delete` / `store.cancel` / `deleteById` 抛任何异常（含防御性 CE，见 §2 代码注释）→ `guarded` 吞并，**后续清理步骤照常执行**（互不短路）；清理失败只降级为残留，绝不升级为替换原异常。
2. `store.delete` 返回 **false ≠ 已删除**（`SafVolume.delete` 在 resolve 失败与 resolver.delete 失败时均返回 false，SafVolume.kt:132-140）→ 一律按「未确认」处理、照常删行。**v3 删除 v2 的「下次重下覆盖同名孤儿密文」收敛承诺**：删行后下次 dryRun `local == null` → 占位 upsert 分配**新 id**（SyncDownloadPipeline.kt:250-262）→ 新 id 的 `openWrite` 不触碰旧 id 密文——**孤儿密文不自动回收**，作为真实残余列入 §5（验收拆分见 §3 存储/管线用例）。
3. 原 CancellationException 永远最后原样 rethrow（**实例同一**，测试 9 与 13b 以 `===` 锁定）：清理在 `withContext(NonCancellable)` 内执行。**v3.1 更正 v3 的不实表述「helper 内部不可能抛出（guarded 全吞）」**——guarded 只保护清理步骤，探测调用曾是裸调（`probePendingTmp` 明确 rethrow CE；NonCancellable 只改变框架行为，不约束自定义实现/mock 主动抛 CE）——裸调形态下探测 CE 会替换正在传播的原 CE 并跳过删行与末尾清零。v3.1 起探测调用点单独 try/catch（含 CE）收敛 UNKNOWN 后继续清理（§2 代码已改）。**探测保护只施加于清理语境**：`probePendingTmp` 作为普通 API 的 CE 传播契约不变（不全局吞取消）。
4. **一次异常恰一次回滚（v3 机制）**：`uncleanFinalizedId` 两处清零——① 成功侧：元数据 upsert（:343 附近）正常返回后立即清零；② 清理侧：`cleanUpAttempt` 末尾清零。互斥推导：attempt 级 catch 先清理（登记簿→0）再 rethrow，:372 补偿 catch 读到 0 即跳过；元数据窗口 CE 未经 attempt 级清理，登记簿非 0 → :372 走**同一个** `cleanUpAttempt(COMMITTED, id)` 入口（非内联重复代码）。:372 补偿体即：

```kotlin
} catch (e: CancellationException) {          // :372（per-action 外层；元数据窗口 CE 落点）
    val id = uncleanFinalizedId                // 提升声明后可见（v2 不可见——评审 #4）
    if (id != 0L) withContext(NonCancellable) { cleanUpAttempt(AttemptPhase.COMMITTED, id) }
    workerScope.cancel(e); throw e
}
```

5. CE 在清理中的例外规则（与规则 3 同源）：`guarded` 的 `catch (_: Exception)` 覆盖 CE **是刻意的**——NonCancellable 语境下框架不会交付 CE，该分支仅为防自定义实现/mock 违约；即便触发，吞并它也比「清理 CE 替换原 CE」安全（原异常优先）。v2 的「只吞非 CE」措辞与实现不符，v3 以实现为准更正措辞。v3.1（复审 #5）：探测调用点的单独保护同此理——这是 `cleanUpAttempt` 内**最后一个**未保护的可抛出调用点，此后对调用方不再存在「清理异常替换原异常」路径。

verify 失败 STORAGE_ERROR 路径行为等价性：现行内层删 `store.delete`+`deleteById` 后外层再 `cancel`+条件删行（no-op 双写）；v3 走 `cleanUpAttempt(COMMITTED)` 单点（`delete`/`deleteById` 各恰 1 次、`cancel`=0），:372 不再重复（登记簿已清）——净效果一致，测试 4 断言按 v3 计数更新。

### 3. TDD 测试计划

管线用例文件：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDownloadPipelineTest.kt`（既有类；MockK 构造沿用 `SyncDeleteExecutorTest` 同款注入模式，report BH_LateCancelAfterFinalizeTest 已验证该驱动路径可行）。v3：逐条标注**红**（旧实现下必失败）/**回归锁**（旧实现已正确或锁定 v3 契约、防回退——含防回退到 v2 草案形态），并按评审补齐：存储层三态组、upsert 本身取消、恰一次回滚、原异常实例、NOT_FINALIZED 两变体。

**存储层组（新增，评审 #3 要求的「真实存储层/provider fake」）**：

新文件 `app/src/test/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStoreTmpProbeTest.kt`（fake `MediaVolume` 接口实现，纯 JVM）：

| # | 用例 | 断言 |
|---|---|---|
| S1 | `probePendingTmp maps volume states to tri-state` | fake existsStrict true→PRESENT / 正常 false→ABSENT / 抛 IOException→UNKNOWN（三态契约本体） |
| S2 | `openEncryptSink wrapper failure self-deletes tmp`（前提链证据；**v3.1 修正输入**，复审 #6） | **非法 iv 不可用**——它在 EncryptedMediaStore.kt:53 入口 require 即抛，尚未 `openWrite`，走不到 :70-75 的包装失败自删路径。改用**通过 :53/:54 两条入口 require、在 :69 `encryptStream` 包装阶段失败**的输入：合法非负但超过实现上限的 `plainLength`（KDoc :64-67 明示 encodeHeader 在属性初始化期拒绝超上限长度、可先于构造体 try/catch 抛出）→ fake volume 记录到 `delete(tmp)` 调用——锁定 :70-75 自删行为（§2.1 前提链的存储层支点） |
| S3 | `openWrite failure never creates tmp`（前提链证据） | fake `openWrite` 抛 IOException → fake volume 无 tmp 创建记录、`delete(tmp)` 未被调（tmp 从未存在——NOT_FINALIZED 语境 tmp 缺失的一类来源） |
| S4 | `finalize false / true are passed through unchanged` | fake rename 返回值直通（finalize 是薄封装，EncryptedMediaStore.kt:90）——锁定探测不干扰 finalize 本体 |

新文件 `app/src/test/java/cn/vectory/syncplayer/storage/volume/SafVolumeExistsStrictTest.kt`（`mockkStatic(DocumentsContract::class)` + mock ContentResolver——repo JVM 纪律内的既有 mockk 手法；若 CI 对 android.jar 静态桩不稳则降级 Robolectric 4.13 @Config，二选一在实现时定）：

| # | 用例 | 断言 |
|---|---|---|
| S5 | `existsStrict returns false only on confirmed absence` | 正常空 cursor（完整遍历无命中、无 loading 标志）→ false |
| S6 | `existsStrict throws on null cursor instead of returning false`（**红**：现行 `exists` 对 null cursor 返回 false，SafVolume.kt:39-51/:142-144——本用例锁死拆分） | `query(...)` 返回 null → `existsStrict` 抛 IOException（不与「未找到」合并） |
| S7 | `existsStrict propagates provider exceptions` | `query(...)` 抛 RuntimeException → 抛出（上抛至 probePendingTmp 收敛 UNKNOWN） |
| S8 | `existsStrict throws when intermediate directory query fails`（**v3.1 新增**，复审 #4：全路径 strict） | 中间目录段 `query` 返回 null cursor（或抛异常）→ 抛 IOException——若复用 `resolveDocId`/`findChildDocId` 会把中间目录失败吞成「整路径不存在」→ 误 ABSENT → 误授权删旧 final；抛出后 probePendingTmp 收敛 UNKNOWN 走保守分支 |
| S9 | `existsStrict throws when child listing is still loading`（**v3.1 新增**，复审 #4） | cursor extras `DocumentsContract.EXTRA_LOADING=true`（provider 异步加载、当前结果可能不完整）→ 抛 IOException——**非 null cursor 的空/部分列表不构成「确认缺失」**，不得折算 ABSENT |

新文件 `app/src/test/java/cn/vectory/syncplayer/storage/volume/FileVolumeExistsStrictTest.kt`（纯 JVM，TemporaryFolder——FileVolume 无 Android 依赖）：

| # | 用例 | 断言 |
|---|---|---|
| S10 | `existsStrict distinguishes absence from access error`（**v3.1 新增**，复审 #4） | 不存在的路径 → false；父路径为**普通文件**的子路径（属性读失败、非 NoSuchFileException——ENOTDIR 类 FileSystemException）→ 上抛 IOException、**不误报 false**——锁定实现不得用 `File.exists()`（其 false 混淆不存在与访问失败，FileVolume.kt:39-41 现状即如此）；存在文件 → true（正向对照） |

**管线组**（1–12；红/回归锁标注）：

1. `cancellation after confirmed finalize rolls back final ciphertext and deletes existing row`（**红**：旧 CE 分支只 cancel+条件删行，`store.delete`=0）
   - Arrange：existing 行 id=42；`downloadTo` true；`finalize(42)` true；`verifyReadable(42)` 抛 `CancellationException()`。
   - Act：`assertFailsWith<CancellationException> { sut.executeSyncLocked(...) }`（runTest 内直接捕获）。
   - Assert：`coVerify(exactly = 1) { store.delete(42L) }`；`exactly = 0 { store.cancel(42L) }`；`exactly = 1 { videoRepository.deleteById(42L) }`——**exactly=1 同时锁定恰一次回滚**（v2 双重回滚形态下会是 2：attempt 清理 + :372 补偿，评审 #4）。
2. `cancellation after confirmed finalize on new file removes ghost ciphertext and placeholder row`（**红**：同上）
   - Arrange：existing=null；placeholder `upsert` returns 4242；`finalize(4242)` true、`verifyReadable(4242)` 抛 CE。
   - Assert：`store.delete(4242)`=1、`cancel`=0、`deleteById(4242)`=1。
3. `cancellation before finalize keeps legacy cancel semantics`（**回归锁**）
   - Arrange：`downloadTo` 抛 CE（phase=NOT_FINALIZED，finalize 从未被调）。
   - Assert：`store.cancel`=1、`store.delete(any())`=**0**——**无论 tmp 在否，NOT_FINALIZED 绝不删 final**（锁定 v3 契约；v2 草案在 tmp 缺失变体会误删——评审 #3 的数据丢失窗口）；existing=null 变体另断言 `deleteById`=1。
4. `verifyReadable IOException keeps single-point rollback and failure count`（**回归锁**；对照组）
   - Arrange：`verifyReadable` 抛 `IOException`（→ STORAGE_ERROR 非 retryable → attempt generic catch 清理一次后 rethrow）。
   - Assert：返回 false；`store.delete(42)`=1；`store.cancel(any())`=**0**；`deleteById(42)`=1；终态 `phase == PARTIAL_FAILED && failedFiles == 1`。
5. `cancellation during metadata read after finalize triggers outer rollback`（**红**：旧 :372 catch 无清理）
   - Arrange：`finalize(42)` true、while 退出；元数据读 `videoRepository.getById(42)` 抛 CE（落 :338——per-action try 内、:372 真实可达）。
   - Assert：CE 传播；`store.delete(42)`=1、`deleteById(42)`=1（:372 经 cleanUpAttempt(COMMITTED) 补偿）。
6. `cancellation during final metadata upsert itself triggers outer rollback`（**红**；v3 新增——评审 #4：v2 仅在 getById 注入，未覆盖 upsert 边界）
   - Arrange：同 5 但 `getById` 成功、`videoRepository.upsert(withMeta)` 抛 CE（落 :343——成功侧清零点之前）。
   - Assert：CE 传播；`store.delete(42)`=1、`deleteById(42)`=1（清零点之后的取消不得回滚——与 #7 对照）。
7. `cancellation after metadata upsert success performs no rollback`（**回归锁**：锁成功侧清零点）
   - Arrange：`getById`/`upsert(withMeta)` 全成功（登记簿已清零）；`extractMetadata` 抛 CE（:350 落点）。
   - Assert：CE 传播至终态 CANCELLED progress；`store.delete(any())`=0、`deleteById(any())`=0（行/密文已一致，取消=干净中断）。
8. `ambiguous finalize CE with tmp confirmed consumed treats pass as committed (existing)`（**红**：旧实现 cancel-only——「已改名但 finalize 未正常返回」existing 变体）
   - Arrange：existing id=42；`finalize(42)` 抛 `CancellationException()`（renameDocument 完成后于 withContext 返回边界交付的形状）；`probePendingTmp(42)` returns **ABSENT**（三态）。
   - Assert：CE 传播；`store.delete(42)`=1、`cancel`=0、`deleteById(42)`=1。
9. `ambiguous finalize false with tmp confirmed consumed rolls back committed state (new file)`（**红**：new 变体——双检误报 false 形状）
   - Arrange：existing=null、`upsert` returns 4242；`finalize(4242)` returns **false**（→ FINALIZE_FAILED，非 retryable）；`probePendingTmp(4242)` returns ABSENT。
   - Assert：返回 false、`failedFiles`==1；`store.delete(4242)`=1、`cancel`=0、`deleteById(4242)`=1。**前置依赖**：本用例同时锁死「false 必须显式抛 FINALIZE_FAILED」（v2 缺该分支会继续 verify/提交元数据，评审 #4）——以「metadata upsert 未被调（`coVerify(exactly=0) { videoRepository.upsert(withMeta 类匹配) }`）」附加断言。
10. `finalize false with tmp still present keeps legacy cancel semantics`（**回归锁**：false≠已改名的另一侧——探测 PRESENT 走 cancel 路径，不删 final）
    - Arrange：`finalize(42)` returns false；`probePendingTmp(42)` returns **PRESENT**。
    - Assert：`store.delete`=0、`store.cancel(42)`=1；existing!=null 时 `deleteById`=0（行保留）。
11. `NOT_FINALIZED with tmp absent never deletes old final (existing)`（**回归锁**——评审 #3 数据丢失窗口的直接锁定：v2 草案在此形态删 final+行，现行实现不删、v3 契约不删）
    - Arrange：existing id=42；`openEncryptSink(42,...)` 抛 `SyncException(STORAGE_ERROR)`（模拟包装失败已自删 tmp / openWrite 失败——phase=NOT_FINALIZED 且 tmp 缺失）。
    - Assert：返回 false（action 计失败）；`store.delete(any())`=**0**（旧 final 完好）；`store.cancel(42)`=1（no-op 但显式调用）；`deleteById(42)`=**0**（existing 行保留）；fake `probePendingTmp` **不得被调**（`coVerify(exactly=0)`——NOT_FINALIZED 不进探测）。
12. `probe UNKNOWN keeps row-only conservative cleanup`（**红**：旧实现无探测；现行为 cancel+保行——v3 改为无条件删行）
    - Arrange：`finalize(42)` 抛 CE；`probePendingTmp(42)` returns **UNKNOWN**。
    - Assert：CE 传播；`store.delete(any())`=**0**（不删 final）；`deleteById(42)`=**1**（行删——行不得指向可能已换的密文；旧实现 existing 时保行 → 红）。
13. `cleanup failure neither masks original CE nor skips row deletion`（**红**）
    - Arrange：同 1，但 `store.delete(42)` 抛 `RuntimeException("cleanup boom")`。
    - Assert：终态异常 `caught === 原 CancellationException`（**实例同一**，评审 #4）；`deleteById(42)`=1（规则 1 不短路）。
13b. `probe throwing CancellationException neither masks original CE nor skips conservative cleanup`（**红**——v3.1 新增，复审 #5：v3 的探测裸调形态在此用例下抛探测 CE 替换原 CE）
    - Arrange：existing id=42；`finalize(42)` 抛 `CancellationException()`（**原 CE 实例**，测试保存引用）；`probePendingTmp(42)` 抛**另一个** `CancellationException()` 实例（模拟自定义实现/mock 在清理语境抛 CE——NonCancellable 不约束实现方行为）。
    - Assert：终态异常 `caught === 原 CE 实例`（探测 CE 不得替换）；`videoRepository.deleteById(42)`=1（探测异常收敛 UNKNOWN、保守删行不跳过）；`store.delete(42)`=0（UNKNOWN 不删 final）；`store.cancel(42)`=0（探测收敛后不再走 PRESENT 分支）。
14. `orphan ciphertext is not reclaimed by next download (acceptance split)`（**回归锁**——评审 #7：用「下一次分配不同 ID 的有状态 fake」锁定实际结果，删除 v2 的覆盖收敛承诺；**v3.1 修正断言切片**，复审 #6）
    - Arrange：有状态 fake `VideoRepository`：deleteById(42) 真实移除行；后续 `upsert(占位)` 返回**新 id 999**（不复用 42）；fake store 记录 delete/cancel 调用入参（有序列表）。第一轮：finalize CE + probe ABSENT → 行删+密文删路径走完后，人为保留 `42.enc`（delete 返回 false 模拟删除失败——**调用记录含 delete(42) 是必然**）。
    - Act：**第一轮结束后快照记录表**（`snapshot = fakeStore.calls.toList()`）；第二轮 `executeSyncLocked`（同 relativePath）→ 占位行 id=999、`openWrite(999.tmp)`/`finalize(999)` 正常。
    - Assert：仅针对**第二轮新增切片**（`fakeStore.calls.drop(snapshot.size)`）：全部只含 999、**绝不含 42**（旧 42.enc 孤儿不被新 id 流程触碰——**空间不自动回收**，与 §5 残余一致；v3 版「fake 全部记录绝不含 42」与第一轮自身的 delete(42) 记录自相矛盾，不可满足——已废弃）；但第二轮完成 → `getByRelativePathAndRelationId` 有行且密文可用（**索引/可播放状态恢复** ✓——两类验收显式拆分）。
15. 既有 `SyncDownloadPipelineTest` 全量回归（verify 回滚/占位行/重试类用例）保持绿。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.TransportCancellationRegressionTest' --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineClientReuseTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

期望：BUILD SUCCESSFUL；`TransportCancellationRegressionTest`（传输层取消语义，报告列为已锁定）不回归。

### 5. 风险与守卫

- **CancellationException 先传播（红线 3）**：所有清理在 `withContext(NonCancellable)` 内执行；`guarded` 对每个清理步骤单独吞并一切异常（含防御性 CE——原异常优先，规则 3/5）；**v3.1（复审 #5）**：探测调用点（FINALIZE_UNCERTAIN 分支）同款单独保护后，`cleanUpAttempt` 对调用方不再存在未保护的可抛出调用点——原 CE/原异常永远最后原样上抛（测试 13/13b 以 `===` 锁定实例同一）。
- **随机读失败抛异常不返回脏明文（红线 5）/ AEAD v2 不变（红线 4）**：本修复只改清理编排与 tmp 探测，不触碰加解密、header、finalize 原子性。
- **幂等性 / 恰一次回滚**：`store.delete`/`deleteById` 对已删除目标为 no-op；attempt 级清理与 :372 补偿清理由「cleanUpAttempt 末尾清零 + :372 读零跳过」互斥（规则 4，测试 1 以 exactly=1 锁定）。
- **行为变化点**：existing 行在 post-finalize 清理时从「保留旧行」变为「删行」——旧行 iv 指向已覆盖密文，保留即不一致；删行后下次 dryRun 重下（**索引/可播放状态恢复**）。
- **不盲删可能为旧版本的 final（双保险）**：`delete(final)` 仅发生于「finalize 返回 true」（COMMITTED）或「**进入过 finalize** 且探测 ABSENT」两种**在 §2.1 前提边界内**判定新内容在位的情形（v3.1：非普遍磁盘证明——provider 前提不可满足时探测已收敛 UNKNOWN 走保守分支）；`NOT_FINALIZED` 一律不探测不删 final；探测 UNKNOWN 只删行不删密文（测试 3/11/12 锁定）。
- **真实残余（v3 如实列出，不再宣称自愈）**：① **孤儿密文不自动空间回收**——delete=false / UNKNOWN 分支遗留的旧 id 密文，下次重下走**新 id**（SyncDownloadPipeline.kt:250-262），新流程不触碰旧密文（测试 14 锁定此实际行为）；空间回收不在本轮范围，归入 todo.md 既有「SAF 契约/存储管理测试投资」方向的后续项（如孤儿扫描）。② probe UNKNOWN 的 new-file 变体可能残留幽灵密文（行已删、密文可能在）——相比现行「必然幽灵」收窄为「探测失败时才可能」。③ generic 非 CE 异常落在元数据窗口无清理（现行行为保留，非 CE 路径、下次 dryRun 自愈）。④ **外部写者残余（v3.1 新增，复审 #4）**：`storageOperationMutex` 只排他应用内写者——若外部 app/系统在 rename 与探测之间删除 tmp，ABSENT 会误判「已提交」而删旧 final；属 §2.1 前提边界②之外的应用侧不可消除残余，如实列出、不以「磁盘证明」措辞掩盖。

### 6. 涉及文件清单（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt`（下载块 try/catch 重构 + 声明提升 + per-action 局部 `cleanUpAttempt`（探测调用单独保护——v3.1）+ `AttemptPhase` + `uncleanFinalizedId` 双清零 + :372 补偿走统一入口）、`app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt`（+`TmpProbeState` / `probePendingTmp(id)` 三态窄 API）、`app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolume.kt`（+`existsStrict` 接口方法）、`app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt`（+existsStrict：**全路径 strict traversal**——逐段查找不复用 resolveDocId/findChildDocId 的 null-吞并；null cursor/查询异常/**EXTRA_LOADING=true** → IOException；全部段完整遍历未命中 → false）、`app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt`（+existsStrict：经 `Files.readAttributes` 读属性——`NoSuchFileException`→false、其余 IOException 上抛；**不用 `File.exists()`**——其 false 混淆不存在与访问错误，v3.1 更正「File 语义天然三态可靠」的不实说法）
- 测试：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDownloadPipelineTest.kt`（+15 用例：1–14 + 13b，9 红 + 6 回归锁）；新建 `app/src/test/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStoreTmpProbeTest.kt`（S1–S4）、`app/src/test/java/cn/vectory/syncplayer/storage/volume/SafVolumeExistsStrictTest.kt`（S5–S9）、`app/src/test/java/cn/vectory/syncplayer/storage/volume/FileVolumeExistsStrictTest.kt`（S10，纯 JVM）

## BUG-008 <a id="bug-008"></a>

**终态 CANCELLED/FAILED 进度写入清零计数器与已完成清单（通知显示 "0 of 0"）**（todo.md #6 / 报告 0012 BUG-008，Severity Low）

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:521-538`** —— 两个终态写入构造**全新零计数器对象**：

```kotlin
} catch (e: CancellationException) {
    progressPublisher.value = SyncProgress(          // :522 ← fresh 对象
        isRunning = false, phase = SyncPhase.CANCELLED,
        relationId = relationId, relationName = name, localBasePath = basePath
    )
    throw e
} catch (e: Exception) {
    progressPublisher.value = SyncProgress(          // :532 ← 同形态
        isRunning = false, phase = SyncPhase.FAILED, ...
    )
```

对照：正常终态 `:500-512`（COMPLETED/PARTIAL_FAILED）显式携带 `completedFiles/totalFiles/failedFiles/transferredBytes/startTimeMs/completedPaths`，不受影响。

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncProgressPublisher.kt:56-69, 305-325`** —— merge 机制：

```kotlin
set(value) {
    stateLock.withLock {
        val snapshotBeforeFlush = _progress.value
        val forceFlush = value.phase != snapshotBeforeFlush.phase || value.phase in TERMINAL_PHASES
        if (forceFlush) publishPendingLocked(ignoreWindow = true)     // 先 flush 脏计数（保住终计数）
        _progress.value = if (forceFlush) {
            _progress.value.applyRequestedChanges(snapshotBeforeFlush, value)
        } else { value }
        ...
// applyRequestedChanges（:305-325）逐字段：
totalFiles = if (requested.totalFiles != baseline.totalFiles) requested.totalFiles else totalFiles
```

**根因**：merge 的设计契约是「调用方基于旧快照 `value.copy(...)` 写入，未显式改动的字段保留 flush 结果」（publisher KDoc :52-54 明文）。但 CANCELLED/FAILED 生产写入是 fresh 对象——`requested.totalFiles=0 ≠ baseline.totalFiles=N` 被逐字段判为「有意更新」→ flush 刚保住的终计数反被 0 覆盖（`totalFiles/completedFiles/failedFiles/transferredBytes/totalBytes/completedPaths` 全灭）。下游：`SyncService.onCreate` collector → `SyncNotificationHelper` 渲染 `completedFiles/totalFiles` → "0 of 0"。

同族写入点（同一根因，一并修）：
- `SyncDeleteExecutor.kt:190-196`：DELETE_BOTH catch-all FAILED 同为 fresh 对象（该流程 `:83-86` 循环内 copy 已推进 completedFiles，终态反清零）。
- `SyncService.kt:105`（onTimeout）与 `:134`（cancelSync）：`SyncProgress(phase = SyncPhase.CANCELLED)` 直送 `SyncNotificationHelper`（不经 publisher）——取消通知同样显示 0/0。

**明确不修的相邻写入点**（非本 BUG，写明理由防误扩）：`SyncRemoteScanner.kt:68/:152/:162/:192/:218/:223`（SCANNING/CONFIRMING/FAILED）与 `SyncDownloadPipeline.kt:134`（空间不足 FAILED）——均发生在 `beginSyncRun`/run 初始化写之前，当前值无本轮计数可保，且 fresh 写入恰好实现「新扫描重置旧轮计数」的语义；若改为 copy 反而会残留**上一轮**的过期计数。

### 2. 修复方案（生产者侧改用 `value.copy`，publisher merge 不动）

todo.md 与报告给出两条方向（copy 写入 / merge 终态特判）；选**生产者侧 copy**：它就是 publisher 已文档化的契约（KDoc :52-54），改动面小、不引入「0 是不是有意的」启发式、`SyncProgressPublisherTest` 既有 merge 语义零变更。

```kotlin
// SyncDownloadPipeline.kt:521-528（CE catch）
progressPublisher.value = progressPublisher.value.copy(
    isRunning = false, phase = SyncPhase.CANCELLED,
    relationId = relationId, relationName = name, localBasePath = basePath
)
// :530-538（Exception catch）同形态：copy(isRunning=false, phase=FAILED, relationId/relationName/localBasePath)

// SyncDeleteExecutor.kt:190-196（catch-all）同形态 copy

// SyncService.kt onTimeout/cancelSync（仅通知展示层，读引擎当前进度）：
SyncNotificationHelper.updateNotification(
    this, syncEngine.progress.value.copy(phase = SyncPhase.CANCELLED)
)
```

copy 写入下 merge 的正确性：`requested` 与 `baseline`（flush 前快照）在计数字段上相等 → 逐字段取「当前（已 flush）值」→ flushed 终计数、`completedPaths`、`startTimeMs` 全保留；`phase/isRunning/relationId/...` 显式改动字段优先生效。CE 写入发生于 `workerScope.cancel` 之后（其它 lane 的 flush 已由 publisher 锁串行化），终值确定。

### 3. TDD 测试计划

1. `app/src/test/java/cn/vectory/syncplayer/sync/SyncProgressPublisherTest.kt`（**既有契约回归测试，非红测试**——当前实现已满足；v2 更正定位、**v3 收敛措辞**：它只锁定 **publisher 端契约**——merge 对 copy 写入的计数保留/终态吸收语义，防 merge 实现未来变更破坏修复所依赖的契约；**「防生产端回退 fresh 写法」不由本用例承担**——该职责由 #2/#3/#4（驱动真实生产写入点 :521/:518/:190 的红测试）承担，此处不重复声明）：
   `copy-based terminal CANCELLED write preserves flushed counters and completedPaths`
   - Arrange：真实 `SyncProgressPublisher(currentTimeMs = 注入时钟)`；`beginSyncRun()` → `beginDownload("f0", ...totalFiles=10, completedFiles=0)` → 时钟推进 600ms（越过 500ms 节流窗）→ `completeDownload("f0", transferredSoFar=10000, totalDownloadBytes=10000, completedFiles=1, totalFiles=10)`。
   - Act：`publisher.value = publisher.value.copy(isRunning = false, phase = SyncPhase.CANCELLED)`。
   - Assert：`value.totalFiles == 10`、`value.completedFiles == 1`、`value.transferredBytes == 10000L`、`value.totalBytes == 10000L`、`value.completedPaths == setOf("f0")`、`value.phase == CANCELLED`、`value.isRunning == false`。
2. `app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineProgressTest.kt`（**红测试**：驱动真实生产写入点 :521——旧实现 fresh 写入 → totalFiles==0）：
   `cancellation mid-run keeps last known counters in terminal progress`
   - Arrange：1 个 download action；`downloadTo` 在收到首字节回调后抛 CE（beginDownload 已发生）。
   - Act：`assertFailsWith<CancellationException> { sut.executeSyncLocked(...) }`。
   - Assert：终态 `progress.value.phase == CANCELLED && totalFiles == 1 && isRunning == false`。
3. 同文件：`unexpected failure after COMPLETED write keeps counters in terminal FAILED`（**红测试**；v2 修正注入点——元数据阶段抛 RuntimeException 会被 per-action catch :375 收敛为 PARTIAL_FAILED 计数，**到不了 :530 批次级 catch**，不可用作注入点。改用 :518 `syncRelationRepository.updateLastSync(any(), any())` 抛 RuntimeException：它在 :500 COMPLETED 终态写**之后**、外层 try 内、:530 catch-all 真实可达）
   - Arrange：1 个 download action 全链成功（:500 已写 COMPLETED + 计数）；`updateLastSync` 抛 RuntimeException。
   - Assert：返回 false（:530 吞异常）且 `phase == FAILED && totalFiles == 1 && completedFiles == 1 && failedFiles == 0`（旧实现 fresh FAILED 写入清零 → 红）。
4. `app/src/test/java/cn/vectory/syncplayer/sync/SyncDeleteExecutorTest.kt`（**红测试**，注入点 :190 catch-all；init 写 `:68` 已置 totalFiles=1）：
   `delete flow catch-all FAILED preserves counters`
   - Arrange：`client.connect` 抛 SyncException（落入 :190 catch-all）。
   - Assert：返回 false 且注入的 publisher `value.totalFiles == 1 && phase == FAILED`。
5. `app/src/test/java/cn/vectory/syncplayer/sync/SyncPreviewViewModelTest.kt` 既有终态观察用例回归（`observeSyncCompletion` 以 phase 为键，不受计数影响，须保持绿）。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncProgressPublisherTest' --tests 'cn.vectory.syncplayer.sync.SyncEngineProgressTest' --tests 'cn.vectory.syncplayer.sync.SyncDeleteExecutorTest' --tests 'cn.vectory.syncplayer.sync.SyncPreviewViewModelTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

期望：BUILD SUCCESSFUL；publisher 既有 merge 用例（`value.copy` 形态）全部保持绿。

### 5. 风险与守卫

- **不涉红线面**：纯进度展示层；`observeSyncCompletion` 以 phase 为键（报告已确认），改计数不改变完成信号语义。
- **不采用 merge 特判的理由**：终态 phase 下「requested 0」无法与合法的「本轮确实 0 任务」区分（需引入 isRunning/时间戳启发式），启发式即回归温床；生产者遵守已文档化契约是最小且无歧义修复。
- **多关系串扰（既有残余，不扩大）**：终态 copy 继承的是 publisher 单例当前值，若上一轮另一关系的计数未被本轮 beginSyncRun 清空……本轮 `beginSyncRun`（:146）在 init 写（:148）前执行，fresh init 写已重置计数，copy 不会跨轮泄漏。
- SyncService 两处 display 写入改为读 `syncEngine.progress.value`：竞态为 last-writer-wins（引擎终态写与 onTimeout 直写竞争），两写均为「CANCELLED + 本轮计数」语义，收敛一致。

### 6. 涉及文件清单（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt`（:521-538 两处）、`app/src/main/java/cn/vectory/syncplayer/sync/SyncDeleteExecutor.kt`（:190-196）、`app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt`（onTimeout/cancelSync 两处通知构造）
- 测试：`SyncProgressPublisherTest.kt`（+1）、`SyncEngineProgressTest.kt`（+2）、`SyncDeleteExecutorTest.kt`（+1）

## BUG-009 <a id="bug-009"></a>

**SFTP `deleteFile` 非幂等：远端已消失（NO_SUCH_FILE）被误报 REMOTE_DELETE_FAILED**（todo.md #4 / 报告 0012 BUG-010；**编号以 todo.md 为准：本项=009**）

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt:290-306`**：

```kotlin
override suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
    val sftp = sftpClient ?: throw SyncException(SyncErrorCode.NOT_CONNECTED, SyncOperation.DELETE)
    try {
        val fullRemotePath = if (remoteBasePath.isNotEmpty()) "$remoteBasePath/$remotePath" else remotePath
        sftp.rm(fullRemotePath)
        true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {                       // ← 全量映射，无幂等分支
        currentCoroutineContext().ensureActive()
        throw SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE,
            path = remotePath, cause = e)
    }
}
```

**契约与正确样板**：`SyncDeleteExecutor.kt:34` KDoc「远端 `deleteFile` 幂等（返回 false 表示『本来就不存在』，按成功语义处理）」；`app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt:413`：`if (!smbFile.exists()) return@withContext false`。

**根因**：TOCTOU——`dryRunDeleteLocked` 扫描时远端文件存在（`SyncRemoteScanner.kt:207-214` 仅对存在文件推导 DELETE_BOTH），执行时已被并发方删除 → sshj `rm` 抛 `SFTPException`（statusCode=NO_SUCH_FILE）→ 落入全量 `catch(Exception)` → `REMOTE_DELETE_FAILED` → executor `failedActions++` → PARTIAL_FAILED 误报。报告已用真实 sshj 0.39.0 jar 构造异常复现；且 catch 为全量 Exception，任何异常类型都会命中（修复必须区分 NO_SUCH_FILE 与真失败）。**与 BUG-001 的联动**：remote-first 重排后 SFTP 幂等成为「远端 ack」判定的一环——already-gone 必须按成功（false）处理，否则 TOCTOU 会把可收敛动作计为失败。

**API 事实核查（javap 实测 sshj 0.39.0 jar，非臆造）**：
`net.schmizz.sshj.sftp.SFTPException` extends SSHException；`public Response$StatusCode getStatusCode()`；测试可用的构造器 `public SFTPException(Response$StatusCode, String)`；枚举常量 `Response$StatusCode.NO_SUCH_FILE` 存在。

### 2. 修复方案

**函数级改动：`SftpFileClient.deleteFile` 增加 SFTPException 专属分支**（置于全量 Exception 之前，CE 分支保持最前）：

```kotlin
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response

} catch (e: CancellationException) {
    throw e
} catch (e: SFTPException) {                     // ← NEW：先于全量 Exception
    currentCoroutineContext().ensureActive()      // CQ-001 语义保留：已取消则终结为 CE
    if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) {
        return@withContext false                  // 幂等：本来就不存在 → 与 SMB !exists→false 对齐
    }
    throw SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, SyncOperation.DELETE,
        path = remotePath, cause = e)
} catch (e: Exception) { ...原样... }
```

`e.statusCode` 为 Kotlin 对 `getStatusCode()` 的属性语法。不做 rm 前预检（stat/exists）——预检自身引入第二次 TOCTOU 且多一次 RTT；「尝试-后分类」是唯一原子判定。

**测试 seam（v2 更正：零生产可见性改动，撤销 v1 的字段 internal 化）**：v1 断言「SFTP 客户端目前无任何单测」**与源码不符**——`app/src/test/java/cn/vectory/syncplayer/sync/sftp/SftpTransportCancellationTest.kt` 已有真实 `SftpFileClient` 的 JVM 测试 harness：构造器注入缝 `sshClientFactory = { sshClient }`（SftpFileClient.kt:25-30，internal 构造器即测试缝）、`stubNetworkOk()` 网络 mock（:82-90）、`connectSuccessfully()`（:143-148，mock `newSFTPClient` 后走**真实 `connect()` 路径**建立 `sftpClient`/`remoteBasePath` 状态），且已有 `deleteFile` 的取消（:205-218）与 IOException→REMOTE_DELETE_FAILED（:313-322）用例。新用例直接复用该 harness：`connectSuccessfully()` 后 mock `sftpClient.rm(...)` 即驱动真实 `deleteFile`，`sftpClient`/`remoteBasePath` 字段（:44-45）**无需 internal 化**——少一处生产改动，测试同时覆盖真实 connect 状态机路径。

### 3. TDD 测试计划

测试落点（v2 更正）：用例**追加进既有 `app/src/test/java/cn/vectory/syncplayer/sync/sftp/SftpTransportCancellationTest.kt`**（同包、harness 现成），不再新建 `SftpFileClientDeleteIdempotencyTest`：

1. `deleteFile maps NO_SUCH_FILE to idempotent false`（**红**：现行全量 catch → REMOTE_DELETE_FAILED）
   - Arrange：`connectSuccessfully()`；`every { sftpClient.rm(any()) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "no such file")`（真实 sshj 0.39.0 构造器；`connectSuccessfully` 传 remotePath="/"，断言入参为 `/v.mp4` 的完整拼接可顺带锁定）。
   - Act：`runBlocking { client.deleteFile("v.mp4") }`。
   - Assert：返回 `false`；不抛 `SyncException`。
2. `deleteFile maps other SFTP errors to REMOTE_DELETE_FAILED`（回归锁：确保仅 NO_SUCH_FILE 判幂等）
   - Arrange：`SFTPException(Response.StatusCode.PERMISSION_DENIED, "...")`（真实枚举）。
   - Assert：`assertFailsWith<SyncException>` 且 `code == SyncErrorCode.REMOTE_DELETE_FAILED`、`operation == SyncOperation.DELETE`。
3. `deleteFile wraps SyncException input per existing catch-all contract`（回归锁，**v3 更正**：v2 曾断言「原实例透传」——该透传契约**不存在**：SftpFileClient.kt:298-304 的 catch-all 对一切非 CE 异常统一重包装，输入 `SyncException` 同样得到**新实例**，仅 code/operation 固定、`cause = e` 保留原异常。按既有包装契约断言，本计划不修改该行为）
   - Arrange：`every { sftpClient.rm(any()) } throws SyncException(SyncErrorCode.REMOTE_DELETE_FAILED, DELETE)`。
   - Assert：`assertFailsWith<SyncException>`；抛出实例**非**投入实例（重包装语义）且 `code == REMOTE_DELETE_FAILED`、`operation == DELETE`、`cause === 投入的原实例`。
4. `deleteFile success returns true`（行为锁定）
   - Arrange：`every { sftpClient.rm(any()) } just runs`。
   - Assert：返回 true。
5. `deleteFile requires connection`（既有行为锁定）
   - Arrange：新 `SftpFileClient(context, sshClientFactory)` 实例、未 connect。
   - Assert：`assertFailsWith<SyncException>` 且 `code == NOT_CONNECTED`。
6. `deleteFile rethrows direct CancellationException without wrapping`（变体补充：既有 :205-218 已覆盖「rm 抛普通异常+协程已取消→CE」形状，本用例补「rm 直接抛 CE」A 形状）
   - Arrange：`every { sftpClient.rm(any()) } throws CancellationException("cancelled by test")`。
   - Assert：`assertFailsWith<CancellationException>`（红线 3 客户端层锁定；CQ-001 顺序：CE 分支在 SFTPException 分支之前）。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.sftp.SftpTransportCancellationTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

期望：BUILD SUCCESSFUL。新增幂等用例与既有 SFTP 取消/分类用例全绿（connect 两种取消形状、downloadTo/ls/rm 取消、IOException 降级、NOT_CONNECTED 守卫——即评审要求的「既有 SFTP 取消测试定向验收」）。

### 5. 风险与守卫

- **结构化错误（红线 7）**：真失败仍为 `SyncException(REMOTE_DELETE_FAILED)`（message=null 形态不变）；幂等分支返回 false 而非抛「成功异常」——executor `:134-135` 契约（false=already gone 按成功）零改动。
- **CancellationException（红线 3）**：CE 分支保持最前；SFTPException 分支内先 `ensureActive()` 再分类（CQ-001 模式：取消与 SFTP 错误并发时取消优先）。
- **敏感信息（红线 6，v2 措辞更正）**：`path = remotePath` 维持既有结构化字段形态（`SyncException` 内部结构化数据，message=null）——**结构化 path ≠ 可安全入日志/UI**：本轮不新增任何日志语句，也不将 path 新映射进用户可见日志/UI（现状已有的 path 日志属既有缺口，见总表「安全」注②，不在本 BUG 范围）。
- **误放风险**：仅 `NO_SUCH_FILE` 判幂等；`PERMISSION_DENIED` 等其余 statusCode 与裸 Exception 一律维持 REMOTE_DELETE_FAILED——「删不掉」绝不能被吞成成功（否则远端泄漏成为 silent 垃圾）。
- sshj 升级兼容：statusCode 为稳定 RFC 常量（SSH_FX_NO_SUCH_FILE=2），非内部 API。

### 6. 涉及文件清单（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt`（deleteFile 增加 SFTPException 分支 + import；**无可见性改动**，撤销 v1 的两字段 internal 化）
- 测试：`app/src/test/java/cn/vectory/syncplayer/sync/sftp/SftpTransportCancellationTest.kt`（既有文件追加 6 用例，复用 `sshClientFactory` 注入缝 harness）

## BUG-010 <a id="bug-010"></a>

**初始 connect 不在重试白名单内——一次网络抖动使整批文件逐个失败**（todo.md #3 / 报告 0012 BUG-009；**编号以 todo.md 为准：本项=010**）

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt:187-191`** —— 首次建连在 3 次重试循环（`:209 while (attempts < 3 && !downloadSuccess)`）**之外**：

```kotlin
for (action in actionChannel) {
    var client = laneClient
    try {
        ...
        if (client == null) {
            client = getClient(source.protocol)
            client.connect(source, relation.remotePath)     // :189 ← 无重试：一抛即弃
            laneClient = client
        }
```

**`:375-399`** —— 外层 catch 直接计失败并丢弃 lane client：

```kotlin
} catch (e: Exception) {
    ...
    failedDownloadCount.incrementAndGet()                   // 逐 action 失败
    try { client?.disconnect() } ... catch ... {}
    laneClient = null                                       // 下个 action 重新建连（同样无重试）
}
```

对照：循环内**重试前重连**有 E5 白名单语义（`:211-218` disconnect+connect；`:326-329` 仅 `isRetryableSyncException(e)` 续试 + `delay(1000L * attempts)`）。

**根因**：AND-010 lane 复用改造把「E5 重试内重连」保留在 attempts 循环内，但 lane 首次建连位于循环外；白名单瞬态码（TIMEOUT/NETWORK_UNAVAILABLE/CONNECTION_REFUSED/UNKNOWN_HOST/…，`:564-571`）在 connect 阶段零重试。弱网首次建连抖动 → action#1 失败、client 丢弃 → 后续每个 action 重新 connect（连接已恢复但每 action 仍要赌一次首连）→ N 次失败而非恢复后 0 次。报告对照实验证明同一异常码在 `downloadTo` 内会被重试成功（不对称性实锤）。

### 2. 修复方案

**函数级改动：新增私有 helper 并替换 `:189` 裸 connect**（重试参数与既有单文件重试完全一致：3 次、`delay(1000L * attempt)`、白名单 `isRetryableSyncException`、终态码立即抛）：

```kotlin
/**
 * BUG-010：初始建连纳入 P1-2 白名单重试（与单文件下载重试同参）。
 * 失败的 connect 可能残留半建连状态，重试前按 E5 语义先 disconnect。
 * CE 不重试；终态码（AUTH/ACCESS/...）立即上抛由外层计一次失败。
 */
private suspend fun connectWithRetry(
    client: RemoteFileClient,
    source: cn.vectory.syncplayer.data.model.SyncSource,
    remotePath: String
) {
    var attempt = 0
    while (true) {
        attempt++
        try { client.connect(source, remotePath); return }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!isRetryableSyncException(e) || attempt >= 3) throw e
            try { client.disconnect() } catch (ce: CancellationException) { throw ce } catch (_: Exception) {}
            kotlinx.coroutines.delay(1000L * attempt)
        }
    }
}
```

调用点（`:187-191`）：

```kotlin
if (client == null) {
    client = getClient(source.protocol)
    connectWithRetry(client, source, relation.remotePath)   // ← 替换裸 connect
    laneClient = client
}
```

设计对齐说明：复用同一 client 实例 disconnect→connect 的重试形态，即既有 E5 循环内重连的既定语义（`SftpFileClient.connect` 对半建连状态的清理已由其 connect 外层 try/catch 处理）；不把 connect 挪进 attempts 循环（那会把「建连失败」与「下载失败」的计数与回滚耦合进 per-attempt 语义，改动面大且 `finalized` 等状态机变量无谓复杂化）。connect 重试预算与下载重试预算独立（一次建连失败耗 0 次下载 attempts），重试耗尽后仍由外层 catch 计**一次** failedDownloadCount 并丢弃 client——失败计数不放大。

### 3. TDD 测试计划

测试文件：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDownloadPipelineClientReuseTest.kt`（AND-010 lane 复用语义的既有归属文件；connect 行为属同一域）

1. `initial connect transient failure is retried within whitelist and action succeeds`
   - Arrange：1 个 download action；`clientFactory.create` returns client；`client.connect` 前 2 次抛 `SyncException(SyncErrorCode.NETWORK_UNAVAILABLE, SyncOperation.CONNECT)`、第 3 次成功（`coEvery ... answers` 计数）；`downloadTo` returns true；store 全链 mock 成功（finalize→true、verifyReadable 正常、metadata 正常）。
   - Act：`sut.executeSyncLocked(1L, listOf(action), emptyList(), ...)`。
   - Assert：返回 true；终态 progress `completedFiles == 1 && failedFiles == 0`；`coVerify(exactly = 3) { client.connect(source, any()) }`；`coVerify(exactly = 2) { client.disconnect() }`（两次重试前各一次清理）。
2. `initial connect terminal failure fails fast without retry`
   - Arrange：`client.connect` 抛 `SyncException(SyncErrorCode.AUTHENTICATION_FAILED, ...)`。
   - Assert：返回 false；`coVerify(exactly = 1) { client.connect(any(), any()) }`；`failedFiles == 1`。
3. `initial connect retries give up after 3 attempts and count one failure`
   - Arrange：`connect` 恒抛 NETWORK_UNAVAILABLE。
   - Assert：返回 false；`coVerify(exactly = 3) { client.connect(any(), any()) }`；`failedFiles == 1`（非 3——connect 重试内部消化）。
4. `cancellation during initial connect propagates without retry`
   - Arrange：`client.connect` 抛 `CancellationException()`。
   - Assert：`assertFailsWith<CancellationException>`；`coVerify(exactly = 1) { client.connect(any(), any()) }`。
5. 既有 lane 复用用例回归：全成功路径 client 创建次数上限仍为 lane 数（`SyncDownloadPipelineClientReuseTest` 既有断言，本修复不改变成功路径建连次数）。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineClientReuseTest' --tests 'cn.vectory.syncplayer.sync.SyncDownloadPipelineTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

期望：BUILD SUCCESSFUL。

### 5. 风险与守卫

- **CancellationException（红线 3）**：helper 的 CE 分支裸 rethrow，位于一切包装之前；测试 4 锁定。`disconnect` 清理的 CE 同样先传播（与 `:214-216` 既有形态一致）。
- **重试白名单（sync.md 契约）**：直接复用 `RETRYABLE_CODES`/`isRetryableSyncException`，不新增码、不扩白名单；终态码（AUTH/ACCESS/PATH_NOT_FOUND/SPACE/...）立即上抛语义不变。
- **不新增敏感日志**：重试日志沿用既有 `DebugLogger.w` 形态（仅类名+文件名），不打印 host/凭据。
- **失败计数不变式**：connect 三连败 = 1 个 failedDownloadCount（外层一次），终态 `completedFiles + failedFiles == totalFiles` 不变式保持。
- **与 BUG-007 的交互**：connect 重试不进入 attempts 循环，`finalized`/`uncleanFinalizedId` 状态机不受影响；两修复正交。

### 6. 涉及文件清单（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt`（+`connectWithRetry`、`:189` 调用点替换）
- 测试：`app/src/test/java/cn/vectory/syncplayer/sync/SyncDownloadPipelineClientReuseTest.kt`（+4 用例）

## BUG-018 <a id="bug-018"></a>

**服务尾部 pending race：start 请求在 `syncJob` 收尾窗口被合并丢弃，PENDING 任务滞留、UI 卡「同步中」**（报告 0114 BUG-018，Medium-Low。**v2 来源更正**：todo.md **无** BUG-018 条目、无「第 10 项」与 engine-only 约束的记载——v1 的「todo.md #10」为误引；本项来源 = **用户指定修复范围** + 报告 0114，本文不将其冒称为权威待办来源）

> 范围解释：按用户指定范围落在引擎/服务侧（不动 ViewModel/UI）。本方案在 `sync/` 模块内（`SyncService` + 新协调器 + `SyncEngine` 注释），ViewModel 的 `_isSyncing` 卡死症状因 B 最终被消费而**传导性消除**，无需改 UI 层。

### 1. 现状与根因

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:97-136`**：

```kotlin
suspend fun setPendingTask(task: PendingSyncTask) {
    storageOperationMutex.withLock {                       // :98 ← 与 drain 循环同锁
        pendingTaskManager.setPendingTaskLocked(task)
    }
}
internal suspend fun consumeAndExecute(relationId: Long): Pair<PendingSyncTask?, Boolean> =
    storageOperationMutex.withLock {                       // :116 ← 整个 drain 循环持锁
        while (true) {
            val task = pendingTaskManager.consumePendingTaskLocked(relationId) ?: break
            ...executeSyncLocked / cancelCleanup / finalize...
        }
        lastTask to lastSuccess
    }
```

KDoc `:110-112` 声称的 invariant——"A task queued while the service job is still active is consumed by this loop"——**为假**（报告 BHB 实验证明，主 Agent 源码复核锁序确认）。

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt:63-92`**：

```kotlin
val relationId = intent?.getLongExtra(EXTRA_RELATION_ID, 0L) ?: 0L
if (syncJob?.isActive == true) {                           // :65 ← 合并丢弃点
    return START_REDELIVER_INTENT
}
pendingStopJob?.cancel(); pendingStopJob = null
syncJob = serviceScope.launch {
    val (task, _) = withContext(Dispatchers.IO) { syncEngine.consumeAndExecute(relationId) }
    if (task == null) { scheduleStop(DELAY_AFTER_NO_TASK_MS); return@launch }
    val finalProgress = syncEngine.progress.value          // :85 ← 非挂起
    if (!finalProgress.isRunning) scheduleStop(DELAY_AFTER_SYNC_MS)
}
```

**`app/src/main/java/cn/vectory/syncplayer/sync/SyncPreviewViewModel.kt:158-171`**：确认侧先 `setPendingTask`（阻塞在锁上）→ `_isSyncing=true` → `SyncService.start(context, relationId)` → `observeSyncCompletion()` 等待该关系终态 phase。

**确切竞态交错**（协程 × 挂起点级，旧代码）：

| 时刻 | 协程 | 位置 | 观察/动作 |
|---|---|---|---|
| t0 | S_A = syncJob（Default） | `withContext(IO){consumeAndExecute(A)}` 内 `executeSyncLocked` 深处挂起点 | 持有 `storageOperationMutex` |
| t1 | V_B = viewModelScope（Main） | `SyncEngine.kt:98` `withLock` 入口挂起点 | 观察到「锁被占」，挂起在 `M.lock()`；**B 行尚未写入** |
| t2 | S_A | `SyncEngine.kt:120` `consumePendingTaskLocked(A)` | 返回 null（B 未写）→ `break` → `withLock` 尾部释放 M |
| t3 | S_A | `withContext` 返回（**最后一个挂起点**）→ `:85/:87` 两条非挂起语句 → Job Completing→Completed | 此窗口内 `syncJob?.isActive == true`（亚毫秒，调度压力下可放大） |
| t4 | V_B（被 M 释放唤醒） | Main：`setPendingTaskLocked(B)` 写 PENDING 行 → 返回 → `SyncService.start(B)` | `_isSyncing=true` |
| t5 | Main | `SyncService.kt:65` **非挂起读** `syncJob?.isActive` | 若落在 t3 窗口 → `return`（合并丢弃） |
| t6 | — | — | B 保持 PENDING，无消费者；S_A 已完成；`observeSyncCompletion` 永不满足 → `_isSyncing` 卡 true（直到任一下次触发自愈） |

**为何报告 Fix Direction A（drain 循环锁内二次检查）不可行——v2 收窄论证范围**：`setPendingTask` 与 drain 循环竞争同一把 M，而 M 被 drain **整个循环**持有 ⇒ V_B 的写行必然发生在 M 释放（=循环退出）之后；循环内/退出前锁内的任何「再查一次 pending 表」必然早于 B 的写入——该论证**只排除「在同一个持锁 drain 的锁内、退出前再查」这一类方案**（即 Fix Direction A 的字面形态与同构变体），**不构成对所有引擎侧方案的排除**（例如「锁释放后再查一次+条件重入」的检查-退出协议、或入队侧配合第二唤醒原语的方案，并非被锁序否定，只是各自引入新的等待窗口或新原语）。选 `Channel` 常驻消费者的理由是**工程性的**：改动面最小（一个新类 + Service 接线）、纯 JVM 可测（无 Robolectric/Hilt 基建）、不新增锁序/第二真相源——**不声称唯一封闭解**。

### 2. 修复方案：`Channel` 唤醒 + 常驻 drain 循环 + **单线程串行域**（新类 `SyncServiceDrainCoordinator`，v3）

**同步原语选型与排除理由**：

| 候选 | 判定 | 理由 |
|---|---|---|
| **`Channel<Long>(UNLIMITED)` + 单常驻 drainLoop**（承载唤醒/保序/缓冲） | ✅ 采用 | receive 是「等新请求」的原语；UNLIMITED 使 trySend 永不失败；FIFO 保序；取消点唯一 |
| **单线程串行域（confined dispatcher）**（承载状态正确性） | ✅ v3 采用（v2 曾以「onStartCommand 是系统 Main 回调无法被自定义 dispatcher 约束」排除——**理由错误**：不需要约束回调线程本身，只需协调器**全部可变状态与循环调度共用同一 confined dispatcher**；生产接线该 dispatcher 恰为 `Main.immediate`，与回调线程天然同域） | check-then-act 段（武装判定 / 停服校验）无挂起点 + 单线程 ⇒ 构造性无 TOCTOU；测试注入 TestDispatcher ⇒ 全矩阵确定性可驱动 |
| Mutex | ❌ | 无条件变量语义：只互斥、不提供「状态变化唤醒等待者」，仍需轮询或第二原语 |
| `StateFlow<Long>`（最新值） | ❌ | conflated：A、B 相继入队而循环未 receive 时只留 B，A 的 PENDING 行滞留——换形态重演本 BUG |
| `AtomicBoolean` needsRerun 标志 | ❌ | 置位可落在 Job 最后检查之后（lost-wakeup 窗口）；跨线程写 main-confined 变量的可见性竞争 |

**v3 修订（复审 #1 Critical / #2 Major / #5 Major）——v2 事件 actor 的四个缺陷**：

1. **停服 TOCTOU 未闭合（#1）**：actor（`serviceScope = Dispatchers.Default`，SyncService.kt:22）处理 `Stop(t)` 读 `events.isEmpty == true` 后调 `onEligibleStop → stopService(lastStartId)`——**检查在 actor 域、lastStartId 写在 Main**：Main 可在检查通过后、停服执行前更新 `lastStartId = B` 并入队 `Req(B)`，服务随后以 B 的 startId 停掉刚请求的 B。同理「A 收尾检查空队列 → B 入队 → A 武装」在跨线程下成立。单消费者≠生产者与消费者无交错。
2. **launch 注册顺序竞态（#1）**：`drainJob = scope.launch { drainLoop() }`——协程体可在**赋值前**开始执行（Main.immediate 下内联执行；Default 下快速完成 pass 的窗口），首次收尾的 `drainJob === myJob` 身份检查读到 null → 不武装 → **永久等待**。
3. **stale 到期事件吞掉最后一次武装机会（#2）**：到期事件入队污染请求队列——`[Req(B), Stop(t)]` 序：B 开始作废 t → B 收尾见队列非空（残留 Stop(t)）**不武装** → 下轮丢弃失配 Stop(t) → 队列空挂 receive——**再无任何路径武装停服，服务空闲后永不停止**。v2 的「两序均不停服」只证明了「不提前停」，未证明「最终会停」。
4. **「取消=放弃」语义与实现冲突（#5）**：v2 文字规定 ACTION_CANCEL/onTimeout 放弃全部已缓冲请求，实现却只 cancel job、不清缓冲、不作废代次——重启循环会把取消前缓冲的 A/B 重新执行（测试 #8 断言 [A, D] 更是与放弃语义直接矛盾）。

**v3 设计原则（逐条对应）**：

- **① 单一串行域**：协调器全部可变状态（`drainJob`/`armedToken`/`stopSeq`/请求缓冲）只在 confined dispatcher 的单线程上读写。生产：`Dispatchers.Main.immediate`（onStartCommand/onTimeout/onDestroy 本就在 Main——入口调用、循环调度、停服动作天然同线程同序）；`consumeAndExecute` 是唯一切出点（回调内 `withContext(Dispatchers.IO)`），切出期间 confined 线程继续处理入口调用，完成后恢复回 confined。测试：注入 runTest 的 TestDispatcher（单线程虚拟时间）。**新增正确性不再依赖任何「既有跨线程纪律」（复审 #1 的要求）——由 confinement 构造性保证**：无挂起点的 check-then-act 段内，同线程的其它工作（入队/到期/取消）物理上不可能插入。
- **② 到期事件不入请求队列**：`stopTimerFired(token)` 是 confined 线程上的**非挂起校验**，通过即停、不通过**原地丢弃**。**校验条件的角色分层（v3.1，附注 (b) 收敛措辞）**：**令牌匹配是主门**——`onSyncRequested` 在**入队时**同步作废令牌（复审 #2）后，「令牌有效 ⇒ 自该次武装以来无新请求」由主门独立承载；`drainJob?.isActive == true`（注册循环存活）与 `requests.isEmpty`（队列空）为**纵深防御**，不声称三条件各自独立必要（「令牌有效而其余条件失败」的到达态在协议内不可经公共 API 独立构造，测试映射见 §5）；且 `isActive == true` ≠ idle、`requests.isEmpty` **不能**证明「无已交付未恢复的请求」（trySend 可直接交付挂起中的 receiver——复审 #2，测试 #22）。「有待执行请求」与「控制信号」分离——stale 到期不污染请求队列 ⇒ pass 收尾的「队列空 ⇒ 武装」永不被 stale 事件卡住 ⇒ **空闲必最终武装、服务必停**（终局性论证前提见正确性论证 v3.1）。
- **③ 先注册后启动（v3.1 修正实现形态，复审 #1）**：Kotlin 先求值**整个** RHS 再赋值——`drainJob = scope.launch(start = CoroutineStart.LAZY) { drainLoop() }.also { it.start() }` 单表达式形式在 `.also` 执行 `start()` 时**字段尚未写入**；生产 Main.immediate（及测试 UnconfinedTestDispatcher）下 `start()` 会**内联执行**协程体（首 pass 快速完成路径），错误实现照样读到 null/旧值漏武装——v3 的该形态并未真正修复注册窗口。必须拆**三条独立语句**：`val job = scope.launch(start = CoroutineStart.LAZY) { drainLoop() }; drainJob = job; job.start()`——协程体首见 `drainJob === myJob` 即正确。
- **④ 取消即弃置（v3.1 按附注 (a) 收敛契约表述）**：弃置契约 =「取消后**不再完成**该请求（不再开始执行其 pass）」，**而非**「缓冲内请求必被排空」——trySend 可能把请求**交付给挂起中的 receiver**（continuation 已排队未运行），此时它已不在缓冲、排空够不着；该形态由 job cancel 兜住（恢复时收 CE、不执行 pass）。`cancelActiveDrain()` 三步原子不变：cancel job（覆盖已交付未恢复者）→ best-effort 排空仍滞留缓冲者 → 作废令牌 + `cancelStop`。漏网计时器（已过 delay 正在排队）经令牌校验丢弃。交付-恢复窗口以测试 #22 显式锁定。

**新文件 `app/src/main/java/cn/vectory/syncplayer/sync/SyncServiceDrainCoordinator.kt`**（无 Android 依赖，纯 JVM 可测）：

```kotlin
internal class SyncServiceDrainCoordinator(
    private val scope: CoroutineScope,          // confined：生产 Main.immediate / 测试 TestDispatcher
    private val consumeAndExecute: suspend (Long) -> Pair<PendingSyncTask?, Boolean>,  // 内部 withContext(IO)
    private val cancelStop: () -> Unit,                         // 取消已武装计时器
    private val armStop: (delayMs: Long, token: Long) -> Unit,  // 计时器到期 body 必须调 stopTimerFired(token)
    private val onEligibleStop: () -> Unit,     // 既有 stopService 顺序不变：stopForeground(STOP_FOREGROUND_REMOVE)
                                                // → stopSelfResult(lastStartId)（SyncService.kt:122-125；读 lastStartId
                                                // 也在 Main——与 onStartCommand 写点同一串行域，复审 #1）。
                                                // **生产前提（v3.1，复审 #7）：回调只调 stopService，不得重入协调器**
    private val onForcedStop: () -> Unit,       // v3.1（复审 #3）：用户取消延迟停服出口（cancelSync 接线）；
                                                // 同样只调 stopService、不重入
    private val stopDelayForPass: (hadTask: Boolean) -> Long?,  // !hadTask→NO_TASK；hadTask&&!isRunning→SYNC；否则 null
) {
    private val requests = Channel<Long>(Channel.UNLIMITED)   // 只承载工作（请求），不承载控制信号——原则 ②
    private var drainJob: Job? = null        // confined 读写（v2 的「Main 写/循环读」跨线程面消失）
    private var armedToken: Long? = null     // confined 读写
    private var armedForced = false          // v3.1：armedToken 的停服种类——false=ELIGIBLE（空闲停）/true=FORCED（取消停）
    private var stopSeq = 0L                 // confined 读写（ELIGIBLE/FORCED 共用单调令牌空间）

    // 注意：isActive == true ≠ idle（活跃可能是 DRAINING 中）——调用方不得据此判定「空闲」
    val isDraining: Boolean get() = drainJob?.isActive == true

    /** onStartCommand 非取消分支：confined 非挂起。v3.1（复审 #1/#2）：先作废令牌/取消计时器、
     *  再发送；LAZY 注册拆三条独立语句。 */
    fun onSyncRequested(relationId: Long) {
        armedToken = null    // v3.1（复审 #2）：入队**前**即撤销停服资格——覆盖「请求已交付挂起中的
        cancelStop()         // receiver、continuation 未运行」窗口（此刻 requests.isEmpty 为真但确有待执行
                             // 请求，旧到期不得凭三条件停服——测试 #22）；FORCED 令牌同在此作废（复审 #3）
        requests.trySend(relationId)
        if (drainJob?.isActive != true) {
            val job = scope.launch(start = CoroutineStart.LAZY) { drainLoop() }
            drainJob = job   // v3.1（复审 #1）：先注册——Kotlin 先求值整个 RHS 再赋值，`.also { it.start() }`
            job.start()      // 单表达式形式在 start() 执行时字段尚未写入；immediate/unconfined dispatcher 下
        }                    // start() 内联执行协程体，错误实现照样读 null 漏武装（原则 ③、测试 #16）
    }

    /** onTimeout / onDestroy / cancelActiveDrainAndArmForcedStop 内部：放弃语义 =
     *  「取消后不再完成任何先前请求」+ 撤停服资格（原则 ④ v3.1 表述）。 */
    fun cancelActiveDrain() {
        drainJob?.cancel(); drainJob = null
        while (requests.tryReceive().isSuccess) { /* best-effort 丢弃仍滞留缓冲的请求；已交付未恢复者由 job cancel 终结 */ }
        armedToken = null
        cancelStop()
    }

    /** v3.1（复审 #3）：用户取消 = cancelActiveDrain + 武装 FORCED 停服计时器（替代现行 cancelSync 的
     *  Default 域 delay+stopService 块）。与 ELIGIBLE 共用令牌空间/计时器槽位/失效协议：
     *  新请求到达（onSyncRequested 入口）即作废 FORCED 令牌——CANCEL→start(D) 后任何漏网取消回调均失配。 */
    fun cancelActiveDrainAndArmForcedStop(delayMs: Long) {
        cancelActiveDrain()
        val token = ++stopSeq
        armedToken = token
        armedForced = true
        armStop(delayMs, token)
    }

    /** 计时器到期：confined 非挂起校验（原则 ①②）。stale → 原地丢弃，不入请求队列。
     *  条件分层（v3.1，附注 (b)）：令牌匹配为主门；循环存活/队列空为纵深防御。 */
    fun stopTimerFired(token: Long) {
        if (armedToken != token) return        // 主门：令牌失配（含 ELIGIBLE/FORCED 互相顶替后的旧令牌）⇒ 丢弃
        if (armedForced) {
            if (drainJob?.isActive == true) return   // 防御纵深：取消后又有活跃 drain（正常序下其入口已作废令牌，此分支不可达）
            armedToken = null
            onForcedStop()                    // 用户取消延迟停服：stopForeground+stopSelfResult(lastStartId)
        } else {
            if (drainJob?.isActive == true && requests.isEmpty) {   // 纵深防御（角色见原则 ② v3.1 分层）
                armedToken = null
                onEligibleStop()              // 校验段无挂起点——检查与停服之间同线程无插入
            }
        }
    }

    private suspend fun drainLoop() {
        val myJob = coroutineContext[Job]!!
        while (true) {
            val relationId = requests.receive()             // 唯一等待点
            armedToken = null    // v3.1 定位调整：正常协议下 onSyncRequested 已在入队时作废令牌，
            cancelStop()         // 此处为**防御保留**（纵深——防未来协议回归）
            val (task, _) = consumeAndExecute(relationId)   // 唯一切出点（内部 withContext(IO)）
            // 收尾段：非挂起、confined 原子（复审 #1：「A 检查空→B 入队→A 武装」在此不可能——
            // B 的 trySend 只能发生在本段之前（isEmpty 见到它）或之后（Req 入口即作废令牌——v3.1））
            if (drainJob === myJob && requests.isEmpty) {
                stopDelayForPass(task != null)?.let { delayMs ->
                    val token = ++stopSeq
                    armedToken = token
                    armedForced = false
                    armStop(delayMs, token)
                }
            }
        }
    }
}
```

交错穷举（v3.1 协议；复审 #1/#2/#3 + 附注 (a)/(b) 的全部交错 + 终局性）：

| 交错 | 事件/时序（全部 confined 单线程全序） | 结果 |
|---|---|---|
| A 执行中 B 入队（核心竞态，#1） | `Req(A)` →（pass 中，切出 IO 期间）`Req(B)` 入缓冲 | A 收尾 `requests.isEmpty == false` → **不武装**（同线程判定，无插入窗口）→ 回 receive 取 B → **B 入队时（onSyncRequested 入口）已作废令牌+cancelStop**（v3.1；pass 开始处保留同款防御，此时 no-op）→ B 全程无停服资格 → B 收尾队列空才武装 |
| 武装后 B 到达（空 pass 500ms 变体） | `arm(t)` → `Req(B)` 先于/后于到期 | **先序**：`onSyncRequested(B)` **入口即**置 `armedToken=null` + cancelStop（v3.1——不等 pass 开始；**覆盖「B 已交付 receiver、continuation 未运行」窗口**：此刻 B 已离开缓冲、`requests.isEmpty==true`，但令牌已失配，旧到期不得凭三条件停服——测试 #22）→ t 到期（即使逃过 cancel）令牌失配 → 原地丢弃；**后序**：t 到期时队列非空（B 仍在缓冲未消费）→ 校验失败丢弃。**两序均不停服**；且 B 收尾后照常武装——终局性保持 |
| stale 到期吞武装机会（#2，v2 反例序） | `[Req(B), Stop(t)]`：B 入队即作废 t → B 收尾 | 收尾时**请求队列空**（Stop(t) 不入此队列）→ 正常武装 t2 → t2 到期 → 正常停服。v2 的「永不停止」反例在 v3 结构上不可构造 |
| 计时器武装、无新请求、到期 | `arm(t)` → t 到期回调 | 注册循环 idle-at-receive（isActive ✓）+ 令牌匹配 + 队列空 → 停服（合法语义，**恰好一次**） |
| 检查-停服间隙注入 B（#1 门控残余；v3.1 按复审 #7 重写） | `stopTimerFired(t)` 校验段（非挂起）内 | confined 下无插入点。**生产前提（成文）**：`onEligibleStop`/`onForcedStop` 回调只调 `stopService`、**不得重入协调器**（接线 KDoc 明示，测试 #13 锁定回调形态）；校验通过后停服决定不被推翻。真实 stop/start 系统竞态（服务销毁与重建/重放）的恢复语义**不在本轮 JVM 测试与保证范围**——`START_REDELIVER_INTENT` 不构成主动 `stopSelfResult` 后必重放/必重启的通用保证，**本计划不声称自愈**（原 v3 的「重放最后 intent 自愈、良性残余」表述删除）；如需验证应另立生命周期级测试 |
| 用户取消后新 start（#3，v3.1 新增） | `cancelActiveDrainAndArmForcedStop(取消延迟)` 武装 FORCED t_f → 延迟窗口内 `onSyncRequested(D)` | D 入口即作废 t_f + cancelStop → 漏网 t_f 回调（逃过 cancelStop、已过 delay）令牌失配丢弃（另有 drainJob 活跃防御纵深）→ D 正常 drain、收尾武装 ELIGIBLE 停服——**旧取消路径不再能越过新 start 移除前台/停服**（现行 SyncService.kt:131-138 的 Default 域绕行缺陷已消除）。测试 #20/#21 |
| 快速完成的首次 pass（#1 注册序） | `onSyncRequested(A)` → LAZY **创建** → **字段赋值** → `start()` | 协程体在 start() 调用内联执行（生产 Main.immediate / 测试 UnconfinedTestDispatcher）时 `drainJob` 已赋值 → 身份检查通过 → 正常武装。v3 的 `launch(LAZY){...}.also{ it.start() }` 单表达式形式因 RHS 求值序**仍先 start 后完成赋值**——错误实现照旧漏武装（测试 #16 红；**换 UnconfinedTestDispatcher 后方可捕获**——StandardTestDispatcher 下协程体延迟到 runCurrent 才执行，错误实现假绿，复审 #1/#6） |

**`SyncService` 接线（v3.1 全量重写，复审 #3：v3 仅以 cancelActiveDrain() 替换 `syncJob?.cancel(); syncJob = null` 两行，未触及既有 `cancelSync` 后半段——`:131-138` 的 `serviceScope`（**Default 域**）`launch { 通知; delay(DELAY_AFTER_CANCEL_MS); stopService(stopStartId) }` 原样残留：无失效协议、绕过 confined 校验；用户取消后 start(D)，该取消协程越过 delay 可取消点（delay 已到期、收尾段已在执行）时仍执行 `stopService` 的第一句 `stopForeground(STOP_FOREGROUND_REMOVE)`（:122-125 无条件）——**D 的前台状态被旧取消路径移除**。v3.1 把用户取消延迟停服整体并入 confined 域与统一失效协议）**。生命周期语义保留：startForeground / lastStartId / START_REDELIVER_INTENT 返回值不变；`scheduleStop` 吸收进 `armStop` 闭包后移除；`pendingStopJob` 改由 `armStop`/`cancelStop` 闭包管理；**`cancelSync` 的 Default 域延迟停服块删除**（被 `cancelActiveDrainAndArmForcedStop` 取代）：

```kotlin
// onCreate:
coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)   // confined 专用 scope
// （serviceScope 保留 Dispatchers.Default 给既有非协调器协程——进度采集等；协调器状态一律不落 serviceScope）
drainCoordinator = SyncServiceDrainCoordinator(
    scope = coordinatorScope,
    consumeAndExecute = { relId -> withContext(Dispatchers.IO) { syncEngine.consumeAndExecute(relId) } },
    cancelStop = { pendingStopJob?.cancel(); pendingStopJob = null },   // pendingStopJob 读写均在 Main（confined 同域）
    armStop = { delayMs, token ->
        pendingStopJob?.cancel()
        pendingStopJob = coordinatorScope.launch {          // confined：到期回调与协调器同线程
            delay(delayMs)
            drainCoordinator.stopTimerFired(token)          // 到期不直接停：非挂起校验（令牌主门+纵深，同线程）
        }
    },
    // 两类停服出口同构：Main 域内读 lastStartId（与 onStartCommand 写点同一串行域）。
    // KDoc 成文前提（复审 #7）：回调只调 stopService，不得重入协调器（测试 #13 锁定形态）。
    onEligibleStop = { stopService(lastStartId) },          // 空闲停（NO_TASK/SYNC 延迟后）
    onForcedStop = { stopService(lastStartId) },            // v3.1：用户取消延迟停（取消时无新 start ⇒ lastStartId 未变，
                                                            // 与原 stopStartId 语义等价；有新 start 则令牌已被作废、不会走到这）
    stopDelayForPass = { hadTask ->                          // 与现行 :80-88 三分支逐条等价
        when {
            !hadTask -> DELAY_AFTER_NO_TASK_MS
            !syncEngine.progress.value.isRunning -> DELAY_AFTER_SYNC_MS
            else -> null
        }
    },
)
// onStartCommand（ACTION_CANCEL 分支之外、return 之前）:
drainCoordinator.onSyncRequested(relationId)   // 替换 :65-89 整段（Main 回调内直接调用——非挂起、即返；
                                               // 原 :70-71 的 pendingStopJob 手工取消由 onSyncRequested→cancelStop 承接）
return START_REDELIVER_INTENT

// cancelSync —— v3.1 全量替换 :127-139（不再有 serviceScope/Default 域延迟停服路径）:
private fun cancelSync() {
    // 立即写 CANCELLED 通知（Main 线程；按 BUG-008 方案用 syncEngine.progress.value.copy(phase=CANCELLED) 保计数）
    SyncNotificationHelper.updateNotification(this, syncEngine.progress.value.copy(phase = SyncPhase.CANCELLED))
    drainCoordinator.cancelActiveDrainAndArmForcedStop(DELAY_AFTER_CANCEL_MS)
    // 弃当前 pass + 弃先前请求（「不再完成」契约）+ 撤 ELIGIBLE 资格 + 武装 FORCED t_f；
    // 延迟窗口内任何新 start（onSyncRequested 入口）即作废 t_f——旧取消回调任何时序都不能移除新 start 的前台
}

// onTimeout —— API 35 预算到期须立即停（既有语义，无延迟）:
override fun onTimeout(startId: Int) {
    SyncNotificationHelper.updateNotification(this, syncEngine.progress.value.copy(phase = SyncPhase.CANCELLED))
    drainCoordinator.cancelActiveDrain()       // 弃置语义（无 FORCED 武装——马上就停）
    stopService(startId)                       // 立即停本 start 请求
}

// onDestroy:
coordinatorScope.cancel()                      // 连带常驻循环与计时器
collectorJob?.cancel(); serviceScope.cancel()  // 既有清理保留
// （syncJob 字段删除——协调器持有 drain 状态；pendingStopJob 仅剩 armStop/cancelStop 闭包读写）
```

**正确性论证（v3；v3.1 补前提与契约表述）**：

- **TOCTOU 消除（复审 #1）**：全部 check-then-act 段无挂起点——`stopTimerFired` 校验（读状态 → 决定停）与 `drainLoop` 收尾段（isEmpty → arm）都在 confined 单线程上原子执行；入队（trySend）、到期回调、取消、`lastStartId` 写点全部在同一物理线程上以全序发生。生产= Main（onStartCommand 回调与 Main.immediate 协程同线程）；测试= 单线程 TestDispatcher。不存在「检查在串行域 A、动作依赖串行域 B 的数据」的形态——`lastStartId` 的读写也同在 Main。**v3.1 补（复审 #2/附注 (a)）**：单线程全序不排除「trySend 已交付 receiver、continuation 未运行」的调度阶段——此刻 `requests.isEmpty==true` 但确有待执行请求，故停服资格的撤销点在**入队时**（onSyncRequested 先作废令牌再发送），不依赖「队列为空 ⇒ 无待执行请求」的反证。
- **终局性：空闲必停（复审 #2；v3.1 补论证前提）**：**前提①**：每个 pass 正常完成——consumeAndExecute 抛非 CE 异常 ⇒ 循环死亡、无人再武装（残余②如实列出，不纳入本声明）；**前提②**：该收尾路径 `stopDelayForPass` 返回非 null——返回 null（hadTask 且引擎仍 isRunning）是既有语义（现行 :85-88 同样不排停）：不武装、服务存续由引擎运行状态接管、系统超时兜底；v3 的「每个 pass 收尾要么见队列非空要么武装」措辞**忽略了 null 分支与异常终结**，v3.1 更正。在两前提下：请求队列只承载工作 ⇒ pass 收尾要么见队列非空（继续消费——无新输入时队列严格递减，有限必耗尽）要么武装。武装的计时器仅三种归宿：① 到期且校验通过 → 停服；② 到期且令牌失配（其间有新请求）→ 丢弃，而该新请求的收尾必再武装（或其 pass 异常终结→前提①外）；③ 被 cancelStop 显式取消（同一交互里必有新请求或服务正在销毁）。不存在「丢弃后无人再武装」的协议内路径——v2 反例的根源（控制事件污染请求队列）已结构性移除。
- **消费保证（收窄，复审 #4/#5 一致化；v3.1 弃置契约按附注 (a) 重述）**：**未被取消/超时/销毁的前提下**每个 start 请求恰获一次 pass（FIFO + UNLIMITED + LAZY 注册）；取消/超时 = **显式弃置**，契约为「**取消后不再完成该请求**」——仍滞留缓冲者被 cancelActiveDrain 排空，**已交付 receiver、continuation 未运行者由 job cancel 终结**（恢复即 CE、不执行 pass）；不承诺也不需要「以排空 channel 实现弃置」（「先发送者必仍在缓冲」不成立）。实现、§3 矩阵、测试 #8/#11/#22、KDoc 一致。被弃置请求的行持久（PENDING），恢复路径 = 该关系下次 start；`consumeAndExecute` 按关系消费的限制不变（另一关系的 start 不重试它）。
- **用户取消延迟停服的失效协议（v3.1 新增，复审 #3）**：FORCED 与 ELIGIBLE 停服共用同一令牌空间、计时器槽位与失效协议——新请求入口（onSyncRequested）同步作废两类令牌并 cancelStop；FORCED 到期另有 `drainJob` 活跃防御纵深。CANCEL→start(D) 后，旧取消回调**任何时序**（含逃过 cancelStop 的漏网到期）均不能停掉 D 的 drain 或移除其前台状态——现行 Default 域绕行路径（SyncService.kt:131-138）已整体删除。测试 #20/#21 锁定。
- **并存边界（不 join 的 cancelActiveDrain）**：旧循环被 cancel 后：挂起在 `receive` → 直接 CE；卡在 `consumeAndExecute` 内部（含 NonCancellable 段）→ 恢复后经内部挂起点 CE；唯一例外 = 取消落在「consumeAndExecute 返回后、收尾段（非挂起）」之前——旧循环仍执行收尾段，但 `drainJob === myJob` 身份检查拒绝武装（drainJob 已 null/换新；**字段读写均 confined，v2 的跨线程可见性残余消失**）→ 下一挂起点（receive）收 CE 终结（测试 #12 确定性驱动此路径）。
- **重复请求幂等**：第二 pass `consumePendingTaskLocked` 空 consume → null → NO_TASK 延迟，与现行语义一致。

**`SyncEngine.kt:110-112` KDoc 更正**（注释即协议，防后人重蹈）：改为描述真实协议——"Enqueues serialized by [storageOperationMutex] land after this drain releases it; they are consumed by the caller's *next* pass. [SyncService]'s drain coordinator guarantees a follow-up pass while the service lives: every start request is buffered (UNLIMITED channel, single confined-thread protocol) and discarded **only** on user cancel / system timeout / destroy, after which the relation recovers by being started again."

### 3. 取消时机矩阵（v3：弃置语义与实现/测试/KDoc 四处一致）

取消源：① `onTimeout`（系统）② `ACTION_CANCEL`（用户）③ `onDestroy`（服务销毁）。v3.1：① 经 cancelActiveDrain() + **立即** stopService(startId)（预算到期无延迟语义）；② 经 cancelSync → `cancelActiveDrainAndArmForcedStop(DELAY_AFTER_CANCEL_MS)`（内部先 cancelActiveDrain 三步原子，再武装 FORCED 停服计时器——用户取消的延迟停服**同样落在 confined 域并接受失效协议**）；③ 经 `coordinatorScope.cancel()`（连带循环、缓冲对象随作用域终结）。

**弃置语义（v3 实现化——复审 #5；v3.1 按附注 (a) 重述）**：弃置契约 =「**取消后不再完成任何先前请求**（不再开始执行其 pass）」——仍滞留缓冲者由 `cancelActiveDrain` 的排空移除（实现行为，非仅文档约定：取消后再 start 的新循环只执行新请求，测试 #8 断言旧缓冲 B 不出现）；**已交付 receiver、continuation 未运行者不在缓冲**（trySend 可直接把请求交付挂起中的 receiver 并排队其 continuation——「先发送的一定还在缓冲随后被排空」不成立），由 job cancel 兜住：该 continuation 恢复时收 CE、pass 不执行（测试 #22 锁定交付-恢复窗口）。被弃置请求的 DB 行持留（PENDING 原样；RUNNING 由 `cancelCleanup` 标记删除或该关系下次 pass 的 `resetRunningToPending` 复位，SyncPendingTaskManager.kt:65）；**恢复路径 = 该关系下次被 start**。不建自动重唤醒（用户取消后自动重排等于取消无效；超时场景必须尽快停）。

**关键限制（与 §2 消费保证一致）**：`consumeAndExecute(relationId)` 按**关系**消费——被弃置的关系 B 不会被「start 另一关系 C」重试；`resetRunningToPending` 仅在 B 自己再次被消费时生效。

| 取消落点 | drainLoop 所处 | CancellationException 传播路径 | 数据/状态结果 |
|---|---|---|---|
| start 未发出 | — | — | 无 pending 行，无循环 |
| `receive()` 挂起中 | WAITING | receive 抛 CE → loop 终结；缓冲已被 `cancelActiveDrain` 排空（若为销毁路径则随 scope 终结） | 无已缓冲请求滞留内存（已弃置）；行持久，靠各自下次 start 恢复 |
| **receive 交付与取消并发**（trySend 与 cancelActiveDrain 竞争） | WAITING↔DRAINING 边界 | confined 单线程下坍缩为**全序**：(a) `onSyncRequested(E)` 先于 `cancelActiveDrain` 且 E 仍留缓冲 → 被排空弃置；(b) 同 (a) 时序但 **E 已交付挂起中的 receiver、continuation 已排队未运行**（v3.1 显式补——附注 (a)：E 已不在缓冲，排空够不着）→ job cancel 使该 continuation 恢复时收 CE，E 同样不被完成；(c) `cancelActiveDrain` 先 → E 入新循环（或触发新循环启动） | 三序均「取消后不再完成该请求；行持久、可恢复」；测试 #11 锁 (a)/(c)、**#22 锁 (b)**（交付-恢复窗口的显式用例） |
| **取出请求后、获 M 前 / 等锁中**（withContext(IO) 未进锁或阻塞在 `storageOperationMutex`） | DRAINING 入口 | withContext 抛 CE → loop 终结 | 该请求被弃置（channel 元素已消费、pass 未执行；缓冲排空不影响它——已取出）；行保持 PENDING（或 RUNNING→下次 reset）；仅该关系下次 start 重试（上方关键限制）。测试 #19 |
| `executeSyncLocked` 下载/删除中 | DRAINING | lane CE → workerScope.cancel → coroutineScope rethrow → pipeline catch CE（终态 CANCELLED progress，BUG-008 修复后保计数）→ rethrow → `consumeAndExecute` catch CE → `cancelCleanup`（NonCancellable：markCancelled+deleteByRelationId）→ rethrow → loop 终结 | 通知 CANCELLED@k/N；pending 行标记 CANCELLED 并删除（用户取消=放弃计划，dryRun 重推可重建） |
| `executeSyncLocked` 返回后、`finalize` 中（`SyncEngine.kt:131`，try 之外） | DRAINING 尾 | DAO 挂起点 CE → 上抛出 consumeAndExecute（**不经 cancelCleanup**）→ loop 终结 | 行滞留 RUNNING → 下次 `resetRunningToPending` 复位重执行（报告 INCONCLUSIVE-2 相邻面；现状保留，矩阵明示） |
| **用户取消后的延迟停止窗口 start(D)**（复审 #3；v3.1 全量接线后重写） | 已取消 → 新循环 | `cancelSync` → **立即**写 CANCELLED 通知（Main）→ `cancelActiveDrainAndArmForcedStop(DELAY_AFTER_CANCEL_MS)`（弃 A/B + 武装 FORCED t_f，全在 confined 域）→ 延迟窗口内 `onSyncRequested(D)` → **入口即作废 t_f** + cancelStop → D 由新循环消费、收尾武装 ELIGIBLE 停服；t_f 漏网回调（逃过 cancelStop）令牌失配丢弃 | consumeCalls == [A(未完), D]——缓冲 B 不再执行；B 行持久，B 下次 start 恢复。测试 #8 锁定弃置；**旧取消延迟停服不再能越过新 start 移除前台/停掉新 drain**（现行 SyncService.kt:131-138 的 Default 域绕行缺陷——取消协程越过 delay 可取消点仍执行 stopForeground——已被整体删除，测试 #20/#21 在协调器层锁定失效协议） |
| **旧清理未结束即重启**（cancelActiveDrain 不 join；新循环已启动，旧循环仍在 consumeAndExecute 内） | 旧 DRAINING + 新 WAITING/DRAINING 并存 | 旧循环于下一挂起点收 CE 终结（引擎 M 串行化使新旧 pass 不并发执行）；若取消落在「consumeAndExecute 返回后、收尾段」之前——旧循环仍执行非挂起收尾段，`drainJob === myJob` 身份检查（confined 读写）拒绝武装 → 下个 receive 收 CE | 旧循环不得武装（测试 #12 确定性驱动：NonCancellable 段放行后正常返回，收尾段身份检查拒武装）；`cancelActiveDrain` 的排空+作废令牌+cancelStop 三步在 confined 域内原子 |
| pass 收尾段（isEmpty 判定 / armStop，均非挂起） | 不可中断交付 | — | 收尾段完整执行后回 receive；被替换/置空的循环在此段武装被身份检查拒绝 |
| `armStop` 计时器 delay 中（循环 WAITING） | pendingStopJob 与 drainLoop 独立（同 confined scope） | 到期 → `stopTimerFired(token)` → **非挂起校验**（令牌匹配主门 + 循环存活/队列空纵深——v3.1 分层措辞） | 期间若有 Req：令牌已在**入队时**作废 + 计时器已 cancelStop，逾期回调失配丢弃（**不停服**）；即便 cancelStop 漏网（已过 delay），校验段无挂起点——检查与停服之间无插入。通过才 `stopService` → onDestroy → coordinatorScope.cancel → receive CE。测试 #14/#15/#17/#22 |
| 服务已停 | — | — | DESTROYED；最后 intent 重放；consumeAndExecute 幂等（空 consume→null） |

lane 内单文件 CE（已捕获计失败）与 job 级 CE（穿透终结循环）的分界由 pipeline 既有 catch 结构承担，本修复不改变。

### 4. TDD 测试矩阵（v3：确定性逐步时序 + 可执行 timer fake + 拆分交错）

新文件 `app/src/test/java/cn/vectory/syncplayer/sync/SyncServiceDrainCoordinatorTest.kt`（纯 JVM；`runTest` 的 scheduler 注入协调器 scope——`backgroundScope + StandardTestDispatcher(scheduler)`）。

**Harness 规则（复审 #6 全部落实；v3.1 增补）**：

- **不用 `advanceUntilIdle` 驱动关键时序**——它遇「仅剩 background 常驻工作」即停止推进，不保证协调器开始/完成 pass 或推动计时器。一律 `runCurrent()`（执行已就绪的 confined 任务）+ `advanceTimeBy(ms)` 后**必接 `runCurrent()`**（让 delay 到期回调真正执行）。每个用例的时序断言前，被测对象必须处于显式确认的状态（gate 完成），禁止「推进时间后盲断言」。
- **Dispatcher 例外（v3.1，复审 #1/#6）**：默认协调器 scope = `backgroundScope + StandardTestDispatcher(scheduler)`；**#16 专用** `backgroundScope + UnconfinedTestDispatcher(scheduler)`——`job.start()` 内联执行协程体，正是被测注册序的暴露条件（StandardTestDispatcher 下协程体延迟到 runCurrent() 才运行，错误实现照样通过 = 假绿）。
- **漏网到期注入（v3.1）**：「逃过 cancelStop 的到期」一律以**直接调 `coordinator.stopTimerFired(token)`** 模拟（#14/#15/#20/#22）；#17 的 detach 模式是同语义的另一形态（走真实 delay 回调路径，并断言回调**确实触发**）。
- **Mutation check 纪律（v3.1，复审 #6 防假绿）**：关键红测试（#16 注册序、#22 交付窗口）实现时必须**人工回退对应修复**（`drainJob = launch(LAZY){...}.also { it.start() }` 单表达式 / 令牌作废挪回 pass 开始处）验证用例变红——否则 harness 无鉴别力，红标记不可信。
- **门控 fake consume**：每个请求 X 配 `enteredGate#X`（consume 入口 `complete`）与 `releaseGate#X`（consume 内 `await`）及 `completedGate#X`（返回前 `complete`）。步骤模板：`onSyncRequested(X)` → `runCurrent()` → **断言 enteredGate#X 已完成**（未确认进入不得推进时间——#9/#10 的防假绿要点）→ 操作 → `runCurrent()` → 断言。例外：#22 刻意**不** runCurrent——制造「已交付 receiver、continuation 已排队未运行」窗口即被测状态。
- **可执行 timer fake（`FakeStopScheduler`，v3.1 重设计——复审 #6：原 `leakingCancel` 下 `arm(t2)` 的 `job?.cancel()` 仍会取消 t1，声称的两个回调实际只有一个可触发=断言空洞）**——「delay→`stopTimerFired(token)`」传动不再是口头约定，且**脱离句柄的旧计时器独立存活**：

```kotlin
class FakeStopScheduler(private val scope: CoroutineScope, private val onFired: (Long) -> Unit) {
    var job: Job? = null; var armCount = 0; var cancelCount = 0; var lastDelayMs = -1L
    val firedTokens = mutableListOf<Long>()          // v3.1：记录实际到期回报（#17 断言 t1 确实触发——非空洞）
    /** #17 专用：true 时 cancel 与新 arm 均不再取消旧 job——模拟「已过 delay、到期分发已排队、
     *  可取消句柄追不上」的漏网计时器（旧计时器脱离句柄独立存活、其回调仍会回报）。 */
    var detachOnCancel = false
    fun arm(delayMs: Long, token: Long) {                    // 绑定为 armStop 回调
        armCount++; lastDelayMs = delayMs
        if (!detachOnCancel) job?.cancel()
        job = scope.launch { delay(delayMs); firedTokens.add(token); onFired(token) }
    }
    fun cancel() {                                           // 绑定为 cancelStop 回调
        cancelCount++
        if (!detachOnCancel) job?.cancel()
        job = null
    }
}
```

- **收尾策略**：协调器 scope 用 `runTest` 的 `backgroundScope`（测试结束自动取消，常驻 receive 循环不阻塞收尾）；每例末尾显式 `coordinator.cancelActiveDrain()` 双保险。调用/回调记录表：`consumeCalls`/`armCount`/`cancelCount`/`eligibleStopCalls`/`forcedStopCalls`（v3.1 新增 FORCED 出口记录）/`firedTokens`。

| # | 用例 | 交错分支 | 步骤（时间驱动均为 advanceTimeBy+runCurrent） | Assert |
|---|---|---|---|---|
| 1 | `request during active drain is consumed by same loop`（**红**：旧代码丢 B——核心回归） | t1–t3 | `onSyncRequested(A)`; runCurrent → entered#A ✓; `onSyncRequested(B)`; 释放 A; runCurrent → completed#A ✓; runCurrent → entered#B ✓; 释放 B; runCurrent | consumeCalls == [A, B] |
| 2 | `request in job tail window is not lost` | t3–t5 | fake consume(A) 返回前在 finally 内**同步重入** `onSyncRequested(B)`；`onSyncRequested(A)`; runCurrent ×2 | consumeCalls == [A, B]；A 收尾时队列非空 → **armCount == 0 直至 B 完成** |
| 3 | `idle coordinator starts loop on first request` | 冷启动 | `onSyncRequested(A)`; runCurrent → entered/completed | consumeCalls == [A] |
| 4 | `duplicate requests run idempotent extra pass` | 同关系重复 | `onSyncRequested(1)`×2; 逐步 runCurrent | consumeCalls == [1, 1]；第二次空 pass 后以 NO_TASK 延迟 armCount == 1 |
| 5 | `FIFO order preserved across relations` | 多关系 | gate 阻塞；依次 enqueue 1,2,3; runCurrent → entered#1; 释放1 → 依次确认/释放 | consumeCalls == [1, 2, 3] |
| 6 | `cancellation while draining ends loop and allows restart` | 取消于 DRAINING | consume(A) = `awaitCancellation()`（entered#A 后挂起）; `onSyncRequested(A)`; runCurrent → entered ✓; `cancelActiveDrain()`; runCurrent; `onSyncRequested(D)`; runCurrent → entered/completed#D | `isDraining` true→false→true；consumeCalls == [A(未完), D]；cancelCount ≥ 1（计时器连带撤销） |
| 7 | `cancellation while waiting keeps nothing buffered` | 取消于 WAITING | `onSyncRequested(A)`; runCurrent → completed ✓（循环挂 receive）; `cancelActiveDrain()`; runCurrent; `onSyncRequested(E)`; runCurrent | consumeCalls == [A, E]（E 由新循环消费；取消时缓冲本已空——排空为 no-op） |
| 8 | `cancelled drain discards buffered requests; restart runs only new relation`（**v3 重写**，复审 #5：v2 断言 [A, D] 含被取消前缓冲的 A/B——与放弃语义矛盾） | 取消排空 | `onSyncRequested(A)`; runCurrent → entered#A; `onSyncRequested(B)`（缓冲）; `cancelActiveDrain()`（取消 A + 排空 B）; `onSyncRequested(D)`; runCurrent → entered#D; 释放 gate#A（旧循环 NonCancellable 段恢复→CE 终结）; runCurrent; advanceTimeBy(2000); runCurrent | consumeCalls == [A(未完), D]——**B 不出现**；armCount == 1（仅 D 收尾）且 eligibleStopCalls == 1（D 后正常停服） |
| 9 | `stop is not armed while buffered request runs beyond stop delay`（**红**：v2/v1 在此交错武装并停服——复审 #1 第一交错） | A 中 B 入队 → B 超延迟运行 | `onSyncRequested(A)`; runCurrent → entered#A ✓; `onSyncRequested(B)`; 释放 A; runCurrent → **entered#B ✓ 且 completed#B 未完成**（确认 B 正在跑）; advanceTimeBy(3000); runCurrent; 断言; 释放 B; runCurrent; advanceTimeBy(1500); runCurrent | B 运行期间 armCount == 0 且 eligibleStopCalls == 0；B 完成后 armCount == 1；到期后 eligibleStopCalls == 1（**恰好一次正常停服**） |
| 10 | `empty pass followed by buffered request never stops mid-pass`（**红**——复审 #1 第二交错；**v3.1 修正断言基线**，复审 #6） | 空 pass 500ms 计时器 | consume(A) 返回 null（空 pass）; `onSyncRequested(A)`; runCurrent → completed ✓（**已 arm NO_TASK——armCount 此刻已为 1，绝对零断言不成立**，记录 `baseline = armCount`）; `onSyncRequested(B)`; runCurrent → entered#B ✓（Req 入口已作废令牌+cancel——v3.1）; advanceTimeBy(2000)（t1=NO_TASK 到期回报：令牌失配→丢弃）; runCurrent; 释放 B; runCurrent; 推进 t2 到期 | **相对增量断言**：B 运行期间 `armCount == baseline` 且 `eligibleStopCalls == 0`；B 完成后 `armCount == baseline + 1`；最终 `eligibleStopCalls == 1`（恰一次正常停服） |
| 11 | `request race with cancellation is deterministic in both orders`（**v3 拆两序**，复审 #6：v2 #11 为顺序调用、非交付窗口验证） | WAITING↔DRAINING 边界坍缩为两全序 | (a) `onSyncRequested(E)`; `cancelActiveDrain()`（E 已入缓冲→被排空）; `onSyncRequested(E)`; runCurrent → completed ✓。(b) `cancelActiveDrain()`; `onSyncRequested(E)`; runCurrent | (a) E 恰获**一次完整 pass**（首次入队被弃、重试成功），终态 completed；(b) E 由新循环消费恰一次——两序分别断言，无不可控窗口 |
| 12 | `zombie old drain cannot arm after restart`（**v3 重设计**，复审 #6：v2 让旧 consume 放行后抛 CE，根本到不了收尾身份检查） | 旧 DRAINING 收尾段 + 新循环并存 | consume(A) = `withContext(NonCancellable) { entered#A.complete(Unit); gate#A.await() }; return null to false-Shape`（放行后**正常返回**→旧循环继续执行非挂起收尾段）; `onSyncRequested(A)`; runCurrent → entered#A; `cancelActiveDrain()`; `onSyncRequested(D)`; 推进至 D 完成+arm+到期+停（记录 armCount==1）; 释放 gate#A; runCurrent | 旧循环收尾段执行但 `drainJob === myJob` 失败 → **armCount 仍 == 1**（不增）；eligibleStopCalls 仍 == 1；旧循环随后于 receive 收 CE 终结 |
| 13 | `eligible stop executes exactly once and callbacks never reenter coordinator`（**v3.1 重定义**——复审 #6/#7：原「onEligibleStop 内重入 onSyncRequested(B)」注入在 runCurrent 下会让 B 已排队的 continuation 完整执行、原断言不可满足；且测试只重入 coordinator.onSyncRequested(B)，无真实 Intent/startId 更新与系统重放，证明不了「最后 intent=B 自愈」——**START_REDELIVER_INTENT 亦非主动 stopSelfResult 后必重启重放的通用保证**，「良性且保证自愈」主张整段删除**） | 停服决定点 | onEligibleStop = { eligibleStopCalls++ }——**与生产形态同构：只调 stopService、不含任何重入协调器的代码**（生产前提成文：协调器回调不得重入，写入类 KDoc 与接线注释）; `onSyncRequested(A)`; runCurrent → completed; advanceTimeBy(1500); runCurrent（正常停服恰一次）; 随后连调 `stopTimerFired(旧token)`×2（stale 重报） | `eligibleStopCalls == 1`（恰一次；stale 重报全丢弃）；停服后 `armedToken == null`。**边界声明**：真实 stop/start 系统竞态（销毁-重建-重放）的恢复语义不在本轮 JVM 测试范围——无真实 Intent/startId 流与系统重放可模拟，如需验证应另立生命周期级测试（Robolectric/instrumented） |
| 14 | `stale timer fired after new request never stops mid-pass`（**v3 新增**——复审 #2 反例序的确定性复现） | `[Req(B), 逃过 cancel 的旧 t 到期]` | `onSyncRequested(A)`; runCurrent → completed（arm t1）; `onSyncRequested(B)`; runCurrent → entered#B ✓（t1 已作废+cancel）；**直接调 `coordinator.stopTimerFired(t1)`**（模拟漏网到期——不入队列、原地校验）; 释放 B; runCurrent; advanceTimeBy(1500); runCurrent | t1 到期不停服（B 中 eligibleStopCalls == 0）；B 后 t2 到期 eligibleStopCalls == 1（**「B 中不停止」+「B 后恰好一次正常停止」**双断言） |
| 15 | `multiple stale timer fires are all ignored and idle stop still occurs`（**v3 新增**） | 多个 stale 到期 | 同 #14 但连调 `stopTimerFired(t1)`×3（旧令牌重复回报） | 三次全丢弃（eligibleStopCalls==0）；最终正常到期恰一次停服（==1） |
| 16 | `fast-completing first pass still arms stop`（**v3.1 修订**——复审 #1/#6：v3 版在 StandardTestDispatcher 下协程体延迟到 runCurrent() 才运行，错误实现照样通过=确定性假绿） | LAZY 注册序 | **协调器 scope 换 `backgroundScope + UnconfinedTestDispatcher(scheduler)`**（本例专用——`job.start()` 内联执行协程体，恰是被测注册序的暴露条件）；consume(A) 不挂起直接返回；`onSyncRequested(A)`; advanceTimeBy(1500); runCurrent | `armCount == 1; eligibleStopCalls == 1`（首 pass 收尾即正确武装——`drainJob` 在协程体首见前已注册）。**Mutation check（实现时必做）**：临时回退为 `drainJob = scope.launch(start = LAZY) { drainLoop() }.also { it.start() }` 单表达式，本用例必须变红——验证 harness 有鉴别力 |
| 17 | `timer from previous incarnation leaking past restart cannot stop new idle stop`（**v3.1 修订**——复审 #6：原 `leakingCancel` 变体下 `arm(t2)` 的 `job?.cancel()` 仍会取消 t1，声称的两个回调实际只有一个可触发=断言空洞） | cancelStop 漏网 | FakeStopScheduler 开 `detachOnCancel = true`（cancel 与新 arm 均不再取消旧 job——旧计时器脱离句柄独立存活、模拟「已过 delay、到期分发已排队、句柄追不上」）：`onSyncRequested(A)`; runCurrent → completed（arm t1）; `cancelActiveDrain()`（t1 存活）; `onSyncRequested(D)`; runCurrent → completed（arm t2）; advanceTimeBy(2000); runCurrent（t1、t2 先后到期回报） | **`firedTokens` 同时含 t1 与 t2——t1 确实触发过（非空洞断言）**；t1 令牌失配丢弃；t2 校验通过 → `eligibleStopCalls == 1`（恰一次，来自新循环） |
| 20 | `leaked forced-stop callback after new start cannot stop new drain`（**v3.1 新增**——复审 #3：CANCEL→D→旧取消回调漏网的确定性复现） | FORCED 停服失效协议 | `onSyncRequested(A)`; runCurrent → entered#A ✓; `cancelActiveDrainAndArmForcedStop(1000)`（弃 A + 武装 FORCED t_f）; `onSyncRequested(D)`（**入口即作废 t_f** + cancelStop）; runCurrent → completed#D ✓（arm t2 ELIGIBLE）; **直接调 `coordinator.stopTimerFired(t_f)`**（模拟逃过 cancelStop 的漏网取消回调——现网缺陷场景）; advanceTimeBy(1500); runCurrent（t2 到期） | t_f 失配丢弃：`forcedStopCalls == 0` 且 D 完整执行不被打断；t2 校验通过 → `eligibleStopCalls == 1`——**旧取消路径无法越过新 start 停服/移除前台**（现行 SyncService.kt:131-138 缺陷的协调器层锁定；Service 壳为薄接线，见 §6） |
| 21 | `forced stop fires exactly once when no new request arrives`（**v3.1 新增**——复审 #3：取消延迟停服的正常路径） | FORCED 停服正常路径 | `onSyncRequested(A)`; runCurrent → completed#A ✓; `cancelActiveDrainAndArmForcedStop(1000)`; advanceTimeBy(1000); runCurrent（t_f 到期）; 随后连调 `stopTimerFired(t_f)`×2（stale 重报） | `forcedStopCalls == 1`（恰一次——用户取消、无新请求，延迟后停服，与现行 DELAY_AFTER_CANCEL_MS 语义等价）；`eligibleStopCalls == 0`（两出口不串）；stale 重报全丢弃 |
| 22 | `old eligible timer firing in delivered-not-resumed window cannot stop`（**v3.1 新增红测试**——复审 #2/附注 (a)：B 已交付 receiver、continuation 未运行，`requests.isEmpty==true` 但确有待执行请求） | 交付-恢复窗口 | `onSyncRequested(A)`; runCurrent → completed ✓（arm t1，循环挂 receive）; `onSyncRequested(B)`——trySend 把 B **交付给挂起中的 receiver**（continuation 已排队、**尚未运行**；B 已离开缓冲）; **刻意不 runCurrent**，直接调 `coordinator.stopTimerFired(t1)`（模拟旧到期恰在此窗口执行）; 断言; runCurrent（B 的 continuation 恢复）→ entered/completed#B ✓; 推进 t2 到期 | 窗口内旧到期被丢弃（`eligibleStopCalls == 0`——v3 草案「令牌只在 pass 开始作废」下此刻三条件全真会停服 → **红**；v3.1 入队即作废令牌 → 失配丢弃）；B 完整执行、收尾 arm t2；最终恰一次停服。**Mutation check**：把令牌作废挪回 pass 开始处本用例必须变红 |

引擎侧表征用例（`app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt` 追加，沿用该文件既有引擎级门控 harness——报告 BHB 实验即以真实 SyncEngine 门面驱动）：

| # | 用例 | 断言 |
|---|---|---|
| 18 | `consumeAndExecute does not observe rows enqueued mid-drain` | 真实 SyncEngine：pass#1 门控挂起 → 并发 `setPendingTask(B)`（阻塞于锁）→ 释放 → pass#1 返回后 B 行仍在表（PENDING）→ 显式再次 `consumeAndExecute` 才消费 B。锁定串行化现实，与协调器 1/2 合证「引擎不可见 + 服务必再跑」分工契约 |
| 19 | `pass cancelled while waiting for lock leaves row recoverable`（取出后等锁取消——矩阵行锁定） | 真实 SyncEngine + fake manager：外部持 `storageOperationMutex` → 后台启动 `consumeAndExecute(B)`（receive 已交付、阻塞等锁）→ 取消该 job → 断言 B 行仍 PENDING（未被消费清除）→ 释放锁 → 再次 `consumeAndExecute(B)` 正常消费执行（弃置语义的恢复路径成立） |

### 5. 风险与守卫

- **CancellationException（红线 3）**：drainLoop 无任何 try/catch；receive 与 consumeAndExecute 的 CE 均原样终结循环；`cancelActiveDrain` 只 cancel 不吞。
- **停服协议守卫（v3：confinement 构造性保证，非纪律约定；v3.1 按附注 (b) 收敛）**：全部 check-then-act 段（收尾武装判定 / 到期校验）无挂起点且 confined 单线程执行——TOCTOU 在结构上不可表达，而非「靠既有跨线程纪律压住」（复审 #1 明确要求）。停服校验以**令牌有效性为主门**（onSyncRequested 入队即作废——复审 #2），`drainJob?.isActive`/`requests.isEmpty` 为纵深防御：**不声称三条件各自独立必要**（「令牌有效而其余条件失败」的到达态不可经公共 API 独立构造——无对应的「只让一项失败」用例，如实注明而非虚构）；`drainJob?.isActive == true` ≠ idle（DRAINING 中也为 true），`isActive && queueEmpty` **不能**排除 #2 的已交付待恢复状态（那由令牌主门排除）。性质→测试映射：不中断运行中 pass=9/10；zombie 旧循环收尾不武装（drainLoop 身份检查）=12；停服恰一次+回调不重入前提=13；stale 到期丢弃=14/15；注册序=16；漏网计时器=17；FORCED 失效协议=20/21；交付-恢复窗口=22。终局性前提（pass 正常完成 + stopDelayForPass 非 null）与两例外见 §2 正确性论证 v3.1。`lastStartId` 读写同在 Main（onStartCommand 写 / onEligibleStop、onForcedStop 读）——同一串行域。
- **E8 / 卷归属 / AEAD**：不涉——本修复只动「谁来调用 consumeAndExecute」的调度层，引擎执行体零改动。
- **敏感信息**：请求队列只承载 relationId（Long）；令牌为 Long。无新增日志。
- **v3 消除的 v2 残余**：v2 曾依赖「main-confined 写 + 跨线程可见性纪律」——v3 中 `drainJob`/`armedToken`/`stopSeq`/缓冲的读写全部 confined，**不新增跨线程共享可变状态**；唯一跨线程面 = `consumeAndExecute` 回调内部的 `withContext(IO)`（其结果恢复回 confined 线程）。`stopService` 顺序（`stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelfResult`）沿用既有 `stopService` 实现（SyncService.kt:122-125），不在协调器内重排。
- **残余风险（只列不扩；①v3.1 重写）**：① 停服决定（stopService 调出）与实际销毁之间到达的新请求：协调器侧「入队即作废令牌」把该窗口收敛到 stopService 调用之后（Main 域内无插入点）；销毁后到达者的处置依赖系统行为——**START_REDELIVER_INTENT 不构成主动 stopSelfResult 后必重放/必重启的通用保证，本计划不声称自愈**；生产前提「协调器回调不得重入」已写入 KDoc 并由 #13 锁定形态；真实 stop/start 系统竞态恢复语义另立生命周期测试（Robolectric/instrumented，不在本轮 JVM 范围）；② `finalize` DAO 抛非 CE 异常 → drainLoop 死、FGS 通知滞留至超时（报告 INCONCLUSIVE-2，现状）；③ START_REDELIVER 只重放最后 intent——多请求缓冲被销毁弃置后靠各自下次 start 恢复（行持久，无数据丢失）；④ 用户取消延迟停服已并入 confined 域与统一失效协议（v3.1），Default 域绕行路径删除后不再存在「旧取消回调越过新 start」的协调器外通道——Service 壳本身为薄接线（无自有计时逻辑）。
- **兼容性**：`SyncService.start`/`stop` 外部 API、通知节奏、START_REDELIVER_INTENT 返回值均不变；调用方（各 ViewModel）零改动。

### 6. 涉及文件清单（写域）

- 生产：新建 `app/src/main/java/cn/vectory/syncplayer/sync/SyncServiceDrainCoordinator.kt`（含 `onForcedStop` 回调与 `cancelActiveDrainAndArmForcedStop`——v3.1）；`app/src/main/java/cn/vectory/syncplayer/sync/SyncService.kt`（+`coordinatorScope = SupervisorJob() + Dispatchers.Main.immediate` confined 专用 scope；**v3.1 全量接线**——onCreate 构造协调器、onStartCommand 非取消分支调 onSyncRequested（删 :70-71 手工 pendingStopJob 取消）、**cancelSync 整体重写**（立即通知 + cancelActiveDrainAndArmForcedStop，删 :131-138 Default 域延迟停服块）、**onTimeout**（通知 + cancelActiveDrain + 立即 stopService(startId)）、**onDestroy** 补 coordinatorScope.cancel()；删 `syncJob` 字段改由协调器持有；`scheduleStop` 吸收进 `armStop` 闭包后移除；`serviceScope` 保留给非协调器协程——取消/超时/销毁的**全部停服路径**收敛到 confined 域，无 Default 域绕行通道）；`app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt`（仅 `:110-112` KDoc 更正）
- 测试：新建 `app/src/test/java/cn/vectory/syncplayer/sync/SyncServiceDrainCoordinatorTest.kt`（20 用例：#1–#17（**#13 v3.1 重定义、#10/#16/#17 修订**）+ **#20/#21/#22 新增**；`FakeStopScheduler`（含 detachOnCancel 模式与 firedTokens 记录）；scope 默认 `backgroundScope + StandardTestDispatcher(scheduler)`，#16 专用 UnconfinedTestDispatcher）；`app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt`（+2 表征用例）

## 总体验收与守卫 <a id="总体验收与守卫"></a>

### 全量门禁（按 AGENTS.md，改动落地后必跑）

```bash
# 类型/语法验证（无 LSP 兜底，唯一来源）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin

# 本 lane 全部新增/受影响测试
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.*' --tests 'cn.vectory.syncplayer.sync.sftp.*'

# 全量回归（57 个测试文件基线全绿，见两份报告的 Test Landscape）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

期望：全部 BUILD SUCCESSFUL；红线锁定用例（E8 清空、卷所有权 fail-closed、取消传播、重试白名单）无一回归。

### 实施顺序建议（依赖关系）

1. **BUG-009**（SFTP 幂等）——独立、最小，且是 BUG-001 remote-first 的语义前提（already-gone 不得计失败）。
2. **BUG-010**（connect 重试）——独立，仅动 pipeline 建连段。
3. **BUG-007**（finalized 清理）——动 pipeline 下载块 try/catch 结构，先于 BUG-008（两者同文件，避免冲突编辑）。
4. **BUG-008**（终态 copy 写入）——依赖 BUG-007 落定后的最终代码形态（`:521-538` 行号会漂移）。
5. **BUG-001**（remote-first）——独立于 pipeline，任何顺序均可；建议在 009 后。
6. **BUG-018**（drain 协调器）——最棘手、改动面横跨 Service/新类/Engine 注释，放最后单独提交。

每步独立提交、独立跑门禁；任一步失败不阻塞其余独立项（007/008 同文件除外）。

### 红线核查总表

| 红线（AGENTS.md） | 001 | 007 | 008 | 009 | 010 | 018 |
|---|---|---|---|---|---|---|
| E8：部分快照绝不推导 DELETE_LOCAL | ✓ 零改扫描 catch | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 |
| 卷归属 blank/unknown/mismatch fail-closed | ✓ 检查保留在最前（测试锁定） | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 | ✓ 不涉 |
| CancellationException 先传播 | ✓ 远端 CE rethrow 前置 | ✓ NonCancellable 清理后 rethrow | ✓ 不涉 | ✓ CE 分支最前 + ensureActive | ✓ helper CE 裸 rethrow | ✓ drainLoop 零吞并 |
| AEAD v2 存储契约不变 | ✓ | ✓ 只动清理编排 | ✓ | ✓ | ✓ | ✓ |
| 敏感信息不进日志/UI | ① | ① | ① | ①③ | ① | ①（事件队列仅 relationId/令牌） |
| SyncException 结构化错误不回退 message | ✓ | ✓ | ✓ | ✓ false≠异常 | ✓ | ✓ |
| 随机读失败抛异常 | ✓ 不涉 | ✓ verifyReadable 语义不动 | ✓ | ✓ | ✓ | ✓ |

> **「敏感信息不进日志/UI」行 v2 更正**（v1 在该行写「✓ 不新增日志」等，与源码现状不符——`SyncDeleteExecutor.kt:96,140`、`SyncDownloadPipeline.kt:389` 现状即记录 path 与带 throwable 的日志）。三栏区分：
> **① 新增行为**——本轮 6 项修复**不新增任何** path/凭据/底层 message 的日志或 UI 输出；
> **② 既有缺口**——上列三处现状日志属红线 6 的**既有欠账**（本轮不扩大、也不在本 lane 顺手修复，应另立条目处置）；
> **③ 本轮处置边界**——结构化 `SyncException.path` 维持内部结构化字段现状（message=null），**结构化 ≠ 可安全入日志/UI**：本轮不将其新映射进任何用户可见输出（BUG-009 §5 措辞已同步更正）。

### 报告与本计划的不符之处（如实记录）

1. **BUG-009/010 编号互换**：报告 0012 中 009=connect 重试、010=SFTP 幂等；todo.md 相反。本计划按任务指令以 **todo.md 为准**（009=SFTP 幂等，010=connect 重试），章节内已互相标注对应报告编号。
2. **报告 0114 BUG-018 建议的 Fix Direction A（drain 循环锁内二次检查）不可行（v2 收窄论证范围）**：`setPendingTask` 与 drain 循环竞争同一 `storageOperationMutex` 且锁覆盖整个循环，入队必然晚于循环退出——锁内再查永远看不到并发入队（见 BUG-018 §1）。**该论证仅排除「同锁锁内、退出前再查」一类方案，不构成对所有引擎侧方案的排除**；选 Channel 常驻消费者（Fix Direction B 变体 + 停服资格串行协议）系工程理由：最小改动、纯 JVM 可测、不新增锁序/第二真相源，不称唯一解。
3. **报告 0012 BUG-008 根因描述的补充**：报告说 merge 把「requested 0 ≠ baseline N 当作有意更新」——merge 行为本身是其 KDoc 契约的正确实现，缺陷在生产端写入形态（fresh 对象）违反契约；本计划修生产端而非 merge 特判（报告的两条 Fix Direction 中选前者，理由见 BUG-008 §2）。
4. **报告 0012 BUG-007 修复建议中的 `store.isFinalized(id)` 探测：部分采用（v3 改形态）**：finalize **返回 true** 的窗口用尝试级阶段零成本判定（`SafVolume.rename` 返回 true 已含改名后双检，SafVolume.kt:162）；「finalize 抛 CE/异常/返回 false ⇒ 未改名」**不成立**（withContext 返回边界可在 renameDocument 完成后交付 CE；尾部双检自身异常被 catch-all 误报 false，SafVolume.kt:150-171）。不确定结果的收束改用**三阶段状态 + 三态 tmp 探测**（v2 的 Boolean `hasPendingTmp` 被复审 #3 否定）：① `NOT_FINALIZED`（从未进入 finalize——`openEncryptSink` 建立前失败/包装失败自删 tmp 均停留此阶段，EncryptedMediaStore.kt:52-77）**不探测、绝不删 final**（v2 在此语境据「tmp 缺失」删旧 final 是数据丢失授权）；② `FINALIZE_UNCERTAIN` 用 `probePendingTmp`（PRESENT/ABSENT/UNKNOWN，经 `MediaVolume.existsStrict` 拆开 null cursor 与「未找到」，SafVolume.kt:39-51/:142-144）——ABSENT 在「已进入 finalize ⇒ tmp 必曾存在」前提链下等价已提交；③ UNKNOWN 保守只删行不删密文。比 `isFinalized`（final 存在性；existing 变体下无法区分新旧版本密文）更窄，且不产生「盲删可能仍为旧版本的 final」风险。
5. **报告 0012 BUG-001 建议「方向 A 查 PendingSyncActionDao」不适用**：DELETE_BOTH 流程不落 pending 表（该表只存 mirror sync 的 DOWNLOAD/DELETE_LOCAL），方向 A 需新增持久化意图类型；采用 todo.md 已倾向的方向 B（remote-first），todo.md 的「pending-delete 队列」表述按此理解执行。
6. **SyncService 零单测（报告 Test Landscape）**：本计划以可 JVM 测试的 `SyncServiceDrainCoordinator`（confined dispatcher 可注入 runTest 的 TestDispatcher——单线程虚拟时间下全交错矩阵确定性可驱动）承载全部调度逻辑、Service 壳只留薄接线，规避 Robolectric/Hilt 测试基建（repo 内虽已有 Robolectric 4.13，但 @AndroidEntryPoint Service 测试需 Hilt test app 配置，收益不成比例）。
7. **v1 曾断言「SFTP 客户端目前无任何单测」——不实（v2 更正）**：`app/src/test/java/cn/vectory/syncplayer/sync/sftp/SftpTransportCancellationTest.kt` 已有真实 `SftpFileClient` 的 JVM 取消/分类测试（`sshClientFactory` 构造器注入缝 :57 + `stubNetworkOk()` 网络 mock + `connectSuccessfully()` 走真实 connect 路径，:143-148）。BUG-009 v2 改为复用该 harness 追加用例、撤销 `sftpClient`/`remoteBasePath` 字段 internal 化，并把既有 SFTP 取消测试列入定向验收。
8. **v1 曾将 BUG-018 标注为「todo.md #10」——误引（v2 更正）**：todo.md 无 BUG-018 条目、无「第 10 项」与 engine-only 约束的记载；BUG-018 来源为**用户指定修复范围** + 报告 0114（见文首权威来源与 BUG-018 标题）。

### 报告未提及的强相关风险（只列不展开）

- `scheduleStop` 的 `stopService(lastStartId)` 读**调用时**的 lastStartId，与 onStartCommand 更新 lastStartId 之间存在既有竞争（新 start 到达 vs 旧 delay 到期）——BUG-018 v3 将协调器调度与 lastStartId 读写收敛到同一 Main confined 串行域，且到期不直接停服、经非挂起校验（令牌匹配主门 + 循环存活/队列空纵深，v3.1 分层措辞）后才停——该竞争被结构吸收（停服仅发生于空闲态且校验段内无插入点；见 BUG-018 §2/§5，测试 #9/#10/#14/#15/#16/#17/#20/#22 锁定）。v3.1 补：用户取消延迟停服（原 cancelSync 的 Default 域路径）亦已并入同一串行域与失效协议——该既有竞争的全部已知通道闭合。
- `pendingTaskManager.finalize`（SyncEngine.kt:131）在管线外层 catch 之外：DAO 抛非 CE 异常 → drainLoop/旧 syncJob 静默死亡，FGS 停留运行中通知直至 6h 超时（报告 0114 INCONCLUSIVE-2，本计划保留现状并在 018 取消矩阵明示）。
- `TransportCancellationRegressionTest` 用 mock 自实现契约，不证明真实 sshj/jcifs 的 `downloadTo` 取消行为（报告 Test smell，与 BUG-007/010 的回归强度相关）。
- `SyncDeleteExecutor` 的 DELETING 进度流与 `SyncDownloadPipeline` 共享 publisher 单例：跨关系/跨流程的进度串扰（报告 REJECTED-4 的显示层残留）在 remote-first 后语义不变，但「删除流程 init 写 fresh 计数」与「上一轮下载终态」的交接从未被测试锁定。
- `SmbFileClient.deleteFile` 的 `exists()` 预检与 `delete()` 之间自身有 TOCTOU 窗口（`smbFile.delete()` 对已消失文件抛异常 → REMOTE_DELETE_FAILED），与 BUG-009 同族但 SMB 侧报告未列；如需对称修复应另开项。
