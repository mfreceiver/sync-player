# Phase 3：异常与错误处理全量审计
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - `grep -rnE 'catch[[:space:]]*\(' --include='*.kt' app/src/main`（202，exit=0，=B.2 锚点） | scan_total=202 | adjudicated=202（P3_PASS 201 + P3_CONFIRMED 1） | excluded_scope_or_FP=0 | unknown=0
  - **对账：P3_PASS 201 + P3_CONFIRMED 1 + P3_CANDIDATE 0 + P3_UNKNOWN 0 = 202** ✓
- 唯一发现: P0=0, P1=0, P2=1（CONFIRMED=1, CANDIDATE=0）；引用他 Phase 的 REF: 63 处捕获点（映射 Phase 2 的 24 个 R3 Finding，不计入本 Phase 计数、不双计）
- U 条目: 0

## 3.1 全量 202 处 catch 唯一主分类

分类基础：Phase 2 R3 已逐点读过全部 202 处中的 153 处通用捕获（±15 行，见 02-redlines.md 2.3 逐点表）；本 Phase 补齐其余 49 处专项捕获（34 处 `CancellationException` 简名 + 5 处全限定名 + 15 处其他具体类型），并按"普通异常处理质量"维度指定唯一主分类。

### 3.1.1 专项捕获 49 处裁定

**CancellationException 专捕 39 处（34 简名 + 5 全限定名）——全部 P3_PASS**。机械校验：catch 行起 4-8 行窗口内必有 `throw`（39/39，其中 SyncEngine.kt:208/567/754 为多行清理块后 rethrow，已逐处打开核对；:208 为 `withContext(NonCancellable)` 清理 pending action 后 rethrow，:567/:754 清理进度/取消状态后 rethrow——精确符合 sync.md:55"清理在 NonCancellable 中完成"）。

**其他具体类型 15 处——全部 P3_PASS**：

| 位置 | 类型 | 处置 |
|---|---|---|
| UserPreferencesRepository.kt:49 | IllegalArgumentException | valueOf 失败→null（偏好降级） |
| StorageManagementScreen.kt:43 | SecurityException | takePersistableUriPermission 失败静默跳过（注释：用户可重试） |
| EncryptedMediaStore.kt:58 | IOException | 包装 SyncException(STORAGE_ERROR) 再抛 |
| ChunkedEncryptSink.kt:115 | IOException | 记 err → close 末统一 rethrow |
| FileOperationsUseCase.kt:513 | FileNotFoundException | 幂等视为已删除 |
| MediaContentProvider.kt:77/140/167 | IllegalArgumentException | 资源化安全值（FNF/null/空 Cursor） |
| StoragePaths.kt:113 | IOException | canonical→absolute 回退（注释说明 containment 仍成立） |
| SafVolume.kt:99 | ErrnoException | 关 fd + 抛 UnsupportedOperationException（SAF 不可 seek 契约） |
| SyncSpaceChecker.kt:105 | IllegalArgumentException | Log.w + 返回 -1（unknown 哨兵，调用方降级） |
| SyncEngine.kt:490 | IllegalArgumentException | 包装 INVALID_FILE_SIZE 结构化错误再抛 |
| GainAnalysisViewModel.kt:174/199、MetadataRebuildViewModel.kt:145/218/264 | CancellationException(全限定) | rethrow（机械校验通过） |
| SyncRelationEditViewModel.kt:262 | SecurityException | 资源化校验错误文案 |
| SftpFileClient.kt:235、SmbFileClient.kt:315 | SyncException | rethrow（保结构化分类） |

### 3.1.2 通用捕获 148 + runCatching 5 处主分类

全部 P3_PASS（除下述 1 处 CONFIRMED）：普通异常的处理形态为「Log + 资源化错误态」「包装结构化异常再抛」「哨兵值 + 调用方复核」三类，均合理。其中 63 处捕获点的 **CE 维度问题已在 Phase 2 立项**（35 FAIL→F-2-2…F-2-11，28 CANDIDATE→F-2-12…F-2-25），本 Phase 对这些点仅记 REF、主分类不双计。

次级标签（可多选，不入对账）：
- **空 catch（清理语义）11 处**：`grep -rnE 'catch \([^)]*\) \{ *\}' --include='*.kt' app/src/main`（exit=0，11 行）——FOUC:336、SyncRelationEdit:188/225、ImageMetadataExtractor:59、SyncEngine:480/563/568/573/642、EncryptedMediaStore:73/119，全部为 close/disconnect/删除清理或 EXIF 解析 best-effort，无业务异常被静默丢弃。
- **吞后无日志（哨兵契约）3 处**：SafVolume.kt:132/185/191（false/-1 哨兵；调用方 M2 注释明确复核 exists/thumbExists，失败最终经 sync log/错误态 surfaced）。
- **documented 残余风险 2 处**：SmbFileClient.kt:144/190（listShares/listDirectories 吞→空列表，sync.md:66 明示接受）。

## 3.2 Provider 异常边界

### CHECK-3-1 [PASS] [C-ST-Provider 异常边界]
- 期望: storage-security.md:31-32（异常边界不泄漏 URI/id/path/volumeId/iv 长度/底层 message；CE 单独传播）
- 检查范围: provider/MediaContentProvider.kt 全文完整读（212 行）：openFile(:74-90)/safeOpenFile(:95-135)/getType(:137-155)/query(:157-194)/insert(:196)/delete(:197)/update(:198)
- 证据: 每个出口清单——
  - openFile: id 解析 IAE(:77)→FileNotFoundException(R.string.media_error_invalid)；内层异常 catch(Exception)(:85)→FileNotFoundException(R.string.media_error_not_found)；CE(:82)显式 rethrow
  - safeOpenFile: 实体缺失/卷不匹配(:99-102)→统一 FNF(资源文案)；media 分支 handle 窗口(:113-124)：contentKey/callback 构造或 openProxy 失败→close handle→FNF；CE(:118)先 close 再 rethrow
  - getType: IAE(:140)→null；内层异常(:151)→null；CE(:149)rethrow
  - query: IAE(:167)→空 MatrixCursor；内层异常(:190)→空 Cursor；CE(:188)rethrow
  - insert/update/delete: 固定 null/0/0（无操作）
  - 所有对外文案均 `context!!.getString(R.string.*)`，不插值 URI/id/path/volumeId/iv
- 判定: PASS——无泄漏出口（卷不匹配查询/getType 同样不区分"不存在"与"别的卷"，满足 D2-a/M8 不披露要求）。

## 发现条目

### F-3-1 [P2] [CONFIRMED] [P3-丢失cause]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt:136
- 代码: `MediaExistence.IO_ERROR(e.message ?: e.javaClass.simpleName)`
- 问题: checkExistence 捕获异常后**丢弃异常对象**（cause 链丢失），仅保留 message 字符串——诊断信息降级，且该 message 随后进入用户 UI（该维度已立 REF:F-2-30，本条仅计"丢失 cause"主分类）。
- 复现: grep -n 'IO_ERROR(e.message' app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt （exit=0，命中 1 行）
- 建议: IO_ERROR 携带 Throwable（或 code+cause），UI 侧只用结构化码。

## 3.3 结论

异常处理整体纪律性强：引擎/数据层 CE 先行守卫全覆盖、结构化错误不携带文案、Provider 边界零泄漏、清理路径完备。唯一质量缺陷 1 处（丢 cause）；CE 维度的 63 处捕获点问题已在 Phase 2 R3 立项（REF 不双计）。
