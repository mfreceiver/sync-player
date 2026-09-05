# 修复计划 — SMB / DAO / Gain Lane（BUG-006, 011, 012, 013, 020, 022）

> 状态：修订 v3（复审 FAIL 8.6/10 → 4 项收敛修订；首轮 #1 BUG-006 双防线、#3 写域移出+L4 交接已确认）。Oracle 背书骨架（substr 精确比较、单列 updateAudioGain、E8 异常落点）未动。
> v2 修订要点：BUG-006 补 listFiles 入口空 share 拒绝（E8 空快照洞）；SMB/SFTP 路径策略分离；VM 层全部移出写域 → 新增「Lane4 交接项」章节；BUG-011 #7/#8 期望值按查询语义修正；TDD 红/绿标注逐用例修正。
> v3 修订要点：L4-3 拆「mock 调用契约」+「真实 Room 交错」两类测试，同步 analyzer 用有界阻塞门控（非 coAnswers）；L4-1 明确协议未知（selectedSource==null）时**不保存**；BUG-011 #8/#9 与 BUG-012/020 红绿/计数修正；层 1b 对非根浏览的异常传播描述改为与 :275-281 既有 catch 实况一致（记录并跳过，不达 UI）。两条澄清（SMB 前导 / 等价性、listShares root 浏览）经复审核实保留。
> v3.1 修订要点（复审 9.2/10 → 3 项收敛）：双条件终态+真实时间有界超时提升为**类 A/B 共用规则**（删除类 A「mock repo 无真实 IO 故调度器可控」错误推论——:148 withContext(Dispatchers.IO) 不因依赖被 mock 而消失）；A/B 统一清理顺序（取消 VM 作业→释放门控→有界等待作业实际结束→还原 mocks/Main→关库；不以 isAnalyzing 值为结束证据）；:16/:清单/:验收三处汇总补列第三个 Lane4 测试类 `GainAnalysisConcurrencyTest`。
> 权威来源：todo.md > docs/automatic/20260905-0012_bug-hunting.md > docs/automatic/20260905-0114_bug-hunting.md
> 纪律：本计划只写文档，不改任何生产/测试代码。

## 总览

- **Lane 范围**：SMB 扫描（BUG-006）、DAO LIKE 路径族（BUG-011/012/020）、增益分析列写（BUG-013）、路径防御（BUG-022），共 6 个 BUG。
- **三条公共主线**：
  1. **路径前缀匹配统一为 BINARY 精确语义**——substr/length SQL 侧等值比较替换全部 11 处参数驱动 LIKE（BUG-011/012/020 一次收口，含报告未点名的第 12 处 `getParentPathsInFolder`）；
  2. **E8 闭环补完**——SMB 扫描不再有"成功返回垃圾/空快照"的分支（BUG-006：listFiles 入口拒绝空 share 为第一道防线，extractRelativePath 锚定校验为第二道防线，均抛 SCAN_INCOMPLETE，复用 SyncRemoteScanner 既有清空 catch）。入口侧保存层拒绝 SMB root 的**实现移交 Lane4**（契约见「Lane4 交接项」）；
  3. **列级写替代快照整行写**（BUG-013）——本 lane 仅交付 DAO+Repository 单列 API，调用点替换与 VM 测试移交 Lane4。
- **写域边界（评审 #3）**：本 lane 不触碰任何 ViewModel——`SyncRelationEditViewModel.kt`（保存校验+路径归一化）、`GainAnalysisViewModel.kt`（增益写回调用点）、`strings.xml` 及三个 VM 层测试文件（`SyncRelationEditViewModelRemotePathTest`、`GainAnalysisViewModelUpdateTest`、`GainAnalysisConcurrencyTest`）均不在写域，详见「Lane4 交接项」。
- **依赖关系**：BUG-011 的 WHERE 谓词是 BUG-022 getSubFolders 重写的组成部分；BUG-012/020 在 PlaybackRecordDao 合并为一次重写；BUG-022 的入口归一化与 BUG-006 保存层拒绝同源，一并移交 Lane4（协议分离策略）。
- **实施顺序建议**（每 BUG 先红后绿）：BUG-011 → BUG-022（同文件续作，仅 DAO 防御部分）→ BUG-012+020（同文件合并）→ BUG-013（仅 DAO/Repository）→ BUG-006。全部完成后跑全量门禁；Lane4 交接项在本 lane 合入后解锁实施。
- **纪律**：本计划撰写期间未运行任何 gradle 命令；验收命令供实施阶段执行。

## BUG-006 SMB remotePath="/" 垃圾相对路径 → dryRun 全量 DELETE_LOCAL（E8 被绕过）

**严重度：Critical（数据丢失）｜权威修复方向（todo.md）：保存层拒绝 `/` + 锚定 startsWith 匹配、失败抛 SCAN_INCOMPLETE**

### 1. 现状与根因（已打开源码核对）

**触发入口**：`SyncRelationEditViewModel.kt:249-265` —— `navigateRemoteUp()` 可自然导航到 `/`（:253 `else "/"`），`selectRemotePath()`（:263-265）将 `/` 写入 `_remotePath`；`save()` 的校验只有 isBlank（:273-278），`:301` 仅 `trim()`，`/` 原样入库。用户无需手输。

**扫描链**：`SyncRemoteScanner.dryRunLocked` → `client.listFiles(relation.remotePath)`（`SyncRemoteScanner.kt:77`）。

**根因 1 — 空 share 的 URL 构造**（`SmbFileClient.kt:175-182`）：

```kotlin
val pathParts = path.trim('/').split("/")   // "/" → [""]，share = ""
val share = pathParts.firstOrNull() ?: ""
val remoteSubPath = pathParts.drop(1).joinToString("/")
val smbUrl = if (remoteSubPath.isNotEmpty()) { "smb://$currentHost/$share/$remoteSubPath/"
} else { "smb://$currentHost/$share/" }     // → "smb://host//"（share 为空）
```

**根因 2 — 未锚定的 indexOf 匹配**（`SmbFileClient.kt:325-334`）：

```kotlin
private fun extractRelativePath(smbUrl: String, share: String, remoteBasePath: String): String {
    val marker = if (remoteBasePath.isNotEmpty()) "/$share/$remoteBasePath/" else "/$share/"
    val idx = smbUrl.indexOf(marker)          // share="" → marker="//"
    if (idx < 0) return smbUrl.substringAfterLast('/')   // 兜底：纯文件名（同样是垃圾）
    return smbUrl.substring(idx + marker.length).trimEnd('/')
}
```

share 为空时 marker = `"//"`，`indexOf("//")` 命中 scheme 分隔符（`smb://` 的 index 4），返回 `host/media/movies/f.mp4`（host 前缀垃圾相对路径）。

**根因 3 — E8 闭环洞：`extractRelativePath` 仅对文件调用，root 空枚举/仅空目录零检查**（`SmbFileClient.kt:302-321`，评审 #1）：

```kotlin
for (child in children) {
    ...
    if (child.isDirectory) {
        listFilesRecursive(ctx, child, share, remoteBasePath, result, maxFiles)   // :308-309 目录直接递归，不经任何路径检查
    } else {
        val relPath = extractRelativePath(child.url.toString(), share, remoteBasePath)  // :311 仅文件调用
        ...
    }
}
```

即便把 `extractRelativePath` 改为锚定+抛错，该检查也**只覆盖文件分支**：

- root 下**零条目**（`dir.listFiles()` 返回空数组）：循环体不执行，无任何检查 → 返回空列表"成功"；
- root 下**仅空目录**：目录分支递归后仍无文件 → 同样空列表"成功"。

空快照沿 `SyncRemoteScanner.kt:131-150`：`remoteLocalPaths` 为空集 → **每个本地视频推导 DELETE_LOCAL**，且无异常 → `:153-175` 的 E8 catch 完全不触发。这是与"垃圾路径"并存的第二条 E8 绕过通道：原方案漏堵（锚定检查在文件分支才执行；保存层拒绝又被移出本 lane 写域，见 Lane4 交接项），客户端必须在**扫描入口**自守。

**后果**：垃圾相对路径经 `storagePaths.toLocalRelativePath`（`StoragePaths.kt:46-48`，纯拼接）得到 `videos/host/...`，与本地索引 `videos/media/...` 永不相等 → `SyncRemoteScanner.kt:131-150` 对每个本地视频推导 `DELETE_LOCAL` + 每个远端文件 `DOWNLOAD`，且扫描"成功"、error=null——**E8 守卫（:153-175 的 catch 清空路径）完全不触发**。`connect("/")` 本身合法（`SmbFileClient.kt:106-114` 走 root 分支建会话），真机 jcifs-ng 将 `smb://host//` 解析为 host root，扫描会真实"成功"。

**旁证**：`listDirectories("/")` 走 `listShares()`（`SmbFileClient.kt:233-236`），不经过 `extractRelativePath`——所以浏览根目录正常，仅**扫描**路径中毒（与本 bug 只影响 sync 一致）。

### 2. 修复方案（客户端双防线；保存层移交 Lane4）

**层 1a — listFiles 入口拒绝空 share（第一道防线，堵根因 3 的空快照洞）**（`SmbFileClient.kt:172-189`）：

在 `listFiles()` 解析出 share（:176-177）之后、创建目录对象并递归（:183、:187）之前插入：

```kotlin
// E8: 空 share = root 扫描。root 之下条目的相对路径在本客户端模型中不可构造
// （URL 无 share 段），任何"成功"输出（空列表/垃圾路径）都会让 dryRun 推导
// DELETE_LOCAL —— 必须在枚举之前失败，绝不返回快照。
if (share.isEmpty()) throw RemoteScanIncompleteException()
```

- 覆盖**所有** root 形态：空枚举、仅空目录、带文件——检查先于任何枚举，与远端实际内容无关；
- **不禁止浏览 root**：`listDirectories("/")` 走 `listShares()`（`SmbFileClient.kt:233-236`），不经 `listFiles()`，浏览不受影响；`listFiles` 的调用方是扫描链（`SyncRemoteScanner.kt:77`）；
- 异常经 `SyncRemoteScanner.kt:153-175` 既有 catch 清空 toDownload/toDelete 并置 scanError——**E8 通道零新增代码**；无参构造，不带 path/host（红线 6）；
- 对 `remotePath="/"` 的存量关系：同步立即以 SCAN_INCOMPLETE 失败并提示，而非静默全删。

**层 1b — extractRelativePath 锚定匹配 + 失败抛 SCAN_INCOMPLETE（第二道防线，堵根因 1/2 的垃圾路径输出）**：

`extractRelativePath` 改为对「真实构造出的基 URL」做前缀锚定。`currentHost` 是实例字段（`SmbFileClient.kt:49`），**签名不变**，两个调用点（:264、:311）无需改动：

```kotlin
private fun extractRelativePath(smbUrl: String, share: String, remoteBasePath: String): String {
    val marker = if (remoteBasePath.isNotEmpty()) "/$share/$remoteBasePath/" else "/$share/"
    val anchoredPrefix = "smb://$currentHost$marker"
    // 锚定校验：URL 必须以 smb://host/share/base/ 开头。share 为空（root 扫描）或任何
    // 结构意外（URL 不在扫描根之下）都判为快照不可信 → E8 失败路径，绝不产出垃圾路径。
    if (!smbUrl.startsWith(anchoredPrefix)) throw RemoteScanIncompleteException()
    val rel = smbUrl.substring(anchoredPrefix.length).trimEnd('/')
    if (rel.isEmpty()) throw RemoteScanIncompleteException()
    return rel
}
```

- 为什么安全：现网代码本就依赖 `child.url.toString()` 以我们构造的 URL 字符串为前缀（`indexOf(marker)` 在同一字符串形态上匹配）；`startsWith` 只是把"任意位置命中"收紧为"必须开头命中"，信任级别不变、严格性提升。
- 删除 `idx < 0` 的 `substringAfterLast('/')` 静默兜底——该兜底正是垃圾路径的第二来源。
- 抛出的 `RemoteScanIncompleteException`（`RemoteScanIncompleteException.kt:20-29`，code=`SyncErrorCode.SCAN_INCOMPLETE`，`SyncException.kt:20`）经 `SyncRemoteScanner.kt:155-175` 现有 catch 清空 toDownload/toDelete 并置 scanError——**E8 通道零新增代码**。UI 文案已存在：`ErrorMessageMapper.kt:85-86` → `R.string.sync_error_scan_incomplete`。
- **不带 path/host 参数**抛出（red line #6：path/host 属敏感上下文；诊断定位由 code+operation 承担）。
- 对结构意外（URL 不在扫描根之下、编码形态不一致等）：锚定失败即抛 SCAN_INCOMPLETE——即使入口检查因未来重构被绕过，扫描的**文件条目分支**（:311）仍无"返回垃圾"的出口。**范围限定（评审 v3 #4）**：目录分支（:308-309）不经 `extractRelativePath`，root 之下目录条目的拦截完全由层 1a 入口拒绝承担（空 share 在枚举前抛出，任何条目——文件或目录——都不可能进入递归）；层 1b 不保护"目录条目分支"，只保护最终落到 :311 的文件条目。

**层 2 — 保存层拒绝（SMB）+ 协议化路径归一化：移交 Lane4**（评审 #2/#3）：

原方案在 `save()` 内新增 `normalizeRemotePath`（折叠空段/`.` 段、拒绝 `..`、`/`→root）并持久化归一化结果。两点被评审批错：

1. **跨协议归一化改变合法 SFTP 路径语义**：SFTP 客户端 `remoteBasePath = remotePath.trim()`（`SftpFileClient.kt:106`，是 `trim()` 而非 `trim('/')`），递归 `sftp.ls(currentPath)`（:203）与浏览 `sftp.ls(path)`（:162）均按**原样**解析路径——前导 `/` 是"绝对 vs 相对（服务器 cwd）"的锚定语义，把 `/a/b` 归一为 `a/b` 持久化后可能扫到另一个目录；裸 `.` 是"当前目录"，转成 `/` 同样改变语义。SMB 与 SFTP 的路径策略必须**分开**定义；
2. **VM 层越权**：`SyncRelationEditViewModel.kt` 属 Lane4 写域。

本 lane 对此仅交付**契约文本**（协议分离的路径策略规则表、契约测试矩阵、`..` 拒绝的协议范围与产品约束），见「Lane4 交接项」章节。本 lane 的 E8 保障**不依赖保存层**：层 1a/1b 已在客户端 fail-closed——即使 Lane4 尚未实施，root 扫描也已安全失败。

### 3. TDD 测试计划（先写失败测试）

**测试文件 1：`app/src/test/java/cn/vectory/syncplayer/sync/smb/SmbFileClientRootScanTest.kt`**（沿用 `SmbTransportCancellationTest` 的 mockk `SmbFileFactory`/`SmbFile` seam 与 `stubNetworkOk` 写法）：

| # | 方法 | Arrange | Act | Assert | 红/绿（修复前） |
|---|---|---|---|---|---|
| 1 | `root scan with files throws RemoteScanIncompleteException with SCAN_INCOMPLETE` | factory 对 `smb://host/`（connect root 探测）与 `smb://host//`（listFiles 根）返回 mock SmbFile：exists()=true、isDirectory=true；根的 listFiles() 返回 share 目录 mock（url=`smb://host/media/`，isDirectory=true，listFiles() 返回叶子 mock url=`smb://host/media/f1.mp4`，isDirectory=false，length()=1，lastModified=0） | `client.connect(source, "/")` 后 `client.listFiles("/")` | 抛 `RemoteScanIncompleteException`，`code == SyncErrorCode.SCAN_INCOMPLETE`（断言枚举，不断言文案） | **红**：旧码不抛，返回 host 前缀垃圾路径（`host/media/f1.mp4`） |
| 2 | `root scan with empty enumeration throws instead of returning empty snapshot` | 同上，但根的 listFiles() 返回**空数组** | 同上 | 抛 `RemoteScanIncompleteException`（code==SCAN_INCOMPLETE） | **红**（评审 #1 新增）：旧码循环不执行、零检查，返回空列表"成功"——E8 空快照洞本体 |
| 3 | `root scan with only empty directories throws instead of returning empty snapshot` | 根的 listFiles() 返回一个空 share 目录 mock（url=`smb://host/media/`，isDirectory=true，其 listFiles() 返回空数组），整树无任何文件 | 同上 | 抛 `RemoteScanIncompleteException` | **红**（评审 #1 新增）：旧码目录分支直接递归（:308-309）不经检查 → 空列表"成功" |
| 4 | `normal share scan returns host-free relative paths` | connect+listFiles 路径 `media/movies`；目录 url=`smb://host/media/movies/`，叶子 url=`smb://host/media/movies/f1.mp4` | `listFiles("media/movies")` | `result.map { it.path } == listOf("f1.mp4")`（无 host 前缀） | **绿（回归锁）**（评审 #6 修正）：旧码对正常 share 本就产出干净相对路径——本用例锁定行为不回退，**不是红灯证据** |
| 5 | `url outside scan root containing marker substring throws instead of silent misattribution` | 叶子 url=`smb://other-host/media/movies/f1.mp4` | `listFiles("media/movies")` | 抛 `RemoteScanIncompleteException` | **红**：旧码 `indexOf("/media/movies/")` 在偏移 15 命中（other-host 内部，idx≥0），静默返回 `f1.mp4`——把别台主机的内容错记为扫描根内文件。注（评审 #6 修正）：此路径走的是 **indexOf 任意位置命中**，不是 filename 兜底 |
| 6 | `url lacking marker entirely throws instead of filename fallback` | 叶子 url=`smb://host/media/series/f1.mp4`（扫描根 `media/movies`，marker `/media/movies/` 在 URL 中完全不存在，idx<0） | `listFiles("media/movies")` | 抛 `RemoteScanIncompleteException` | **红**（评审 #6 新增）：旧码走 `substringAfterLast('/')` 兜底返回 `f1.mp4`——真正的 filename 兜底垃圾路径来源 |

**测试文件 2（scanner 接入验证）：并入既有 `app/src/test/java/cn/vectory/syncplayer/sync/SyncRemoteScannerTest.kt`**（沿用其 `clientFactory` / `client = mockk<RemoteFileClient>(relaxed = true)`（:39-40）与 `coEvery { client.listFiles(...) }`（:89）写法，紧邻既有 E8 用例 `scan failure yields empty actions and non-null error (E8)`（:74））：

| # | 方法 | Arrange | Act | Assert | 红/绿 |
|---|---|---|---|---|---|
| 7 | `listFiles throwing SCAN_INCOMPLETE yields empty download and delete sets with scanError code` | relation/source 正常；本地索引 2 行（如 `a.mp4`、`b.mp4`）；`coEvery { client.listFiles(any()) } throws RemoteScanIncompleteException()` | `dryRunLocked(relationId)` | `result.scanError?.code == SyncErrorCode.SCAN_INCOMPLETE`；`toDownload.isEmpty() && toDelete.isEmpty()`（下载/删除集合均空） | **绿（接入锁定）**：scanner 的 E8 catch（:153-175）既有行为——本用例锁定客户端新抛点与"清空闭环"的接线，若接入断裂（异常被吞/集合未清）即转红 |

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.sync.smb.SmbFileClientRootScanTest' \
  --tests 'cn.vectory.syncplayer.sync.SyncRemoteScannerTest'
# 期望：全绿。修复前红灯集（评审 #6 修正）：SMB 客户端用例 1/2/3/5/6 红（不抛错/返回垃圾路径/返回空快照）；
# 用例 4（正常 share 正例）与 scanner 用例 7 为绿——分别是回归锁与既有 E8 闭环的接入锁定，不计入红灯证据。
# VM 保存层测试（SyncRelationEditViewModelRemotePathTest）属 Lane4 交接项，不在本门禁。
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin   # 期望：BUILD SUCCESSFUL
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest    # 期望：全量回归无红（含 SyncRemoteScannerTest 既有 E8 用例）
# 注：JVM 门禁（testDebugUnitTest）不含任何设备测试（androidTest），见 BUG-011 验收节说明
```

### 5. 风险与守卫

- **E8（红线 1）**：双防线——层 1a 在**枚举之前**拒绝空 share（root 空枚举/仅空目录/带文件全覆盖，空快照洞闭合）；层 1b 锚定消除文件分支一切"返回垃圾"出口（含删除 `idx < 0` filename 兜底）。两者均抛 SCAN_INCOMPLETE → 复用 `SyncRemoteScanner.kt:153-175` 现有清空逻辑，扫描要么完整、要么异常，契约与 `RemoteScanIncompleteException` KDoc 一致。scanner 接入由用例 7 锁定。
- **CancellationException（红线 3）**：新增代码无 catch 块；`RemoteScanIncompleteException` 在 `withContext(Dispatchers.IO)` 内同步抛出，不与 CE 竞争。
- **敏感信息（红线 6）**：两处异常均为**无参构造**（不带 path/host）；诊断定位由 code+operation 承担。
- **结构化错误（红线 7）**：SCAN_INCOMPLETE→`sync_error_scan_incomplete` 既有映射（`ErrorMessageMapper.kt:85-86`），不触碰 Throwable.message。
- **回归面**：`extractRelativePath` 签名与正常路径行为不变（用例 4 绿锁）；浏览 root 不受影响（`listDirectories("/")` 走 listShares，:233-236）——**不禁止浏览 root**，仅扫描链 fail-closed。非根浏览路径（`listDirectories` :264 也调用 extractRelativePath）的锚定异常**不传播到 UI**（评审 v3 #4 修正）：:264 位于条目级 try 内，CE 先抛（:275-276），Exception 被 :277-281 既有 catch 捕获——`ensureActive()` + `Log.w` 后**跳过该条目、循环继续**。即浏览行为保持既有"单条目失败跳过"语义，层 1b 只是把"本会产垃圾路径的条目"变成"被跳过的条目"（fail-closed、无新传播路径）；本文档不主张、也不需要浏览错误传播到 UI——若未来需要，属独立变更（须自带 catch 移除的显式改动与测试），不在本计划范围。SFTP 客户端零改动。

### 6. 涉及文件（写域）

- 生产：`sync/smb/SmbFileClient.kt`（listFiles 入口空 share 拒绝 + extractRelativePath 锚定重写）
- 测试：新增 `SmbFileClientRootScanTest.kt`（6 用例）；既有 `SyncRemoteScannerTest.kt` +1 接入锁定用例
- **不在本 lane 写域**（评审 #3）：`settings/SyncRelationEditViewModel.kt`（save 校验+协议化路径归一化）、`res/values/strings.xml`（`relation_invalid_remote_path`）、VM 测试文件 → 见「Lane4 交接项」

## BUG-011 getByPathPrefix 族 LIKE 通配符/大小写越界 → 兄弟目录密文误删

**严重度：High（数据丢失，deleteFolder 链）｜todo.md 测试投资项点名此族（INCONCLUSIVE-1）**

### 1. 现状与根因（已打开源码核对）

SQLite `LIKE` 默认语义：`_` 匹配任意单字符、`%` 匹配任意序列、**ASCII 大小写不敏感**。本族查询把 folderPath 直接拼进 LIKE 模式且无 ESCAPE，产生两类越界：

- **通配符孪生**：folder=`one_piece` → 模式 `one_piece/%` 匹配兄弟目录 `one-piece/x.mp4`（`_`→`-`）；
- **大小写孪生**：folder=`Movies` → 匹配 `movies/...`（LIKE 对 ASCII 折叠，而 `=` 是 BINARY）。

受害最重的链路（已逐行核对）：

`FileOperationsUseCase.deleteFolder`（`browser/FileOperationsUseCase.kt:378-424`）→ `deleteFolderContentsLocked`（:431-459）→ `videoRepository.getByPathPrefix(path)`（:435）→ 逐条 `deleteMediaByIdLocked`（:478 起，真删密文+缩略图+DB 行，不可恢复）。

**VideoItemDao.kt 中 8 处参数驱动 LIKE 前缀**（行号为当前源码实测）：

| 查询 | 位置 | LIKE 行 |
|---|---|---|
| `getByPathPrefix` | :91-99 | :96 `ELSE (relativePath = :folderPath OR relativePath LIKE :folderPath || '/%')` |
| `getByPathPrefixAndRelation` | :101-110 | :107 同上 |
| `getSubFolders` / `getSubFoldersOnce` | :12-31 / :33-52 | :28 / :49 `ELSE parentPath LIKE :folderPath || '/%'` |
| `getDeepParentPaths` | :116-123 | :120 `ELSE parentPath LIKE :folderPath || '/%/%'` |
| `searchByFileNameInFolder` | :161-176 | :173 `ELSE (v.parentPath = :folder OR v.parentPath LIKE :folder || '/%')` |
| `getParentPathsInFolder` | :184-191 | :188 同上模式 |
| `getRecursiveFolderStats` | :201-214 | :210 `ELSE relativePath LIKE :folderPath || '/%'` |
| `getChildVideoStats` | :235-250 | :245 `vi.parentPath LIKE :folderPath || '/%'` |

**PlaybackRecordDao.kt 中 3 处**（详见 BUG-012/020 章节）：`getByFolderFlow` :55、`updateCompletedByFolder` :65、`getVideoIdsWithoutRecord` :74。

> 报告未点名但同病的第 12 处：`getParentPathsInFolder`（:184-191）——本计划一并修复（与报告"7+ 个查询同病"表述相容）。

**一致性次生问题**：`getSubFolders` 的投影 `SUBSTR(parentPath, LENGTH(:folderPath) + 2)`（:15/:20）按 BINARY 长度切片，而 WHERE 用 LIKE 匹配——大小写孪生行命中后切片在错误偏移上执行，产出乱码段。前缀匹配统一为 BINARY 后两者自洽。

### 2. 修复方案：substr 二进制精确前缀比较（统一模式）

**核心谓词**（替换所有 `X LIKE :folderPath || '/%'`）：

```sql
substr(X, 1, length(:folderPath) + 1) = :folderPath || '/'
```

- BINARY 比较（video_item 各列无 COLLATE NOCASE 声明，`=` 默认 BINARY）→ 大小写敏感；
- 无元字符 → `%`/`_` 按字面匹配；
- `length`/`substr` 全在 SQL 侧（code point 语义），与 Kotlin 侧 UTF-16 无跨界混用（满足报告 Interesting #11 约束；既有查询 `SUBSTR(parentPath, LENGTH(:folderPath)+2)` 已是同款 SQL 内部自洽写法）。

**选型对比（为什么不 ESCAPE / PRAGMA / GLOB）**：
- `LIKE ... ESCAPE '\'` + 绑定侧转义（todo.md 提及的可选方向）：修通配符但**不修大小写折叠**，`Movies`→`movies` 越界仍在；
- `PRAGMA case_sensitive_like=ON`：连接级全局开关，会破坏 `searchByFileName` 刻意使用的 NOCASE 搜索，且随连接池行为不可控；
- `GLOB`：大小写敏感但自有元字符（`*?[`）需另一套转义；
- **substr 精确比较**：双缺陷一次修复、零元字符、在任何 SQLite 构建上确定（顺带解决 R1 INCONCLUSIVE-1"需真机决定性实验"——不再有 LIKE 参与路径前缀判定，JVM/Robolectric 测试即权威）。

**逐条 @Query 变更**（完整 SQL，直接可贴）：

`getByPathPrefix`（:92-98 WHERE 体）：
```sql
SELECT * FROM video_item
WHERE CASE
    WHEN :folderPath = '' THEN 1
    ELSE (relativePath = :folderPath
          OR substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/')
END
```

`getByPathPrefixAndRelation`（:102-109，同一替换，外加 `syncRelationId = :syncRelationId` 不变）：
```sql
WHERE syncRelationId = :syncRelationId
  AND CASE
      WHEN :folderPath = '' THEN 1
      ELSE (relativePath = :folderPath
            OR substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/')
  END
```

`getSubFolders` / `getSubFoldersOnce`（:26-29 / :47-50 的 WHERE；投影不动，空段过滤见 BUG-022）：
```sql
WHERE CASE
    WHEN :folderPath = '' THEN parentPath != ''
    ELSE substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
END
```

`getDeepParentPaths`（:118-121；语义等价替换：前缀精确 + 余段含 `/`，根分支常量模式无参数、保持原样）：
```sql
WHERE CASE
    WHEN :folderPath = '' THEN parentPath LIKE '%/%'
    ELSE (substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
          AND instr(substr(parentPath, length(:folderPath) + 2), '/') > 0)
END
```

`searchByFileNameInFolder`（:171-174；`fileName LIKE '%'||:query||'%' COLLATE NOCASE` 是刻意的用户搜索语义，**保持不动**，只改 folder 限定）：
```sql
AND CASE
    WHEN :folder = '' THEN 1
    ELSE (v.parentPath = :folder
          OR substr(v.parentPath, 1, length(:folder) + 1) = :folder || '/')
END
```

`getParentPathsInFolder`（:186-189）：
```sql
WHERE CASE
    WHEN :folder = '' THEN 1
    ELSE (parentPath = :folder
          OR substr(parentPath, 1, length(:folder) + 1) = :folder || '/')
END
```

`getRecursiveFolderStats`（:208-211）：
```sql
WHERE CASE
    WHEN :folderPath = '' THEN 1
    ELSE substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
END
```

`getChildVideoStats`（:245 一行，:246-247 的斜杠计数算术不动）：
```sql
ELSE (substr(vi.parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
      AND LENGTH(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2))
          - LENGTH(REPLACE(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2), '/', '')) = 0)
```

PlaybackRecordDao 三处（`getByFolderFlow` :55 的前缀行替换为 substr 形式，其余两查询与 BUG-012/020 合并重写，见对应章节）。

**性能权衡（需在评审中明示）**：`LIKE 'prefix%'` 可走 `parentPath`/`relativePath` 索引范围扫描，substr 等值比较不能 → 全表扫描。本项目个人规模（`MAX_REMOTE_FILES=10000` 封顶、video_item 数千行级）单查询扫描耗时可忽略；`getItemsInFolder` 的 parentPath 索引主路径不受影响。正确性（不误删密文）优先于该量级的索引收益。

### 3. TDD 测试计划

**测试文件：`app/src/test/java/cn/vectory/syncplayer/data/local/VideoItemDaoPathSemanticsTest.kt`**（新文件；`@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34], application = NoOpTestApp::class)` + `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()`——Robolectric 4.13 已在依赖（`app/build.gradle.kts:216`、`libs.versions.toml:26`），androidTest `VideoItemDaoTest` KDoc"无 Robolectric"注释已过时；沿用其 insert 辅助函数风格与 `runBlocking{...first()}` 断言风格）。

共享 fixture（`insert` 辅助同 `androidTest/.../VideoItemDaoTest.kt:52-75`）：

```
目标树（relation 1）：one_piece（自体行）、one_piece/a.mp4、one_piece/sub/b.mp4
通配符孪生：one-piece/x.mp4（parentPath "one-piece"）
大小写孪生：ONE_PIECE/x.mp4（parentPath "ONE_PIECE"）
```

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `getByPathPrefix excludes underscore wildcard twin` | 如上 fixture | `getByPathPrefix("one_piece")` | size==3；`none { it.relativePath.startsWith("one-piece") }` |
| 2 | `getByPathPrefix excludes ascii case twin` | 同 fixture | `getByPathPrefix("ONE_PIECE")` | 仅 `ONE_PIECE/x.mp4` 1 行 |
| 3 | `getByPathPrefix folder containing percent matches nothing` | 任意行 + folder=`"%"` | `getByPathPrefix("%")` | empty（旧 LIKE `%/%` 会命中所有含 `/` 行） |
| 4 | `getByPathPrefixAndRelation twins plus relation isolation` | fixture 复制到 relation 2 | `getByPathPrefixAndRelation("one_piece", 1)` | 3 行全 relationId==1 |
| 5 | `getSubFoldersOnce excludes case and wildcard twins` | parentPath：`one_piece/sub`、`one-piece/s`、`ONE_PIECE/s` | `getSubFoldersOnce("one_piece")` | `== listOf("sub")` |
| 6 | `getDeepParentPaths excludes twins` | parentPath：`one_piece/sub/deep`、`one-piece/sub/deep` | `getDeepParentPaths("one_piece")` | `== listOf("one_piece/sub/deep")` |
| 7 | `getRecursiveFolderStats counts descendants only and excludes twins` | 完整 fixture（relation 1，mediaType=VIDEO）：①自体行 relativePath=`one_piece`（parentPath=`""`）fileSize=1；②`one_piece/a.mp4`（parentPath=`one_piece`）fileSize=2；③`one_piece/sub/b.mp4`（parentPath=`one_piece/sub`）fileSize=4；④孪生 `one-piece/x.mp4`（parentPath=`one-piece`）fileSize=100；⑤孪生 `ONE_PIECE/x.mp4`（parentPath=`ONE_PIECE`）fileSize=100 | `getRecursiveFolderStats("one_piece")` | totalFileCount==**2**（仅②③；查询 :208-210 只有 `relativePath LIKE folder || '/%'` **后代分支，无自体等值分支**，①自体行不统计——原方案期望 3 错）；totalVideoCount==2、totalImageCount==0；totalSize==**6**（2+4，按后代行合计，孪生 100 不掺入） |
| 8 | `getChildVideoStats matches only direct child folder videos` | 完整 fixture（mediaType=VIDEO）：①当前层 `one_piece/a.mp4`（parentPath=`one_piece`）；②直接子文件夹 `one_piece/sub/b.mp4`（parentPath=`one_piece/sub`）；③更深层 `one_piece/sub/deep/c.mp4`（parentPath=`one_piece/sub/deep`）；④孪生 `one-piece/s/x.mp4`（parentPath=`one-piece/s`）；⑤孪生 `ONE_PIECE/s/y.mp4`（parentPath=`ONE_PIECE/s`） | `getChildVideoStats("one_piece")` | 期望 id 集 == {②}：查询 :244-247 要求 parentPath 为 `folder/单段`（余段斜杠计数==0），即**直接子文件夹内**媒体——①（parentPath 无 `/` 后缀段，不命中前缀）与③（余段含 `/`）均**排除**，④⑤孪生排除。原方案误把 ①（parentPath=`one_piece`）当期望命中——与本查询语义矛盾；**不为错误期望扩大生产查询范围** |
| 9 | `searchByFileNameInFolder scopes to exact folder tree` | `vacation/sub/H.mp4`（parentPath=`vacation/sub`）+ `Vacation/sub/H.mp4`（parentPath=`Vacation/sub`），fileName 均含 `H` | `searchByFileNameInFolder("H", "vacation").first()` | 1 行且 relativePath==`vacation/sub/H.mp4`。红灯机制（评审 v3 #3 修正）：嵌套层使 `'Vacation/sub/H.mp4' LIKE 'vacation/%'` 成立（ASCII 折叠 + `/` 字面前缀）→ 旧码误含 → 修复前红；**原方案直接子层 fixture（parentPath=`Vacation`，无 `/`）旧码即绿**——`Vacation` 既不 `=` `vacation`（BINARY）也不匹配 `vacation/%`（模式含字面 `/`，文本无 `/`）——不构成红灯，故改用嵌套 fixture |
| 10 | `getParentPathsInFolder excludes twins` | parentPath `vacation/a`、`Vacation/a` | `getParentPathsInFolder("vacation").first()` | `== listOf("vacation/a")` |
| 11 | `existing boundary behavior stays`（回归锁定） | androidTest 既有用例 `folder` vs `folderX.mp4` 场景移植 | `getByPathPrefix("folder")` | 不含 `folderX.mp4`（路径边界安全性不回退） |

PlaybackRecordDao/getByFolderFlow 用例放入 BUG-012 章节的测试文件。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.data.local.VideoItemDaoPathSemanticsTest'
# 期望：全绿。修复前红灯集：1/2/3/4/5/6/7/8/9/10 红——均为旧 LIKE **误含孪生行**致期望不符（评审 v3 #3 修正）：
# 7 的红=孪生 fileSize=100 被计入致 totalFileCount/totalSize 不符（①自体行在新旧谓词下均不统计，期望值本身即按后代 2 写，与红绿无关）；
# 8 的红=旧码误含④⑤（`_` 通配与 ASCII 折叠命中 `one_piece/%`）致 id 集不符——①当前层（parentPath 无 `/`）与③更深层（余段含 `/`）在旧码下同样不命中，非红灯来源；
# 9 的红=嵌套大写孪生命中 `vacation/%`；11 为绿（既有边界行为回归锁，非红灯证据）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin   # Room @Query 编译期校验通过
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest    # 全量回归（JVM 门禁）
# androidTest 说明（评审 #6）：testDebugUnitTest 只执行 src/test，不执行 src/androidTest——
# 既有 androidTest VideoItemDaoTest 属设备侧验证，如需复跑须另行 connectedDebugAndroidTest
# （需设备/模拟器，可选、非本 lane 验收条件）
```

### 5. 风险与守卫

- **卷归属 fail-closed（红线 2）**：谓词收紧只会**缩小** deleteFolder 的行集合——误删面单调收窄；`deleteMediaByIdLocked` 内 volumeId 守卫（:487-493）不动。
- **Database 约定**：纯 @Query 文本变更，schema 零变化 → 无迁移、DB version 不动（Room 仅在编译期重新校验 SQL）。
- **行为变化明示**：此前"碰巧"依赖大小写折叠的用法（如 folder=`movies` 期望命中 `Movies/`）会改为不命中——但同库内 `getItemsInFolder`（parentPath 精确等值）与 `VideoRepository.getDirectChildFolderHasSubFolders`（Kotlin `startsWith`，`VideoRepository.kt:59`）本就是 BINARY 语义，本修复让全库路径匹配语义统一为 BINARY，消除跨查询不一致。
- **CancellationException（红线 3）**：无新增 catch；DAO suspend 签名不变。

### 6. 涉及文件（写域）

- 生产：`data/local/dao/VideoItemDao.kt`（8 处 @Query 重写）；`data/local/dao/PlaybackRecordDao.kt`（3 处，与 BUG-012/020 合并——见下）
- 测试：新增 `data/local/VideoItemDaoPathSemanticsTest.kt`
- 合并策略：VideoItemDao.kt 同文件被 BUG-011（8 查询）、BUG-022（getSubFolders 投影过滤）、BUG-013（新增 updateAudioGain）触碰——一次提交内分层修改、互不重叠；PlaybackRecordDao.kt 被 BUG-011/012/020 触碰的 3 条查询合并为一次重写（substr + root CASE）。

## BUG-012 markFolderRead LIKE 泄漏 → 兄弟目录观看状态损坏

**严重度：Medium（用户数据损坏：兄弟目录凭空出现已读记录/被翻转）**

### 1. 现状与根因（已打开源码核对）

执行链（已逐行核对）：

`FileOperationsUseCase.markFolderRead`（`browser/FileOperationsUseCase.kt:351-368`）→ `PlaybackRepository.markFolderCompleted`（`data/repository/PlaybackRepository.kt:86-106`）→ 两条带毒查询：

**`PlaybackRecordDao.updateCompletedByFolder`（:62-68）**：
```kotlin
@Query("""
    UPDATE playback_record SET isCompleted = :completed
    WHERE videoItemId IN (
        SELECT id FROM video_item WHERE relativePath LIKE :folderPath || '/%'
    )
""")
suspend fun updateCompletedByFolder(folderPath: String, completed: Boolean)
```

**`getVideoIdsWithoutRecord`（:71-76）**：
```kotlin
@Query("""
    SELECT vi.id FROM video_item vi
    LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
    WHERE vi.relativePath LIKE :folderPath || '/%' AND pr.id IS NULL
""")
suspend fun getVideoIdsWithoutRecord(folderPath: String): List<Long>
```

`markFolderCompleted` 在 `withTransaction` 内先经 `getVideoIdsWithoutRecord` 给"无记录"的目标视频**创建** `isCompleted=completed` 的记录（:89-102），再 `updateCompletedByFolder` 批量置位（:104）。事务是对的，**谓词是错的**：

- folder=`one_piece`：`getVideoIdsWithoutRecord` 把孪生 `one-piece`、`ONE_PIECE` 下无记录的视频也返回 → 为**兄弟目录凭空创建已读记录**（用户视角：没看过的视频突然"已看"）；
- `updateCompletedByFolder` 再把兄弟目录**已有记录**的 isCompleted 强行翻转。

### 2. 修复方案（与 BUG-011 统一模式 + 与 BUG-020 合并重写）

两条查询一次重写（substr 精确前缀 + 补 root `''` CASE 分支，后者是 BUG-020 的修复）：

```kotlin
// Batch mark as completed (mark read) for all videos in a folder recursively
@Query("""
    UPDATE playback_record SET isCompleted = :completed
    WHERE videoItemId IN (
        SELECT id FROM video_item WHERE CASE
            WHEN :folderPath = '' THEN 1
            ELSE substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
        END
    )
""")
suspend fun updateCompletedByFolder(folderPath: String, completed: Boolean)

// Get video IDs in a folder that don't have playback records
@Query("""
    SELECT vi.id FROM video_item vi
    LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
    WHERE CASE
        WHEN :folderPath = '' THEN 1
        ELSE substr(vi.relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
    END
    AND pr.id IS NULL
""")
suspend fun getVideoIdsWithoutRecord(folderPath: String): List<Long>
```

`getByFolderFlow`（:50-59）的 :55 前缀行同模式替换（root 分支已存在，不动）：
```sql
ELSE (substr(vi.relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
      AND SUBSTR(vi.relativePath, LENGTH(:folderPath) + 2) NOT LIKE '%/%')
```
（`NOT LIKE '%/%'` 是常量模式、无参数注入，语义="余段不含 /"，保持。）

`PlaybackRepository.markFolderCompleted` 与 `FileOperationsUseCase.markFolderRead` **生产代码零改动**——它们的组合逻辑正确，毒在 DAO 谓词。

### 3. TDD 测试计划

**测试文件 1：`app/src/test/java/cn/vectory/syncplayer/data/local/PlaybackRecordDaoFolderSemanticsTest.kt`**（Robolectric + in-memory Room，同 BUG-011 骨架；需 `db.playbackRecordDao()` 与 `db.videoItemDao()` 联用）：

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `updateCompletedByFolder marks only exact folder descendants` | video 行：`one_piece/a.mp4`、`one-piece/x.mp4`、`ONE_PIECE/x.mp4`，各预插 isCompleted=false 的 playback_record | `updateCompletedByFolder("one_piece", true)` | 仅 `one_piece/a.mp4` 的 record isCompleted==true；两孪生仍 false（3 次 `getByVideoId` 断言） |
| 2 | `getVideoIdsWithoutRecord returns only exact folder items` | 同上 3 行、无任何 record | `getVideoIdsWithoutRecord("one_piece")` | `size==1` 且为 one_piece 行 id |
| 3 | `getByFolderFlow excludes case twins` | `vacation/a.mp4` 与 `Vacation/a.mp4` 各带 record | `getByFolderFlow("vacation").first()` | 1 条且 videoItemId==vacation 行 id |

**测试文件 2：`app/src/test/java/cn/vectory/syncplayer/data/local/PlaybackRepositoryMarkFolderCompletedTest.kt`**（真实 in-memory DB 构造 `PlaybackRepository(dao, db)`，覆盖组合语义；root 用例属 BUG-020）：

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `markFolderCompleted creates records only for exact folder` | 同 fixture 3 行、无 record | `markFolderCompleted("one_piece", true)` | `getByVideoId(one_piece id) != null && isCompleted`；两孪生 `getByVideoId == null`（未凭空创建） |
| 2 | `markFolderCompleted unsets only exact folder` | 3 行 record 全 isCompleted=true | `markFolderCompleted("one_piece", false)` | one_piece false；孪生仍 true |

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.data.local.PlaybackRecordDaoFolderSemanticsTest' \
  --tests 'cn.vectory.syncplayer.data.local.PlaybackRepositoryMarkFolderCompletedTest'
# 期望：全绿。修复前红灯集（评审 v3 #3 补全）：DAO 文件 1/2/3 号用例红——孪生被误含
# （#3 getByFolderFlow：`Vacation/a.mp4` 命中旧 `vacation/%` 模式，同为修复前红）；
# Repository 文件 1/2 号用例红——旧谓词为孪生凭空创建记录/翻转孪生状态
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin && \
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

### 5. 风险与守卫

- 观看状态是可恢复数据（非密文），严重度低于 BUG-011，但修复谓词同源——**一次重写同时收口两条红线相邻面**（误删面/误改面同缩）。
- CancellationException：无新增 catch；`withTransaction` 块结构不动。
- 断言只看 isCompleted 布尔与记录存在性（数量/枚举），不断言文案。

### 6. 涉及文件（写域）

- 生产：`data/local/dao/PlaybackRecordDao.kt`（3 条 @Query，与 BUG-011/020 合并重写，同一次提交）
- 测试：新增上述 2 个测试文件

## BUG-013 增益分析快照整行 @Upsert → 复活已删行 + 回滚并发列修改

**严重度：High（DB 数据完整性：幽灵行/列回滚）**

### 1. 现状与根因（已打开源码核对）

`GainAnalysisViewModel.kt`：

- **:104** `startAnalysis()` 把发现时的快照 `_discoveredFiles.value` 传入 `launchAnalysis(files, resetAll = true)`（:118-210）；**:112** `retryFailed()` 同样从 `_discoveredFiles.value` 过滤——两个入口都在用**发现时刻**的实体快照；
- **:160-162** 分析成功后整行写回：

```kotlin
val result = audioGainAnalyzer.analyze(MediaUris.mediaUri(video.id))
if (result != null) {
    videoRepository.upsert(
        video.copy(audioGainDb = result.gainDb)
    )
```

- **`VideoItemDao.kt:73-74`**：`@Upsert suspend fun upsert(item: VideoItemEntity): Long`，`VideoRepository.kt:70` 纯透传。

分析期间（并发 3 路、`Dispatchers.IO`、Semaphore(3)）该行可能被用户操作修改或删除（回收站清空/删除文件/同步 reconcile/打星），整行 upsert 造成两类损坏：

1. **复活已删行**：行已被 `deleteMediaRecord`（`VideoRepository.kt:83-88`，删 video_item + playback_record）删除后，upsert 以同 id 重新 INSERT——幽灵行的 `volumeId`/`iv` 指向已删密文，播放/分享/删除全部走错路径（iv 长度校验、卷归属校验都在，但行本身不该存在）；
2. **回滚并发列修改**：`video.copy(audioGainDb=…)` 保留快照里其余全部列——期间用户改的 `star`、同步更新的 `fileSize`/`remoteModifiedTime`/`dateSynced` 等被旧值覆盖。

### 2. 修复方案：单列 UPDATE（列级写，行删即 no-op）

**DAO（`VideoItemDao.kt`，紧邻既有 `updateStar`（:229-230）同款风格新增）**：

```kotlin
// Update loudness gain for a video item (single-column write: never resurrects a
// deleted row and never clobbers concurrent column updates).
@Query("UPDATE video_item SET audioGainDb = :audioGainDb WHERE id = :id")
suspend fun updateAudioGain(id: Long, audioGainDb: Float?)
```

**Repository（`VideoRepository.kt`，紧邻 `updateStar`（:130）新增）**：

```kotlin
suspend fun updateAudioGain(id: Long, audioGainDb: Float?) =
    videoItemDao.updateAudioGain(id, audioGainDb)
```

**ViewModel 调用点替换（`GainAnalysisViewModel.kt:160-162`：`videoRepository.upsert(video.copy(audioGainDb = result.gainDb))` → `videoRepository.updateAudioGain(video.id, result.gainDb)`）：移交 Lane4**（评审 #3——VM 层越权）。本 lane 仅交付上述 DAO+Repository API；调用点实现契约与测试要求见「Lane4 交接项」L4-2/L4-3。

- 参数取 `Float?`（列可空，`VideoItemEntity.kt:30`）：为将来"清除增益"留通道；调用点传非空 `result.gainDb`（Lane4 落地时遵循）。
- 行不存在 → `UPDATE ... WHERE id=:id` 影响 0 行，静默 no-op——**不复活**（语义由本 lane DAO 用例 2 锁定）。
- 只触 `audioGainDb` 一列——**不回滚**并发修改。
- `video` 快照仅继续用于 `id`/`fileName`（:153 active 列表显示）——只读，无害（Lane4 落地时维持）。

### 3. TDD 测试计划

**DAO 层用例（并入 `VideoItemDaoPathSemanticsTest.kt`）**：

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `updateAudioGain persists gain without touching other columns` | 插入行（star=3, fileSize=100, audioGainDb=null） | `updateAudioGain(id, 6f)` | `audioGainDb==6f && star==3 && fileSize==100L` |
| 2 | `updateAudioGain on deleted id does not resurrect row` | 插入行后 `deleteById(id)` | `updateAudioGain(id, 6f)` | `getAll().size == 0`（修复前语义等价物 `upsert` 会变 1） |
| 3 | `updateAudioGain accepts null clearing gain` | 插入行（audioGainDb=6f） | `updateAudioGain(id, null)` | `audioGainDb == null` |

**VM 层测试：移交 Lane4**（评审 #3/#5）——原测试骨架三处错误：`mockkStatic(MediaUris)`（MediaUris 是无 @JvmStatic 的 object，须 `mockkObject`）、`advanceUntilIdle()` 对硬编码 `Dispatchers.IO`（`GainAnalysisViewModel.kt:148`）不可靠、缺 `retryFailed()` 入口与并发交错用例。正确契约收录于「Lane4 交接项」L4-3（评审 v3 #1 拆为**类 A mock 调用契约** + **类 B 真实 Room 交错集成**两类：正确 mock、同步 analyzer 有界阻塞门控、双条件终态等待、真实调度器有界超时、两入口+两交错），本节不再保留可执行性存疑的骨架。

> 红灯证据说明（评审 #6 修正）：DAO 用例 1/2/3 针对新增 API，**不存在"修复前红"**——它们随 API 同一提交落地，属"新增即绿"的表征测试；**不得把编译失败当红灯证据**。本 BUG 的**行为性红灯**由 Lane4 调用点测试承担：`coVerify(exactly = 0) { videoRepository.upsert(any()) }` 在调用点替换前**可编译且红**（现状 VM 走 upsert），替换后转绿——红灯步骤=可编译但行为错误。

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.data.local.VideoItemDaoPathSemanticsTest'
# 期望：全绿（DAO 用例 1/2/3 为新增 API 的表征测试，随实现同提交落地；无红灯步骤，见上文红灯证据说明）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest   # 全量回归
# VM 测试（GainAnalysisViewModelUpdateTest）属 Lane4 交接项，出现在 Lane4 门禁而非此处
```

### 5. 风险与守卫

- 单列 UPDATE 与 `updateStar`（:229-230）同构——既有模式，无新范式。
- 复活行的下游防线（iv 校验 `FileOperationsUseCase.kt:255-257`、卷归属 :251-254）继续存在；本修复在源头消除幽灵行，防线只是纵深。
- CancellationException：VM 既有 :176-177/:201-202 CE-first catch 不动；新 DAO 调用在原 try 块内，行为一致。
- 无 schema 变化（新增 @Query 方法不影响 schema hash）→ 无迁移。
- **修复宣称边界（评审 #3）**：本 lane 仅交付 DAO/Repository 单列 API，调用点仍走整行 upsert——**不得据此宣称 BUG-013 端到端修复**；端到端修复的宣称时点 = Lane4 交接项（L4-2 调用点替换 + L4-3 VM 测试）全绿之后。

### 6. 涉及文件（写域）

- 生产：`data/local/dao/VideoItemDao.kt`（+updateAudioGain）、`data/repository/VideoRepository.kt`（+透传）
- 测试：DAO 用例并入 `VideoItemDaoPathSemanticsTest.kt`
- **不在本 lane 写域**（评审 #3）：`settings/GainAnalysisViewModel.kt`（:160-162 调用替换）、VM 测试 `GainAnalysisViewModelUpdateTest.kt` → 见「Lane4 交接项」
- 合并策略：VideoItemDao.kt 与 BUG-011/022 同文件不同区块（查询重写区 / 新增方法区），无冲突

## BUG-020 PlaybackRecordDao folder 查询缺 root '' 分支 → 根目录标记已读静默 no-op

**严重度：Low（latent——当前 UI 只以非空 relativePath 调用）**

### 1. 现状与根因（已打开源码核对）

`PlaybackRecordDao.kt:62-76` 两条查询无 root CASE：folderPath=`''` 时模式退化为 `LIKE '' || '/%'` = `LIKE '/%'`，而 video_item.relativePath 从不以 `/` 开头 → 恒空集。对照 VideoItemDao 家族的既有 root 处理（如 `VideoItemDao.kt:94-97` 的 `WHEN :folderPath = '' THEN 1`、:26-27 的 `parentPath != ''`），这两条是漏网之鱼。后果：对根目录"标记已读"时，`markFolderCompleted` 静默 no-op（不建 record、不置位）——不损坏数据但功能失效且无反馈。

同文件 `getByFolderFlow`（:50-59）**有** root 分支（:54 `NOT LIKE '%/%'`），佐证这是遗漏而非设计。

### 2. 修复方案

已并入 BUG-012 的两条重写 SQL（`WHEN :folderPath = '' THEN 1` 即全部行）——root 语义 = 全库标记，与 `FileOperationsUseCase.deleteFolderContentsLocked` 对 root 用 `getAll()`（:432-433）的既有约定一致。无额外生产改动。

### 3. TDD 测试计划（并入 BUG-012 的两个测试文件）

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `updateCompletedByFolder root marks all`（DAO 测试文件） | 根行 `a.mp4`、`sub/b.mp4` 各带 isCompleted=false record | `updateCompletedByFolder("", true)` | 2 条 record 全 true |
| 2 | `getVideoIdsWithoutRecord root returns all`（DAO 测试文件） | 同上 2 行、无 record | `getVideoIdsWithoutRecord("")` | size==2 |
| 3 | `markFolderCompleted root covers all items`（Repository 测试文件） | 同上 2 行、无 record | `markFolderCompleted("", true)` | 2 条新 record 且 isCompleted==true |

### 4. 验收矩阵

同 BUG-012 的命令（同两个测试类）；修复前用例 1/2/3 红（返回空集/0 行）。

### 5. 风险与守卫

- 行为变化明示：root 从"静默 no-op"变为"全库生效"。当前 UI 无 root 调用方（latent），变化方向对齐 VideoItemDao 家族语义。
- 无 schema 变化、无迁移。

### 6. 涉及文件（写域）

- 生产：无独立改动（并入 BUG-012 重写）
- 测试：并入 BUG-012 的 2 个测试文件——DAO 测试文件 **+2**（用例 1 `updateCompletedByFolder` root、用例 2 `getVideoIdsWithoutRecord` root），Repository 测试文件 **+1**（用例 3 `markFolderCompleted` root）（评审 v3 #3 计数修正，原"各 +1"有误）

## BUG-022 路径防御：双斜杠空段 → getSubFolders 产出空名文件夹条目

**严重度：Low（需空路径段输入；当前主要经 BUG-006 垃圾路径族可达——该源头在本计划 BUG-006 中被掐断）**

### 1. 现状与根因（已打开源码核对）

`VideoItemDao.kt:12-31`（`getSubFolders`；`getSubFoldersOnce` :33-52 同构）：

```sql
SELECT DISTINCT SUBSTR(
    CASE WHEN :folderPath = '' THEN parentPath
    ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2) END,
    1,
    INSTR(CASE ... END || '/', '/') - 1)
FROM video_item
WHERE ...
```

投影用 `SUBSTR/INSTR` 数学取"前缀后的第一段"。当 parentPath 在切割点后以 `/` 开头（空段）时 `INSTR` 命中位置 1，`SUBSTR(..., 1, 0)` 返回**空字符串**，成为子文件夹列表中的空名条目：

- folder=`"Movies"`、行 parentPath=`"Movies//S1"` → 空名（且永不匹配任何真实文件夹）；
- folder=`""`（root）、行 parentPath=`"//evil"` → 空名。

空段的进入通道（已核对）：
1. **BUG-006 垃圾相对路径族**（SMB `extractRelativePath`）——本计划 BUG-006 已在客户端双重掐断（listFiles 入口空 share 拒绝 + extractRelativePath 锚定抛错）；保存层折叠通道移交 Lane4；
2. **存量 DB 行**：修复上线前已入库的脏 parentPath/relativePath 仍在表里；
3. **SFTP 存量关系**：`SftpFileClient` 的相对路径构造（`SftpFileClient.kt:176` removePrefix 链）不折叠空段，存量 `a//b` 型 remotePath 关系仍可产出空段——入口归一化契约（协议分离：SFTP 保持锚定语义、仅折叠空段）已定义于「Lane4 交接项」L4-1，实现归 Lane4；本 lane 只做 DAO 端防御。

### 2. 修复方案（DAO 防御；入口归一化契约移交 Lane4）

**层 1 — 保存层归一化：契约移交 Lane4**（评审 #2/#3）。原方案复用 BUG-006 的 VM 内 `normalizeRemotePath` 做跨协议统一折叠（且 `.`→root、剥离前导 `/` 后持久化）——两点被否决：跨协议归一化改变 SFTP 绝对/相对锚定语义（评审 #2）；VM 层越权（评审 #3）。协议分离的规则表（SMB/SFTP 各自的空段/`.`/`..`/root 规则）与契约测试矩阵见「Lane4 交接项」L4-1；其中**空段折叠**（`a//b`→`a/b`）对两协议均为保锚定的等价变换，DAO 端防御在此之前的过渡期承担兜底。

**层 2 — DAO 防御**（`getSubFolders` / `getSubFoldersOnce` 投影外包一层过滤，消费任何来源的空段行）：

```kotlin
@Query("""
    SELECT DISTINCT folderName FROM (
        SELECT SUBSTR(
            CASE WHEN :folderPath = '' THEN parentPath
            ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2) END,
            1,
            INSTR(CASE WHEN :folderPath = '' THEN parentPath
                 ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2) END || '/', '/') - 1
        ) AS folderName
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath != ''
            ELSE substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
        END
    )
    WHERE folderName != ''
""")
fun getSubFolders(folderPath: String): Flow<List<String>>
```

`getSubFoldersOnce` 同构替换（suspend / List 返回不变）。内层 WHERE 即 BUG-011 的精确前缀谓词——两个 BUG 在这两个查询上合并为一次重写。

> 消费方 `VideoRepository.getDirectChildFolderHasSubFolders`（:51-68）对 deepParents 已有 `directChild.isNotEmpty()` Kotlin 侧过滤（:63），无需改动——DAO 层过滤是纵深而非必需。

### 3. TDD 测试计划（并入 `VideoItemDaoPathSemanticsTest.kt`）

| # | 方法 | Arrange | Act | Assert |
|---|---|---|---|---|
| 1 | `getSubFoldersOnce filters empty segment folder names at sub level` | 行：relativePath=`"Movies//S1/x.mp4"`（parentPath=`"Movies//S1"`）、relativePath=`"Movies/S2/y.mp4"`（parentPath=`"Movies/S2"`） | `getSubFoldersOnce("Movies")` | `== listOf("S2")`（修复前含 `""`） |
| 2 | `getSubFoldersOnce root filters empty names` | 行：parentPath=`"//evil"`（relativePath=`"//evil/x.mp4"`）、parentPath=`"a"` | `getSubFoldersOnce("")` | `== listOf("a")` |
| 3 | `getSubFolders flow variant filters empty names` | 同 1 | `getSubFolders("Movies").first()` | `== listOf("S2")` |

### 4. 验收矩阵

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.data.local.VideoItemDaoPathSemanticsTest'
# 期望：全绿（修复前 1/2/3 红——结果含 ""）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
```

### 5. 风险与守卫

- 存量脏行（`host/...` 垃圾前缀、`//` 空段）不会被本修复清除，只是不再以空名文件夹形态浮现——彻底清洗属 sync lane 的 reconcile/重扫逻辑，不在本 lane 写域（移交说明，见文末风险清单）。
- 无 schema 变化、无迁移；投影包装不改返回类型。

### 6. 涉及文件（写域）

- 生产：`data/local/dao/VideoItemDao.kt`（getSubFolders/getSubFoldersOnce 重写，与 BUG-011 的 WHERE 替换合并为同一版本）
- 测试：并入 `VideoItemDaoPathSemanticsTest.kt`（+3 用例）
- **不在本 lane 写域**（评审 #2/#3）：保存层归一化入口（`SyncRelationEditViewModel.kt`）→ 见「Lane4 交接项」L4-1

## 涉及文件清单（写域汇总，评审 #3 修订：VM 层全部移出）

**生产代码（4 文件）**：

| 文件 | BUG | 改动 |
|---|---|---|
| `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt` | 006 | `listFiles()` 入口空 share 拒绝（:176-177 后、:183 前）+ `extractRelativePath`（:325-334）锚定重写，均抛 `RemoteScanIncompleteException` |
| `app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt` | 011/022/013 | 8 处 @Query WHERE 重写 + getSubFolders/getSubFoldersOnce 投影过滤 + `updateAudioGain` 新增 |
| `app/src/main/java/cn/vectory/syncplayer/data/local/dao/PlaybackRecordDao.kt` | 011/012/020 | 3 处 @Query 重写（substr + root CASE） |
| `app/src/main/java/cn/vectory/syncplayer/data/repository/VideoRepository.kt` | 013 | +`updateAudioGain` 透传 |

**零改动确认**：`PlaybackRepository.kt`、`FileOperationsUseCase.kt`、`SyncRemoteScanner.kt`、`SyncEngine.kt`、`AppDatabase`、SFTP 客户端——逻辑正确，毒在谓词/写入粒度/客户端入口校验，均在上述文件内修复；schema 零变化，**无迁移、DB version 不动**。

**移出本 lane 写域 →「Lane4 交接项」**（评审 #2/#3）：
- `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt`（save 校验 + 协议化路径归一化）
- `app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt`（:160-162 调用替换）
- `app/src/main/res/values/strings.xml`（`relation_invalid_remote_path`）
- VM 层测试文件 ×3（均不在 Lane2 写域，评审 v3.1 #3 补全）：`SyncRelationEditViewModelRemotePathTest.kt`（L4-1 契约测试）、`GainAnalysisViewModelUpdateTest.kt`（L4-3 类 A，mock 调用契约）、`GainAnalysisConcurrencyTest.kt`（L4-3 类 B，真实 Room 交错集成）

**测试代码（4 新文件 + 1 既有文件新增用例）**：

| 文件 | 覆盖 |
|---|---|
| `app/src/test/java/cn/vectory/syncplayer/data/local/VideoItemDaoPathSemanticsTest.kt`（新） | 011（11 用例，#7/#8 期望已按查询语义修正）+ 022（3 用例）+ 013 DAO（3 用例） |
| `app/src/test/java/cn/vectory/syncplayer/data/local/PlaybackRecordDaoFolderSemanticsTest.kt`（新） | 012（3）+ 020（2） |
| `app/src/test/java/cn/vectory/syncplayer/data/local/PlaybackRepositoryMarkFolderCompletedTest.kt`（新） | 012（2）+ 020（1） |
| `app/src/test/java/cn/vectory/syncplayer/sync/smb/SmbFileClientRootScanTest.kt`（新） | 006 客户端（6：root 带文件/空枚举/仅空目录三红灯 + 正常 share 绿锁 + indexOf 越界/idx<0 兜底两红灯） |
| `app/src/test/java/cn/vectory/syncplayer/sync/SyncRemoteScannerTest.kt`（既有，+1 用例） | 006 scanner 接入锁定（SCAN_INCOMPLETE → 下载/删除集合均空） |

## Lane4 交接项（本 lane 只定义契约，实现归 Lane4；评审 #2/#3/#5）

> 背景：原方案把保存层校验/归一化与增益调用点替换写入本 lane 写域，评审判定 VM 层越权（#3）且跨协议归一化改变 SFTP 路径语义（#2）。以下为移交契约——Lane4 按此实施；本 lane 的 DAO/客户端交付物先行合入（L4-2/L4-3 依赖本 lane 的 `updateAudioGain` API）。

### L4-1 保存校验 + 协议化路径策略入口（`SyncRelationEditViewModel.kt`）

**路径策略契约（SMB 与 SFTP 分离，评审 #2）**——SFTP 客户端按原样解析路径（`remoteBasePath = remotePath.trim()`，`SftpFileClient.kt:106`；扫描递归 `sftp.ls(currentPath)`（:203）、浏览 `sftp.ls(path)`（:162）），前导 `/` 与裸 `.` 具备真实锚定语义，**不可持久化改变**：

| 规则 | SMB | SFTP |
|---|---|---|
| 前导 `/` | 剥离（`/media/movies`→`media/movies`；SMB 客户端模型为 share 根相对，无独立绝对语义） | **保留**——前导 `/` ⇔ 绝对路径；剥离会把绝对扫描翻转为服务器 cwd 相对，可能扫到另一个目录 |
| 空段折叠 `a//b`→`a/b` | 是（POSIX 等价） | 是（不改变锚定） |
| 非首 `.` 段折叠 `a/./b`→`a/b` | 是 | 是（段级等价） |
| 裸 `.` | 拒绝（等价 root，见下） | **拒绝（不得转 `/`）**——`.` 是 cwd 相对、随 cwd 漂移；转成 `/` 会把 cwd 相对翻转为绝对 root，扫错目录。产品约束：同步根须为明确稳定目录 |
| 裸 `/`（root） | **拒绝**（BUG-006 E8 洞；SMB 客户端层 1a 已 fail-closed，保存层提前拦截把失败从"同步期报错"提前为"保存期反馈"） | 保留（SFTP 客户端对 `/` 处理正确，`SftpFileClient.kt:176` removePrefix 产出干净相对路径；SFTP root 同步为既有合法功能） |
| `..` 段 | 拒绝 | 拒绝（**协议范围：两协议一致**；产品约束：同步根须为规范稳定路径——遍历段的有效根依赖服务器端解析结果，且破坏下游本地/远端前缀匹配不变量） |
| 含点名称（`shows/v2.0`、`My.Movies`） | `.` 仅**整段**特殊，段内点原样保留 | 同左 |

**实现要点**：`save()`（:273-278 isBlank 校验后）调用协议感知的 `normalizeRemotePath(raw, protocol): String?`（internal 顶层纯函数，可直测）；返回 null → `_error.value = context.getString(R.string.relation_invalid_remote_path)`（strings.xml +1，文案实现时定稿）+ return@launch；`:301` 改存归一化结果。import 增补 `cn.vectory.syncplayer.data.model.SyncProtocol`。

**selectedSource == null（协议未知）时的确定策略（评审 v3 #2，三选一明示）**：

- **选定：协议未知 → 不保存**（拒绝 + `relation_invalid_remote_path` 反馈，不落库）。理由：`_selectedSourceId` 查不到对应 source 本身是竞态/非法状态；SMB 层 1a 只拒空 share、**不能代协议选择**——若协议未知时按任何默认语义（如 SMB 剥前导 `/`）归一化并落库，会把 SFTP 绝对路径误持久化为相对路径；拒绝保存是唯一不依赖协议猜测的 fail-closed 终态，且用户可恢复（重试时 availableSources 已加载，按实际协议处理）。
- 备选（若 Lane4 实施时改选，须整条替换，不得与选定混用）：(a) 有界等待 availableSources 加载（`withTimeoutOrNull` + `first()`），超时仍未知 → 按上款拒绝；(c) 放行但**仅保存原始 `trim()` 值、绝不套用任何协议默认归一化**，并标注为保存校验例外——(c) 会持久化未归一化路径（BUG-022 空段入口重新打开），仅在前两者均不可行时考虑。
- **禁止**「不拦 + 默认协议归一化」：这正是评审指出的 SFTP 误剥前导 `/` 通道。

**契约测试**（新 `app/src/test/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModelRemotePathTest.kt`）：

- **纯函数逐输入期望表**（`normalizeRemotePath(raw, protocol)`，两列分别直测，评审 v3 #2 补全）：

| 输入 | SMB | SFTP |
|---|---|---|
| `/media/movies` | `"media/movies"` | `"/media/movies"`（保留前导 `/`） |
| `media/movies` | `"media/movies"` | `"media/movies"` |
| `./a` | `"a"` | `"a"`（相对锚定不变） |
| `.` | `null`（等价 root，拒） | `null`（cwd 相对，拒；**不转 `/`**） |
| `/` | `null`（root，拒） | `"/"`（合法 root，保留） |
| `shows/v2.0` | `"shows/v2.0"`（点非整段，原样） | `"shows/v2.0"` |
| `a/../b` | `null`（`..` 拒） | `null`（`..` 拒） |
| `a//b` | `"a/b"` | `"a/b"` |
| `a/./b` | `"a/b"` | `"a/b"` |
| `""` / `"  "` | `null` | `null` |
| `" /a//b/. "` | `"a/b"`（trim→剥前导→折叠空段/尾 `.` 段） | `"/a/b"`（trim→保前导 `/`→折叠空段/尾 `.` 段） |

- save() 行为用例：SMB root 拒绝（不落库+error 非空）、SFTP root 保留（`remotePath == "/"` 落库）、空段折叠落库、`..` 拒绝；
- **source 未加载用例**（评审 v3 #2 新增）：`_selectedSourceId` 指向的 source 不在 availableSources（selectedSource==null）→ `setRemotePath("/a/b")` + `save()` → **不落库**（`coVerify(exactly = 0) { syncRelationRepository.save(any()) }`）且 error 非空——验证 `/a/b` 不会被按 SMB 默认策略剥成 `a/b` 落库；
- **suspend 断言一律 `coVerify`**（评审 #6：`syncRelationRepository.save()` 为 suspend，原方案 `verify` 不适用）。

### L4-2 增益调用点替换单列 API（`GainAnalysisViewModel.kt:160-162`）

`videoRepository.upsert(video.copy(audioGainDb = result.gainDb))` → `videoRepository.updateAudioGain(video.id, result.gainDb)`（API 由本 lane 先行交付）。

- **TDD 红灯步骤（评审 #6）**：红态断言 `coVerify(exactly = 0) { videoRepository.upsert(any()) }`——调用点替换前**可编译且红**（现状 VM 走 upsert）；不得以"新 API 未编译"充当红灯。绿态：替换后追加 `coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }`。
- 快照仅继续用于 `id`/`fileName`（:153），只读无害。

### L4-3 VM 测试要求（评审 v3 #1：拆为两类——mock 调用契约 + 真实 Room 交错集成）

**A/B 共用规则①——双条件终态 + 真实时间有界等待（评审 v3.1 #1，A/B 同一契约）**：`GainAnalysisViewModel.kt:148` 的 `withContext(Dispatchers.IO)` **不因依赖被 mock 而消失**——类 A（mock 契约）与类 B（真实 Room）一律使用同一终态契约：等待目标 IDs（`completedIds`/`failedIds`）达预期 **且** `isAnalyzing == false`（:169 轮内即发布失败 ID、:207 finally 才清 `isAnalyzing`，两事件间有时间窗；期间 `retryFailed()` 被 :110 `if (_isAnalyzing.value) return` 拒绝——只等 IDs 必有竞态）。等待形式（A/B 相同）：

```kotlin
withContext(Dispatchers.Default) {                     // 真实调度器：真实 IO 线程的墙钟进度不进 runTest 虚拟时间
    withTimeoutOrNull(5_000) {
        combine(idsStateFlow, isAnalyzingStateFlow) { ids, analyzing ->
            analyzing || !ids.containsAll(expectedIds)
        }.first { !it }                                 // 双条件：目标 IDs 齐且 !isAnalyzing
    } ?: fail("analysis round did not settle in 5s")   // 超时必须断言失败，不得静默通过
}
```

- `retryFailed()` 调用（A/B 的 retry 链用例）必须发生在上述等待**成功之后**；
- **不得**把 `withTimeoutOrNull` 放在外层 `runTest` 测试调度器上（虚拟时钟不会推进真实 IO 阻塞）；**不得**依赖 `advanceUntilIdle()`（:148 硬编码 `Dispatchers.IO` 使空闲推断不可靠）。

**A/B 共用规则②——清理顺序（评审 v3.1 #2）**：`viewModelScope` **不会自动成为 `runTest` 的子任务**——测试结束不会替你取消/等待 VM 的分析作业；失败路径下先行 `unmockkAll()`/`resetMain()`/关库会与仍在写库的真实 IO 线程竞争（跨测试污染、关库后访问）。`@After` 统一顺序：

1. **取消/清理 VM 分析作业**（对 VM 调 `cancelAnalysis()`；注意该方法会**立即清除** `isAnalyzing`——该值不得作为作业已结束的证据）；
2. **释放门控**（`gate.countDown()`，finally 语义——让阻塞在 analyzer 内的 IO 线程得以返回；只取消不释放，步骤 3 会挂住）；
3. **有界等待本轮已启动的分析作业实际结束**——等待的是**作业完成**这一事实（已捕获的 job 引用 `join()`，配真实时间上限；实现手段由 Lane4 定：暴露作业引用/注入可观测 seam 等），**不是**轮询 `isAnalyzing`；
4. **还原全局环境**：`unmockkAll()`（还原 `mockkObject(MediaUris)` 全局 mock）→ `Dispatchers.resetMain()`；
5. （类 B）**关闭 in-memory DB**——最后执行。

**类 A — mock 调用契约测试**（新 `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisViewModelUpdateTest.kt`；`Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest`，mockk `VideoRepository`/`AudioGainAnalyzer`/Context(relaxed)/真实 `SavedStateHandle(mapOf("relationId" to 1L))`）：

- **mock 纠错**：`MediaUris.kt:6-13` 是**无 `@JvmStatic` 的 object** → 必须 `mockkObject(MediaUris)`（原案 `mockkStatic` 错误）；`analyze` 是**普通同步函数**（非 suspend，返回可空 `AudioGainResult`）——`every { audioGainAnalyzer.analyze(any()) } returns AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)`（三参构造）。
- **类 A 只验证调用契约**：断言 `coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }` + `coVerify(exactly = 0) { videoRepository.upsert(any()) }`。并发改列/删行的**数据效果**（不回滚/不复活）由类 B 证明——无状态 mock 替身证明不了数据效果。
- **两个入口都要测**：`startAnalysis()`（:104）与 `retryFailed()`（:112），含「失败→`retryFailed()`→成功」链：analyze 首次返回 null（进 `failedIds`，:169）→ **按共用规则①双条件等待成功后**调 retry → retry 返回值——验证 retry 入口同样走单列 API（评审 v3.1 #1：mock 不消除 :148 的 IO 切换，类 A 的 retry 链与类 B 同构地受 :169/:207 时间窗约束）。
- **红灯步骤**：红态断言 `coVerify(exactly = 0) { upsert }` 在调用点替换前**可编译且红**（现状 :160-162 走 upsert），替换后转绿——不得以编译失败当红灯。

**类 B — 真实 in-memory Room + Repository 交错集成测试**（新 `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisConcurrencyTest.kt`；Robolectric + `Room.inMemoryDatabaseBuilder` 构造真实 `VideoItemDao`/`VideoRepository`，仅 mock `AudioGainAnalyzer` 与 Context；`Dispatchers.setMain(UnconfinedTestDispatcher())`）——证明"单列写不回滚并发列修改、删行不复活"：

- **同步 analyzer 的确定性门控（评审 v3 #1(i)）**：`analyze` 在 `Dispatchers.IO` 线程池被**同步**调用，门控用**非协程有界阻塞原语**在同步调用内桥接（不得用 `coAnswers`/`CompletableDeferred`——非 suspend 函数无法挂起）：

  ```kotlin
  val gate = java.util.concurrent.CountDownLatch(1)
  every { audioGainAnalyzer.analyze(any()) } answers {
      check(gate.await(5, TimeUnit.SECONDS)) { "gate timeout" }  // 有界阻塞：卡死即测试失败，可见可诊断
      AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)
  }
  ```

  主侧在**真实 IO 线程上执行并发写**（`updateStar(id, 5)` / `deleteMediaRecord`，经真实 Repository 直调、不经 VM）完成后 `gate.countDown()` 放行。
- **顺序契约（评审 v3 #1(iii)）**：启动分析 → 等待 analyzer 已进入门控（可观测前置：`activeAnalysis` StateFlow 含目标文件名，:154 发布）→ 执行并发写 → `countDown()` 释放 → **按共用规则①双条件等待整轮结束** → 断言。「失败→retry→成功」用例中 `retryFailed()` 调用必须发生在等待成功**之后**。终态等待形式即共用规则①的 `combine`+`withTimeoutOrNull`（真实调度器+真实毫秒），不再单列类 B 版本。
- **必备用例**：
  1. **改列交错**：analyze 门控挂起中注入 `updateStar(id, 5)`（真实 DB）→ 释放 → 双条件满足后断言 `star == 5 && audioGainDb == 6f`（单列写不回滚）；
  2. **删行交错**：analyze 门控挂起中注入 `deleteMediaRecord`（真实 DB 删行）→ 释放 → 双条件满足后断言按 id 查询为 null（不复活）、`updateAudioGain` 影响 0 行静默 no-op、无异常逃逸（CE-first catch :176-177/:201-202 不受影响）；
  3. **失败→`retryFailed()`→成功**（真实 DB 版）：analyze 首次返回 null → 等双条件 → `retryFailed()` → 等双条件 → 断言 `audioGainDb` 已写、`failedIds` 已移除该 id（:136 retry 起点剔除待重试 id）。
- **清理**：按**共用规则②**的五步顺序执行（类 B 含步骤 5 关闭 in-memory DB）。评审 v3.1 #2 修正点：失败路径下先行还原/关库会与仍在写库的真实 IO 线程竞争——门控释放后必须**有界等待分析作业实际结束**（作业完成事实，非 `isAnalyzing` 值），再动全局 mock/Main，最后关库。

### L4-4 依赖顺序与集成门禁

1. **顺序**：本 lane（Lane2）全部合入 → Lane4 实施 L4-1/L4-2/L4-3（L4-2/L4-3 依赖本 lane 的 `updateAudioGain` API 已存在）。
2. **Lane4 门禁**：`compileDebugKotlin` + `testDebugUnitTest` 全量（含 `SyncRelationEditViewModelRemotePathTest`、`GainAnalysisViewModelUpdateTest`（类 A）、`GainAnalysisConcurrencyTest`（类 B，真实 Room 交错））。
3. **宣称边界**：BUG-013 的端到端修复（不复活/不回滚）在 L4-2+L4-3 全绿（含类 B 交错用例）后方可宣称；本 lane 仅交付 API 层语义（DAO 用例锁定）。

## 验收矩阵（汇总，评审 #3/#6 修订）

```bash
# 分 BUG 门禁（各章节内含具体期望；实施时先红后绿——红灯集合见各节，绿锁/接入锁定用例不计入红灯证据）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.data.local.VideoItemDaoPathSemanticsTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.data.local.PlaybackRecordDaoFolderSemanticsTest' --tests 'cn.vectory.syncplayer.data.local.PlaybackRepositoryMarkFolderCompletedTest'
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.sync.smb.SmbFileClientRootScanTest' --tests 'cn.vectory.syncplayer.sync.SyncRemoteScannerTest'
# 全量门禁（类型验证唯一来源 = compileDebugKotlin；全量回归含既有 SyncRemoteScannerTest E8 用例）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

- **JVM 门禁边界（评审 #6）**：`testDebugUnitTest` 只执行 `src/test`，**不含** `src/androidTest` 的任何设备测试；androidTest `VideoItemDaoTest` 如需验证须另行 `connectedDebugAndroidTest`（需设备/模拟器，可选、非验收条件）。
- VM 测试类 ×3（`SyncRelationEditViewModelRemotePathTest`、`GainAnalysisViewModelUpdateTest`、`GainAnalysisConcurrencyTest`）属 Lane4 交接项，出现在 Lane4 门禁（L4-4）而非本处。
- 与 `release.sh` 本地门禁（compileDebugKotlin + testDebugUnitTest）一致，无额外基建。

## 与 bug 报告不符之处（逐条，含行号修正）

1. **R2 BUG-011 行号偏移**：报告引 `getByPathPrefix` :92-99 / `getByPathPrefixAndRelation` :102-110；实测 `VideoItemDao.kt:91-99` / `:101-110`（KDoc 行差 1）。机制无出入。
2. **R2 BUG-012 行号**：报告引 `PlaybackRepository.markFolderCompleted` :86-105；实测 `:86-106`。
3. **R2 BUG-013 位置细化**：报告引 `GainAnalysisViewModel.kt:149-162`；实测整行 upsert 在 **:160-162**，快照来源是 `startAnalysis()` :104 与 `retryFailed()` :112（retryFailed 入口未被报告点名，同样持发现时快照）。
4. **家族成员补充**：`getParentPathsInFolder`（`VideoItemDao.kt:184-191`）同病但未被 R2 点名（报告称"7+"，相容）——本计划纳入修复。
5. **todo.md 修复方向取舍**：todo 提及"可用 `LIKE ... ESCAPE '\'` + 绑定侧转义修复"与"androidTest 真机决定性实验（INCONCLUSIVE-1）"。本计划改选 substr 二进制精确比较：ESCAPE 不修 ASCII 大小写折叠（`Movies`→`movies` 越界仍在）；substr 消除路径判定中的一切 LIKE → 语义在任何 SQLite 构建上确定，JVM/Robolectric 测试即权威，无需真机实验。
6. **过时注释**：androidTest `VideoItemDaoTest.kt:24-25` KDoc 称"项目无 Robolectric 依赖"——已过时（`app/build.gradle.kts:216` `testImplementation(libs.robolectric)`，4.13）。新 DAO 测试据此落在 JVM 侧。
7. **R1 报告 URL 细节**：报告描述根扫描 URL 为 `smb://host//`；实测 `listFiles` :178-182 构造确为 `smb://host//`（两斜杠），`connect` root 分支（:110）另用 `smb://host/`——两处构造不同，但机制结论（marker=`//` 命中 scheme 分隔符）完全成立。
8. **未独立核验证实项**：R2 BUG-020 称"当前 UI 只以非空 relativePath 调用 markFolderRead"——本计划未逐一追 UI 调用方，按 latent 处理（若存在 root 调用方，修复后行为从 no-op 变为全库生效，方向仍正确）。

## 报告未提及的强相关风险（只列不展开）

- **SFTP 空段残留**：存量 `a//b` 型 remotePath 的 SFTP 关系，其客户端相对路径构造（`SftpFileClient.kt:176`）不折叠空段——入口归一化契约已移交「Lane4 交接项」L4-1（协议分离：SFTP 保持前导 `/` 锚定语义，仅做保锚定的空段折叠），本 lane 仅 DAO 端防御。
- **存量脏行的收敛行为**：BUG-006 修复后的首次同步，会把中毒期间写入的垃圾路径本地行推导为 DELETE_LOCAL（经预览确认后删除 + 干净路径重下）——可收敛、源在远端，但用户会看到一批删除项。
- **锚定匹配与 URL 编码**：非 ASCII/特殊字符 share 名下，jcifs `child.url` 的编码形态若与原始构造串不一致，锚定 startsWith 会 fail-closed 抛 SCAN_INCOMPLETE（旧 indexOf 同样依赖该字符串形态，信任级不变，方向安全）。
- **substr 谓词放弃索引范围扫描**：路径前缀查询退化为全表扫描（个人规模可忽略；详见 BUG-011 风险节）。
- **相邻越界（非本 lane）**：R2 INCONCLUSIVE-3 `SyncRelationDao.findConflictingBasePaths` 把 checkPath 当 LIKE 模式——base path 含 `_`/`%` 时误报冲突；同一病根，移交对应 lane。
- **Room 子查询投影兼容性**：getSubFolders 包装层子查询 + DISTINCT 别名属常规 Room 能力，但需 compileDebugKotlin 编译期 SQL 校验兜底（门禁已含）。
