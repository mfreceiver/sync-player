# Sync Player — AGENTS.md

> 详细架构/选型/约定见 [`docs/specs/`](docs/specs/)（权威来源）。本文件仅保留 agent 工作必需的环境与速查，不重复完整信息。

## Spec 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/specs/architecture.md`](docs/specs/architecture.md) | Tech Stack、分层、MVI/MVVM、State Splitting、Key Directories、OrientationPreference↔OrientationMode |
| [`docs/specs/sync.md`](docs/specs/sync.md) | 同步引擎、SyncException/SyncErrorCode、E8 守卫、两阶段提交、重试白名单、SyncProgressPublisher/SyncSpaceChecker |
| [`docs/specs/storage-security.md`](docs/specs/storage-security.md) | 存储布局、MediaFileStore、ContentProvider 异常边界、App Lock、TOFU、凭证 |
| [`docs/specs/conventions.md`](docs/specs/conventions.md) | Testing、i18n、Database、Performance、Versioning、Player UX、错误处理、残余风险 |
| [`docs/release.md`](docs/release.md) | 发版规范、签名凭据链、changelog、CI |

## Environment

- **JAVA_HOME**: `/home/mar/android-studio/jbr` (Android Studio bundled JBR, OpenJDK 21)
- **ANDROID_HOME**: `/home/mar/android-sdk` (build-tools 35.0.x, platform android-35)
- **Android Studio**: `/home/mar/android-studio`
- **Project root**: `/home/mar/personal_projects/sync-player`
- **Gradle**: 9.3.1
- **Kotlin LSP disabled** (`opencode.json: lsp=false`). Reason: Hilt (KSP) + Room (KSP) + Compose Compiler 生成代码无 LSP 可解析；任何 LSP 会刷 stale/unresolved 误报。**Gradle compile 是类型/语法验证的唯一来源**——代码改动后始终运行 `compileDebugKotlin`；任何工具若重新启用 LSP，不要信其诊断。

All Gradle commands require `JAVA_HOME=/home/mar/android-studio/jbr`. ANDROID_HOME is optional if already in env.

## Build Commands

```bash
# Fast compile check (~30s, no APK)
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin

# Debug APK
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug --no-daemon
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (signed, R8 minified, ~12MB, ~1.5min)
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk

# Unit tests
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

Always run a build after code changes to verify compilation — there is no LSP fallback.

## Project

- **Package**: `cn.vectory.syncplayer`
- **Repo**: `http://192.168.3.252:11330/mfreceiver/sync-player.git` (branch: `main`)
- **Target**: Android 14+ (minSdk 34, targetSdk 35, compileSdk 35), personal use, sideloaded
- **JVM target**: 17
- **Current version**: `git describe --tags`（git 派生，见 [conventions.md](docs/specs/conventions.md#versioninggit-派生)）

技术栈表、目录结构详见 [architecture.md](docs/specs/architecture.md)。

## 核心约定速查（详见 spec 文档）

- **架构**: MVVM + Repository（Player 用 MVI），Hilt DI，Single Activity + Compose。高频状态独立 StateFlow，重组隔离。→ [architecture.md](docs/specs/architecture.md)
- **测试**: JUnit4 + MockK + Coroutines Test；断言枚举/数量/调用次数，**绝不**断言用户文案；suspend 用 `coEvery`；CancellationException 不吞。→ [conventions.md](docs/specs/conventions.md#testing)
- **i18n**: 所有用户可见文本在 `strings.xml`；Repository 不依赖 Context 生成文案；日志诊断文本不本地化。→ [conventions.md](docs/specs/conventions.md#i18n--strings)
- **同步**: `SyncException(code,...)` 结构化错误（message=null）；E8 扫描失败清空 toDownload/toDelete（外部 SD v2：缺卷也按 E8）；重试仅瞬态码白名单（外部 SD v2 增 STORAGE_IO_TRANSIENT/KEY_UNAVAILABLE）；缺卡=STORAGE_UNAVAILABLE 整卷停派；CancellationException 先传播。→ [sync.md](docs/specs/sync.md)
- **存储/Provider**: 双卷（内部 `filesDir/userdata` + 已登记外部 SD 卷 CTR_V2 加密），`MediaFileStore` 门面 + tmp→finalize 两阶段提交 + CAS（expectedOldGeneration）PENDING→READY；sync 侧经 `SyncStoragePort` 端口；reset 任一卷不可达拒绝；Provider 异常边界不泄漏 URI/id/path。→ [storage-security.md](docs/specs/storage-security.md)
- **安全**: 生物识别 App Lock，FLAG_SECURE，SSH TOFU，凭证 EncryptedSharedPreferences。→ [storage-security.md](docs/specs/storage-security.md)
- **Database**: Room v4，破坏性迁移（`dropAllTables=true`），`parentPath` 索引。→ [conventions.md](docs/specs/conventions.md#database)
- **Performance**: ≥300ms debounce 重型 Flow；阻塞 I/O 在 `Dispatchers.IO`（`@IoDispatcher` 注入可测）。→ [conventions.md](docs/specs/conventions.md#performance)
- **Versioning**: git 派生（versionCode/versionName），发版用 `./scripts/release.sh`。→ [conventions.md](docs/specs/conventions.md#versioninggit-派生)

## 安全红线（改代码时不可违背）

1. **E8 扫描失败保护**：部分远端快照绝不推导 DELETE_LOCAL。
2. **CancellationException 始终先传播**，不被 `catch(Exception)` 吞。
3. **下载两阶段提交不可弱化**：先写 `.tmp`、close 校验长度、finalize 原子 rename；`probePendingTmp` UNKNOWN 时只删行、不删 final。
4. **敏感信息不进日志/UI**：密码/密钥/凭据/path/底层 message 不暴露给用户。
5. **结构化错误不回退到 Throwable.message**：UI 用错误码→资源文案。
6. **删除 fail-closed**：文件存在性探测失败时保留 DB 行，绝不按"猜测不存在"删除。

## Release & Upload

> 完整发版规范见 [`docs/release.md`](docs/release.md)。本节是速查。

```bash
# 一次性凭据就绪：
./scripts/setup-signing.sh        # pass(GPG) → local.properties
./scripts/gitea/setup-secrets.sh  # local.properties → Gitea Secrets

# 每次发版（改动已在 main）：
./scripts/release.sh 12.0.5
# → 校验 + 本地门禁（compileDebugKotlin + testDebugUnitTest + lintDebug）+ 分类 changelog
# → annotated tag + push → release.yml CI 出签名 APK
```

`release.sh` 要求干净工作树（或 `--allow-dirty`）。它自己跑本地门禁，无需预跑测试。

Actions: `http://192.168.3.252:11330/mfreceiver/sync-player/actions`
Release: `http://192.168.3.252:11330/mfreceiver/sync-player/releases/tag/v<VERSION>`
