# 新 Session 执行提示词：加密媒体存储与外置目录

请在 `/home/mar/personal_projects/sync-player` 继续执行已批准的加密媒体存储改造。

## 必读文档

1. 设计：`docs/ocmar/specs/2026-07-25-encrypted-external-storage-design.md`
2. 计划：`docs/ocmar/plans/2026-07-25-encrypted-external-storage.md`
3. 项目约束：`AGENTS.md`

## 任务目标

将媒体从当前内部目录明文存储改造为：

- 支持用户通过 SAF tree URI 选择一个存储位置，包括外置 SD 卡；
- v1 只有一个活跃卷，切换卷后旧记录不可读，必须清空新卷并全量重同步；
- 正文使用可 seek 的 AES-CTR：8 字节随机 IV + 8 字节大端 long counter，counter=`byteOffset/16`；
- 缩略图使用 AES-GCM，每次随机 12 字节 IV，文件格式 `ver(1B)‖iv(12B)‖ciphertext+tag`；
- 磁盘媒体名使用 `<id/1000>/<id>.enc`，缩略图使用 `thumbs/<id>.enc`，真实文件名只保存在 Room；
- 通过 `MediaContentProvider` + `ProxyFileDescriptorCallback` 按范围解密，供 ExoPlayer、MediaMetadataRetriever、MediaExtractor、Coil 和分享使用；
- 删除 `SyncDocumentsProvider` 暴露功能、被动全盘扫描、孤儿导入和 RECONCILE 全链；
- Room schema v17→v18 破坏性清空媒体表并重新同步。

## 已经完成的设计审查结论

rev-bgpt 已全面审查设计和计划。以下问题已经在文档中修订，实施时不可回退：

- Provider 必须校验 `entity.volumeId == activeStore.volumeId`；
- Provider callback 必须运行在专用 `HandlerThread`，禁止主线程 Handler；
- SAF FD 必须实际探测 `Os.lseek`，不可 seek 的 Provider 必须拒绝；
- 缩略图 IV 不得按 ID 确定性派生；
- 不得使用不存在的 `videoRepository.insert`、`delete(id)`、`getByFolderPath`，使用真实的 `upsert`、`deleteById` 和现有/明确新增的文件夹查询；
- Room 迁移 DDL 必须以生成的 `18.json` 为准，包含 `AUTOINCREMENT`、索引、FK；
- `ByteArray` 不得使用不一致的 equals/hashCode；
- 下载每次 retry 必须关闭 sink、删除 tmp、从 offset 0 重新下载，不能继续追加同一 CTR 流；
- `DELETE_BOTH`、MetadataRebuild、GainAnalysis、PlayerViewModel、ImageViewer、Grid/Search、DAO projections、FileProvider 配置都必须完成迁移。

## 执行规则

1. 使用 `ocmar-subagent-driven-development`，按计划 Task 1→15 顺序执行；每个 Task 独立验证并记录 diff。
2. 先写失败测试，再实现，再运行最窄相关测试；每个代码任务至少运行：
   ```bash
   JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
   ```
3. Room migration 测试放在 `app/src/androidTest`，不是 `src/test`；使用真实 `18.json` 校验。
4. 不要修改或混入当前工作树已有的无关改动：
   - `app/src/test/java/cn/vectory/syncplayer/util/VideoUtilsTest.kt`
   - `app/src/test/java/cn/vectory/syncplayer/util/FormatUtilsTest.kt`
   - `.ocmar/`
5. 不要擅自改变威胁模型、加密格式、单活跃卷范围或迁移策略；有矛盾先停下并报告。
6. 不要提交或 push，除非用户另行明确要求。

## 关键实现检查点

- `CryptoScheme`：CTR counter block 必须是 `IV(8)‖putLong(counter)`；RangeDecryptor 必须处理非对齐 offset、EOF 截断、负参数和短读。
- Thumbnail：GCM encoded 数据至少包含 1+12+16 字节，读取前限制最大大小；IV 随密文保存。
- `FileVolume`：所有相对路径做 canonical containment 校验，拒绝 `/` 和 `..`；`SafVolume.rename` 校验返回值、目标存在和源消失。
- `MediaContentProvider`：严格校验 authority/path/id；未知实体返回 `FileNotFoundException` 或空 Cursor；MIME 使用 `getMimeType(fileName)`，不要返回 `video/*` 代替准确 MIME。
- `SyncEngine`：成功 finalize 后才提取 metadata/upsert；失败或崩溃清理 `.enc.tmp`；全局移除 dry-run/execute 中所有 RECONCILE 物理扫描逻辑。
- 分享：直接发 `MediaContentProvider` 的 URI + `FLAG_GRANT_READ_URI_PERMISSION`，不写明文 temp。

## 最终验证

完成 Task 15 前至少运行：

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest
```

真机必须覆盖：内部目录、外置 SD SAF 目录、播放 seek、拖动取帧、缩略图、增益分析、第三方播放器分享、重启恢复、文件管理器确认磁盘无明文文件名/内容、SAF 不可 seek/权限撤销错误提示。
