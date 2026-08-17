# Phase 10：配置与依赖
- 状态: DONE
- 本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。
- 锚点扫描对账:
  - 依赖版本对照（libs.versions.toml ↔ architecture.md Tech Stack） | scan_total=25 个版本锚点 | adjudicated=25（25/25 一致） | excluded_scope_or_FP=0 | unknown=0
  - `grep -nE 'uses-permission|allowBackup|…' app/src/main/AndroidManifest.xml` | scan_total=清单见 10.2 | adjudicated=8 权限/属性 | excluded_scope_or_FP=0 | unknown=0
  - `grep -rnE 'Manifest\.permission\.|android\.permission\.' --include='*.kt' app/src` | scan_total=0（exit=1 零命中） | adjudicated=0 | excluded_scope_or_FP=0 | unknown=0
  - `grep -rnE 'Class\.forName|@Keep' --include='*.kt' app/src/main` | scan_total=0（exit=1） | adjudicated=0 | excluded_scope_or_FP=0 | unknown=0
- 唯一发现: P0=0, P1=0, P2=2（CONFIRMED=1, CANDIDATE=1）；引用他 Phase 的 REF: 0
- U 条目: 1（U-10-1 版本时效/CVE）

## 10.1 依赖版本总表（来源：gradle/libs.versions.toml，单一来源）

| 依赖 | toml 版本 | architecture.md 记载 | 一致 |
|---|---|---|---|
| Kotlin | 2.2.10 | 2.2.10 | ✓ |
| AGP | 9.1.0 | 9.1.0 | ✓ |
| KSP | 2.3.6 | 2.3.6 | ✓ |
| Compose BOM | 2025.12.00 | 2025.12.00 | ✓ |
| Hilt | 2.59.2 | 2.59.2 | ✓ |
| Room | 2.7.0 | 2.7.0（schema v2） | ✓ |
| Media3 | 1.6.0 | 1.6.0 | ✓ |
| jcifs-ng | 2.1.10 | 2.1.10 | ✓ |
| SSHJ | 0.39.0 | 0.39.0 | ✓ |
| BouncyCastle | 1.80 | 1.80 | ✓ |
| security-crypto | 1.1.0-alpha06 | 1.1.0-alpha06（conventions:66 记录 alpha 为 accepted residual） | ✓ |
| biometric | 1.1.0 | 1.1.0 | ✓ |
| Coil | 2.7.0 | 2.7.0 | ✓ |
| exifinterface | 1.4.1 | 1.4.1 | ✓ |
| coroutines | 1.10.1 | 1.10.1 | ✓ |
| datastore | 1.1.4 | 1.1.4 | ✓ |
| navigation/lifecycle | 2.9.0 | 2.9.0 | ✓ |
| activity-compose | 1.10.0 | 1.10.0 | ✓ |
| core-ktx / appcompat | 1.16.0 / 1.7.0 | 1.16.0 / 1.7.0 | ✓ |
| junit / mockk / coroutines-test / turbine | 4.13.2 / 1.13.13 / 1.10.1 / 1.2.0 | 同 | ✓ |

- 集中管理：`grep -nE 'version\s*=?\s*"[0-9]' app/build.gradle.kts` exit=1（零内联钉扎）——ARCH-1/architecture.md:28 完全符合；解析来源全部 `libs.*` version catalog；根 build.gradle.kts 仅插件 alias；settings.gradle.kts 仅 `:app` 单模块 + `FAIL_ON_PROJECT_REPOS`。
- versionCode/versionName git 派生（build.gradle.kts:31-42，rev-list --count / describe），conventions:38-39 合规。

### U-10-1 [UNKNOWN] [依赖时效/CVE]
- 尝试: 已读 libs.versions.toml 全 82 行与 build.gradle.kts 全 223 行
- 结果: 25 个版本与 architecture.md 一致，但本地无任何带日期的漏洞数据库/权威时效证据
- 阻塞: 任务书禁止联网、禁止凭训练知识下结论——"是否最新版本/是否存在 CVE"不可静态复现
- 恢复条件: 提供离线 CVE 库或允许联网查询（如 OSV 按坐标+哈希锁定的查询记录）

## 10.2 Manifest 逐条核对

`grep -nE 'uses-permission|allowBackup|dataExtractionRules|exported|grantUriPermissions|readPermission|writePermission' app/src/main/AndroidManifest.xml`（exit=0）：

| 项 | 值 | 代码佐证 | 判定 |
|---|---|---|---|
| INTERNET | 声明 | SmbFileClient/SftpFileClient 网络传输 | PASS |
| ACCESS_NETWORK_STATE | 声明 | NetworkUtils.checkNetworkAvailable（SftpFileClient.kt:62 调用） | PASS |
| FOREGROUND_SERVICE(+DATA_SYNC) | 声明 | SyncService dataSync 前台服务 | PASS |
| POST_NOTIFICATIONS | 声明（Manifest.xml:8） | SyncNotificationHelper 发通知；**但代码零运行时请求**（见 F-10-1） | PARTIAL |
| WAKE_LOCK | 声明（Manifest.xml:9） | 代码零使用（F-10-2） | PARTIAL |
| 自定义 permission.READ_MEDIA | signature 级 | MediaContentProvider readPermission——仅同签名可读，供 ExoPlayer 等外部消费 | PASS |
| allowBackup | **false** | 数据暴露路径分析：backup/auto-transfer 全部禁用，EncryptedSharedPreferences/Room/媒体密文不会经 adb backup/云备份离开沙盒——无需 dataExtractionRules | PASS |
| MainActivity exported=true | 启动器必需 | 单 Activity | PASS |
| SyncService exported=false / FileProvider exported=false+grantUriPermissions | 隔离 | SyncService.kt / buildShareIntent | PASS |

### F-10-1 [P2] [CANDIDATE] [P10-权限]
- 类型: PRESENT
- 位置: app/src/main/AndroidManifest.xml:8
- 代码: `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
- 问题: minSdk 34（API 33+ 需运行时请求），但全库 `grep -rnE 'Manifest\.permission\.|android\.permission\.' --include='*.kt' app/src` exit=1 零命中——无处请求 POST_NOTIFICATIONS，新装用户的通知授权默认"未授权"，同步进度/完成通知可能从不显示（FGS 本身仍运行）。静态审计无法验证运行时授权流，故 CANDIDATE。
- 复现: grep -rn 'POST_NOTIFICATIONS' --include='*.kt' app/src （exit=1 零命中）
- 建议: 首次启动同步前请求（rememberLauncherForActivityResult.RequestPermission）。

### F-10-2 [P2] [CONFIRMED] [P10-权限]
- 类型: PRESENT
- 位置: app/src/main/AndroidManifest.xml:9
- 代码: `<uses-permission android:name="android.permission.WAKE_LOCK" />`
- 问题: 代码零使用（`grep -rn 'WakeLock' --include='*.kt' app/src` exit=1）；若为 Media3 的 setWakeLockEnabled 预留则该 API 默认关闭且未启用——未使用权限扩大授权面（个人应用影响小）。
- 复现: grep -rn 'WAKE_LOCK\|WakeLock\|wakeLock' --include='*.kt' app/src （exit=1 零命中）
- 建议: 删除或在使用 setWakeLockEnabled 时再加。

## 10.3 ProGuard/R8 与反射交叉核对

`grep -rnE 'Class\.forName|@Keep' --include='*.kt' app/src/main` → **0 处**应用层反射/@Keep。proguard-rules.pro 三组规则均针对库反射：
1. BouncyCastle `-keep class org.bouncycastle.** { *; }`——注释给出实证理由（Provider 反射实例化 $Mappings/算法实现，细粒度 keep 已被验证不足致 InternalError）。
2. jcifs/SSHJ 类名级 keep（注释：无自反射调用方法，方法体优化保留；SMB 已在 release 验证）。
3. `-assumenosideeffects android.util.Log`（v/d/i/w）——release 剥离调试日志、保留 error。**与 R6 交叉**：Log.w 的 volumeId 日志在 release 被剥离，但 DebugLogger 环形缓冲（F-2-27/F-2-28 的 UI 通道）不受该规则影响——release 仍暴露。
应用自身零反射点 → 无遗漏 keep。PASS。

## 10.4 Room schema / 破坏性迁移

- `exportSchema=true`（AppDatabase.kt:22）+ KSP `room.schemaLocation`（build.gradle.kts:130-132）→ `app/schemas/.../2.json` 存在 ✓（C-CV-9）。
- `fallbackToDestructiveMigration(dropAllTables = true)`（DatabaseModule.kt:34）✓（C-CV-10，REF Phase 6 矩阵 C-CR-11/C-CV-10 PASS）。

## 10.5 结论

构建配置高度规范：版本单一来源且与 spec 逐项一致、git 派生版本、签名经 local.properties、R8 规则有实证注释。缺口为两个权限卫生问题（POST_NOTIFICATIONS 未请求=功能缺陷 CANDIDATE；WAKE_LOCK 未使用 CONFIRMED）；依赖时效/CVE 按 任务书禁联网 一律 UNKNOWN（U-10-1）。
