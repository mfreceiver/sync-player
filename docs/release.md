# sync-player 发版工程

> sync-player 的规范化发版流程。两种模式：**自动发版**（CI 主路径，推荐）与 **手动发版**
> （CI 不可用时的 fallback）。sync-player 是个人侧载应用——**不入 Play Store，故不出 AAB，
> 只出 APK**。版本号全部由 git 派生，仓库里没有任何手维护的版本字段。
>
> CI 镜像 / runner / workflow 的底层配置见 [`docs/specs/gitea-cicd.md`](specs/gitea-cicd.md)；
> 本文聚焦「维护者如何发一个版本」，不重复 CI 落地细节。

---

## 1. 概述

| 模式 | 触发 | 产出 | 适用 |
|---|---|---|---|
| **自动发版（推荐）** | `./scripts/release.sh <x.y.z>` 打并推送 `v*` tag | CI 出签名 APK + SHA256SUMS（有意不上传 mapping；本地保留可选），自动建 Gitea Release | 日常发版 |
| **手动发版（fallback）** | 本地 `assembleRelease` + curl Gitea API | 仅 APK（无 mapping / SHA256SUMS） | CI / runner 宕机 |

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
- **CI 出的 APK 仍带 short hash**：`release-check.sh` 传 `-PreleaseVersion=<tag 去 v>` 只钉死 tag 段，hash 段永远反映 HEAD。例：tag `v12.1.0`、HEAD `abc1234` → 发版 APK 的 `versionName` = `12.1.0-abc1234`。
- **永远不要**手改 `versionCode` / `versionName`，发版走 [`scripts/release.sh`](../scripts/release.sh)。

---

## 3. 前置条件

### 3.1 Gitea Secrets（CI 签名用）

4 个 Secret，由 [`scripts/gitea/setup-secrets.sh`](../scripts/gitea/setup-secrets.sh) 一次性注入
（轮换签名时重跑，幂等）：

| Secret | 值 |
|---|---|
| `RELEASE_KEYSTORE` | keystore 的 base64（`base64 -w0 release.keystore`） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key alias（缺省 `release`） |
| `RELEASE_KEY_PASSWORD` | key 密码（缺省同 store 密码） |

4 个签名 Secret 由 [`scripts/gitea/setup-secrets.sh`](../scripts/gitea/setup-secrets.sh) 一次性注入
（轮换签名时重跑，幂等），上传前用 keytool 校验。

> **WECOM_WEBHOOK 不是 repo Secret，是站点级 Actions Variable**（管理后台 → 管理设置 → 工作流 →
> 变量），全实例仓库（sync-player / ocdroid / x-liker …）继承，workflow 里引用 `${{ vars.WECOM_WEBHOOK }}`。
> 已验证：sync-player workflow 能解析到（89 字符），且 Gitea 在日志里自动 mask 为 `***`。
> 轮换 webhook 去管理后台改该 Variable（本地 `pass` 的 `sync-player/wecom-webhook` 仍是 source of truth 备忘）。

### 3.2 本地签名（开发机）

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
./scripts/gitea/setup-secrets.sh    # local.properties → Gitea Secrets（CI 用）
```

> `local.properties` 不入库，丢了大不了重跑 `setup-signing.sh`。fallback：若 `pass` 不可用，
> `setup-signing.sh` 读 `$SYNC_PLAYER_RELEASE_CREDS`（默认 `/home/mar/.android/sync-player_release.creds`，
> 仓库外，mode 600）。

### 3.3 构建环境

所有 Gradle 调用需要：

```bash
JAVA_HOME=/home/mar/android-studio/jbr   # Android Studio bundled JBR (OpenJDK 21)
```

仓库脚本统一 `source scripts/env.sh` 设置 `JAVA_HOME` / `ANDROID_HOME`，不要在别处重复 export。

---

## 4. 自动发版流程（推荐）

### 4.1 `release.sh` 在本地做什么

[`scripts/release.sh`](../scripts/release.sh) 校验 → **本地门禁** → 生成分类 changelog → 打 annotated tag → 推送：

1. **校验**：版本格式 `x.y.z`；tag 未被占用；当前分支（非 `main` 仅警告）；工作树干净。
   脏树**默认报错**（里程碑发版要求干净树），`--allow-dirty` 显式放行（此时 `versionName` 带 `-dirty`）。
2. **本地门禁**：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin testDebugUnitTest lintDebug`
   ——破损代码**连 tag 都打不出去**，避免红 CI（`JAVA_HOME` 缺省 `/home/mar/android-studio/jbr`，不可用时报错退出）。
3. **生成分类 changelog**：上次 tag..HEAD 的 conventional commits 按类型分节（见 §6）。
4. **打 annotated tag** `v<x.y.z>`：把分类 changelog 写进 tag 注解（`git tag -a -F`）。
5. **`git push origin <tag>`** → 触发 [`release.yml`](../.gitea/workflows/release.yml)。

> `release.sh` 自动 push tag（一步到位触发 CI），但**不** push `main`——改动须已先在 main。
> 想在 push 前人工核验 APK，可手动跑 `JAVA_HOME=... ./gradlew assembleRelease` 看
> `app/build/outputs/apk/release/app-release.apk` 后再 push。

### 4.2 `release.yml`（CI）自动做什么

| 步骤 | 动作 |
|---|---|
| Checkout | 内联 `git clone`（直连 Gitea）+ `fetch --tags` + checkout **tag 解引用 commit**（`refs/tags/$TAG^{commit}`；dispatch 场景用必填 tag 输入，任意分支发起均可，只认 tag） |
| 恢复 keystore | 解 `RELEASE_KEYSTORE` base64 → `$RUNNER_TEMP/release.keystore`，写 `local.properties`（含 4 个 `release.*`） |
| release-check | `GRADLE_USER_HOME=/root/.gradle/release` 下跑 `clean + lintRelease + testDebugUnitTest + assembleRelease -PreleaseVersion=<v>` → 归档到 `artifacts/` |
| 上传 | `publish-release.sh <tag>`：幂等（既有 release 先 DELETE 再 POST）→ **body 取自 annotated tag 注解的分类 changelog**（POSIX awk JSON-escape）→ 上传 APK + SHA256SUMS 两个 asset（有意不上传 mapping——个人侧载无线上崩溃符号化需求；本地保留可选） |
| 清理 | `if: always()` 删临时 keystore / `local.properties` / `~/.git-credentials` |
| 通知企业微信 | `if: always()` → `notify-wecom.sh`：成功发绿色（版本 + 下载/Changelog 链接），失败发红色（Actions 链接）。best-effort——`WECOM_WEBHOOK` 缺失或 qyapi 不可达只警告，不影响发版结果 |

### 4.3 端到端命令序列

```bash
# 一次性（凭据就绪）：
./scripts/setup-signing.sh       # pass → local.properties（开发机签名）
./scripts/gitea/setup-secrets.sh # local.properties → Gitea Secrets（CI 签名；轮换签名时重跑）

# 每次发版（release.sh 内含本地门禁 compileDebugKotlin + testDebugUnitTest + lintDebug）：
./scripts/release.sh 12.1.0      # 校验 → 门禁 → 分类 changelog → annotated tag → push → 触发 release.yml

# 可选独立预检：./scripts/check.sh --full（加 lintDebug）

# 监控：  http://192.168.3.252:11330/mfreceiver/sync-player/actions
# 取产物：http://192.168.3.252:11330/mfreceiver/sync-player/releases/tag/v12.1.0
```

CI 全绿即发版完成；产物（APK + SHA256SUMS）挂在 Gitea Release 下——有意不上传 mapping
（个人侧载应用，无线上崩溃符号化需求；本地保留可选）。

---

## 5. 手动发版流程（fallback）

当 CI / runner 不可用时，本地出包 + curl Gitea API 自建 Release。**仅出 APK**——
不上传 mapping / SHA256SUMS，也不写 changelog body，故 CI 流程产出更全，应优先用自动发版。

```bash
# 1) 本地签名构建（需 local.properties 里有 release.* 四键）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk

# 2) 从 ~/.git-credentials 取 token
TOKEN=$(grep '192.168.3.252:11330' ~/.git-credentials | sed 's|http://[^:]*:\([^@]*\)@.*|\1|')
VERSION=12.1.0

# 3) 建 Release（target_commitish 指向 main）
curl -s -X POST "http://192.168.3.252:11330/api/v1/repos/mfreceiver/sync-player/releases" \
  -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
  -d "{\"tag_name\":\"v$VERSION\",\"target_commitish\":\"main\",\"name\":\"v$VERSION\",\"draft\":false,\"prerelease\":false}"
# 返回 JSON 的 "id" 即 RELEASE_ID

# 4) 上传 APK asset
curl -s -X POST "http://192.168.3.252:11330/api/v1/repos/mfreceiver/sync-player/releases/RELEASE_ID/assets" \
  -H "Authorization: token $TOKEN" \
  -F "attachment=@app/build/outputs/apk/release/app-release.apk" -F "name=app-release.apk"
```

> 手动流程**不会**生成 mapping / SHA256SUMS，也不会把 changelog 写进 tag 注解（除非你先跑
> `release.sh` 的 tag 步骤）。仅作 CI 全挂时的应急通道。

---

## 6. Changelog 规范

`release.sh` 自动生成 **分类 changelog**，写入 **annotated tag 注解**，并由 `publish-release.sh`
回填到 Gitea Release 的 `body`（见 §4.2）。

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

- **不落盘** `CHANGELOG.md`——changelog 只存 tag 注解 + Gitea Release body。
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

## 7. 签名与凭据

### 7.1 本地（source of truth = `pass`）

- **`pass`**（GPG 加密）是签名凭据的 source of truth，条目 `sync-player/release/{store-file,store-password,key-alias,key-password}`——与 ocdroid / x-liker 家族一致。
- `scripts/setup-signing.sh` 从 `pass` 重新生成 gitignored 的 `local.properties`（保留 `sdk.dir` / `java.home` 等非 `release.*` 键）；fallback `$SYNC_PLAYER_RELEASE_CREDS` 平文件。
- `app/build.gradle.kts` 的 `signingConfigs.release` 在配置期读 `local.properties`；缺省值：`storeFile=release.keystore`、`keyAlias=release`、`keyPassword=storePassword`。
- keystore 文件本身不入库；**重建 `local.properties` 后**，跑 `setup-secrets.sh` 同步到 Gitea Secrets。完整链路：`pass → setup-signing.sh → local.properties → setup-secrets.sh → Gitea Secrets`。

### 7.2 CI（4 Gitea Secrets）

`release.yml` 把 4 个 Secret 解出，写 `$RUNNER_TEMP/release.keystore`（base64 解码）+
`local.properties`（4 个 `release.*` + `sdk.dir=$ANDROID_HOME`），`chmod 600`。随后跑一道
**fail-fast keytool 校验**（`keytool -list` 验 store 密码 + alias，~1s）——坏凭据立即在「恢复 keystore」
step 红，省掉 6 分钟构建后 `packageRelease` 才炸（与 §7.3 本机校验互补：本机挡「推坏 Secret」，CI 挡
「Secret 推上去后漂移/过期」）。job 结束 `if: always()` 清理。

### 7.3 keytool 预校验

`setup-secrets.sh` 上传前用 `$JAVA_HOME/bin/keytool` 做**两道校验**：

```bash
keytool -list -keystore <storeFile> -storepass <storePassword>              # store 密码能开库
keytool -list -keystore <storeFile> -storepass <storePassword> -alias <alias>  # alias 存在
```

任一失败 → 立即退出，**不写任何 Secret**，避免用无效凭据覆盖掉线上可用的 Secret。

### 7.4 注意

- **keystore 密码改了 / 换了新 key** → 必须重跑 `setup-secrets.sh` 覆盖 4 个 Secret。
- **签名指纹变了**（换 key）→ 手机上的旧版本需**先卸载**，签名不同无法覆盖安装。
- 轮换是幂等的：`setup-secrets.sh` 对每个 Secret 走 `PUT`，重跑即覆盖。

---

## 8. CI 工作流细节（release.yml）

| 项 | 值 |
|---|---|
| 触发 | `on.push.tags: v*` + `workflow_dispatch`（UI 手动补发，**必填 tag 输入**——构建 checkout 该 tag 所指 commit；缺失/非法/无法解引用即失败，绝不猜测） |
| Runner | `runs-on: android`（单共享 runner，与 ocdroid 共用 `universal-ci-android:35-jdk21` 镜像） |
| Checkout | 内联 `git clone` 直连 Gitea（无外部 action，Unraid 不通 github.com） |
| Gradle 隔离 | `GRADLE_USER_HOME=/root/.gradle/release`——**按工作流隔离**，避免与 integration / branch 并发时 journal 锁冲突（详见下文） |
| Publish 解析 | CI 镜像**现有** python3/jq，但 `publish-release.sh` **故意保持 POSIX**（`grep` / `cut` / `head` + awk JSON-escape）——镜像曾缺过 python3（导致 run #196 `exit 127`），POSIX 工具在所有 base image 永不缺失，避免镜像漂移再次阻塞发版 |
| 发布幂等 | `publish-release.sh` 先 `DELETE` 既有 release 再 `POST` 重建，重传同名 asset 不报冲突 |

**Gradle 并发隔离**：三个 workflow 各自独占 `GRADLE_USER_HOME`，避免共享 `caches/` 与
journal lock 互相阻塞：

```
branch-check       → /root/.gradle/branch
integration-check  → /root/.gradle/integration
release            → /root/.gradle/release
```

CI 镜像 / runner / `valid_volumes` / 缓存挂载等底层配置见
[`docs/specs/gitea-cicd.md`](specs/gitea-cicd.md) §6。

---

## 9. 产物

`release-check.sh` 把构建产物归档到 `artifacts/`，由 `publish-release.sh` 全量上传到 Gitea Release：

| 文件 | 来源 | 大小（典型） | 用途 |
|---|---|---|---|
| `sync-player-<version>-<shorthash>.apk` | `assembleRelease` + R8 minify + 签名 | ~10–12 MB | 安装包（个人侧载） |
| `SHA256SUMS` | `cd artifacts && sha256sum *` | 小 | 上述文件的校验和 |

`mapping.txt` **有意不上传**（个人侧载应用，无线上崩溃符号化需求）：R8 产物留在本地
`app/build/outputs/mapping/release/mapping.txt`，是否保留可选，需要时可用于本地符号化。

命名规则：`release-check.sh` 用 `sync-player-$VERSION-$SHORT.apk`，其中 `VERSION` = tag 去 `v`
（如 `12.1.0`），`SHORT` = `git rev-parse --short HEAD`。**无 AAB**（不入商店）。

---

## 10. 故障排查

- **keystore 密码不匹配 / alias 不存在**
  → `setup-secrets.sh` 的 keytool 预校验会先红。修正 `local.properties` 的 `release.storePassword` /
    `release.keyAlias` 后重跑 `setup-secrets.sh` 覆盖 4 个 Secret。

- **`publish-release.sh` / `notify-wecom.sh` 故意保持 POSIX（不用 python3/jq）**
  → CI 镜像**现有** python3/jq，但镜像曾缺过 python3（导致 run #196 `exit 127`）。POSIX `grep` / `cut` / `head` / `awk` 在所有 base image 永不缺失——故意不绑死镜像工具集，避免镜像漂移再次阻塞发版。
    （`setup-secrets.sh` 用 python3——它跑在**本机**，开发机有 python3，**不在 CI**。）

- **Gradle journal 锁超时**（release 与 integration 并发）
  → 已由 `release.yml` 的 `GRADLE_USER_HOME=/root/.gradle/release` 隔离解决（§8）。

- **`release.yml` 失败要重跑**
  → `publish-release.sh` 幂等（DELETE + 重建）。最简：在 Gitea Actions UI 用 `workflow_dispatch`
    「Run workflow」**填入要重发的 `v*` tag**（构建会 checkout 该 tag 所指 commit）；或删远端 tag 再推本地
    annotated tag（保留注解）：
    ```bash
    git push origin :refs/tags/v12.1.0   # 删远端 tag（本地 annotated tag 仍在）
    git push origin v12.1.0              # 重推 → 重新触发 release.yml
    ```

- **`testReleaseUnitTest` not found**
  → release 用 `testDebugUnitTest`（release build 开 R8 minify 会 strip 测试类，无 release variant 单测）。

- **发版 APK 的 `versionName` 还带 hash**（如 `12.1.0-abc1234`）
  → 这是设计：`-PreleaseVersion` 只钉 tag 段，hash 段永远反映 HEAD（§2）。

- **签名变了手机装不上**
  → 卸载旧版本再装。不同签名无法覆盖安装（§7.4）。

---

## 11. 与 ocdroid / x-liker 的对比（三项目发版流程）

> 面向跨项目维护者。sync-player 列经本文脚本核实；ocdroid / x-liker 列取自对其仓库脚本的实际分析。

| 维度 | **sync-player** | ocdroid | x-liker |
|---|---|---|---|
| 版本来源 | git 派生（`rev-list --count` + `describe`，CI 传 `-PreleaseVersion`） | git 派生（同款，`-PreleaseVersion`） | **硬编码** build.gradle（手动 bump versionCode/versionName） |
| 发版入口 | `release.sh <x.y.z>` | `release.sh patch\|minor\|major`（bump 上一 tag） | 手动改版本 + CHANGELOG |
| **push 时机** | **自动 push tag**（一步触发 CI） | **不 push**（打印指令，人工核验 APK 后再 push） | 全手动（commit main → tag → push） |
| 本地门禁 | ✓ `release.sh` 内建 compile + test + lint | ✓ `release.sh` 内 `check.sh` | ✓ 手动 `check.sh --full` |
| changelog | ✓ 自动分类 conventional commits → tag 注解 + release body | ✓ 自动分类（同款）→ tag 注解 + 手动上传 body | 手动 `CHANGELOG.md`（release body 不上传） |
| 凭据源 | `pass` → `setup-signing.sh` → local.properties → Secrets | `pass`（`ocdroid/release/*`）+ `setup-signing.sh` | `pass`（`x-liker/release/*`）+ `secrets.sh` |
| **Gradle 并发隔离** | ✓ **按工作流隔离** `GRADLE_USER_HOME`（branch/integration/release） | 无（单 runner capacity=1） | 无（文档警告 journal 锁，靠串行约定） |
| Secrets 上传校验 | ✓ `setup-secrets.sh` keytool 预校验 | 无 `setup-secrets.sh`（手填 UI/API） | 无（手填） |
| publish JSON 解析 | POSIX grep/cut + awk（**故意**，免疫镜像 python3 漂移） | python3（镜像现有则可用；但镜像漂移会复现 run #196 类故障） | grep -oP（POSIX-safe） |
| 发布幂等 | **delete + recreate** | find-or-create | find-or-create |
| 产物 | APK + SHA256SUMS（无 AAB；有意不上传 mapping） | APK + **AAB** + mapping + SHA256SUMS | APK + mapping + SHA256SUMS（无 AAB） |
| lint 门控 | `abortOnError=true`（lint-clean） | `abortOnError=true` | （lint 状态未明确） |
| 覆盖率门控 | 无 kover | ✓ kover（koverVerify） | 无 |

**sync-player 相对两个兄弟项目的主要发版改进**：Gradle `GRADLE_USER_HOME` 按工作流隔离（消除并发
journal 锁——ocdroid/x-liker 都在文档里警告此问题）、`setup-secrets.sh` 的 keytool 预校验（杜绝无效凭据
推进 Gitea）、`publish-release.sh` 幂等 delete+recreate + POSIX-only（重跑不脏、CI 镜像无 python3 也能跑）。
**从兄弟项目采纳**：ocdroid 的自动分类 changelog + release.sh 本地门禁、家族统一的 `pass` 凭据源。
