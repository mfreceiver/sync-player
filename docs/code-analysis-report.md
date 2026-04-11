# Sync Player 项目综合分析报告

> 分析日期：2026-04-11  
> 分析范围：`app/src/main/java/cn/vectory/syncplayer/` 全部源文件  
> 当前版本：9.4.0 (versionCode 49)

---

## 目录

1. [数据风险](#1-数据风险)
2. [性能瓶颈](#2-性能瓶颈)
3. [安全隐患](#3-安全隐患)
4. [架构与健壮性](#4-架构与健壮性)
5. [UI/交互层问题](#5-ui交互层问题)
6. [汇总与优先级建议](#6-汇总与优先级建议)

---

## 1. 数据风险

### 1.1 [严重] 破坏性数据库迁移 — 数据全量丢失

**位置**：`di/DatabaseModule.kt:32`

```kotlin
Room.databaseBuilder(...)
    .fallbackToDestructiveMigration()  // ← 升级时清除全部数据
    .build()
```

**问题**：当前 schema 版本为 9（AGENTS.md 记载 v6，已不一致），每次版本升级会**无条件清空全部本地数据库**，包括：
- 所有视频元数据（`video_item` 表）
- 所有播放记录和进度（`playback_record` 表）
- 所有同步日志（`sync_log` 表）
- 所有同步关系配置（`sync_relation` 表）
- 所有同步源配置（`sync_source` 表，密码在 EncryptedSharedPreferences 中不受影响）

**影响**：用户每次升级后需要重新配置同步源、重新同步全部文件、丢失全部播放进度。

**建议**：
- 编写 Room Migration 替代 destructive migration
- 将 `fallbackToDestructiveMigration()` 改为 `fallbackToDestructiveMigrationFrom(8)` 仅对极老版本回退
- 导出 schema 文件到 git 以支持自动迁移生成

---

### 1.2 [严重] Room Schema 文件缺失

**位置**：`app/build.gradle.kts:65`

```kotlin
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
```

配置了 schema 导出路径，但 `app/schemas/` 目录不存在（glob 未找到文件）。schema 文件未纳入版本控制，导致：
- 无法自动生成 Migration
- 无法验证迁移正确性
- 历史版本数据库结构不可追溯

**建议**：执行一次编译以生成 schema 文件，并提交到 git。

---

### 1.3 [高] 同步任务进程死亡丢失

**位置**：`sync/SyncEngine.kt:71-72`

```kotlin
@Volatile
var pendingTask: PendingSyncTask? = null
```

`PendingSyncTask` 仅存储在内存中。如果应用在 `setPendingTask()` 之后、`SyncService.onStartCommand()` 之前被系统杀死（OOM killer、电池优化等），任务数据永久丢失。由于 `SyncService` 使用 `START_NOT_STICKY`，系统不会重新创建服务。

**影响**：用户在预览页确认同步后，如果应用被杀，同步不会执行且无法自动恢复。

**建议**：将 `PendingSyncTask` 序列化到 Room 表或 DataStore 中，服务启动时检查并恢复。

---

### 1.4 [高] `File.renameTo()` 非原子操作

**位置**：`sync/SyncEngine.kt:363`

```kotlin
tmpFile.renameTo(finalFile)
```

`File.renameTo()` 在以下情况可能失败或产生不一致：
- 源和目标在不同文件系统（Android 内部存储通常不会，但不可假设）
- 目标文件已存在时行为不确定（代码虽在上一行 delete 了，但有窗口期）
- 返回 `false` 时不抛异常，代码未检查返回值

**影响**：下载完成的文件可能停留在 `.sync_tmp` 状态，用户无法播放。

**建议**：
```kotlin
if (!tmpFile.renameTo(finalFile)) {
    // fallback: copy + delete
    tmpFile.copyTo(finalFile)
    tmpFile.delete()
}
```

---

### 1.5 [中] deleteByRelativePath 缺少 syncRelationId 限定

**位置**：`data/local/dao/VideoItemDao.kt:65-66`

```kotlin
@Query("DELETE FROM video_item WHERE relativePath = :relativePath")
suspend fun deleteByRelativePath(relativePath: String)
```

此查询删除所有匹配 `relativePath` 的记录，不限定 `syncRelationId`。虽然 `localBasePath` 的唯一约束（`sync_relation` 表）使得两个不同关联不太可能产生相同 `relativePath`，但 `syncRelationId = 0`（手动放入的文件）可能与同步文件路径冲突。

**建议**：添加带 `syncRelationId` 参数的重载方法，并在 `SyncEngine.executeDelete()` 和 `SyncEngine.executeSync()` 删除阶段使用。

---

### 1.6 [中] 同步日志无上限增长

**位置**：`data/repository/SyncLogRepository.kt:24`

```kotlin
fun getRecentFlow(limit: Int = 200): Flow<List<SyncLogEntity>> = syncLogDao.getSince(0)
```

两个问题：
1. `limit` 参数被忽略，`getSince(0)` 返回全部日志
2. `clearOldLogs()` 方法存在但**从未在代码中调用**

每次同步会为每个文件插入一条日志（DOWNLOAD/DELETE/SKIP），对于数千个文件的同步关系，日志表会持续增长。

**建议**：
- 修复 `getRecentFlow` 使其正确使用 `LIMIT`
- 在 `SyncEngine.executeSync()` 完成后自动调用 `clearOldLogs()`

---

### 1.7 [中] 部分下载失败时同步标记为"成功"

**位置**：`sync/SyncEngine.kt:386-396` + `:448`

当并发下载中某个文件失败时：
1. 失败被记录到 sync_log（status = "FAILED"）
2. 但 `executeSync()` 最终仍然返回 `true`
3. `syncRelationRepository.updateLastSync()` 写入"成功：下载 X 个..."

用户看到的同步结果是"成功"，但实际可能有文件未下载。失败信息只在日志中，对用户不可见。

**建议**：统计实际失败数，在结果中体现，如"成功：下载 X 个，失败 Y 个"。如有失败，考虑返回 `false` 或增加部分成功的状态。

---

## 2. 性能瓶颈

### 2.1 [高] 远程文件全量递归扫描

**位置**：`sync/smb/SmbFileClient.kt:202-233`、`sync/sftp/SftpFileClient.kt:106-138`

```kotlin
private fun listFilesRecursive(..., result: MutableList<RemoteFile>) {
    // 递归遍历全部子目录，一次性加载到内存
    for (child in children) {
        if (child.isDirectory) listFilesRecursive(...)
        else result.add(...)
    }
}
```

对于包含数千个文件、深层嵌套的远程目录：
- **内存压力**：所有 `RemoteFile` 对象同时驻留内存
- **网络耗时**：SMB/SFTP 每次列举目录是一次网络往返，大量小目录会产生大量 RTT
- **无分页**：无法中断或分批处理
- **无缓存**：每次 dryRun 都完整扫描

**建议**：
- 对于 SMB，考虑使用 `SmbFile.listFiles()` 的过滤能力
- 增量同步：记录上次扫描时间戳，仅检查变更部分
- 分批处理：对大型目录分页获取

---

### 2.2 [高] 浏览时自动修复缩略图

**位置**：`browser/BrowserViewModel.kt:324-335`

```kotlin
private fun repairMissingThumbnails(videos: List<VideoItemEntity>) {
    val needsRepair = videos.filter { ... }
    viewModelScope.launch(Dispatchers.IO) {
        needsRepair.forEach { video ->
            videoMetadataExtractor.repairThumbnailIfNeeded(video)
        }
    }
}
```

每次打开文件夹都会：
1. 检查所有视频的缩略图是否存在
2. 对缺失的全部触发 `MediaMetadataExtractor` 提取

如果一个文件夹有 100 个视频且全部缺少缩略图，会在后台启动 100 次元数据提取。这会：
- 占用大量 CPU 和 I/O
- 导致明显的 UI 卡顿（即使 IO 调度器上运行，`stateIn` 的合并仍触发 Compose 重组）
- 每次浏览重复检查（无修复状态标记）

**建议**：
- 将修复操作限制为每文件夹最多 N 个
- 添加去重/防重入逻辑
- 将已检查过且无需修复的记录缓存

---

### 2.3 [中] ExoPlayer 播放位置轮询

**位置**：`player/PlayerScreen.kt:186-203`

```kotlin
LaunchedEffect(exoPlayer) {
    while (true) {
        val playerPos = exoPlayer.currentPosition.coerceAtLeast(0)
        // ... 更新 UI 状态
        delay(200L)
    }
}
```

使用 `while(true) + delay(200)` 主动轮询播放进度。每 200ms 触发一次状态更新和 Compose 重组。

**建议**：使用 ExoPlayer 的 `Player.Listener.onPositionDiscontinuity()` + `onPlaybackStateChanged()` 回调驱动更新，或使用 `ExoPlayer.createMessage()` 的定时回调。

---

### 2.4 [中] SMB/SFTP 客户端单例单连接

**位置**：`di/SyncModule.kt:19-35`

```kotlin
@Provides @Singleton
fun provideSmbClient(...): SmbFileClient { ... }

@Provides @Singleton
fun provideSftpClient(...): SftpFileClient { ... }
```

两个客户端都是 `@Singleton`，内部维护单个连接状态。`SyncEngine` 使用 `syncMutex` 确保同时只有一个同步操作，意味着：
- 多个同步关系必须串行执行
- 浏览远程目录（设置页测试连接）和同步不能并行
- 连接断开后无法自动重连

**建议**：
- 将客户端改为 `@ActivityRetainedScoped` 或使用工厂模式
- 在 `SyncEngine` 内部管理连接池
- 添加连接健康检查和自动重连逻辑

---

### 2.5 [中] 同步时逐文件提取元数据

**位置**：`sync/SyncEngine.kt:377-378`

```kotlin
val savedEntity = videoRepository.getByRelativePath(localRelPath)
if (savedEntity != null) {
    videoMetadataExtractor.extractAndSave(savedEntity)
}
```

每个文件下载完成后立即调用 `VideoMetadataExtractor`，涉及：
- `MediaMetadataRetriever` 创建和释放
- 缩略图 Bitmap 压缩写入磁盘
- 数据库 upsert

当并发下载数个文件时，元数据提取可能成为瓶颈。

**建议**：将元数据提取移到同步完成后的批处理阶段，或使用专用的工作队列。

---

### 2.6 [低] `combine` 重组触发频繁

**位置**：`browser/BrowserViewModel.kt:108-117`

```kotlin
val browseItems: StateFlow<List<BrowseItem>> = combine(
    effectiveFolder, _sortMode, _filter, playbackRecords, basePaths
) { ... }
```

每次 `playbackRecords` 或 `basePaths` 变化（由 Room Flow 驱动），都会触发完整的 `buildBrowseItems()` 计算，包括磁盘扫描和文件列表构建。

**建议**：对 `buildBrowseItems` 添加 debounce 或 diff 策略。

---

## 3. 安全隐患

### 3.1 [严重] SSH 主机密钥验证完全禁用

**位置**：`sync/sftp/SftpFileClient.kt:41`

```kotlin
client.addHostKeyVerifier(PromiscuousVerifier())
```

`PromiscuousVerifier` 接受任何主机密钥，包括自签名和伪造密钥。这使得 SFTP 连接完全暴露在中间人攻击（MITM）之下。攻击者可以：
- 拦截 SFTP 凭据（用户名/密码）
- 篡改传输中的文件
- 注入恶意文件

**建议**：
- 首次连接时保存主机密钥指纹到本地
- 后续连接验证密钥一致性
- 提供用户界面展示和确认指纹

---

### 3.2 [高] `android:allowBackup="true"` 泄露凭据

**位置**：`AndroidManifest.xml:13`

```xml
android:allowBackup="true"
```

启用备份后，通过 `adb backup` 可以提取：
- Room 数据库（包含同步源用户名、主机地址、端口）
- EncryptedSharedPreferences（虽然加密，但 master key 也在设备上）
- 应用全部内部文件

对于存储 SMB/SFTP 凭据的应用，这是不可接受的风险。

**建议**：改为 `android:allowBackup="false"`，或使用 `android:fullBackupContent` 排除敏感文件。

---

### 3.3 [中] 密码在内存中持久驻留

**位置**：`data/repository/SyncSourceRepository.kt:55-64`

```kotlin
private fun SyncSourceEntity.toDomain() = SyncSource(
    ...
    password = encryptedPrefs.getString("pw_$id", null) ?: "",
    ...
)
```

每次 `toDomain()` 调用都将密码加载到 `SyncSource` 数据对象中。由于 `SyncSourceRepository` 是 `@Singleton`，且 `getAllFlow()` 和 `getById()` 返回包含密码的对象，密码会长期驻留在：
- Flow 的 emission 对象中
- ViewModel 中缓存的列表中
- Compose 的状态中

**建议**：分离 `SyncSource` 的显示模型和凭据模型，仅在建立连接时加载密码。

---

### 3.4 [中] SMB 无传输加密保障

**位置**：`sync/smb/SmbFileClient.kt:57-58`

```kotlin
props.setProperty("jcifs.smb.client.minVersion", "SMB202")
props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
```

虽然支持 SMB 3.x（SMB3 支持端到端加密），但 jcifs-ng 客户端**未强制要求加密**。如果服务端不支持加密或降级到 SMB 2.x，凭据和数据以明文传输。

**建议**：添加 `jcifs.smb.client.encryptionRequired=true` 配置项，或至少在 UI 中提示用户网络环境的安全性。

---

### 3.5 [低] FileProvider 暴露 userdata 目录

**位置**：`app/src/main/res/xml/filepaths.xml:3`

```xml
<files-path name="userdata" path="userdata/" />
```

`userdata/` 目录通过 FileProvider 暴露。结合 `BrowserViewModel.buildShareIntent()` 的分享功能，任何在该目录下的文件都可以通过 URI 分享给其他应用。

**影响**：这是设计意图（分享视频文件），但如果 userdata 目录下意外存放了敏感文件，也会被暴露。

**建议**：当前可接受，但建议在分享逻辑中增加文件类型白名单校验。

---

## 4. 架构与健壮性

### 4.1 [高] PlayerScreen 中未管理的 CoroutineScope 泄漏

**位置**：`player/PlayerScreen.kt:111`

```kotlin
overlayHideJob.value = CoroutineScope(Dispatchers.Main).launch {
    delay(750L)
    currentOverlay = null
}
```

每次 `showOverlay()` 调用都创建一个新的 `CoroutineScope`，这个 scope：
- 不与 Composable 生命周期绑定
- 不会在 Composable 离开组合时自动取消
- `overlayHideJob` 中记录的 job 取消了旧 scope 的任务，但旧 scope 本身不会被回收（直到 GC）

**建议**：使用 `rememberCoroutineScope()` 替代手动创建 scope。

---

### 4.2 [高] 网络连接断开无重连机制

**位置**：`sync/SyncEngine.kt` 全文

整个 `SyncEngine` 没有连接健康检查或重连逻辑。在长时间同步大量文件时：
- SMB/SFTP 连接可能因超时、网络切换、服务器重启而断开
- 断开后整个同步失败，无法从中断点恢复
- 必须从头重新 dryRun + executeSync

**建议**：
- 在下载循环中添加连接活性检查
- 实现断点续传（记录已完成文件列表，重启时跳过）
- 对单个文件下载失败增加自动重试（2-3 次）

---

### 4.3 [中] 空异常捕获块过多

**位置**：全项目多处

```kotlin
// SmbFileClient.kt:152, 198, 231
catch (_: Exception) { }

// SftpFileClient.kt:60
catch (_: Exception) { }

// VideoMetadataExtractor.kt:65
catch (_: Exception) { }

// AudioGainAnalyzer.kt:52, 130
catch (_: Exception) { }
```

多处完全忽略异常，可能隐藏严重的运行时问题（文件系统损坏、编解码器错误、网络异常等）。

**建议**：至少记录 `Log.w(TAG, "...", e)` 以便调试时排查。对于关键操作（如元数据提取），应区分可恢复和不可恢复异常。

---

### 4.4 [中] GesturePlayerView 持有 Activity Context

**位置**：`player/GesturePlayerView.kt:13-16`

```kotlin
class GesturePlayerView @JvmOverloads constructor(
    context: Context,
    ...
) : FrameLayout(context, attrs) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
```

`GesturePlayerView` 通过构造函数直接持有 Activity Context（非 Application Context），且在 `PlayerScreen.kt:144` 中通过 `remember { GesturePlayerView(context) }` 创建。

虽然 `remember` 在 Composable 离开组合时会释放引用，但如果自定义 View 内部注册了任何系统回调（如 `AudioManager.OnAudioFocusChangeListener`），可能导致 Activity 泄漏。

**建议**：在 `GesturePlayerView` 中避免缓存 `audioManager`，改为每次使用时获取；或在 `onDetachedFromWindow()` 中清理引用。

---

### 4.5 [中] 同步引擎全局互斥锁限制扩展性

**位置**：`sync/SyncEngine.kt:65`

```kotlin
private val syncMutex = Mutex()
```

`dryRun()`、`executeSync()`、`dryRunDelete()`、`executeDelete()` 全部使用同一个 `syncMutex`。这意味着：
- 无法同时预览两个不同的同步关系
- 无法在同步进行中浏览远程文件
- 所有同步操作必须完全串行

**建议**：使用 per-relation 的锁（如 `MutableMap<Long, Mutex>`），允许不同关联并行操作。

---

### 4.6 [低] speedMultiplier 快照捕获问题

**位置**：`player/PlayerScreen.kt:144-149`

```kotlin
val gestureView = remember {
    GesturePlayerView(context).apply {
        this.speedMultiplier = speedMultiplier  // ← 仅初始化时设置一次
        getPlayerView().player = exoPlayer
    }
}
```

`speedMultiplier` 在 `remember` 块中仅设置一次。如果用户在播放器内修改长按加速倍速（通过设置页），此值不会更新。虽然当前场景中设置页不在播放器内，但这是一个潜在的状态同步问题。

**建议**：使用 `LaunchedEffect(speedMultiplier)` 或 `SideEffect` 更新 `gestureView.speedMultiplier`。

---

## 5. UI/交互层问题

### 5.1 [中] 视频ID列表通过URL传递

**位置**：`navigation/Route.kt:11-15`

```kotlin
fun create(videoItemId: Long, ..., videoIds: List<Long> = emptyList()): String {
    val idsParam = if (videoIds.isEmpty()) "" else videoIds.joinToString(",")
    return "player/$videoItemId?...&videoIds=$idsParam"
}
```

视频 ID 列表以逗号分隔字符串嵌入 URL。对于大型文件夹（数千个视频），URL 可能超过 NavHost 的内部限制（通常 Bundle 的 key-value 限制约 1MB，但 URL 长度建议不超过 2048 字符）。

**建议**：将 ID 列表存储在 ViewModel 或临时存储中，通过引用传递。

---

### 5.2 [中] 无磁盘空间预检查

**位置**：`sync/SyncEngine.kt:227-481`

`executeSync()` 在下载前不检查本地磁盘剩余空间。如果待下载文件总量超过可用空间：
- 下载过程中会因磁盘满而失败
- 已下载的 `.sync_tmp` 文件可能残留
- 部分文件已完成，部分失败，状态不一致

**建议**：在 `executeSync()` 开始前检查 `totalDownloadBytes` 与 `userdataRoot.usableSpace`，不足时提前警告用户。

---

### 5.3 [低] PlayerScreen 状态变量过多

**位置**：`player/PlayerScreen.kt:89-106`

```kotlin
var showControls by remember { mutableStateOf(true) }
var currentVideoId by remember { mutableStateOf(...) }
var isPlaying by remember { mutableStateOf(true) }
var isLongPressing by remember { mutableStateOf(false) }
var currentPosition by remember { mutableLongStateOf(0L) }
var currentDuration by remember { mutableLongStateOf(0L) }
var currentOverlay by remember { mutableStateOf<PlayerOverlay?>(null) }
// ... 共 13+ 个 remember 状态
```

`PlayerScreen` composable 内有 13+ 个独立 `remember` 状态。任何一个状态变化都可能导致不必要的重组。虽然部分使用了 `mutableLongStateOf` 等原生类型优化，但整体复杂度高。

**建议**：将播放器 UI 状态整合到一个 `data class` + `MutableStateFlow` 中，减少独立状态变量数量。

---

## 6. 汇总与优先级建议

### 🔴 紧急修复（数据安全/丢失风险）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1.1 | 破坏性数据库迁移 | `DatabaseModule.kt:32` | 升级后全量数据丢失 |
| 3.1 | SSH 主机密钥验证禁用 | `SftpFileClient.kt:41` | MITM 攻击风险 |
| 3.2 | allowBackup 泄露凭据 | `AndroidManifest.xml:13` | ADB 可提取敏感数据 |

### 🟠 高优先级（数据一致性/性能）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1.3 | 同步任务进程死亡丢失 | `SyncEngine.kt:71` | 同步可能静默失败 |
| 1.4 | File.renameTo 非原子 | `SyncEngine.kt:363` | 文件可能停留在临时状态 |
| 1.7 | 部分失败标记为成功 | `SyncEngine.kt:448` | 用户误认为同步完成 |
| 2.1 | 远程文件全量递归扫描 | `SmbFileClient`/`SftpFileClient` | OOM / 超长等待 |
| 2.2 | 浏览时批量修复缩略图 | `BrowserViewModel.kt:324` | CPU/IO 风暴 |
| 4.1 | CoroutineScope 泄漏 | `PlayerScreen.kt:111` | 内存泄漏 |
| 4.2 | 无网络重连机制 | `SyncEngine.kt` | 长同步易中断 |

### 🟡 中优先级（安全加固/优化）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1.2 | Room Schema 文件缺失 | `build.gradle.kts:65` | 无法生成迁移 |
| 1.5 | deleteByRelativePath 无关联限定 | `VideoItemDao.kt:65` | 可能误删 |
| 1.6 | 同步日志无上限 | `SyncLogRepository.kt:24` | 数据库膨胀 |
| 2.3 | ExoPlayer 轮询更新 | `PlayerScreen.kt:187` | 不必要重组 |
| 2.4 | SMB/SFTP 单例单连接 | `SyncModule.kt` | 串行瓶颈 |
| 3.3 | 密码内存驻留 | `SyncSourceRepository.kt:55` | 内存中可读密码 |
| 3.4 | SMB 无加密强制 | `SmbFileClient.kt:57` | 明文传输风险 |
| 4.3 | 空异常捕获 | 多处 | 隐藏运行时错误 |
| 4.5 | 全局同步互斥锁 | `SyncEngine.kt:65` | 无法并行操作 |
| 5.1 | URL 传递视频 ID 列表 | `Route.kt:11` | 大列表可能截断 |
| 5.2 | 无磁盘空间预检查 | `SyncEngine.kt` | 磁盘满后部分失败 |

### 🟢 低优先级（代码质量/优化）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 2.5 | 逐文件元数据提取 | `SyncEngine.kt:377` | 同步速度 |
| 2.6 | combine 重组频繁 | `BrowserViewModel.kt:108` | 列表性能 |
| 4.4 | Activity Context 泄漏风险 | `GesturePlayerView.kt:34` | 潜在泄漏 |
| 4.6 | speedMultiplier 快照 | `PlayerScreen.kt:147` | 状态不同步 |
| 5.3 | PlayerScreen 状态过多 | `PlayerScreen.kt:89` | 可维护性 |

---

## 附录：代码统计

| 模块 | 文件数 | 核心关注点 |
|------|--------|-----------|
| `data/` | 16 | Room DB、DAO、Repository、Entity、Model |
| `sync/` | 11 | 同步引擎、SMB/SFTP 客户端、服务 |
| `player/` | 3 | ExoPlayer、手势控制、播放器 UI |
| `browser/` | 4 | 文件浏览、搜索 |
| `settings/` | 13 | 设置页面（同步源/关联/播放/增益） |
| `navigation/` | 2 | 路由、NavHost |
| `di/` | 2 | Hilt 模块 |
| `audio/` | 1 | 音频增益分析 |
| `storage/` | 1 | 存储路径管理 |
