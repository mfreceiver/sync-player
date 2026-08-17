# Phase 7：测试质量审计
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。**本 Phase 的"测试存在/断言形态"结论均为静态读测，不代表测试可运行或通过。**
- 锚点扫描对账:
  - `grep -rnF 'assertEquals(' app/src/test` | scan_total=**353**（=B.2 锚点 ✓） | adjudicated=353（30 PCRE 候选逐个裁定全 PASS；323 非候选默认形态合规） | excluded_scope_or_FP=0 | unknown=0
  - `grep -rnP 'assertEquals\([^\n]*(?:[\x{4E00}-\x{9FFF}]|[a-z][[:space:]]+[a-z])' app/src/test`（-P，规避 ERE Invalid range end） | scan_total=30 | adjudicated=30（30 PASS：JUnit 断言标签/枚举/纯函数数据） | excluded_scope_or_FP=0 | unknown=0
  - `grep -rn "every {" app/src/test` | scan_total=107 | adjudicated=107（0 处 mock suspend 函数） | excluded_scope_or_FP=0 | unknown=0
  - 名称引用映射（129 主文件 × `grep -rln <Stem> app/src/test`） | scan_total=129 | adjudicated=129（59 有名称引用、70 无） | excluded_scope_or_FP=0 | unknown=0
- 唯一发现: P0=0, P1=0, P2=1（CONFIRMED=0, CANDIDATE=1）；引用他 Phase 的 REF: 0
- U 条目: 0

## 7.1 静态名称引用候选映射

**明确标注：名称引用 ≠ 覆盖率。import/mock 会造成假阳性（引用类名≠测其行为）；顶层函数/间接行为测试会造成假阴性**（例：SyncProgressPublisher 无名称引用，但其节流/计数语义经 SyncEngineProgressTest 通过引擎间接锁定；SyncSpaceChecker 同理经 SyncEngine 测试与 SyncEngineProgressTest.kt 空间用例间接覆盖）。

有名称引用 59 文件（略）；**无名称引用清单（按 sync > crypto > storage > provider > browser/player > ui/navigation > 其他 排序）**：

| 级 | 文件 |
|---|---|
| sync | NetworkUtils / SyncNotificationHelper / SyncPreviewScreen / SyncPreviewViewModel / SyncProgressPublisher / SyncSpaceChecker / sftp/SftpFileClient / sftp/SyncPlayerHostKeyVerifier / smb/SmbFileClient |
| crypto | ChunkedEncryptSink* / DecryptingFdCallback* |
| storage | （无） |
| provider | MediaContentProvider* |
| browser/player | BrowserContent / BrowserScreen / BrowserSortFilter / BrowserTopBar / FirstRunOnboardingCard / SearchViewModel / GesturePlayerView / SeekPreviewExtractor |
| ui/navigation | AppNavHost / NavigationHelper / Route / DirectoryTreeDialog / ErrorDisplays / FileStatusIcon / FolderGridItem / FolderIcon / ProgressRing / StarRatingInput / Thumbnail / VideoGridItem / theme/Color / theme/Theme |
| 其他 | MainActivity / SyncPlayerApp / AudioGainAnalyzer / AppDatabase / SyncLogDao / SyncRelationDao / SyncSourceDao / SyncLogEntity / SyncRelationEntity / SyncSourceEntity / FolderStats / OrientationPreference / DebugLogger / di×3 / ImageViewerScreen / AppLockManager / LockScreenOverlay / settings 全部 Screen/ViewModel（10+） |

\* 带星号者经 CryptoSchemeTest 的 sink 契约用例（ChunkedEncryptSink 经 encryptStream 全链路锁定：length mismatch/overflow/header 失败清理）与 EncryptedMediaStoreTest/MediaUrisTest 间接覆盖；MediaContentProvider 异常边界无任何直接或间接行为测试（仅静态审阅，见 CHECK-3-1）。

## 7.2 违规断言（conventions"绝不断言用户文案"）

353 处 assertEquals 全量锚点命中；PCRE 候选 30 处逐个裁定（JUnit4 `assertEquals(label, expected, actual)` 首参为断言标签，非期望值）：**0 处断言用户可见文案**。代表性合规形态：`assertEquals(SyncErrorCode.STORAGE_ERROR, …error.code)`（枚举）、`assertEquals("only the proven legacy orphan…", 1, deleted)`（标签+数量）、`assertEquals("视频.mp4", sanitizeFileName("视频.mp4"))`（纯函数文件名数据，非 strings.xml 文案）。

## 7.3 suspend mock（conventions:11）

107 处 `every {` 与主源码 suspend 函数名全集交叉核对（脚本机械比对）：**0 处**以 `every` mock suspend 函数；13 个测试文件正确使用 `coEvery`。PASS。

## 7.4 关键安全行为测试存在性（CHECK，非 PRESENT）

### CHECK-7-1 [PASS] E8 失败不删
- 期望: 红线 1；sync.md:49
- 检查范围: SyncEngineDryRunTest.kt:147-195（两个用例）
- 证据: `coEvery { client.listFiles(...) } throws RemoteScanIncompleteException()` + 3 条本地记录 → `assertEquals(0, result.toDelete.size)` + `coVerify(exactly=0) { syncLogRepository.log(action="DELETE_LOCAL",…) }` + phase=FAILED——行为级模拟扫描失败并断言零删除。
- 判定: PASS

### CHECK-7-2 [PASS] 卷不匹配计 FAILED
- 期望: 红线 2；sync.md:51-52
- 检查范围: SyncEngineProgressTest.kt:355-390；SyncEngineBoundaryTest.kt:296
- 证据: mismatchedEntity(volumeId="other-vol") → executeSync 返回 false、PARTIAL_FAILED、completedFiles=0/failedFiles=1、`coVerify(exactly=0){store.delete(500L)}`、`store.deleteThumb` 0 次、DB 行删除 0 次；DELETE_BOTH 同构用例（BoundaryTest:296）。
- 判定: PASS

### CHECK-7-3 [PASS] CancellationException 传播
- 期望: 红线 3；sync.md:54-55
- 检查范围: SyncEngineBoundaryTest.kt:163-204
- 证据: parentJob.cancelAndJoin() → `assertTrue("CancellationException must propagate", caught != null)` + NonCancellable 清理 coVerify markCancelled/deleteByRelationId 各 1 次 + markCompleted/markFailed 0 次。
- 判定: PASS（引擎层；Phase 2 R3 的 35 处 VM 层缺口不在此测试范围，对应 F-2-2…F-2-11 无测试锁定）

### CHECK-7-4 [PASS] AEAD 边界（空文件/恰 64KiB/跨 chunk 随机读）
- 期望: crypto.md 格式契约（红线 4/5）
- 检查范围: CryptoSchemeTest.kt:26,205（六长度表）、RangeDecryptorTest.kt:77
- 证据: 六长度 = {0, 1, CHUNK-1, **CHUNK(恰 64KiB)**, CHUNK+1, 3×CHUNK+123} 全链路 round-trip + cipherLengthFor 对账；`randomReadsMatchPlaintextSlice`（随机 offset/len 跨 chunk）；tamper/reorder/truncation/legacy 用例齐备。
- 判定: PASS

### CHECK-7-5 [PASS] 重试白名单
- 期望: sync.md:57-58
- 检查范围: SyncEngineBoundaryTest.kt:212,231,253,273
- 证据: ACCESS_DENIED 不重试、INSUFFICIENT_SPACE 不重试、TIMEOUT 二次成功、CONNECTION_REFUSED 3 次后失败——白名单/终态码双向行为覆盖。
- 判定: PASS

## 7.5 androidTest 3 文件（647 行）完整读归类

| 文件 | 行数 | 形态 | 覆盖 |
|---|---|---|---|
| data/local/PendingSyncActionDaoTest.kt | 268 | 真实 in-memory Room（ApplicationProvider） | 事务性 replace、状态迁移全套、删除隔离、getAndDelete 原子交接 |
| data/local/VideoItemDaoTest.kt | 342 | 同上（注释明示无 Robolectric 的取舍） | 根/子/深目录查询、parentPath 索引、同库同关系唯一约束、排序/星标/LIKE/级联删除 |
| storage/volume/SafVolumeTest.kt | 37 | 需真实持久化 treeUri fixture（无 fixture 即失败，注释禁止 assertTrue(true) 伪造） | SAF write/read/seek 往返 |

与 conventions.md:7/68 的"androidTest 真实 Room""未在真机执行（编译验证通过）"自述一致。

## 发现条目

### F-7-1 [P2] [CANDIDATE] [测试缺口]
- 类型: ABSENCE
- 期望: storage-security.md:43（TOFU mismatch fail-closed 为安全契约）；sync.md:46"不可违背的契约（均有测试锁定）"（该条虽列 sync 四契约，TOFU 属安全同级）
- 检查范围: app/src/test 全部 28 文件 + androidTest 3 文件
- 证据: `grep -rln 'SyncPlayerHostKeyVerifier' app/src/test`（exit=1 零命中）；`grep -rln 'SmbFileClient\|SftpFileClient' app/src/test`（exit=1 零命中）——TOFU 首连/不匹配 fail-closed、SMB/SFTP 异常分类与 MAX 上限路径无任何直接测试（RemoteFileClientContractTest 为契约文档测试）。因 spec 未强制要求此类测试，记 CANDIDATE 供人工裁决。
- 问题: 安全关键决策逻辑（TOFU、协议异常分类）仅靠静态审阅保证，回归无测试锁定。
- 建议: 为 HostKeyVerifier（首连保存/不匹配 false/并发写）与 classifySmb/classifySftp 补单测。

## 7.6 结论

测试纪律与 conventions 高度一致：7 个高覆盖目标类全部有专测（BrowseItemBuilderTest 850 行为最大）、零文案断言、零 suspend 误 mock、5 项关键安全行为全部行为级锁定。主要缺口为 TOFU/协议客户端分类器无直接单测（CANDIDATE）与 MediaContentProvider 异常边界无行为测试（静态审阅 PASS 兜底）。
