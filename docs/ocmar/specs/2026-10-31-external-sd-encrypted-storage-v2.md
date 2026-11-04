# 外置 SD 卡加密媒体存储 v2 — 实施设计

日期：2026-10-31；状态：实施设计，尚未实现/验证；基线 ac5a333。设计：astra。

不使用 SAF、不申请 MANAGE_EXTERNAL_STORAGE/目录授权，不改变内部明文默认体验。采信 minimi（Android 14）实测：外部应用专属目录 File API 读写/rename 成功、367GB 可用、顺序写 14MB/s、内部 680MB/s；热缓存读 2.4GB/s 不是冷卡读保证。

## 目标与明确取舍

- 双卷并存：internal 明文 + 可选一张已登记 external 加密卷；"默认写入卷"只决定新增文件。已有行读取/删除/更新固定走该行 volumeId，切换默认不让旧卷不可读、不自动 fallback 到内部、不自动把旧文件搬走。已有文件更新仍写原卷，避免隐式迁移。
- 选择"不自动迁移"，不提供一次性迁移作为本期功能。理由：14MB/s 迁移耗时、两卷/DB 无统一事务、混合播放/取消/拔卡补偿代价大。用户可显式删除所选文件后重新同步到新默认卷；破坏性升级和日常切换是两回事。
- 威胁模型：防拔卡离线读取；卡上不落媒体/缩略图明文、真实文件名、密钥、DB；大小/文件数量/访问模式泄露接受。CTR 不认证，长度校验不能发现等长篡改，不承诺防恶意改卡/回放/已 root 或运行中明文抓取。需要主动篡改保护时另做 AEAD 格式项目，不混入 CTR 实现。

## 当前架构与历史复用

当前门面 MediaFileStore（app/src/main/java/cn/vectory/syncplayer/storage/MediaFileStore.kt）负责 tmp、probe、thumb、delete、space；真实落盘编排已从 Engine 移到 SyncDownloadPipeline（app/src/main/java/cn/vectory/syncplayer/sync/SyncDownloadPipeline.kt），不要按过时 spec 只改 Engine。MediaContentProvider:68-78 目前直接 PFD；URI 消费者已就位，无需重做播放器架构。保留现有 E8、A7 四态探针、A9 NOFOLLOW_LINKS、BUG-007 清理、A1 按列提交。
从 66d4d13 按文件借鉴 CryptoScheme、RangeDecryptor、DecryptingFdCallback、MediaCryptoMaster、SeekableReadHandle/MediaVolume、FileVolume 和 Provider 后台 handler。不要 cherry-pick 整提交：旧 store 每次查 current 卷、File.exists/list 失败当空、旧 Provider 泄漏 URI/id/path 且无当前取消边界，均不可复活。SafVolume/DocumentFile/tree URI 权限完全不恢复。

## 卷与路径

新增 storage/volume/{MediaVolume,FileVolume,MediaVolumeRegistry}，FileVolume 构造传 volumeId/root，不再硬编码 internal；内部与外部都用普通 File。注册表从 getExternalFilesDirs() 枚举候选，以 StorageManager.getStorageVolume(dir) 确认 removable、UUID、MEDIA_MOUNTED；数组下标仅用于枚举，不能持久化 [1] 或绝对 /storage 路径。持久化逻辑 volumeId = internal 或 external:<uuid>，每次挂载重新解析 root，UUID 为空/不确定身份不启用。选择外部须在自己的 userdata-v2 目录做小型 write/read/rename/delete 能力检查；不删除非本应用布局内容，不创建可疑失挂载路径作为替代。

内部保持 filesDir/userdata/<id/1000>/<id> 和 thumbs/<id> 明文。外部根为 getExternalFilesDir 对应目录/userdata-v2/<datasetId>/，正文 <id/1000>/<id>.<generation>.enc，临时正文同路径 +.tmp，thumbs/<id>.<generation>.enc（GCM）。datasetId 由新 Room 单行 StorageDatasetEntity 保存随机 UUID，避免 dropAllTables 后 id 重用碰撞旧卡文件；不是秘密，卡上只能看到不透明命名。

Registry 仅管理已登记卷与默认写入目标；每个操作显式接收不可变 StorageRef/WriteAttempt。已打开 FD 绑定当时文件，不随设置切换。卷状态监听是提示，操作前后仍须严格验证；拔卡中途 fail-closed，不重定向另一张卡。内部不依赖媒体密钥初始化，Keystore 失败不应阻断内部播放。

MediaVolume 是哑字节后端：openRead(随机读 handle)、openWrite、atomicRename、strictStat、delete、listOwned、space；不能知道 AES。FileVolume 路径只由 id/dataset/generation 派生，校验根内包含关系、拒绝绝对路径/../符号链接与越界，不将 File.exists=false 或 listFiles=null 当作不存在/空卷。NoSuchFile 仅在卷身份/根健康确认下代表缺失，否则 IO_ERROR；probePendingTmp UNKNOWN 沿用"只删行、不删 final"。

## 接口先行（两实施者开始前冻结）

建议 storage/StorageContracts.kt：
- StorageFormat = PLAIN | CTR_V2；StorageState = PENDING | READY。
- StorageRef(id: Long, volumeId: String, datasetId: String?, generation: String?, format: StorageFormat, iv: ByteArray?, plainSize: Long)。必须复制 iv，禁止可变数组别名。
- WriteAttempt(ref: StorageRef, attemptId: String)，由 store 创建；新的 external generation/IV 每 attempt 刷新，失败全量重下，不续写。
- suspend selectWriteTarget(existing: StorageRef?): VolumeTarget：existing 优先；新增使用一次同步开始时快照的默认卷。
- suspend newAttempt(id: Long, target: VolumeTarget, expectedLength: Long): WriteAttempt
- suspend openWriteSink(attempt: WriteAttempt): OutputStream
- suspend finalize(attempt: WriteAttempt): Boolean；verifyReadable(attempt)、cancel(attempt)、probePendingTmp(attempt)
- read/probe/delete/writeThumb 都传 StorageRef（不隐式读 default）；space(target): SpaceResult = Available(bytes) | Unavailable(code)。Provider 开读也必须用单次 entity 快照。
- suspend updateSyncStorageMetadata(id, expectedOldGeneration, volumeId, format, iv, generation, storageState, fileSize, remoteModifiedTime, dateSynced): Int；仅 UPDATE 同步所有列，CAS 防止提交到已变更/删除的行，0 行必须失败，不 upsert 复活、不覆盖 star/audioGainDb。
- 新 SyncErrorCode STORAGE_UNAVAILABLE、STORAGE_IO_TRANSIENT、KEY_UNAVAILABLE；后两类命名可调整但必须冻结枚举与 UI 映射，不靠 message。

## Room v5 与升级

当前 Room version=4，DatabaseModule:33-35 已 fallbackToDestructiveMigration(dropAllTables=true)。升级 5 并导出 schema5，不写保留数据 migration。恢复 volumeId:String、iv:ByteArray?，新增 storageFormat、generation:String?、storageState；新增 StorageDatasetEntity 单行 datasetId。internal：PLAIN/iv=null/generation=null；external：CTR_V2/iv 8B/generation 随机 128 位 token；不合法组合 fail-closed，禁止"iv 缺失就按明文"。fileSize 始终明文长度。保留 relativePath+syncRelationId 唯一键及 parentPath 索引，新增 volumeId 索引；thumb IV 在 GCM 文件头不进 DB。

破坏性迁移实际会清空全部 Room 表（源/关系/媒体/播放/待执行/同步日志），不能声称只删媒体；EncryptedSharedPreferences/DataStore 不随 Room 自动清空。发布/升级提示明确需重建同步配置和重新同步。datasetId 必须在 DB 首次创建事务内建立，启动初始化完成前不放行同步/Provider 存储访问。

旧内部 v4 文件只可做受控孤儿清理：数据库已打开并完成迁移 → 持 storageOperationMutex → 快照新 DB 引用 → 删除确认不被引用的旧布局；保留 marker 先写后删策略，枚举/marker 失败只留孤儿，不盲扫新文件。外部旧 epoch 不自动全删，卡重新插入也不执行全量 wipe；可在显式清理页确认后回收。不能把 .storage_v4_purged 简单重命名并每卷全删。空间占用残留需 UI 可见但不显示路径。

## 密钥与 crypto

选"内部专用 EncryptedSharedPreferences 存随机 256 位 media master，由独立 Android Keystore AES-GCM KEK 保护"。可复用旧 MediaCryptoMaster 抽象/KekProvider 测试，或直接独立 encrypted prefs 封装；不要同时维护两套未说明的密钥真相源。媒体主密钥不是不可导出 KEK：HKDF 用 master.encoded，Keystore KEK 本身 encoded 为空不能拿来 HKDF。使用独立别名/prefs，与远端凭证密钥逻辑隔离。首次创建只允许在无加密引用的新状态；同步 commit 确认持久化后才能落密文；并发初始化单例锁。

KEK 加密让 Android provider 生成 nonce 并取 cipher.iv；失效/解包失败/磁盘损坏抛 KEY_UNAVAILABLE，绝不静默换钥、删文件或清库。备份排除密钥 blob/DB 组合，保留 allowBackup=false 并审核 dataExtractionRules。清数据/卸载会丢密钥，原卡密文不可恢复，恢复方案是显式重同步；App Lock 不等于生物识别绑定媒体 key。

CTR：AES/CTR/NoPadding；8B 随机 IV || 8B 大端 block counter；counter = offset/16，Long 计算，处理块内 skip。每 attempt 新 IV，不按 id 或时间确定性派生。为降低跨全库 64 位 nonce 生日碰撞风险，建议 CTR_V2 用 128 位 generation 作为 HKDF 域隔离输入派生每 attempt 内容 key（与 thumb 域分离），这属于新格式，与历史 CTR 不做原地互通；若采用旧全局 contentKey，文档必须量化 64 位 nonce 残余碰撞风险，不能声称绝不碰撞。v2 无历史密文兼容承诺。

新的 CTR ChunkedEncryptSink 是有界缓冲输出流（例如 256KiB），不是分块 AEAD；每 attempt 一个 Cipher，分块调用 update，close 做 doFinal、关闭底层和长度核验，任何异常均失败；构造失败也 close 句柄，close 幂等，写后关闭拒绝，短/超写不能 finalize，CancellationException 不包成 IOException。纯 CTR 正文无头无 tag，ciphertextLength == plainLength。缩略图 AES-GCM：version 1B + 随机 nonce 12B + 密文 + tag 16B，限制最大 1MiB，完整认证后输出明文，仅内存不写临时明文。

## 提交、崩溃与并发

内部维持现有 tmp→close 校验→同目录原子 rename→verify→按列 DB 提交。外部每 attempt 写独立 generation.tmp；close 验证明文字节数及物理密文长度；可在成功 close 前做一次 FileDescriptor.sync 用于更强数据持久性，不逐 chunk fsync；不承诺未验证的 SD 断电目录持久性。只允许同卷同目录原子 rename，失败不能退化 copy+delete。

外部 rename 至 generation final 后 O(1) 属性核验，再在当前窄 NonCancellable 区一次 UPDATE 提交 generation+iv+volumeId+size+mtime+READY，并同区清零 unclean 登记；0 行回滚本 attempt。metadata/thumbnail 在提交后经 content URI 提取。现有行在 DB 提交前仍指向旧 generation/旧 IV；因此 rename 后进程崩溃只留下新孤儿，不出现新密文+旧 IV。提交后 Provider 按新 ref 读；已打开旧 FD 继续读旧 inode。旧 generation 只在 DB 提交已确定且不再被引用后延迟清理，失败留孤儿。缩略图用同一媒体 generation，重建同 generation 使用 GCM 新 nonce + thumb.tmp→rename，缺 thumb 可重新生成。

复用 BUG-007 三阶段；NOT_FINALIZED 只取消 tmp；FINALIZE_UNCERTAIN PRESENT 只取消 tmp；UNKNOWN 只删行、不删 final；ABSENT/COMMITTED 补偿严格限定本 attempt ref，禁止误删旧 generation。取消清理 NonCancellable 仅做短清理/DB 操作，不把网络/整文件加密塞进去。显式保留 UNKNOWN 删行红线会使旧 generation 也成为孤儿，宁可占空间不猜删。cleanup 仅一次，不吞主路径 CancellationException。

## Provider/播放/分享

现有 authority、签名 readPermission、grantUriPermissions 保留（AndroidManifest:39-44）。查询一次 READY entity：internal 走当前 PFD.open；external 解析该 entity 卷+generation+IV，打开随机读 handle，StorageManager.openProxyFileDescriptor(MODE_READ_ONLY, DecryptingFdCallback, 后台 HandlerThread)。RangeDecryptor 对齐 offset/16，不整文件解密；每 FD handle 独占或 seek/read 加锁，避免仅 ThreadLocal Cipher 但共享 seek 指针竞争。长偏移、EOF、负值、零读、截断、callback 并发/onRelease 竞态都覆盖。openProxy 失败立即关 handle，onRelease 幂等关闭；线程生命周期有 shutdown/测试清理。

callback 发生在 openFile 返回后，不能依赖 openFile catch 消毒；onRead/onGetSize/onRelease 自行把异常映射安全 ErrnoException(EIO/ENODEV/EBADF 等)，不携带原 cause/message/path，取消优先传播。缩略图全量 GCM 认证后 pipe/内存 FD，避免在同一个 callback handler 同步写 pipe 导致死锁。

两/三段 URI 兼容，分享继续带 READ grant，DISPLAY_NAME/媒体 SIZE 保持现有合法分享元数据；不导出明文 File/temp。Media3/Coil/MetadataRetriever/MediaExtractor/SeekPreview/AudioGain/第三方分享均走既有 URI，做回归而非大规模重写。内部文件仍直接 PFD 快速路径。

## 同步/空间/慢卡

主改 SyncDownloadPipeline 而非只有 SyncEngine。每轮在 storageOperationMutex 内快照默认卷；每个 existing 按其 ref 选目标。初始化新行 PENDING，Provider 不公开；逐 attempt 准备独立 IV/generation，成功 commit 才 READY。SyncRemoteScanner final 探针传 ref/由明确 DB 快照解析：外卡不可用 = IO_ERROR → E8 清空下载和删除；不能解释成全库 MISSING，也不能根据缺卡行生成删除。DELETE_BOTH 仍 remote-first，但可先检查本地卷可用，不改变既有远端优先删除语义。

空间按目标卷分组；CTR 正文 n 字节，GCM 缩略图 n+29 与保守预算/安全余量另计；内部也预留 Room/元数据空间。旧 final 更新时仍占空间，因此必须能容纳完整新 tmp；不预先扣除待删除字节。统计 Long 溢出饱和，不把不可查询空间设 Long.MAX_VALUE；SpaceResult.Unavailable 区别空间不足。每 attempt 复检，内部并发需每卷预约避免各 worker 同时看到同一 free 额度，finally 释放。考虑已有 tmp、旧 generation 孤儿占用（StatFs 已计入，勿重复减同一字节）。

外卡不在/不匹配/失挂载 = STORAGE_UNAVAILABLE，确认可恢复 EIO = STORAGE_IO_TRANSIENT，ENOSPC = INSUFFICIENT_SPACE，权限/只读 = ACCESS_DENIED 或非重试 STORAGE_ERROR，密钥 = KEY_UNAVAILABLE，格式/长度损坏 = 非重试 STORAGE_ERROR，非确定 rename = FINALIZE_FAILED 先三态收束。只有新增两瞬态码加入重试白名单，最多 3 次/1s、2s 退避；已知全卷 offline 时停止派发该卷剩余 action，提示插卡后重新同步，避免每文件重试风暴。不得把所有 IOException 都判可重试。

在 sink.write/flush/close 边界就转换结构化异常，SMB/SFTP 需保留 SyncException；SFTP 现有 catch SyncException 已直通（SftpFileClient:295-302），裸 IO 会被包装 DOWNLOAD_FAILED，必须测试不误报网络。FINALIZE catch 也不能吞掉已分类 Storage 异常。

默认 external 写 lane=1，internal 维持现有 3；外部写/缩略图重建串行受控，播放读优先但不承诺硬实时。有界 256KiB 缓冲自然 backpressure，不增大堆积内存，不用内部明文 staging。进度基于成功 sink.write 的明文字节而非网卡瞬时下载；保留 500ms 节流。14MB/s 下 10GB 约 12 分钟、100GB 约 119 分钟（十进制粗估，不含网络/元数据/重试），不设置按内部 680MB/s 推导的文件总 deadline；网络 socket 超时只约束网络读，不应把正常慢盘误判 stall。冷卡顺序/随机 seek、边同步边播放需真机验证，热缓存读数不能替代。

## 设置与删除

StorageManagement 页面显示默认目标、内部/已登记外卡、可用空间/不可用枚举提示；全部用户文案 strings.xml，不显示 UUID/挂载路径/底层异常。开关在同步/重置期间禁用，服务层同 mutex 再次校验而非只靠 UI。切换不删除、不改 existing volumeId、不销毁密钥；无卡外部保持选中但显示不可用，不自动写内部。持久化目标必须在能力检查成功后完成。

重置按卷确认，缺卡/未知枚举不能成功清空行；普通删除先真实文件及 thumb 成功/严格确认缺失后再删 DB。全库 reset 任一卷不可达则拒绝全量清库，或明确逐卷成功只删对应行；本期选拒绝全库 reset 以简化。废弃参数缺失的全局 deleteAllMedia → deleteAllOwned(volumeId, datasetId)，不顺带抹去整个 getExternalFilesDirs 根、其他应用数据、密钥。

## 并行实施包 A：存储/密码/数据基础 + Provider

独占文件：新增 crypto/{CryptoScheme,ChunkedEncryptSink,RangeDecryptor,DecryptingFdCallback,MediaCryptoMaster}.kt；新增 storage/StorageContracts.kt、storage/volume/{MediaVolume,FileVolume,MediaVolumeRegistry}.kt；修改 storage/{MediaFileStore,MediaPaths,MediaFinalProbe,StoragePaths}.kt；data/local/{AppDatabase,entity/VideoItemEntity,entity/StorageDatasetEntity,dao/VideoItemDao,dao/StorageDatasetDao}.kt；data/repository/VideoRepository.kt；di/DatabaseModule.kt 及新增 StorageModule.kt；provider/MediaContentProvider.kt；SyncPlayerApp.kt 启动存储初始化；schema5；对应 test/crypto、test/storage、test/provider、DB androidTest。

A 先提交/共享冻结的 StorageContracts 和 DAO 接口/测试 fake，再并行做实现。A 不编辑 SyncDownloadPipeline/UI/strings/偏好；Provider 使用现有安全文案，新资源名交 B 统一添加。B 负责同步错误枚举，A 消费冻结枚举；避免双方改 DI 同文件（新增 StorageModule 给 A，现有 SyncModule 给 B）。

A 验收：两卷按 ref 路由且切换不影响已有读取；无 SAF；CTR 随机非对齐 seek/0 字节/短尾/大于 2^32 偏移/EOF 正确；close 长度不符/close 异常/初始化失败无 FD 泄漏且不能 finalize；GCM 篡改拒绝；Keystore 生成/重载/失效不换钥；拔卡不会 NOT_FOUND；UNKNOWN 不删 final；generation 崩溃点确保旧/新 key 与文件一致；Provider 模式拒绝、无授权第三方拒绝、授权分享成功、异步 callback 无敏感泄漏；双卷删除/epoch 隔离；Room v4→v5 真破坏性升级。JUnit4+MockK/Coroutines Test，断言字节/枚举/调用次数，不断言用户文案。

## 并行实施包 B：同步集成/设置/维护链路

独占文件：sync/{SyncDownloadPipeline,SyncEngine,SyncRemoteScanner,SyncSpaceChecker,SyncDeleteExecutor,SyncException,ErrorMessageMapper}.kt；按需要 sync/{RemoteFileClient,sftp/SftpFileClient,smb/SmbFileClient,VideoMetadataExtractor,ImageMetadataExtractor}.kt；di/SyncModule.kt；data/repository/UserPreferencesRepository.kt；settings/{SettingsScreen,SettingsViewModel,StorageManagementScreen,StorageManagementViewModel,MetadataRebuildViewModel}.kt；browser/FileOperationsUseCase.kt；res/values*/strings.xml 全部资源；docs/specs/{storage-security,sync,conventions}.md 与 AGENTS 索引事实更新；对应 sync/browser/settings 测试。B 不改 A 的实体/DAO/store/provider 实现，接口疑问先反馈冻结契约。

B 验收：新增/更新/重试准确选择卷；切换默认只影响新文件；成功提交 IV/generation/size 同一 UPDATE 且 0 行不复活；star/audioGain 并发不丢；sink 短写、close 失败、rename 前/后取消、未知 tmp、DB 提交后取消恰一次清理；E8 扫描失败两列表为空；缺卡不删 DB、不执行计划；按卷空间预检/预约、溢出、ENOSPC、外卡瞬态重试次数、offline 批次停止；外卡写 lane1 内部 3、背压与取消；reset 缺卡拒绝；设置无 SAF 授权/路径文案；现有浏览/播放器/分享/缩略图/元数据维护链路均回归。

## 合并与门禁

阶段 0 两人共同签字上述接口/格式/错误码/文件所有权；A 给合同及 fake 后 A/B 并行，不把 B 卡在完整 crypto 实现。B 承担最终集成运行，A 复核存储状态机。必须运行 JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin、testDebugUnitTest、lintDebug。设备验证：internal+external 混合播放、seek、分享、原生取帧、后台同步、重启/进程 kill、各提交窗口拔卡/插回同卡、换另一张卡、空间不足、升级恢复、媒体密钥丢失、10GB 写吞吐、冷缓存播放+同步。测试/真实卡验证尚未执行，设计不能宣称通过。
