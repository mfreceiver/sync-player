# TODO

> 工作待办清单。已完成的重要分析沉淀在「分析报告」一节，完整细节见对应报告文档。

## 分析报告

### 2026-09-05 深度 Bug Hunting 与测试状态空间探索（无人值守审计）

- **报告**：[docs/automatic/20260905-0012_bug-hunting.md](docs/automatic/20260905-0012_bug-hunting.md)
- **方法**：测试地图 + Git bug 历史挖掘 + 2 探索 Agent（边界 / 状态序列）→ 2 复现 Agent + 主 Agent 共 23 个 targeted JVM 实验（8 个实验文件，已按审计规范清理），生产代码零改动。
- **结论**：21 条假设 → **10 个 CONFIRMED**、6 REJECTED、5 INCONCLUSIVE；基线与终验测试套件全绿（57 文件）。

#### 待修复（按严重度排序）

- [ ] **BUG-006（Critical，数据损坏）** SMB 关系 remotePath=`/` → `extractRelativePath` 未锚定 `indexOf("//")` 命中 `smb://` → 垃圾相对路径 → dryRun 推导**全量 DELETE_LOCAL** 且 `error=null`（E8 守卫被绕过）。修复方向：保存层拒绝 `/` + 改锚定 `startsWith` 匹配、失败抛 SCAN_INCOMPLETE。位置：`SmbFileClient.kt:175-182,325-334`、`SyncRelationEditViewModel.kt:273`。
- [ ] **BUG-001（High）** DELETE_BOTH 本地先删、远端失败只计数 → 下次 dryRun 按「远端有+本地无」重新推导 DOWNLOAD，用户删除意图被静默复活并循环。修复方向：dryRun 排除 pending DELETE 路径（查 PendingSyncActionDao）或本地行延迟到远端 ack。位置：`SyncDeleteExecutor.kt:109-136`、`SyncRemoteScanner.kt:97-104`。
- [ ] **BUG-007（High）** CancellationException 落在 finalize 之后 → CE 清理只调 `store.cancel`（删 tmp），已改名的最终密文无人回滚：现有行 iv/密文错配（不可播）、新文件变体产生幽灵密文。修复方向：CE 清理感知 finalize 状态，已 finalize 走 `store.delete`。位置：`SyncDownloadPipeline.kt:310-318` vs `:296-309`。
- [ ] **BUG-002（Medium）** 播放进度并发保存丢失更新：`saveProgress` 非原子 read-modify-write + `progressSaveScope` 多线程并发、无每视频串行化/单调保护 → 快速切换/慢 IO 下续播位置回退、isCompleted 翻转。修复方向：VM 内 conflated Actor 串行保存。位置：`PlaybackRepository.kt:33-58`、`PlayerViewModel.kt:111,252-263`。
- [ ] **BUG-003（Medium）** `SettingsViewModel.init` 的 `combine().collect` 一层 try/catch：DataStore IOException 后偏好收集**永久死亡**，设置页与真实偏好静默脱钩直到重启。修复方向：`.catch{}` 恢复算子（DataStore 官方模式）。位置：`SettingsViewModel.kt:52-78`。
- [ ] **BUG-004 / BUG-005（Medium，crash 类）** `StorageManagementViewModel.refresh()` 读、`switchToExternal/Internal` 写 DataStore 均无 try/catch，IOException 逃逸 viewModelScope → 进程崩溃（已用真实线程实验捕获 uncaught 异常确认）。修复方向：与同类 setter 一致的捕获+降级。位置：`StorageManagementViewModel.kt:62-118`。
- [ ] **BUG-009（Medium）** SFTP `deleteFile` 非幂等：NO_SUCH_FILE（真实 sshj 异常类型验证）被全量映射为 REMOTE_DELETE_FAILED → 幂等成功被报失败（SMB 侧语义正确，行为不一致）。修复方向：catch NO_SUCH_FILE → 已消失语义。位置：`SftpFileClient.kt:290-306`。
- [ ] **BUG-010（Medium）** 初始 `connect()` 在 3 次重试循环之外：白名单瞬态码（NETWORK_UNAVAILABLE/TIMEOUT）不重试，一次网络抖动使整批文件逐个失败（对照实验证明 download 内同码会重试成功）。修复方向：connect 纳入白名单重试。位置：`SyncDownloadPipeline.kt:187-191,375-399`。
- [ ] **BUG-008（Low）** 终态 CANCELLED/FAILED 进度写入用 fresh 零计数器对象 → publisher merge 清零 totalFiles/completedFiles/transferredBytes/completedPaths → 通知显示 "0 of 0"。修复方向：终态写 `value.copy(phase=…)` 或 merge 特判。位置：`SyncDownloadPipeline.kt:521-538`、`SyncProgressPublisher.kt:305-325`。

#### 待补测试投资（防回归，详见报告 §Recommended Test Investments）

- [ ] （androidTest）VideoItemDao `getByPathPrefix` 的 `_`/`%`/ASCII 大小写孪生目录名精确性（INCONCLUSIVE-1，SQLite LIKE 语义确定，需真机决定性实验；可用 `LIKE ... ESCAPE '\'` + 绑定侧转义修复）。
- [ ] （重构后 JVM）SafVolume null cursor / rename-over-existing / async-delete 契约（SafVolume 目前零逻辑覆盖）。
- [ ] SyncService（零单测）：START_REDELIVER × pending 队列 × onTimeout 交错。

#### 已验证无需处理（防重复调查）

E8 部分快照防护四来源全闭；存储全局 `storageOperationMutex` 串行化闭环；取消后不留半截密文；双取消幂等；Aurora 拖动数学与 AND-001 保存 scope 行为正确（BUG-002 仅并发语义缺陷）；零字节密文格式闭合；`SafVolume.usableSpace()=-1` 两消费方均按未知处理。完整清单见报告 §Interesting Tests That Passed / §Rejected Hypotheses。

---
