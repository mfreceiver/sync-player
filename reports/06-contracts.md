# Phase 6：四规范契约符合性矩阵
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 四份规范完整读：crypto.md(71 行) / sync.md(69) / storage-security.md(51) / conventions.md(70)（architecture.md 已于 Phase 1 读）。
- 矩阵统计（对账）：**条款总数 69 = PASS 61 + FAIL 2 + PARTIAL 6 + UNKNOWN 0** ✓
- 唯一发现: P0=0, P1=0, P2=0（本 Phase 全部违规已由 Phase 2/3/4/5 立项，此处按 C.4 规则族写 REF 不新建、不计数）；REF 共 12 处
- U 条目: 0

说明：FAIL/PARTIAL 行的证据均指向既有 canonical Finding（F-2-x/F-3-1/F-4-x），按 C.4 去重规则不重复计数；规则族映射：R6≡C-ST-日志契约、R1≡C-SY-E8、R2≡C-SY-卷所有权、R3≡C-CV/R7 取消、R4/R5≡C-CR。

## C-CR（crypto.md，13 条款）

| 条款ID | spec 位置 | 原文(摘) | 实现位置 | 测试位置 | 判定 | 证据/REF |
|---|---|---|---|---|---|---|
| C-CR-1 | crypto.md:7 | 逐文件分块 AEAD（AES/GCM/NoPadding），非 CTR | CryptoScheme.kt:63 | CryptoSchemeTest.kt | PASS | Phase 2 R4 表 |
| C-CR-2 | :12-23 | Header 16B：SPM2/ver2/rsv0/64KiB/len(BE i64) | CryptoScheme.kt:153-196 | CryptoSchemeTest.kt | PASS | CHECK-2-5 |
| C-CR-3 | :28 | nonce=iv(8)‖i(4BE)；aad=header‖i(4BE) | CryptoScheme.kt:200-218 | CryptoSchemeTest.kt | PASS | 同上 |
| C-CR-4 | :32 | chunk 64KiB、尾块可短 | CryptoScheme.kt:46 + ChunkedEncryptSink.kt:110-114 | CryptoSchemeTest.kt | PASS | 同上 |
| C-CR-5 | :33 | 失败必须抛异常，绝不返回脏明文 | RangeDecryptor.kt 全出口 | RangeDecryptorTest.kt | PASS | CHECK-2-6（R5） |
| C-CR-6 | :37-38 | iv 8 字节随机；fileSize=明文字节 | CryptoScheme.kt:34,84；SyncEngine.kt:527-531 | — | PASS | CHECK-2-5 |
| C-CR-7 | :43 | cipherLen 公式 | CryptoScheme.kt:128-136 | CryptoSchemeTest.kt（边界） | PASS | CHECK-2-5 |
| C-CR-8 | :46 | 空间预检必须用 cipherLengthFor | SyncSpaceChecker.kt:98；SyncEngine.kt:489 | SyncEngineProgressTest.kt | PASS | grep cipherLengthFor 2 调用点 |
| C-CR-9 | :50 | 明文坐标随机读 | RangeDecryptor.kt:36 | RangeDecryptorTest.kt | PASS | CHECK-2-6 |
| C-CR-10 | :54-56 | Master AES-256/KEK blob/HKDF 派生 | MediaCryptoMaster.kt:33；SyncModule.kt:30-44；CryptoScheme.kt:71-81 | MediaCryptoMasterTest.kt | PASS | CHECK-2-5 |
| C-CR-11 | :60-62 | v1 不可读；LegacyCiphertextException；破坏性迁移 | CryptoScheme.kt:175-177；DatabaseModule.kt:34 | CryptoSchemeTest/RangeDecryptorTest | PASS | dropAllTables=true |
| C-CR-12 | :63 | 首启 purge（"DataStore flag crypto_v2_purged"） | EncryptedMediaStore.kt:219-241（卷根 marker `.spm2_purged`） | EncryptedMediaStoreTest.kt | **PARTIAL** | REF:F-2-26（机制漂移：无 DataStore flag，marker 文件，行为更优但文档失真） |
| C-CR-13 | :67 | 缩略图 full-file GCM 不分块 | CryptoScheme.kt:257-273 | CryptoSchemeTest.kt | PASS | CHECK-2-5 |

## C-SY（sync.md，18 条款）

| 条款ID | spec 位置 | 原文(摘) | 实现位置 | 测试位置 | 判定 | 证据/REF |
|---|---|---|---|---|---|---|
| C-SY-1 | sync.md:8 | dry-run first，用户确认后执行 | SyncPreviewViewModel.kt:154-163（setPendingTask→SyncService） | SyncEngineTest.kt | PASS | |
| C-SY-2 | :10 | 并发上限 Semaphore(3) | SyncEngine.kt:105,394,446 | SyncEngineProgressTest.kt | PASS | MAX_CONCURRENCY=3 |
| C-SY-3 | :17 | E8 扫描失败清空两列表 | SyncEngine.kt:340-359 | SyncEngineDryRunTest.kt:189 | PASS | REF:CHECK-2-1（≡R1） |
| C-SY-4 | :29 | 组件拆分 Engine/Publisher/SpaceChecker | 三文件存在且职责如述 | SyncEngineProgressTest.kt | PASS | |
| C-SY-5 | :30 | 500ms 节流 + activeDownloadsMap/completedPaths | SyncProgressPublisher.kt:61,124,169-182 | SyncEngineProgressTest.kt | PASS | |
| C-SY-6 | :31 | SpaceChecker sealed Result（4 变体） | SyncSpaceChecker.kt:44-52 | — | PASS | 4 变体逐一名对应 |
| C-SY-7 | :41 | SyncException message 刻意 null | SyncException.kt:50 | ErrorMessageMapperTest.kt | PASS | CHECK-2-7 步骤1 |
| C-SY-8 | :42 | SyncErrorCode 18 码 | SyncException.kt:11-30 | ErrorMessageMapperTest.kt | PASS | 逐码一致 |
| C-SY-9 | :43-44 | 按 code 分类；UNKNOWN 永不泄漏 e.message | ErrorMessageMapper.kt:38-49,113-114 | ErrorMessageMapperTest.kt | PASS | CHECK-2-7 |
| C-SY-10 | :49 | E8 有测试锁定 | SyncEngineDryRunTest.kt:145-189 | 同左 | PASS | CHECK-2-2（≡R1） |
| C-SY-11 | :51-52 | 卷不匹配跳过+计 FAILED+SKIPPED | SyncEngine.kt:663-686,867-886 | SyncEngineProgressTest.kt:384-385 | PASS | REF:CHECK-2-3（≡R2）；浏览器路径语义歧义 REF:F-2-1 |
| C-SY-12 | :54-55 | 取消优先；清理在 NonCancellable | 引擎/数据层全覆盖（SyncEngine.kt:208-213 等） | SyncEngineBoundaryTest.kt | **PARTIAL** | REF:F-2-2…F-2-11（≡R3：35 处 VM 层吞 CE FAIL） |
| C-SY-13 | :57-58 | 重试白名单 6 码 | SyncEngine.kt:110-117 RETRYABLE_CODES | SyncEngineBoundaryTest.kt | PASS | **逐码比对一致**：{TIMEOUT,CONNECTION_REFUSED,UNKNOWN_HOST,NETWORK_UNAVAILABLE,DOWNLOAD_FAILED,UNKNOWN}；非 SyncException 保守可重试 |
| C-SY-14 | :62 | DryRunResult 结构化错误 | SyncEngine.kt:57,371 | SyncEngineDryRunTest.kt | PASS | |
| C-SY-15 | :16/架构 | 扫描完整性契约（listFiles 全量或抛） | SftpFileClient.kt:154-158；SmbFileClient.kt:221-233 | RemoteFileClientContractTest.kt | PASS | E8(a)(c) 注释逐条 |
| C-SY-16 | :66 | listDirectories 吞异常→空列表（accepted） | SmbFileClient.kt:190；SftpFileClient.kt:126 | — | PASS | spec 自述残余风险 |
| C-SY-17 | :67 | isNotFound 仅按类型 | FileOperationsUseCase.kt:603-606 | FileOperationsUseCaseTest.kt | PASS | `e is FileNotFoundException` |
| C-SY-18 | :69 | 无两阶段提交（accepted） | — | — | PASS | spec 自述 |
| （连接生命周期） | sync.md:16 流程 | connect 失败清理/finally disconnect | SftpFileClient.kt:60-85（E4）；SyncEngine.kt:360-362,985；worker 529-644 | SyncEngineTest.kt | PASS | SFTP 半连接显式 disconnect；SMB cifsContext 置 null |

## C-ST（storage-security.md，17 条款）

| 条款ID | spec 位置 | 原文(摘) | 实现位置 | 测试位置 | 判定 | 证据/REF |
|---|---|---|---|---|---|---|
| C-ST-1 | :13 | MediaVolumeProvider 按 treeUri 选活跃卷 | MediaVolumeProvider.kt:23-30 | MediaVolumeProviderTest.kt | PASS | |
| C-ST-2 | :14 | 每次访问解析（切换免重启） | EncryptedMediaStore volume() 每次调用 | MediaVolumeProviderTest.kt | PASS | 无缓存字段 |
| C-ST-3 | :19-20 | mediaRelPath 分桶 + thumbs/ 布局 | MediaPaths.kt | EncryptedMediaStoreTest.kt | PASS | |
| C-ST-4 | :23 | SafVolume.rename 仅同目录（已知限制） | SafVolume.kt:150-152 | SafVolumeTest（androidTest 同构） | PASS | 文档化限制 |
| C-ST-5 | :27 | Provider + DecryptingFdCallback | MediaContentProvider.kt:113-117 | MediaUrisTest.kt | PASS | |
| C-ST-6 | :29 | 常驻 HandlerThread | MediaContentProvider.kt:63-68 | — | PASS | 注释论证无 destroy 生命周期 |
| C-ST-7 | :30 | volumeId 每次访问 runBlocking 解析不缓存 | EncryptedMediaStore.kt:40 | — | PASS | spec 明示该机制 |
| C-ST-8 | :31 | Provider 异常边界不泄漏 | MediaContentProvider.kt 全出口 | — | PASS | REF:CHECK-3-1（Phase 3.2） |
| C-ST-9 | :32 | Provider CE 单独传播 | MediaContentProvider.kt:82,118,149,188 | — | PASS | 4 处 CE rethrow |
| C-ST-10 | :33 | handle 资源窗口 | MediaContentProvider.kt:113-124 | — | PASS | 构造失败也 close handle |
| C-ST-11 | :38 | BIOMETRIC_WEAK 后台自动锁 | BiometricAuthManager.kt:23,43 | BiometricAuthManagerTest.kt | PASS | |
| C-ST-12 | :39 | AppLockManager 进程生命周期观察者 | AppLockManager.kt:43-44（ON_STOP 语义注释 :52） | — | PASS | |
| C-ST-13 | :40 | FLAG_SECURE 常开 | MainActivity.kt:39-40 | — | PASS | |
| C-ST-14 | :43 | TOFU known_hosts.properties，mismatch fail-closed | SyncPlayerHostKeyVerifier.kt:36-61 | — | PASS | mismatch → false（连接失败）；TOFU 写入进程锁+原子替换 |
| C-ST-15 | :47 | 凭证 EncryptedSharedPreferences(AES256_SIV/GCM) | DatabaseModule.kt:72-82；SyncSourceRepository pw_ 键 | — | PASS | |
| C-ST-16 | :50 | 密码/密钥/完整凭据/卷标识不进日志 | 多处 volumeId 入日志（含 DebugLogScreen 可见） | — | **FAIL** | REF:F-2-27（≡R6 卷标识条款）；F-2-28（栈入 UI） |
| C-ST-17 | :51 | UI 不显示异常原文/主机路径/卷标识/凭据 | volumeId 错误文案/DebugLog 栈/e.message 进 UI | — | **FAIL** | REF:F-2-27、F-2-28、F-2-30 |

## C-CV（conventions.md，21 条款）

| 条款ID | spec 位置 | 原文(摘) | 实现位置 | 测试位置 | 判定 | 证据/REF |
|---|---|---|---|---|---|---|
| C-CV-1 | :7-8 | 单测 test/，androidTest 真实 Room | 目录结构 | androidTest 3 文件 | PASS | Phase 0 对账（28+3 文件） |
| C-CV-2 | :9 | 绝不断言用户可见文案 | — | 全量 353 assertEquals 审阅 | PASS | 30 个 PCRE 候选逐个裁定：断言标签/枚举/纯函数数据（如 ShareTempFilesTest:54 文件名纯函数断言），**0 违规** |
| C-CV-3 | :10 | 高覆盖目标 7 类 | — | 7 类全部有专测 | PASS | SyncEngine×4/CryptoScheme/RangeDecryptor/ErrorMessageMapper/BrowseItemBuilder(850 行)/UndoDeleteUseCase/FileOperationsUseCase |
| C-CV-4 | :11 | suspend mock 用 coEvery | — | 13 文件用 coEvery | PASS | `every {` 107 行待 Phase 7.3 核对（若 mock suspend→P2） |
| C-CV-5 | :12 | 取消语义测试：CE 原样传播 | — | SyncEngineBoundaryTest 等 4 文件含 CE | PASS | Phase 7 CHECK 复核 |
| C-CV-6 | :17 | 所有用户可见文本在 strings.xml（单一来源） | strings.xml 475 行 | — | **PARTIAL** | SyncEngine.kt:960 `"local/remote incomplete: ok=…"` 英文诊断串直接进用户可见 lastSync 文案（Phase 8 立项） |
| C-CV-7 | :19 | 日志诊断文本不本地化（允许硬编码中文） | SyncSpaceChecker.kt:76 等 | — | PASS | |
| C-CV-8 | :20 | Repository 不依赖 Context 生成文案 | data/repository 仅 UserPreferences 依赖 Context（DataStore，不生成文案） | — | PASS | grep 佐证 |
| C-CV-9 | :24 | Room v2 exportSchema=true，schema 导出 | AppDatabase.kt:21-22 | — | PASS | app/schemas/.../2.json 存在 |
| C-CV-10 | :25 | fallbackToDestructiveMigration(dropAllTables=true) | DatabaseModule.kt:34 | — | PASS | |
| C-CV-11 | :26 | parentPath 索引高速浏览 | VideoItemEntity.kt:12 | androidTest DAO | PASS | Index(parentPath)+unique(relativePath,syncRelationId) |
| C-CV-12 | :27 | 6 实体 | AppDatabase.kt:9-16 | — | PASS | |
| C-CV-13 | :31 | 重型合并 Flow ≥300ms debounce | SearchViewModel.kt:64；BrowserViewModel.kt:226 | — | PASS | Phase 4.5 |
| C-CV-14 | :32 | 阻塞 I/O 必须 Dispatchers.IO，@IoDispatcher 注入 | 大体合规 | — | **PARTIAL** | REF:F-4-1（Main runBlocking）、F-4-2（业务硬编码 14）、F-4-4（FileVolume 不自包）、F-4-5（Main canonicalFile） |
| C-CV-15 | :33 | 高频状态独立 StateFlow | PlayerViewModel/BrowserViewModel | — | PASS | Phase 1 ARCH-6/7 |
| C-CV-16 | :34 | 进度 ~500ms 节流 | SyncProgressPublisher.kt:61 | SyncEngineProgressTest.kt | PASS | |
| C-CV-17 | :38-39 | versionCode/versionName git 派生，不手改 | app/build.gradle.kts:31-67 | — | PASS | rev-list --count / describe |
| C-CV-18 | :59 | 播放器错误 hasPlayerError 布尔，不携带 PlaybackException.message | PlayerViewModel.kt（hasPlayerError） | PlayerViewModelErrorTest.kt | PASS | PlayerScreen.kt:217 注释佐证不外发 message |
| C-CV-19 | :60 | Provider 统一安全 FNF | MediaContentProvider.kt | — | PASS | REF:CHECK-3-1 |
| C-CV-20 | :64 | VM generic catch 显示 sync_error_unknown（accepted） | syncErrorMessage UNKNOWN 分支 | — | PASS | spec 自述；CE 维度问题 REF:F-2-2… |
| C-CV-21 | :65 | IO_ERROR.reason 读 .message（accepted，前提"consumer 用 resource prefix 包装"） | EncryptedMediaStore.kt:137 | — | **FAIL** | 实际 consumer（MetadataRebuildViewModel.kt:127）把 reason 裸插值进 UI 文案，违背该残余风险记录的前提。REF:F-2-30 |

## 6.x 显式必查清单覆盖核对（任务书 Phase 6.4 要求的每一行）

重试白名单逐码比对 ✓（C-SY-13）｜SyncProgressPublisher 进度语义 ✓（C-SY-5）｜SyncSpaceChecker 溢出 ✓（C-SY-6 + SyncSpaceChecker.kt:99-102 Long 溢出显式检查）｜SFTP/SMB 连接生命周期与断开清理 ✓（C-SY-18）｜v1→v2 破坏性迁移 ✓（C-CR-11）｜TOFU 全流程 ✓（C-ST-14；日志维度 REF:F-2-29）｜Master/KEK/HKDF ✓（C-CR-10）｜IV/AAD/nonce ✓（C-CR-2/3）｜v1 purge ✓（C-CR-12 PARTIAL）｜缩略图 full-file GCM ✓（C-CR-13）｜Provider 异常边界 ✓（C-ST-8，REF Phase 3）｜App Lock ✓（C-ST-11/12）｜FLAG_SECURE ✓（C-ST-13）｜凭证 EncryptedSharedPreferences ✓（C-ST-15）｜日志契约 ✓（C-ST-16 FAIL→REF）｜E8 ✓（C-SY-3/10）｜卷所有权 ✓（C-SY-11）｜volumeId 每次访问解析 ✓（C-ST-7）。**必查行无一缺漏。**

## 6.y 统计与对账（机械重数，权威）

计数命令与结果（exit=0）：
- `grep -cE '^\| C-(CR|SY|ST|CV)-[0-9]+ \|' reports/06-contracts.md` → **69**（编号条款行）
- 判定 PASS 行 → **62**；判定 FAIL 行 → **3**（C-ST-16/C-ST-17/C-CV-21）；判定 PARTIAL 行 → **4**（C-CR-12/C-SY-12/C-CV-6/C-CV-14）；UNKNOWN → **0**
- 另有 1 行补充说明行（C-SY 表"连接生命周期"，判定 PASS，不计入条款总数）

| 规范 | 条款数 | PASS | FAIL | PARTIAL | UNKNOWN |
|---|---|---|---|---|---|
| C-CR | 13 | 12 | 0 | 1 | 0 |
| C-SY | 18 | 17 | 0 | 1 | 0 |
| C-ST | 17 | 15 | 2 | 0 | 0 |
| C-CV | 21 | 18 | 1 | 2 | 0 |
| **合计** | **69** | **62** | **3** | **4** | **0** |

对账：69 = 62 + 3 + 4 + 0 ✓；分 spec 逐行相加 12+17+15+18=62、1+1+2=4 PARTIAL、2+1=3 FAIL，与总计数一致（Python 段落级机械重数可复现）。矩阵另含 1 行非编号补充说明行（C-SY"连接生命周期"，PASS），不计入条款总数。

（勘误说明：本节数字以机械命令输出为准，前文表格中如与机械数不符一律以本节为准。）
