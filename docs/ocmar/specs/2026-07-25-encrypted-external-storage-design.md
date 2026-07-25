# 加密媒体存储与外置目录支持 — 设计文档

- **日期**: 2026-07-25
- **状态**: Draft（待评审）
- **作者**: orchestrator
- **适用项目**: sync-player (`cn.vectory.syncplayer`)
- **风险等级**: High（跨系统：同步引擎/播放器/Room schema/安全；安全敏感；破坏性 schema 迁移）

---

## 1. 背景与目标

当前媒体只能写入应用内部私有目录（`StoragePaths.userdataRoot = filesDir/userdata`），明文存储。用户认为：

1. 仅依赖内部目录**不可靠**（容量受限、设备更换难迁移）；
2. 希望**支持写入指定目录（含外置 SD 卡）**；
3. 希望**加密文件内容与文件名**。

本文档为该改造的设计方案，已经多轮需求对齐与方案选型后定型。

### 1.1 已确认的关键决策（来自需求对齐）

| # | 决策点 | 选定 | 理由 |
|---|---|---|---|
| D1 | 威胁模型 | **防丢失/被盗读取**（设备离手/锁屏后被 USB/文件管理器读取） | 决定密钥管理强度与性能取舍 |
| D2 | 加密格式互通性 | **App 自有格式**（不与 gocryptfs/Cryptomator 互通） | 避免 GPL 依赖、最简可控；个人使用无需 PC 解密 |
| D3 | 存量文件迁移 | **全新空库重同步** | 媒体本就是远端镜像缓存，可重建；规避 GB 级就地加密风险 |
| D4 | SAF DocumentsProvider 功能 | **移除**（不再向第三方暴露） | 减少明文泄露面与一个消费者 |
| D5 | 分享 | **透传解密**（不写 temp 明文） | 安全；由 MediaContentProvider 导出 URI + grantUriPermission |
| D6 | 被动文件导入 | **只走同步入库** | 新模型下被动扫描失效（见 §3.3） |
| D7 | 缩略图存储 | **加密文件于卷上** `thumbs/<id>.enc`（非 DB BLOB） | 避免内部存储 +300MB 与 DB 臃肿 |
| D8 | 文件名"加密"方式 | **opaque-id 命名**（磁盘按 `<id>.enc` 命名，真名只在 DB） | 比字面加密更严格（连结构/类型都不泄露），绕开长度/base64 问题 |

---

## 2. 威胁模型与边界

### 2.1 所防威胁
- SD 卡/设备被拔出，在另一台设备上经 USB / 文件管理器直接读取媒体文件。
- adb backup 抽取文件系统。

### 2.2 不所防（明确排除）
- 运行中已解锁设备被翻看（v1 密钥不绑定生物识别，App 锁仅 UI 级）。
- Root / 取证 / eMMC dump（软件或硬件 Keystore 密钥可被提取）。

### 2.3 关键推断
- **Room DB 不需加密**：DB 位于 `/data/data/.../files/`，应用私有、UID 隔离，USB/文件管理器在另一台设备上**读不到**。明文元数据放 DB 符合威胁等级。
- **媒体文件必须加密**：一旦放外置/SD 即直接暴露。
- **磁盘文件名必须不可识别**：否则经 USB 可见。

---

## 3. 架构总览

### 3.1 两个核心抽象

```
┌─────────────────────────────────────────────────────────┐
│  消费者层（统一改用 content:// URI）                       │
│  ExoPlayer · SeekPreviewExtractor · Coil · AudioGain     │
│  · FileOperationsUseCase(分享/删除)                      │
│         ↓                                                │
├─────────────────────────────────────────────────────────┤
│  MediaContentProvider（唯一解密网关）                     │
│   openFile() → StorageManager.openProxyFileDescriptor    │
│   → ProxyFileDescriptorCallback.onRead(offset,len)       │
│   → AES-CTR 按 offset/16 重算 counter，就地解密          │
├─────────────────────────────────────────────────────────┤
│  EncryptedMediaStore（编排加密 + 存储后端）               │
│   write/read/delete/list                                 │
├─────────────────────────────────────────────────────────┤
│  CryptoScheme        │  MediaVolume 抽象                 │
│  MasterKey/IV/CTR    │  ├─ FileVolume (java.io.File)     │
│  名称派生            │  └─ SafVolume (DocumentFile+tree) │
└─────────────────────────────────────────────────────────┘
```

**分层职责：**
- `MediaVolume`：哑存储后端，只认不透明字节与磁盘名字符串，**不知加密**。
- `CryptoScheme`：主密钥/每文件 IV → 内容加密与按 offset 解密；磁盘名由 opaque ID 派生，明文路径只在 DB 保存。
- `EncryptedMediaStore`：组合 `MediaVolume` + `CryptoScheme`，对上层提供 write/read/delete/list。
- `MediaContentProvider`：把"按字节范围就地解密"包装成可 seek 的 `ParcelFileDescriptor`（`ProxyFileDescriptorCallback`），让所有原生 API 透明读取密文。

### 3.2 真相源反转（重要）

| | 旧模型 | 新模型 |
|---|---|---|
| 文件系统内容 | 明文 + 明文名 | 密文 + 不透明 ID 名 |
| 真相源 | **文件系统**（DB 可重建） | **Room DB**（文件系统是哑密文负载） |
| 磁盘能否重建元数据 | 能 | **不能**（仅不可识别密文 blob） |

**由此导致的功能删除（见 §9）：** disk→DB 全量 reconcile、被动孤儿文件导入、手动拖文件入库——这些在密文 + ID 命名下失去意义。

**新引入风险：** DB 丢失 = 元数据全失且不可从密文重建。v1 缓解路径 = 清空卷 + 从远端全量重同步（D3）。v1 **不实现** DB 备份；主密钥设备绑定。跨机恢复（口令派生密钥）留作后续。

### 3.3 数据流

| 场景 | 流程 |
|---|---|
| **下载写入** | 远端流 → AES-CTR → 卷输出流（流式加密，明文仅在内存）→ 原子改名 `<id>.enc.tmp→<id>.enc` → 经解密网关提取元数据/缩略图 → DB upsert |
| **播放读取** | ExoPlayer `content://media/<id>` → Provider 代理 FD → 按 seek offset 就地解密 |
| **拖动预览** | `MediaMetadataRetriever.setDataSource(ctx, contentUri)` → 同一代理 FD，可取帧 |
| **缩略图** | 下载时经网关提取 → AES-GCM 加密（**每次随机 IV 存入文件头 `ver(1)‖iv(12)‖ct+tag`**）→ `thumbs/<id>.enc` 于卷 → Coil `content://thumb/<id>` |
| **增益分析** | `MediaExtractor.setDataSource(ctx, contentUri)` |
| **分享** | share intent 带 `content://media/<id>` + `FLAG_GRANT_READ_URI_PERMISSION`，目标 App 读取时透传解密 |

**核心原则：** DB 存明文（relativePath/fileName/parentPath，供搜索/排序/展示），磁盘存密文（内容 + 不透明名）。DB 不存绝对路径，改存 `volumeId`；v1 只支持一个活跃卷，切换卷需清空并重同步。

---

## 4. 存储配置与 Volume 抽象

### 4.1 MediaVolume 接口

```kotlin
interface MediaVolume {
    val volumeId: String
    suspend fun openRead(encryptedRelPath: String): SeekableReadHandle   // 可随机定位
    suspend fun openWrite(encryptedRelPath: String): OutputStream
    suspend fun list(encryptedDirRelPath: String): List<String>
    suspend fun delete(encryptedRelPath: String): Boolean
    suspend fun exists(encryptedRelPath: String): Boolean
    suspend fun mkdirs(encryptedDirRelPath: String)
    fun usableSpace(): Long
}
```

- **FileVolume**：`java.io.File`（内部默认）。`openRead`→`RandomAccessFile`；`usableSpace`→`File.usableSpace`。
- **SafVolume**：`DocumentFile` + 持久化 SAF tree URI。`openRead`→`contentResolver.openFileDescriptor`；**接入卷时须探测返回的 FD 是否真支持 `Os.lseek`（SAF 可能返回不可 seek 的 pipe/proxy FD），不可 seek 则拒绝该卷并提示**（核心随机读取强依赖 seek）。`rename` 须校验"目标已存在且源已消失"后才视为成功，不得假定原子、不得忽略返回值。`usableSpace`→`StorageManager` 匹配卷 + `StatFs`，取不到降级为 `Long.MAX_VALUE`。

### 4.2 存储位置配置

- 新偏好 `media_storage_tree_uri`(String?)：null=内部默认；非空=SAF tree URI（`takePersistableUriPermission` 跨重启保留）。
- ⚠️ **保留 SAF tree URI（选目录用）**；移除的是 DocumentsProvider（向外暴露）。
- 浏览/搜索/排序全走 Room（不遍历密文目录），SAF 仅在实际读写删时触碰——规避"SAF 大目录列表慢"。
- 空间检查改用 `volume.usableSpace()`（修 explorer 风险点 #9：原仅查内部存储）。

### 4.3 活跃卷（v1 单卷）

每文件记录 `volumeId`，但 **v1 仅支持一个"活跃卷"**（内部默认，或一个 SAF 目录）。读取时 Provider 校验 `entity.volumeId == 当前活跃卷 ID`，**不一致则报"该文件不在当前卷"（抛 `FileNotFoundException`）**——杜绝跨卷 ID 碰撞误读。**切换活跃卷 = 旧卷文件不可读**，需清空旧卷 + 全量重同步（与 D3 一致）。多卷并存（Registry）留作后续，不在 v1 承诺。

---

## 5. 加密核心

### 5.1 密钥管理

- **主密钥**：AES-256，生成时存入 Android Keystore（硬件支持则 StrongBox/HW-backed），仅 App 进程可取。
- **不绑定生物识别**（D1 威胁等级决定）——密钥无 `setUserAuthenticationRequired`，日常零摩擦。
- **子键派生**（可选，默认启用）：HKDF 从主密钥派生 `contentKey`（正文）、`thumbKey`（缩略图），廉价隔离。
- **每文件 IV**：64-bit 加密随机（配合 8 字节 counter，见 §5.2），存 DB。主密钥 + 每文件唯一 IV 的 CTR 用法在个人规模下碰撞概率可忽略，**不需每文件派生独立密钥**。

### 5.2 内容加密（AES-CTR，可 seek）

- 磁盘为**纯密文，无 header** → `磁盘偏移 == 明文偏移`，代理 FD 偏移数学干净。
- counter block = `IV(8 字节) ‖ counter(8 字节大端)`，`counter = byteOffset / 16`（long，**无 32 位截断/回绕、无实际上限**）。
- seek 到任意 offset 时重算 counter、无状态初始化 cipher → 天然线程安全（ExoPlayer 并发读）。
- 不带 AEAD 认证：D1 威胁等级下可接受（攻击者可破坏不可读；用户会发现花屏）。完整性留作后续。

### 5.3 文件名"加密" — opaque-id 命名（D8）

- 磁盘文件名 = `<videoId>.enc`，**扁平 + 分片**：`<volumeRoot>/<id/1000>/<id>.enc`。
- 真名 / 目录结构**只存 DB**，磁盘完全不出现。
- 比字面加密更严格（结构/类型不泄露），绕开 base64 膨胀撞 255 字符上限（FAT32/exFAT SD 卡）、扩展名泄露。
- 代价：磁盘目录对人不可读——符合 D2（只要 App 能读）。

### 5.4 缩略图加密

- AES-GCM（小文件、带认证、无 seek 需求），命名 `thumbs/<id>.enc`，与媒体同卷。
- **每次写入随机 12 字节 IV**，存入缩略图文件头，格式 `ver(1B) ‖ iv(12B) ‖ ciphertext+tag`；读取时先取 header 中的 iv 再解密。**杜绝 ID 复用导致的 GCM nonce reuse**（破坏性迁移/删除重插后 ID 可能复用，确定性 IV 在该场景不安全）。

### 5.5 代理 FD 解密器

```kotlin
class DecryptingFdCallback(
    volume: MediaVolume, encryptedRelPath: String,
    key: SecretKey, iv: ByteArray, fileSize: Long
) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = fileSize
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        // 从 floor(offset/16)*16 起按 16 字节块解密，切片填入 data
        // cipher 用 ThreadLocal 实例（并发安全）
    }
    override fun onRelease() { /* 关闭底层密文句柄 */ }
}
```

- `openProxyFileDescriptor`（API 27+，minSdk 34 OK）**必须用专用后台 `HandlerThread`** 承载 callback（**禁止主线程 Handler**，否则解密+底层 I/O 阻塞 UI 致 ANR）；单线序列化 onRead，ExoPlayer 单加载线程 v1 足够，后续可换 executor 并行。
- `Cipher` 用 `ThreadLocal`；首次 onRead/onGetSize 时 `volume.openRead` 打开密文句柄并缓存，onRelease 关闭。

---

## 6. MediaContentProvider — 解密网关

### 6.1 Authority 与 URI

- Authority：`cn.vectory.syncplayer.media`
- `content://cn.vectory.syncplayer.media/media/<id>` — 视频/图片正文（可 seek，AES-CTR 代理 FD）
- `content://cn.vectory.syncplayer.media/thumb/<id>` — 缩略图（AES-GCM 全量解密，pipe 返回）

### 6.2 方法

- `openFile(uri, mode)`：解析 id+类型 → Room 查实体（**实体缺失或 `entity.volumeId != 当前活跃卷 ID` → 抛 `FileNotFoundException`**，杜绝跨卷 ID 碰撞误读）→ `media` 走 `openProxyFileDescriptor(DecryptingFdCallback)`（后台 HandlerThread），`thumb` 读 header iv 后 AES-GCM 全量解密 + pipe。
- `getType(uri)`：**统一经 `ui.util.getMimeType(fileName)`** 返回准确 mime（`video/mp4` 等，非通配 `video/*`）。
- `query(uri, [OpenableColumns])`：返回 `DISPLAY_NAME = 真实 fileName`、`SIZE = fileSize`；**实体缺失返回空 Cursor 而非崩溃**。

### 6.3 安全（仿 FileProvider）

- Provider `exported="true"`、`grantUriPermission="true"`，受自定义权限 `cn.vectory.syncplayer.permission.READ_MEDIA`（`protectionLevel="signature"`）保护。
- 自身进程同签名 → 自动持有，ExoPlayer/Coil 直连无碍。
- 分享给第三方：share intent 带 `FLAG_GRANT_READ_URI_PERMISSION` → 系统按 URI 临时授权目标 App（绕过签名权限，标准机制）。非授权第三方探测 → 被签名权限挡住。

---

## 7. SyncEngine

### 7.1 流式下载即加密

**核心安全属性：App 不主动把明文写入外置/SD 卷；下载明文仅经过内存缓冲后进入加密流。系统级缓存、日志或内存转储不在本威胁模型内。**

1. 生成 **8 字节随机 IV**（内容 CTR）；`upsert` 占位 `VideoItemEntity`（`relativePath/fileName/syncRelationId/fileSize/mediaType/volumeId`，`iv` 占位）取返回 `id` → 确定磁盘路径 `<id/1000>/<id>.enc.tmp`。
2. 打开卷输出流到 `.enc.tmp`。
3. 远端流 → AES-CTR cipher → 卷输出流（顺序写，CTR 无需 seek）。
4. 原子改名 `<id>.enc.tmp → <id>.enc`。
5. 元数据/缩略图经 `content://media/<id>` 解密网关提取；缩略图 AES-GCM 写 `thumbs/<id>.enc`，每次随机 IV 写入文件头。
6. DB upsert 全部元数据。

**接口改动（小）：** `RemoteFileClient.downloadFile(remotePath, localFile)` → `downloadTo(remotePath, sink: OutputStream, onProgress)`。SMB/SFTP 客户端内部本是 `inputStream.copyTo(FileOutputStream)`，把 `FileOutputStream` 换成传入的加密 sink 即可。

### 7.2 其余改动

- **空间检查**：`mediaStore.volume.usableSpace()`；需要考虑并发下载总量与残留 `.enc.tmp`，SAF 无法取得空间时不得把 `Long.MAX_VALUE` 当作无限制安全保证。
- **删除**：按 `videoItemId` 删除 `media/<id>.enc`、`thumbs/<id>.enc`、播放记录和 DB 行；`DELETE_BOTH` 也必须走同一按 id 的本地删除路径后再处理远端删除。
- **reconcile 彻底删除**：移除 `dryRun` 物理文件检查（`SyncEngine.kt:224-228,250-265`）、`executeSync` 的 `toReconcile` 分支（`332-417`）、RECONCILE action 类型及相关 UI/日志统计；密文 opaque-id 磁盘无法按远端相对路径发现。
- **失败重试生命周期**：每次 attempt 关闭并丢弃当前加密 sink、删除 `.enc.tmp`、重新生成 IV、重新打开截断 sink，从 offset 0 重下；禁止把同一 CTR 流继续追加。仅成功 attempt 执行 finalize；异常、取消、短读和 `downloadTo=false` 均清理 tmp 与占位 DB 行。
- **断点续传**：v1 中断后从 offset 0 重下；CTR offset 恢复留作后续增强，不得把失败 attempt 的密文继续追加。
- **磁盘结构**：关系 `localBasePath` 不再建磁盘目录，仅作 DB 虚拟根（浏览用）；磁盘完全扁平分片 `<id/1000>/<id>.enc`。
- **并发下载**：tmp 命名含 uuid 避免碰撞。

---

## 8. DB schema v17 → v18

**按 D3（全新空库重同步）——迁移为破坏性清空媒体表 DROP+RECREATE（非逐行转换）。**

### 8.1 `VideoItemEntity` 改动

| 操作 | 列 | 原因 |
|---|---|---|
| 删 | `localAbsolutePath` | 绝对路径反模式（换卷全失效，explorer 风险点 #2） |
| 删 | `thumbnailPath` | 缩略图由 `id` 隐式派生 |
| 加 | `volumeId: String` | 标识文件在哪个卷 |
| 加 | `iv: ByteArray` | 8 字节 CTR nonce（Room 原生 BLOB） |
| 保留 | `relativePath`/`parentPath`/`fileName`（明文）/`mediaType`/`duration`/`width`/... | 浏览/搜索/排序/展示走 Room |

- **不加 `encryptedName`**：磁盘名由 `id` 确定性派生（`<id>.enc`）。
- **缩略图 IV 不入 DB**：每次写入随机 12B IV 存入文件头（`ver(1B)‖iv(12B)‖ciphertext+tag`），杜绝 ID 复用导致 GCM nonce reuse。
- 索引：保留 `(relativePath, syncRelationId)` 唯一、`(parentPath)`、`(syncRelationId)`；加 `(volumeId)`。
- 迁移 DDL 必须从 Room 生成的 `18.json` 逐字取（含 `AUTOINCREMENT`、列顺序、索引名和 FK），`playback_record` 须连带重建；不得手写猜测。
- `ByteArray` 字段保持正常 data-class 语义，若需自定义比较必须使用 `iv.contentEquals`，不得让 `equals` 与 `hashCode` 语义不一致。

### 8.2 其它

- `SyncRelationEntity.localBasePath`、`PendingSyncActionEntity.relativePath`：保留（虚拟路径语义不变）。
- DB 文件位置不变（`filesDir`，内部）。
- Room schema 导出更新至 v18（`app/schemas/`）。

---

## 9. 消费者改造清单

所有"直连文件系统"调用点统一改 `content://` URI：

| 消费者 | 现状 | 改为 |
|---|---|---|
| ExoPlayer `PlayerScreen.kt:131-134` | `MediaItem.fromUri(Uri.fromFile(File(localAbsolutePath)))` | `MediaItem.fromUri("content://.../media/<id>")` |
| SeekPreviewExtractor `:55,:169` | `retriever.setDataSource(filePath)` | `setDataSource(ctx, contentUri)` |
| Coil 缩略图 `Thumbnail.kt:36` | `AsyncImage(File(thumbnailPath))` | `AsyncImage("content://.../thumb/<id>")`（传 id） |
| AudioGainAnalyzer `:34` | `MediaExtractor.setDataSource(filePath)` | `setDataSource(ctx, contentUri)` |
| ImageViewerScreen `:293` | `AsyncImage(File(localAbsolutePath))` | `AsyncImage(content://media/<id>)` |
| Video/ImageMetadataExtractor | `setDataSource(localAbsolutePath)` | 经 `content://media/<id>` 解密提取 |
| FileOperationsUseCase 分享 `:127` | `FileProvider.getUriForFile(...)` | share intent + `content://media/<id>` + `FLAG_GRANT_READ_URI_PERMISSION` |
| FileOperationsUseCase 删除 `:112` | `File(userdataRoot, rel).delete()` | `mediaStore.delete(<id>.enc)+thumb+DB行` |
| `imageviewer/ImageViewerScreen.kt:293` | `AsyncImage(File(entity.localAbsolutePath))` | `AsyncImage(content://media/<id>)` |
| `PlayerViewModel.getVideoFilePath()` | 返回 `localAbsolutePath` | 删除旧 API，改按 `videoId` 构造 URI |
| `MetadataRebuildViewModel` | 遍历 `userdataRoot` 读取旧路径字段 | 删除物理扫描，按 DB 行经 `content://` 重建 |
| `GainAnalysisViewModel:112-130` | 按 `localAbsolutePath` 分析 | 改按 id/URI 调用分析器 |
| Grid/Search/DAO 投影链 | `thumbnailPath` 穿过 `VideoGridItem`、`FolderGridItem`、Search/Browser UI 和 DAO DTO | 全链改传 `videoItemId`，移除 `thumbnailPath` |

**删除项：**
- `SyncDocumentsProvider.kt` 整文件 + manifest 注册 + `saf_provider_enabled` 偏好 + 设置开关。
- `BrowserViewModel` 孤儿扫描/手动导入/物理目录遍历（`321-370`、`650-681` 行）。
- `SyncEngine` RECONCILE 全链（`dryRun:224-265`、`executeSync:332-417`）以及 `DELETE_BOTH` 中的物理路径访问（`745-750,802-828`）。
- `FileProvider` 的 manifest 注册与 `res/xml/filepaths.xml`（分享改用 `MediaContentProvider`）。
- `saf_provider_enabled` 的 repository、Flow、设置 UI 和测试全链。

**StoragePaths.kt 重塑：** 保留内部路径（DB、debug logs）；`userdataRoot`/`thumbnailsDir` 委托当前 `MediaVolume`。所有持有 `localAbsolutePath`/`thumbnailPath` 的调用点改用 `id`。

---

## 10. 设置 UI

- 新增"**媒体存储位置**"入口：显示当前位置（内部默认 / SAF 目录名）+ 可用空间；按钮拉 `ACTION_OPEN_DOCUMENT_TREE` 选目录并 `takePersistableUriPermission`。
- **v1 单活跃卷**：每文件记录 `volumeId`；读取校验 `volumeId == 活跃卷 ID`，切换活跃卷后旧卷文件不可读，需清空旧卷并全量重同步。多卷 Registry 留作后续。
- 移除"SAF 提供程序"开关。生物识别/安全锁设置不变（密钥不绑定生物识别）。
- 首次启用无需迁移（D3）：选位置 → 同步。

---

## 11. 验证策略

- **单元**：CryptoScheme 往返、**任意 offset 解密正确性**（seek 核心风险）、IV 唯一性；FileVolume 用 tmp 目录测、SafVolume 用 instrumented。
- **集成**：SyncEngine 下载即加密 → 经解密读回 → 字节与原文件一致；删除清理彻底。
- **Provider**：instrumented 测 `openFile` 读出明文、offset 读取匹配。
- **真机手测**（关键）：ExoPlayer 播加密视频 + seek + 拖动预览取帧 + 缩略图渲染 + 增益分析 + 分享到第三方播放器透传。
- **构建**：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` 与 `assembleDebug`；Room schema 导出 v18。
- **迁移**：v17→v18 破坏性清空媒体表（D3；个人侧载单用户）。

---

## 12. 不在本次范围 / 后续

- DB 加密（SQLCipher）——D1 威胁等级下不必要。
- DB 备份/导出 + 跨机恢复（口令派生密钥）。
- 断点续传 CTR offset 恢复。
- AEAD 完整性认证（当前 CTR 无认证）。
- 每文件独立密钥（HKDF + salt）。
- 密钥绑定生物识别（提升至 D1 之外的威胁等级时）。

---

## 13. 风险登记

| # | 风险 | 缓解 |
|---|---|---|
| R1 | SAF 卷返回不可 seek 的 pipe/proxy FD | 接入卷时探测 `Os.lseek`，不可 seek 则拒绝该卷；真机覆盖内部 + SD |
| R2 | DB 丢失 = 元数据全失不可重建 | 文档化；D3 重同步为恢复路径；后续 DB 备份 |
| R3 | AES-CTR 无认证，密文可被破坏 | D1 可接受；完整性留后续 |
| R4 | SAF tree URI 被用户在系统设置撤销 | 检测访问失败并提示重新授权；提供回退内部卷 |
| R5 | 破坏性 schema 迁移导致现有用户数据丢失 | D3 已接受；个人单用户；迁移前可提示 |
| R6 | 流式加密中断产生半加密 `.enc.tmp` | 原子改名前 `.enc` 不存在；启动时清理孤儿 `.tmp` |
| R7 | 代理 FD 后台单线瓶颈或误跑主线程 | 使用专用 HandlerThread，禁止主线程 Handler；v1 单线可接受 |
| R8 | SAF 不直接暴露可用空间 | `StorageManager` + `StatFs` 估算；不可得时降级提示 |
| R9 | GCM 缩略图 nonce reuse（ID 复用） | 每次写随机 IV 并存入缩略图文件头 |
| R10 | CTR 计数器截断或回绕 | 使用 8B IV + 8B long counter |
| R11 | 跨卷 ID 碰撞误读 | Provider 校验 `entity.volumeId == 活跃卷 ID` |
| R12 | 切换活跃卷后旧文件不可读 | v1 明确要求清空旧卷并全量重同步 |

---

## 14. 决策溯源（关键为什么）

- **为何 URI 网关而非各消费者自配解密**：`ProxyFileDescriptorCallback` 把"按范围解密"包成可 seek FD，让 ExoPlayer / MediaMetadataRetriever / MediaExtractor / Coil **全部吃标准 content:// URI**，把 6 处改造收敛为统一换 URI，并解决最棘手的拖动预览取帧。
- **为何 opaque-id 而非字面加密文件名**：更严格（结构/类型不泄露）、更简单（无 base64/长度/扩展名麻烦），符合 D2。
- **为何流式加密而非临时文件**：避免内部缓存 = 全片大小的压力；App 不主动把明文写入媒体卷。
- **为何缩略图不放 DB**：本次目标是把体量挪出内部存储；~300MB 进内部 DB 违背目标，且 DB 臃肿。
