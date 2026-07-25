### 成果分点
- Task 1 (CryptoScheme): 完成 AES-CTR 核心加解密、HKDF 派生、GCM 缩略图加解密、IV 唯一性验证。6 单元测试全过。
- Task 2 (MediaCryptoMaster): 完成 Keystore Kek 包裹软件 AES-256 主密钥的 `MediaCryptoMaster` 及其 Hilt 绑定。2 单元测试全过。
- Task 3 (MediaVolume/FileVolume/MediaPaths): 完成 `MediaVolume` 接口、`FileVolume` 实现（含 canonical 路径安全校验）、`MediaPaths` 路径派生。4 单元测试全过。
- Task 4 (SafVolume): 完成 `SafVolume` 实现（基于 SAF DocumentsContract API，绕过 `DocumentFile` 依赖）。instrumented 测试骨架已写（需真机）。
- Task 5 (EncryptedMediaStore): 完成 `EncryptedMediaStore` 编排 `MediaVolume` + `CryptoScheme`，提供读写/缩略图/清理。3 单元测试通过。
- Task 6 (RangeDecryptor/DecryptingFdCallback): 完成 `RangeDecryptor`（非对齐 offset/EOF 截断）+ `DecryptingFdCallback`（ProxyFileDescriptorCallback 包装）。2 单元测试通过。

### Diff 摘要
- 新增文件（untracked）：
  - `crypto/CryptoScheme.kt` — 加密核心
  - `crypto/MediaCryptoMaster.kt` — 主密钥管理 + KeystoreKekProvider
  - `storage/volume/MediaPaths.kt` — 不透明路径派生
  - `storage/volume/MediaVolume.kt` + `SeekableReadHandle` — 卷抽象
  - `storage/volume/FileVolume.kt` — File 后端
  - `storage/volume/SafVolume.kt` — SAF 后端
  - `storage/volume/EncryptedMediaStore.kt` — 编排加密+卷
  - `crypto/RangeDecryptor.kt` — 按 offset 解密逻辑
  - `crypto/DecryptingFdCallback.kt` — 代理 FD 回调解密
  - `test/...` 对应的单元测试文件
- 修改文件（tracked）：
  - `di/DatabaseModule.kt` — 添加 `MIGRATION_17_18` 骨架（暂注释）
  - `di/SyncModule.kt` — 添加 `MediaCryptoMaster` 提供
  - 其它文件（`BrowserViewModel`、`SyncEngine` 等）为实体改迁移中的预修改

### 代表性 diff
见各文件各自实现，核心加密 CTR decriptBlockAlignedRange 逻辑：

```diff
+    fun decryptBlockAlignedRange(
+        key: SecretKey, iv: ByteArray, startBlockIndex: Long,
+        ciphertext: ByteArray, off: Int, len: Int
+    ): ByteArray {
+        val cipher = Cipher.getInstance(TRANSFORM).apply {
+            init(Cipher.DECRYPT_MODE, key, counterBlock(iv, startBlockIndex))
+        }
+        return cipher.doFinal(ciphertext, off, len)
+    }
+
+    private fun counterBlock(iv: ByteArray, counter: Long): IvParameterSpec {
+        require(iv.size == IV_LEN)
+        val blk = ByteBuffer.allocate(BLOCK).order(ByteOrder.BIG_ENDIAN)
+            .put(iv)
+            .putLong(counter)
+            .array()
+        return IvParameterSpec(blk)
+    }
```

### 遗留风险
- 整体项目因实体字段已变更（`localAbsolutePath`/`thumbnailPath` 移除）但消费者未全面更新，仍有多处编译错误，需在后续 Task 7-15 中逐文件修复。
- `SafVolume` 的 instrumented 测试依赖真实 SAF tree URI，当前仅骨架无法自动化验证。
- `MIGRATION_17_18` 因当前编译阻塞被注释掉，待 Room schema 确认后需取消注释并验证 DDL 一致性。