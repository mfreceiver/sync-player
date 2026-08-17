# Crypto — At-Rest Media Encryption (AEAD v2)

> 本文档是 Sync Player 加密格式的权威来源。`AGENTS.md` 仅引用本文。

## Authoritative Format: Per-File Chunked AEAD

媒体静态加密使用**逐文件分块 AEAD**（`AES/GCM/NoPadding`），**不是**遗留的 AES/CTR 流。

### Layout

```
[Header 16B][Chunk 0][Chunk 1]…[Chunk N-1]
```

### Header（16 字节，明文，绑定进每个 chunk 的 AAD）

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `"SPM2"` |
| 4 | 1 | formatVersion `2` |
| 5 | 1 | reserved `0` |
| 6 | 2 (BE u16) | chunkSizeKiB `64` |
| 8 | 8 (BE i64) | plaintextLength |

### Chunk i

```
AES/GCM(key, nonce = iv(8) ‖ i(4 BE), aad = header(16) ‖ i(4 BE))
→ ciphertext ‖ tag(16)
```

- Chunk 明文大小 = 64 KiB；最后一个 chunk 可短。
- 失败**必须抛异常**，绝不返回脏明文。

### Entity 字段

- `VideoItemEntity.iv`：**8 字节**每文件随机 nonce 前缀（v1 沿用）。
- `VideoItemEntity.fileSize`：**明文**字节数（= 远端源大小）。

### 密文长度

```
cipherLen(plainLen) = 16 + ceil(plainLen/65536)*16 + plainLen
```

- 空间预检**必须**用 `CryptoScheme.cipherLengthFor()`，不要手算。

## Random Read

`RangeDecryptor.readRange(handle, offset, len)` 接受**明文**坐标：逐 chunk seek + GCM 解密。失败必须抛异常。

## Master Key

- `MediaCryptoMaster` — AES-256，KEK 封装的 blob 存于 `EncryptedSharedPreferences`。
- `KekProvider` 抽象（可测试性）。
- 内容密钥由 `HKDF(master, "syncplayer/content")` 派生。

## Destructive v1→v2 Break

- v1 密文（AES/CTR，无 magic）升级后**不可读**。
- `RangeDecryptor` 在缺失 `SPM2` magic 时抛 `LegacyCiphertextException`。
- 迁移是**破坏性**的（`fallbackToDestructiveMigration(dropAllTables=true)`）：schema 变更时 DB 清空，用户重新同步产生 v2 密文。
- `SyncPlayerApp` 在首次 v2 启动时一次性 purge 遗留 `media/`+`thumb/` 密文（`EncryptedMediaStore.purgeLegacyIfNeeded`）。
- 幂等由**卷根 marker 文件** `.spm2_purged` 保证：purge 完成后 best-effort 写入该 marker，同一卷再次启动时检测到 marker 即跳过扫描（按卷幂等，非全局 flag）。marker 写入失败无妨——下次启动重新 purge，扫描本身幂等。

## Thumbnail Format

缩略图格式**不变**：单独的全文件 GCM 信封（不分块）。

## Known Residual Risk

- AEAD v2 iv 为 8 字节 → 每 key 约 2^32 文件的生日碰撞边界（可接受；远超个人库规模）。
