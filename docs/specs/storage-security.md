# Storage, ContentProvider & Security

> 本文档是 Sync Player 存储与安全约束的权威来源。`AGENTS.md` 仅引用本文。

## Storage Layout

媒体为**平文明文**，存储在应用内部单一卷：

```
filesDir/
├── userdata/           ← MediaFileStore 管辖
│   ├── 0/1/2/…         ← 数字桶目录（MediaPaths.mediaRelPath: "<id/1000>/<id>"）
│   ├── thumbs/         ← 缩略图（"thumbs/<id>"）
│   └── .storage_v4_purged  ← 升级清扫 marker
├── debug-logs/         ← 调试日志（StoragePaths）
└── sync-player.db      ← Room
```

- `MediaFileStore`（`storage/MediaFileStore.kt`）是存储 facade：下载 sink / 文件解析 /
  缩略图 / 删除 / 空间 / 孤儿清扫。所有相对路径仅由 Long id 派生，无用户输入参与路径拼接。
- 历史版本（v12–v13）曾有 SAF 外部卷与 AEAD 静态加密；因非内部存储性能无法支撑、
  且加密对个人用途无实际收益，已在 DB v4 中整体移除（破坏性迁移 + 启动一次性清扫）。

### 下载两阶段提交（崩溃安全，与加密无关，历史沿用）

1. 下载写入 `"<id>.tmp"`；`LengthVerifyingOutputStream` 在 close 时校验写入字节数
   == 预期长度，不等抛 `IOException`。
2. `finalize(id)` 原子 rename 为 final；`probePendingTmp` 三态探测（PRESENT/ABSENT/
   UNKNOWN）收束"finalize 已发起但结果未确认"的窗口——UNKNOWN 时调用方只删行、
   不删 final（BUG-007 机制）。
3. `verifyReadable(id, expectedSize)`：finalize 后 O(1) 探针（存在 + 长度一致），
   失败抛异常走单点回滚。

### 清扫

- **升级孤儿清扫**（`SyncPlayerApp` 启动，`storageOperationMutex` 下）：marker
  `.storage_v4_purged` 保证每卷只执行一次；清扫幂等，marker 写失败无害。
- **重置存储**（存储管理页）：`deleteAllMedia()` 删数字桶 + thumbs（只动自己布局内
  的目录），随后清空三张业务表。

## ContentProvider

`MediaContentProvider`（`ContentProvider`，**不是** DocumentsProvider）向 ExoPlayer / Coil /
MediaMetadataRetriever / 分享接收方暴露本地媒体与缩略图：

- media/thumb 分支均 `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`。
- **异常边界（非泄漏）**：`openFile`/`getType`/`query` 的所有内部异常（DB、store、open/
  read、requireMedia/requireThumb）统一映射为安全的 `FileNotFoundException`（资源文案），
  不向调用方暴露 URI、id、path 或底层 `message`（包括 PFD.open 自带路径的 FNFE）。
- `CancellationException` 单独传播，不吞。
- 分享用 3 段 URI `content://…/media/<id>/<fileName>`（`MediaUris.mediaUri(id, fileName)`），
  兼容只看 URI 末段的收件方；`idOf` 对两段/三段 URI 均取第 2 段为 id。

## Security

### App Lock
- 生物识别（BIOMETRIC_WEAK），后台切换自动锁定。
- `AppLockManager` 进程生命周期观察者。
- `FLAG_SECURE` 常开（防截屏/录屏）。

### SSH Host Key
- **TOFU (Trust On First Use)** 验证（`known_hosts.properties`，mismatch fail-closed）。
- `SyncPlayerHostKeyVerifier`。

### Credentials
- `EncryptedSharedPreferences`（`secure_prefs`，AES256_SIV keys / AES256_GCM values），
  仅存远端凭证。

### 凭证不泄漏
- 密码、密钥、完整远端凭据**不进入**用户可见错误或普通日志。
- 用户界面不显示异常原文、主机路径或凭据相关信息。
- **路径歧义澄清**：用户可见 UI 文案**不**暴露主机/远端完整路径；logcat 诊断日志
  **允许** relativePath（用于定位问题），与红线 6 的 "path 不暴露给用户" 不冲突——
  禁止的是 UI 文案中的完整路径。
