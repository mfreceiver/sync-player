# 文件管理与同步功能改造方案

> 版本：v1.0 | 日期：2026-04-04
> 状态：待审阅

---

## 一、现状分析

### 1.1 当前架构概览

当前应用采用**单服务器 + 单同步源**的简单架构：

```
ServerConfig (单例 id=1)
  ├── protocol (SMB/SFTP)
  ├── host / port / username / password
  ├── remotePath (远程同步目录)
  └── lastSyncTime / lastSyncResult

SyncEngine (单例)
  ├── dryRun() → 读取唯一 ServerConfig → 比对远端 vs 本地
  ├── executeSync() → 下载 + 删除本地
  ├── dryRunDelete() → 比对喜爱列表 vs 远端
  └── executeDelete() → 双端删除

VideoItemEntity
  ├── relativePath (相对于 syncRootDir: context.filesDir/synced/)
  └── localAbsolutePath
```

**核心限制：**
- `ServerConfigEntity` 主键硬编码为 `id=1`，仅支持一个服务器配置
- `SyncEngine` 直接读取唯一 `ServerConfig`，同步根目录固定为 `filesDir/synced/`
- `VideoItemEntity.relativePath` 相对于唯一同步根，无多源隔离
- 浏览器无文件夹创建/删除能力
- 侧边栏只有「服务器配置」和「文件同步」两个入口

### 1.2 涉及文件清单

| 层级 | 文件 | 改动程度 |
|------|------|----------|
| 数据模型 | `ServerConfigEntity.kt`, `ServerConfigDao.kt`, `ServerConfigRepository.kt`, `ServerConfig.kt`, `AppDatabase.kt` | **重写** |
| 数据模型 | `VideoItemEntity.kt`, `VideoItemDao.kt`, `VideoRepository.kt` | **修改** |
| 同步引擎 | `SyncEngine.kt`, `SyncPreviewViewModel.kt`, `DeletePreviewViewModel.kt` | **重写** |
| 同步客户端 | `RemoteFileClient.kt`, `SmbFileClient.kt`, `SftpFileClient.kt` | **修改**（新增浏览/创建目录接口） |
| 浏览器 | `BrowserScreen.kt`, `BrowserViewModel.kt`, `BrowseItem.kt` | **修改** |
| 设置 | `ServerConfigScreen.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt` | **重写/拆分** |
| 导航 | `AppNavHost.kt`, `Route.kt` | **修改** |
| 新增 | `SyncSource*`, `SyncRelation*`, `SyncSourceScreen`, `SyncRelationScreen` 等 | **新增** |

---

## 二、目标架构

### 2.1 两层架构总览

```
┌───────────────────────────────────────────────────────────┐
│                     侧边栏 (Drawer)                       │
│                                                           │
│   📡 同步源管理     ──→  同步源列表页 (CRUD)               │
│   🔗 同步关系管理   ──→  同步关系列表页 (CRUD + 同步/删除)  │
│   ⚙ 播放设置                                            │
│   ℹ 关于                                                │
└───────────────────────────────────────────────────────────┘

同步源 (SyncSource)           同步关系 (SyncRelation)
┌────────────────────┐      ┌─────────────────────────┐
│ id: Long (PK)      │      │ id: Long (PK)            │
│ name: String       │      │ name: String (友好名称)   │
│ protocol: SMB/SFTP │◄────│ syncSourceId: Long (FK)  │
│ host: String       │      │ remotePath: String       │
│ port: Int          │      │ localBasePath: String    │
│ username: String   │      │ lastSyncTime: DateTime?  │
│ encryptedPassword  │      │ lastSyncResult: String?  │
└────────────────────┘      └─────────────────────────┘
                                     │
                                     ▼
                           本地文件系统
                           filesDir/
                           ├── 动作片/        ← 基目录 (带☁️标识)
                           │   ├── a.mp4
                           │   └── b.mkv
                           ├── 纪录片/        ← 基目录 (带☁️标识)
                           │   ├── c.mp4
                           │   └── 子目录/
                           │       └── d.mp4
                           └── 普通文件夹/    ← 非基目录 (无☁️标识)
                               └── ...
```

### 2.2 核心设计原则

1. **同步源只管连接**：协议 + 认证信息，不含目录路径。可配置 0 到多个。
2. **同步关系绑定路径对**：一个同步源 + 一个远端目录 + 一个本地基目录 = 一条同步关系。每条有友好名称。
3. **基目录互不嵌套**：创建同步关系时校验本地基目录不能是已有基目录的祖先或后代。
4. **删除保护**：同步源被同步关系引用时禁止删除；需先删除所有关联关系。
5. **云朵标识精确**：仅基目录文件夹右下角显示 ☁️，其子内容不加标识。

---

## 三、数据模型变更

### 3.1 新增实体：SyncSourceEntity

```kotlin
@Entity(tableName = "sync_source")
data class SyncSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                    // 用户命名，如 "家庭NAS"、"公司服务器"
    val protocol: String,                // "SMB" | "SFTP"
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPassword: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

**对比旧模型**：从 `ServerConfigEntity` 移除 `remotePath`、`lastSyncTime`、`lastSyncResult`，主键改为自增（不再硬编码 id=1），新增 `name` 字段。

### 3.2 新增实体：SyncRelationEntity

```kotlin
@Entity(
    tableName = "sync_relation",
    foreignKeys = [
        ForeignKey(
            entity = SyncSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["syncSourceId"],
            onDelete = ForeignKey.RESTRICT  // 阻止有引用时删除源
        )
    ],
    indices = [
        Index(value = ["syncSourceId"]),
        Index(value = ["localBasePath"], unique = true)  // 基目录唯一
    ]
)
data class SyncRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                     // 友好名称，如 "电影同步"、"纪录片同步"
    val syncSourceId: Long,               // FK → SyncSourceEntity
    val remotePath: String,               // 远端目录路径
    val localBasePath: String,            // 本地基目录（相对于 app filesDir）
    val lastSyncTime: Long? = null,
    val lastSyncResult: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

### 3.3 修改实体：VideoItemEntity

```kotlin
@Entity(
    tableName = "video_item",
    indices = [
        Index(value = ["relativePath", "syncRelationId"], unique = true)  // 改为联合唯一
    ]
)
data class VideoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncRelationId: Long,             // 新增：所属同步关系
    val relativePath: String,             // 相对于 localBasePath
    val fileName: String,
    val localAbsolutePath: String,
    val fileSize: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val thumbnailPath: String? = null,
    val dateSynced: Long = System.currentTimeMillis(),
    val remoteModifiedTime: Long? = null
)
```

**关键变更**：新增 `syncRelationId` 字段，`relativePath` 的含义从「相对于全局 sync root」变为「相对于该同步关系的 localBasePath」。

### 3.4 数据库迁移策略

```kotlin
// AppDatabase version: 3 → 4
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 创建 sync_source 表
        db.execSQL("""
            CREATE TABLE sync_source (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                protocol TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT NOT NULL,
                encryptedPassword TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """)

        // 2. 创建 sync_relation 表
        db.execSQL("""
            CREATE TABLE sync_relation (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                syncSourceId INTEGER NOT NULL,
                remotePath TEXT NOT NULL,
                localBasePath TEXT NOT NULL,
                lastSyncTime INTEGER,
                lastSyncResult TEXT,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY (syncSourceId) REFERENCES sync_source(id) ON DELETE RESTRICT
            )
        """)
        db.execSQL("CREATE INDEX index_sync_relation_syncSourceId ON sync_relation(syncSourceId)")
        db.execSQL("CREATE UNIQUE INDEX index_sync_relation_localBasePath ON sync_relation(localBasePath)")

        // 3. 迁移旧数据：将 server_config 数据迁移到新表
        // 插入 sync_source（如果旧 server_config 存在）
        db.execSQL("""
            INSERT INTO sync_source (name, protocol, host, port, username, encryptedPassword, createdAt)
            SELECT '默认服务器', protocol, host, port, username, encryptedPassword, strftime('%s','now')*1000
            FROM server_config WHERE id = 1
        """)

        // 插入 sync_relation（如果旧 server_config 存在且有 remotePath）
        db.execSQL("""
            INSERT INTO sync_relation (name, syncSourceId, remotePath, localBasePath, lastSyncTime, lastSyncResult, createdAt)
            SELECT '默认同步', 
                   (SELECT id FROM sync_source LIMIT 1),
                   remotePath,
                   'synced',
                   lastSyncTime,
                   lastSyncResult,
                   strftime('%s','now')*1000
            FROM server_config WHERE id = 1 AND remotePath IS NOT NULL AND remotePath != ''
        """)

        // 4. 为 video_item 添加 syncRelationId 列
        db.execSQL("ALTER TABLE video_item ADD COLUMN syncRelationId INTEGER NOT NULL DEFAULT 0")
        // 将已有的 video_item 关联到迁移后的同步关系
        db.execSQL("""
            UPDATE video_item SET syncRelationId = (
                SELECT id FROM sync_relation LIMIT 1
            ) WHERE syncRelationId = 0 AND EXISTS (SELECT 1 FROM sync_relation)
        """)

        // 5. 重建索引（联合唯一索引）
        db.execSQL("DROP INDEX IF EXISTS index_video_item_relativePath")
        db.execSQL("CREATE UNIQUE INDEX index_video_item_relativePath_syncRelationId ON video_item(relativePath, syncRelationId)")

        // 6. 删除旧表
        db.execSQL("DROP TABLE server_config")
    }
}
```

---

## 四、同步源管理

### 4.1 数据层

**新增 DAO：SyncSourceDao**

```kotlin
@Dao
interface SyncSourceDao {
    @Query("SELECT * FROM sync_source ORDER BY name ASC")
    fun getAllFlow(): Flow<List<SyncSourceEntity>>

    @Query("SELECT * FROM sync_source ORDER BY name ASC")
    suspend fun getAll(): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_source WHERE id = :id")
    suspend fun getById(id: Long): SyncSourceEntity?

    @Query("SELECT COUNT(*) FROM sync_relation WHERE syncSourceId = :sourceId")
    suspend fun getRelationCount(sourceId: Long): Int

    @Upsert
    suspend fun upsert(source: SyncSourceEntity): Long

    @Query("DELETE FROM sync_source WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

**新增 Repository：SyncSourceRepository**

提供 `getConfig()` 风格的领域模型转换（密码解密），并封装删除前的引用检查逻辑。

### 4.2 UI — 同步源列表页 (SyncSourceListScreen)

```
┌─────────────────────────────────┐
│ ← 同步源管理            ＋ 新增  │
├─────────────────────────────────┤
│                                 │
│ ┌─────────────────────────────┐ │
│ │ 📡 家庭NAS                  │ │
│ │    SMB · 192.168.1.100:445  │ │
│ │    2 个同步关系              │ │
│ │                  编辑  删除  │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ 📡 公司服务器                │ │
│ │    SFTP · 10.0.0.5:22      │ │
│ │    0 个同步关系              │ │
│ │                  编辑  删除  │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ 尚未配置同步源               │ │
│ │ 点击右上角 + 新增同步源      │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**交互规则**：
- 点击卡片 → 编辑同步源（进入 SyncSourceEditScreen）
- 点击删除 → 检查 `getRelationCount()`
  - 存在关联关系 → 提示 "该同步源仍有 N 个同步关系，请先删除所有同步关系"
  - 无关联关系 → 确认弹窗 → 删除
- 点击 + → 进入新增页

### 4.3 UI — 同步源编辑页 (SyncSourceEditScreen)

从现有 `ServerConfigScreen` 演化，核心变更：

| 变更点 | 旧 | 新 |
|--------|----|----|
| 表单字段 | 含 remotePath | 不含 remotePath（remotePath 在同步关系里配置） |
| 名称字段 | 无 | 新增 name 输入框 |
| 保存逻辑 | upsert 到单例行 | upsert 到多行表 |
| 浏览远端 | 无 | 新增"浏览远端目录"按钮（连接成功后可选目录，用于后续创建同步关系） |

### 4.4 RemoteFileClient 接口扩展

```kotlin
interface RemoteFileClient {
    // 已有接口保持不变
    suspend fun connect(config: SyncSourceConfig)  // 改为接收 SyncSource 模型
    suspend fun disconnect()
    suspend fun testConnection(config: SyncSourceConfig): Boolean
    suspend fun listFiles(path: String): List<RemoteFile>
    suspend fun downloadFile(...)
    suspend fun deleteFile(remotePath: String): Boolean

    // 新增：目录浏览（供同步关系选择远端目录）
    suspend fun listDirectories(path: String): List<RemoteFile>  // 仅返回目录
}
```

**注意**：`connect()` 的入参从 `ServerConfig` 改为新的 `SyncSource` 域模型（不含 remotePath），调用时由 SyncRelation 提供 remotePath。

---

## 五、同步关系管理

### 5.1 数据层

**新增 DAO：SyncRelationDao**

```kotlin
@Dao
interface SyncRelationDao {
    @Query("""
        SELECT sr.*, ss.name as sourceName, ss.protocol as sourceProtocol
        FROM sync_relation sr
        JOIN sync_source ss ON sr.syncSourceId = ss.id
        ORDER BY sr.name ASC
    """)
    fun getAllWithSourceFlow(): Flow<List<SyncRelationWithSource>>

    @Query("SELECT * FROM sync_relation ORDER BY name ASC")
    fun getAllFlow(): Flow<List<SyncRelationEntity>>

    @Query("SELECT * FROM sync_relation WHERE id = :id")
    suspend fun getById(id: Long): SyncRelationEntity?

    @Query("SELECT * FROM sync_relation WHERE localBasePath = :path")
    suspend fun getByLocalBasePath(path: String): SyncRelationEntity?

    @Query("""
        SELECT * FROM sync_relation 
        WHERE localBasePath != :excludePath
        AND (:checkPath LIKE localBasePath || '/%' OR localBasePath LIKE :checkPath || '/%')
    """)
    suspend fun findConflictingBasePaths(checkPath: String, excludePath: String = ""): List<SyncRelationEntity>

    @Upsert
    suspend fun upsert(relation: SyncRelationEntity): Long

    @Query("DELETE FROM sync_relation WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

**关键查询**：`findConflictingBasePaths` — 检查新的本地基目录是否与已有基目录存在嵌套关系（祖先或后代）。

### 5.2 UI — 同步关系列表页 (SyncRelationListScreen)

```
┌─────────────────────────────────────┐
│ ← 同步关系管理               ＋ 新增 │
├─────────────────────────────────────┤
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 🔗 电影同步                     │ │
│ │    家庭NAS → /share/电影         │ │
│ │    本地: 动作片/                  │ │
│ │    上次同步: 2026-04-03 14:30    │ │
│ │        同步  删除远端  编辑  删除 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 🔗 纪录片同步                    │ │
│ │    家庭NAS → /share/纪录片       │ │
│ │    本地: 纪录片/                  │ │
│ │    上次同步: 从未同步             │ │
│ │        同步  删除远端  编辑  删除 │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**交互规则**：
- 点击「同步」→ 进入同步预览页（SyncPreviewScreen），传入 relationId
- 点击「删除远端」→ 进入删除预览页（DeletePreviewScreen），传入 relationId
- 点击「编辑」→ 进入同步关系编辑页
- 点击「删除」→ 确认弹窗 → 删除同步关系记录（不删除已同步的本地文件，仅解除关系）
- 点击 + → 进入新增同步关系页

### 5.3 UI — 同步关系编辑页 (SyncRelationEditScreen)

```
┌─────────────────────────────────────┐
│ ← 新增同步关系                       │
├─────────────────────────────────────┤
│                                     │
│ 友好名称                             │
│ ┌─────────────────────────────────┐ │
│ │ 电影同步                        │ │
│ └─────────────────────────────────┘ │
│                                     │
│ 同步源                               │
│ ┌──────────────────────────── ▼ ─┐ │
│ │ 家庭NAS (SMB)                  │ │
│ └────────────────────────────────┘ │
│                                     │
│ 远端目录                             │
│ ┌─────────────────────────────────┐ │
│ │ /share/电影                浏览 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ 本地基目录                           │
│ ┌─────────────────────────────────┐ │
│ │ 动作片                     选择 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ⚠ 基目录不能互相嵌套                  │
│                                     │
│           [测试连接] [保存]          │
│                                     │
└─────────────────────────────────────┘
```

**关键交互**：
- **选择同步源**：下拉选择已有同步源。无同步源时提示先去创建。
- **选择远端目录**：点击「浏览」→ 连接同步源 → 弹出远端目录浏览器 → 选择目录 → 返回路径
- **选择本地基目录**：两种方式：
  1. 输入新名称 → 在 filesDir 下创建新文件夹
  2. 选择已有文件夹 → 弹出本地目录浏览器
- **嵌套校验**：保存前调用 `findConflictingBasePaths()`，若存在冲突则拒绝保存并提示

---

## 六、文件浏览器变更

### 6.1 文件夹创建/删除

**新增操作**（在 BrowserViewModel 中）：

```kotlin
// 创建文件夹
fun createFolder(parentPath: String, folderName: String) {
    viewModelScope.launch {
        val basePath = File(context.filesDir, parentPath)
        val newDir = File(basePath, folderName)
        if (newDir.exists()) {
            _error.value = "文件夹已存在"
            return@launch
        }
        newDir.mkdirs()
        // 刷新列表（StateFlow 自动更新）
    }
}

// 删除文件夹（仅限空文件夹，或非基目录的文件夹）
fun deleteFolder(path: String) {
    viewModelScope.launch {
        val dir = File(context.filesDir, path)
        // 检查是否为基目录
        val isBase = syncRelationDao.getByLocalBasePath(path) != null
        if (isBase) {
            _error.value = "同步基目录不能删除，请先删除对应的同步关系"
            return@launch
        }
        // 检查是否为空
        if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
            _error.value = "文件夹不为空，无法删除"
            return@launch
        }
        dir.delete()
    }
}
```

**BrowserScreen UI 变更**：
- TopBar actions 新增「+」按钮（创建文件夹）
- 文件夹长按弹出菜单增加「删除」选项
- 空文件夹的删除需确认

### 6.2 云朵标识

**BrowseItem 模型扩展**：

```kotlin
data class BrowseItem(
    // ... 已有字段 ...
    val isSyncBaseDir: Boolean = false  // 新增：是否为同步基目录
)
```

**BrowserViewModel 构建逻辑**：

```kotlin
// 在 buildBrowseItems 中获取所有基目录路径
val baseDirs = syncRelationDao.getAllBasePaths()  // Set<String>

// 标记文件夹项
val folders = videoRepository.getSubFolders(folder).first()
    .map { folderName ->
        val fullPath = if (folder.isEmpty()) folderName else "$folder/$folderName"
        BrowseItem(
            // ...
            isSyncBaseDir = baseDirs.contains(fullPath)
        )
    }
```

**FolderItem 组件变更**：

```kotlin
@Composable
private fun FolderItem(item: BrowseItem, onClick: () -> Unit) {
    Row(...) {
        Box(modifier = Modifier.size(40.dp)) {
            Icon(imageVector = Icons.Default.Folder, ...)
            
            // 云朵标识 - 仅基目录
            if (item.isSyncBaseDir) {
                Icon(
                    imageVector = Icons.Default.Cloud,  // 或自定义云朵图标
                    contentDescription = "同步基目录",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                )
            }
        }
        // ... 文件夹名
    }
}
```

**注意**：子目录和文件不加云朵标识，只有基目录文件夹（`localBasePath` 对应的文件夹）右下角有云朵。

### 6.3 浏览器根目录改造

当前浏览器直接展示唯一同步根目录下的内容。改造后：

- **根目录** = `context.filesDir`（而非 `filesDir/synced`）
- 展示 filesDir 下的所有文件夹（包含同步基目录 + 用户自建文件夹）和散落的文件
- 同步基目录带云朵标识，其他文件夹无标识
- 进入某个同步基目录后，看到的是该同步关系下的同步内容

---

## 七、SyncEngine 改造

### 7.1 核心接口变更

```kotlin
@Singleton
class SyncEngine @Inject constructor(
    private val syncSourceRepository: SyncSourceRepository,
    private val syncRelationRepository: SyncRelationRepository,
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository,
    private val syncLogRepository: SyncLogRepository,
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val smbFileClient: SmbFileClient,
    private val sftpFileClient: SftpFileClient,
    @ApplicationContext private val context: Context
) {
    // 改为接收 relationId 参数
    suspend fun dryRun(relationId: Long): Pair<List<SyncAction>, List<SyncAction>>
    suspend fun executeSync(relationId: Long, toDownload: List<SyncAction>, toDelete: List<SyncAction>): Boolean
    suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?>
    suspend fun executeDelete(relationId: Long, actions: List<SyncAction>): Boolean

    // 内部：根据 syncSourceId 获取正确的 client
    private fun getClient(protocol: SyncProtocol): RemoteFileClient = ...

    // 内部：根据 relation 获取本地同步根目录
    private fun getLocalSyncRoot(relation: SyncRelationEntity): File
        = File(context.filesDir, relation.localBasePath).also { it.mkdirs() }
}
```

### 7.2 dryRun 变更要点

```kotlin
suspend fun dryRun(relationId: Long): Pair<List<SyncAction>, List<SyncAction>> {
    // 1. 读取同步关系
    val relation = syncRelationRepository.getById(relationId) ?: return Pair(emptyList(), emptyList())
    
    // 2. 读取关联的同步源
    val source = syncSourceRepository.getById(relation.syncSourceId) ?: return Pair(emptyList(), emptyList())
    val client = getClient(source.protocol)
    
    // 3. 连接并扫描远端
    client.connect(source, relation.remotePath)
    val remoteFiles = client.listFiles(relation.remotePath)
    
    // 4. 读取本地已有文件（限定在该关系的范围内）
    val localVideos = videoRepository.getBySyncRelationId(relationId)
    
    // 5. 比对逻辑（与现有相同，但 relativePath 基于新的 localBasePath）
    ...
}
```

---

## 八、同步/删除喜爱功能调整

### 8.1 当前行为

当前 `dryRunDelete()` 逻辑：
1. 获取所有喜爱的文件
2. 连接唯一服务器
3. 比对远端是否存在同名同大小文件
4. 返回 DELETE_BOTH 操作列表

### 8.2 改造后行为

用户进入「同步关系管理」→ 选择某条同步关系 → 点击「删除远端」：

```kotlin
suspend fun dryRunDelete(relationId: Long): Pair<List<SyncAction>, String?> {
    val relation = syncRelationRepository.getById(relationId) 
        ?: return Pair(emptyList(), "同步关系不存在")
    val source = syncSourceRepository.getById(relation.syncSourceId) 
        ?: return Pair(emptyList(), "同步源不存在")
    
    val client = getClient(source.protocol)
    
    // 获取该同步关系下的喜爱文件（而非全局喜爱）
    val favorites = playbackRepository.getFavorites()
    val relationVideos = videoRepository.getBySyncRelationId(relationId)
    
    val toDelete = mutableListOf<SyncAction>()
    
    client.connect(source)
    val remoteFiles = client.listFiles(relation.remotePath)
    val remoteMap = remoteFiles.filter { !it.isDirectory }.associateBy { it.path }
    
    for (favorite in favorites) {
        val videoItem = relationVideos.find { it.id == favorite.videoItemId } ?: continue
        val localFile = File(videoItem.localAbsolutePath)
        if (!localFile.exists()) continue
        
        val remoteFile = remoteMap[videoItem.relativePath]
        if (remoteFile != null && remoteFile.size == videoItem.fileSize) {
            toDelete.add(SyncAction(
                type = SyncActionType.DELETE_BOTH,
                relativePath = videoItem.relativePath,
                fileSize = videoItem.fileSize
            ))
        }
    }
    
    return Pair(toDelete, null)
}
```

**核心变更**：将喜爱的视频限定在指定同步关系的范围内，而非全局扫描。

---

## 九、导航与侧边栏变更

### 9.1 路由表

```kotlin
sealed class Route(val route: String) {
    data object Browser : Route("browser")
    data object Player : Route("player/{videoItemId}") {
        fun create(videoItemId: Long): String = "player/$videoItemId"
    }
    
    // 替换旧的 ServerConfig 和 SyncPreview
    data object SyncSourceList : Route("sync_source_list")
    data object SyncSourceEdit : Route("sync_source_edit/{sourceId}") {
        fun create(sourceId: Long): String = "sync_source_edit/$sourceId"
        fun createNew(): String = "sync_source_edit/0"  // 0 表示新增
    }
    data object SyncRelationList : Route("sync_relation_list")
    data object SyncRelationEdit : Route("sync_relation_edit/{relationId}") {
        fun create(relationId: Long): String = "sync_relation_edit/$relationId"
        fun createNew(): String = "sync_relation_edit/0"
    }
    data object SyncPreview : Route("sync_preview/{relationId}") {
        fun create(relationId: Long): String = "sync_preview/$relationId"
    }
    data object DeletePreview : Route("delete_preview/{relationId}") {
        fun create(relationId: Long): String = "delete_preview/$relationId"
    }
    
    data object PlaybackSettings : Route("playback_settings")
    data object About : Route("about")
}
```

### 9.2 侧边栏改造

```kotlin
// BrowserScreen 中的 ModalDrawerSheet
NavigationDrawerItem(
    icon = { Icon(Icons.Default.Dns, contentDescription = null) },
    label = { Text("同步源管理") },
    selected = false,
    onClick = { onNavigateToSyncSourceList() }
)
NavigationDrawerItem(
    icon = { Icon(Icons.Default.Link, contentDescription = null) },
    label = { Text("同步关系管理") },
    selected = false,
    onClick = { onNavigateToSyncRelationList() }
)
NavigationDrawerItem(
    icon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = null) },
    label = { Text("播放设置") },
    selected = false,
    onClick = { onNavigateToPlaybackSettings() }
)
NavigationDrawerItem(
    icon = { Icon(Icons.Default.Info, contentDescription = null) },
    label = { Text("关于") },
    selected = false,
    onClick = { onNavigateToAbout() }
)
```

---

## 十、实施阶段建议

### Phase 1：数据层重构（底层基础）
1. 新增 `SyncSourceEntity` + DAO + Repository
2. 新增 `SyncRelationEntity` + DAO + Repository
3. 修改 `VideoItemEntity`（添加 `syncRelationId`）
4. 编写数据库迁移 `MIGRATION_3_4`
5. 更新 `AppDatabase`、`DatabaseModule`

### Phase 2：同步源管理 UI
1. 新建 `SyncSourceListScreen` + `SyncSourceListViewModel`
2. 从 `ServerConfigScreen` 演化出 `SyncSourceEditScreen` + `SyncSourceEditViewModel`
3. 移除 remotePath 字段
4. 更新导航路由和侧边栏

### Phase 3：同步关系管理 UI
1. 新建 `SyncRelationListScreen` + `SyncRelationListViewModel`
2. 新建 `SyncRelationEditScreen`（含远端目录浏览器、本地目录选择器、嵌套校验）
3. 更新导航

### Phase 4：SyncEngine 改造
1. 重构 `SyncEngine` 以 `relationId` 为入口参数
2. 更新 `SyncPreviewViewModel` / `DeletePreviewViewModel` 接收 `relationId`
3. 适配 `VideoItemEntity` 的 `syncRelationId`

### Phase 5：文件浏览器改造
1. 添加文件夹创建/删除功能
2. 添加云朵标识（`isSyncBaseDir`）
3. 改造根目录逻辑（`filesDir` 而非 `filesDir/synced`）
4. 基目录删除保护

### Phase 6：清理与优化
1. 移除旧 `ServerConfigScreen`、`SettingsScreen` 中的服务器配置区域
2. 移除旧 `ServerConfigDao`、`ServerConfigRepository`（已被新模型替代）
3. 移除 `SettingsViewModel` 中的服务器配置逻辑
4. 保留 `SettingsScreen` 仅含播放设置和日志

---

## 十一、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 数据库迁移失败（旧数据丢失） | 高 | 迁移前备份；在非生产环境充分测试迁移脚本 |
| 嵌套基目录校验遗漏 | 中 | 在 DAO 层 SQL 校验 + ViewModel 层二次校验 |
| 多同步关系并发同步冲突 | 低 | 当前为手动触发，可加全局互斥锁 |
| 远端目录浏览器体验不佳 | 中 | 参考 FolderSync 的目录选择器交互模式，提供面包屑导航 |
| 旧用户升级后找不到功能入口 | 中 | 首次升级时显示引导提示，说明新的侧边栏入口位置 |

---

## 十二、废弃清单

改造完成后应移除的代码：

| 文件 | 说明 |
|------|------|
| `ServerConfigEntity.kt` | 被 `SyncSourceEntity` 替代 |
| `ServerConfigDao.kt` | 被 `SyncSourceDao` 替代 |
| `ServerConfigRepository.kt` | 被 `SyncSourceRepository` 替代 |
| `ServerConfig.kt` (model) | 被 `SyncSource` (model) 替代 |
| `ServerConfigScreen.kt` | 被 `SyncSourceEditScreen` 替代 |
| `SettingsScreen.kt` 中的服务器配置区域 | 移至 `SyncSourceEditScreen` |
| `SettingsViewModel.kt` 中的服务器配置逻辑 | 移至 `SyncSourceEditViewModel` |
