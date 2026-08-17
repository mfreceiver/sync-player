# Storage, ContentProvider & Security

> 本文档是 Sync Player 存储卷抽象、ContentProvider 与安全约束的权威来源。`AGENTS.md` 仅引用本文。

## Storage Volume Abstraction

```
MediaVolume (interface)
  ├── FileVolume("internal")     ← 内部存储 (context.filesDir/userdata)
  └── SafVolume("saf-<treeUri>") ← 外部 SAF 卷 (用户选择的 tree Uri)
```

- `MediaVolumeProvider` 按 `UserPreferencesRepository.getMediaStorageTreeUri()` 选择活跃卷。
- **每次访问解析**（切换不需重启进程）。
- `EncryptedMediaStore` 作为存储 facade：encrypt sink / decrypt read / purge / space query。

### 路径布局（基于文件）

- 媒体：`MediaPaths.mediaRelPath(id)` → `"${id/1000}/${id}.enc"`（分桶防单目录巨量文件）。
- 缩略图：`"thumbs/${id}.enc"`。

### SafVolume.rename 限制
仅在源和目标父目录相同时有效（`EncryptedMediaStore.finalize` 使用）。`FileVolume.rename` 允许跨目录。两者不一致是已知限制。

## ContentProvider

`MediaContentProvider`（`ContentProvider`，**不是** DocumentsProvider）通过 `DecryptingFdCallback` 向 ExoPlayer 暴露加密媒体 + 缩略图。

- 常驻 `HandlerThread`（ContentProvider 无 destroy 生命周期）。
- `volumeId` **每次访问**经 `runBlocking` 解析（不缓存），保证卷重挂载后正确。
- **异常边界（非泄漏）**：`openFile`/`getType`/`query` 的所有内部异常（DB、volume、store、open/read、requireMedia/requireThumb）统一映射为安全的 `FileNotFoundException`（资源文案），不向调用方暴露 URI、id、path、volumeId、iv 长度或底层 `message`。
- `CancellationException` 单独传播，不吞。
- `handle` 资源窗口：`openCiphertextRead` 成功后，`contentKey()`/`DecryptingFdCallback` 构造或 `openProxyFileDescriptor` 失败时也 close handle。

## Security

### App Lock
- 生物识别（BIOMETRIC_WEAK），后台切换自动锁定。
- `AppLockManager` 进程生命周期观察者。
- `FLAG_SECURE` 常开（防截屏/录屏）。

### SSH Host Key
- **TOFU (Trust On First Use)** 验证（`known_hosts.properties`，mismatch fail-closed）。
- `SyncPlayerHostKeyVerifier`。

### Credentials
- `EncryptedSharedPreferences`（`secure_prefs`，AES256_SIV keys / AES256_GCM values）。

### 凭证不泄漏
- 密码、密钥、完整远端凭据、卷标识**不进入**用户可见错误或普通日志。
- 用户界面不显示异常原文、主机路径、卷标识或凭据相关信息。
- **路径歧义澄清**：用户可见 UI 文案**不**暴露主机/远端完整路径；logcat 诊断日志**允许** relativePath（用于定位问题），与红线 6 的 "path 不暴露给用户" 不冲突——禁止的是 UI 文案中的完整路径。
