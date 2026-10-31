# sync-player 发版工程（本地发版）

> sync-player 的规范化发版流程。**纯本地发版，无 CI 参与**：`release.sh` 在本机完成
> 门禁 → 签名构建 → 产物归档 → tag → push，tag push 不触发任何 workflow。sync-player
> 是个人侧载应用——**不入 Play Store，故不出 AAB，只出 APK**。版本号全部由 git 派生，
> 仓库里没有任何手维护的版本字段。
>
> 仓库仍保留 `branch-check.yml` / `integration-check.yml` 两个与发版无关的校验 workflow，
> 底层配置见 [`docs/specs/gitea-cicd.md`](specs/gitea-cicd.md)。

---

## 1. 概述

| 项 | 值 |
|---|---|
| 发版入口 | `./scripts/release.sh <x.y.z>`（单命令，全流程本机完成） |
| 产出 | `dist/sync-player-<version>-<shortsha>.apk` + `dist/SHA256SUMS`（gitignored） |
| tag | annotated `v<x.y.z>`，注解 = 分类 changelog，随 main 一起 push |
| CI | 无（曾用 CI 发版，已于 2026-10-31 移除，改本地发版） |

sync-player **不入 Play Store**，因此：

- 只构建 **APK**，不构建 AAB。
- 发版即「打 tag」：版本号由 git 派生，**不打 tag 不发版**，也不手改 `versionCode` / `versionName`。

---

## 2. 版本模型（git 派生）

版本号在 `app/build.gradle.kts` 配置期由 git 现场计算，**不入库**任何版本文件：

| 字段 | 来源 | 说明 |
|---|---|---|
| `versionCode` | `git rev-list --count HEAD` | 单调递增整数，每个 commit 自增（v12.0.1 时为 234） |
| `versionName` | `git describe` 派生 | `<最近 tag 去 v>-<short hash>[-dirty]` |

关键逻辑（`app/build.gradle.kts`）：

```kotlin
val releaseVersionOverride = providers.gradleProperty("releaseVersion").orNull?.trim()?.orEmpty()
val gitVersionName: String = run {
    val nearestTag = gitOut("describe", "--tags", "--abbrev=0").removePrefix("v")
    val tagPart = releaseVersionOverride?.takeIf { it.isNotEmpty() } ?: nearestTag
    val shortHash = gitOut("rev-parse", "--short", "HEAD")
    val dirty = if (gitOut("status", "--porcelain").isEmpty()) "" else "-dirty"
    // → "<tagPart>-<shortHash><dirty>"
}
val gitVersionCode: Int = gitOut("rev-list", "--count", "HEAD").toIntOrNull()?.coerceAtLeast(1) ?: 1
```

- 工作树脏 → `versionName` 带 `-dirty` 后缀（`release.sh` 会对脏树警告）。
- **发版 APK 仍带 short hash**：`release.sh` 传 `-PreleaseVersion=<tag 去 v>` 只钉死 tag 段，
  hash 段永远反映 HEAD。例：tag `v12.1.0`、HEAD `abc1234` → 发版 APK 的 `versionName` =
  `12.1.0-abc1234`。
- **永远不要**手改 `versionCode` / `versionName`，发版走 [`scripts/release.sh`](../scripts/release.sh)。

---

## 3. 前置条件

### 3.1 本地签名（开发机）

签名凭据的 **source of truth 是 `pass`（GPG 加密）**，与 ocdroid / x-liker 家族一致。`pass` 条目：

```
sync-player/release/store-file       → keystore 绝对路径
sync-player/release/store-password   → keystore 密码
sync-player/release/key-alias        → key alias（缺省 release）
sync-player/release/key-password     → key 密码（缺省同 store 密码）
```

[`scripts/setup-signing.sh`](../scripts/setup-signing.sh) 从 `pass` 重新生成 gitignored 的
`local.properties`（含 `release.*` 四键 + 保留既有 `sdk.dir` / `java.home`）：

```bash
./scripts/setup-signing.sh          # pass → local.properties
```

> `local.properties` 不入库，丢了大不了重跑 `setup-signing.sh`。fallback：若 `pass` 不可用，
> `setup-signing.sh` 读 `$SYNC_PLAYER_RELEASE_CREDS`（默认 `/home/mar/.android/sync-player_release.creds`，
> 仓库外，mode 600）。

### 3.2 构建环境

所有 Gradle 调用需要：

```bash
JAVA_HOME=/home/mar/android-studio/jbr   # Android Studio bundled JBR (OpenJDK 21)
```

仓库脚本统一 `source scripts/env.sh` 设置 `JAVA_HOME` / `ANDROID_HOME`，不要在别处重复 export。

---

## 4. 发版流程（本地，单命令）

### 4.1 `release.sh` 做什么

[`scripts/release.sh`](../scripts/release.sh) 校验 → **本地门禁** → 生成分类 changelog →
签名构建 → 产物归档 → 打 annotated tag → push：

1. **校验**：版本格式 `x.y.z`；tag 未被占用；当前分支（非 `main` 仅警告）；工作树干净。
   脏树**默认报错**（里程碑发版要求干净树），`--allow-dirty` 显式放行（此时 `versionName` 带 `-dirty`）。
2. **本地门禁**：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin testDebugUnitTest lintDebug`
   ——破损代码**连构建都不进**（`JAVA_HOME` 缺省 `/home/mar/android-studio/jbr`，不可用时报错退出）。
3. **生成分类 changelog**：上次 tag..HEAD 的 conventional commits 按类型分节（见 §5）。
4. **签名构建**：`./gradlew assembleRelease -PreleaseVersion=<tag去v>`（读 `local.properties`
   的 `release.*` 签名）→ 复制 `app-release.apk` 到 `dist/sync-player-<version>-<shortsha>.apk`，
   同目录生成 `SHA256SUMS`（`dist/` 已 gitignore）。
5. **打 annotated tag** `v<x.y.z>`：把分类 changelog 写进 tag 注解（`git tag -a -F`）。
6. **push**：`git push origin main <tag>`。tag push **不触发任何 CI**；push 失败自动回滚本地 tag——
   失败路径（门禁红 / assembleRelease 失败 / push 失败）都不会留下已 push 或悬空的 tag。

> 构建与产物在 tag 之前完成：assembleRelease 失败时流程在打 tag 前终止，无需清理。
> `release.sh` 会 push `main`（改动须已 commit 到 main；up-to-date 时该 push 为 no-op）。

### 4.2 端到端命令序列

```bash
# 一次性（凭据就绪）：
./scripts/setup-signing.sh       # pass → local.properties（本地签名）

# 每次发版（release.sh 内含本地门禁 + 签名构建）：
./scripts/release.sh 12.1.0      # 校验 → 门禁 → changelog → assembleRelease → dist/ → tag → push

# 可选独立预检：./scripts/check.sh --full（加 lintDebug）

# 取产物：dist/sync-player-12.1.0-<shortsha>.apk + dist/SHA256SUMS
# 安装：  adb install -r dist/sync-player-12.1.0-<shortsha>.apk
```

脚本退出即发版完成；产物在本机 `dist/` 目录（有意不落 mapping——个人侧载无线上崩溃
符号化需求；R8 产物留在 `app/build/outputs/mapping/release/mapping.txt`，本地保留可选）。

---

## 5. Changelog 规范

`release.sh` 自动生成 **分类 changelog**，写入 **annotated tag 注解**（本地 `git tag -n99` /
`git show` 可查看）。

生成逻辑：`git log --no-merges <上次 tag>..HEAD` 取全部 commit subject，按 conventional-commit
正则 `^([a-zA-Z]+)(\([^)]*\))?!?:[[:space:]](.+)$` 解析出 `type` / `scope` / `description`（scope 被丢弃，
只保留 description），再按 `type` 归入分节：

| type | 分节 |
|---|---|
| `feat` | Features |
| `fix` | Bug Fixes |
| `docs` | Documentation |
| `test` / `tests` | Tests |
| `refactor` | Refactor |
| `perf` | Performance |
| `chore` / `ci` / `build` / `style` / `revert` / 其它 | Miscellaneous |
| `release` | **跳过**（不发版 meta 噪声） |

特点：

- **不落盘** `CHANGELOG.md`——changelog 只存 tag 注解。
- 本地查看：`git tag -n99 v<x.y.z>` 或 `git show v<x.y.z>`。
- 非 conventional 格式的 commit（如纯中文 subject）归入 Miscellaneous。

**强烈建议** commit message 用 conventional-commit 前缀，让分类自动生效：

```
feat:            新功能
fix(scope):      修 bug（scope 可选，会被丢弃只留描述）
docs:            文档
test:            测试
refactor:        重构（不改行为）
perf:            性能
chore/ci/build/style/revert:  杂项
release:         发版元提交（自动跳过）
```

---

## 6. 签名与凭据

### 6.1 本地（source of truth = `pass`）

- **`pass`**（GPG 加密）是签名凭据的 source of truth，条目 `sync-player/release/{store-file,store-password,key-alias,key-password}`——与 ocdroid / x-liker 家族一致。
- `scripts/setup-signing.sh` 从 `pass` 重新生成 gitignored 的 `local.properties`（保留 `sdk.dir` / `java.home` 等非 `release.*` 键）；fallback `$SYNC_PLAYER_RELEASE_CREDS` 平文件。
- `app/build.gradle.kts` 的 `signingConfigs.release` 在配置期读 `local.properties`；缺省值：`storeFile=release.keystore`、`keyAlias=release`、`keyPassword=storePassword`。
- keystore 文件本身不入库；重建 `local.properties` 只需重跑 `setup-signing.sh`。完整链路：`pass → setup-signing.sh → local.properties`。
- 凭据缺失时 AGP `validateSigningRelease` **大声失败**（无静默 debug-key 回退）。

### 6.2 注意

- **keystore 密码改了 / 换了新 key** → 重跑 `./scripts/setup-signing.sh` 覆盖 `local.properties`。
- **签名指纹变了**（换 key）→ 手机上的旧版本需**先卸载**，签名不同无法覆盖安装。

---

## 7. 产物

`release.sh` 把构建产物归档到 `dist/`（gitignored）：

| 文件 | 来源 | 大小（典型） | 用途 |
|---|---|---|---|
| `sync-player-<version>-<shorthash>.apk` | `assembleRelease` + R8 minify + 签名 | ~10–12 MB | 安装包（个人侧载） |
| `SHA256SUMS` | `cd dist && sha256sum *` | 小 | 上述文件的校验和 |

命名规则：`sync-player-$VERSION-$SHORT.apk`，其中 `VERSION` = tag 去 `v`（如 `12.1.0`），
`SHORT` = `git rev-parse --short HEAD`。**无 AAB**（不入商店）。

`mapping.txt` **有意不归档**（个人侧载应用，无线上崩溃符号化需求）：R8 产物留在本地
`app/build/outputs/mapping/release/mapping.txt`，是否保留可选，需要时可用于本地符号化。

---

## 8. 故障排查

- **keystore 密码不匹配 / alias 不存在**
  → 修正 `local.properties`（或重跑 `./scripts/setup-signing.sh`）后重发。

- **assembleRelease 失败**
  → 流程在打 tag 前终止，不会留下 tag / push；修好后直接重跑 `release.sh` 即可。

- **push 失败（网络 / 远端拒绝）**
  → 脚本自动删除本地 tag 并退出；重跑 `release.sh`（门禁有 Gradle 缓存，第二次很快）。
    若 `main` 已 push 而 tag 回滚，也可手动补发：
    ```bash
    git tag -a v12.1.0 -m "..."   # 或从 changelog 重建
    git push origin v12.1.0
    ```

- **重发同一版本**
  → 本地 `git tag -d v12.1.0` 后重跑 `release.sh`（重新构建、重新打 tag）；若旧 tag 已在远端，
    先 `git push origin :refs/tags/v12.1.0` 删远端再推。

- **`testReleaseUnitTest` not found**
  → 门禁用 `testDebugUnitTest`（release build 开 R8 minify 会 strip 测试类，无 release variant 单测）。

- **发版 APK 的 `versionName` 还带 hash**（如 `12.1.0-abc1234`）
  → 这是设计：`-PreleaseVersion` 只钉 tag 段，hash 段永远反映 HEAD（§2）。

- **签名变了手机装不上**
  → 卸载旧版本再装。不同签名无法覆盖安装（§6.2）。
