# sync-player × Gitea Actions CI/CD

> 本仓库接入 Gitea Actions CI/CD 的落地文档。CI 镜像 / runner 与 ocdroid **共享**
> （`universal-ci-android:35-jdk21`，act_runner 标签 `android`，部署在 Unraid），
> runner 部署细节见 ocdroid 的 `docs/specs/gitea-cicd.md` §2。本文只覆盖 sync-player 侧。
>
> **发版不走 CI**（2026-10-31 起本地发版，CI 发版 workflow / Secrets / publish 脚本已移除）：
> 发版流程见 [`docs/release.md`](../release.md)。本文仅覆盖保留的两个校验 workflow。

---

## 0. 范围差异（vs ocdroid）

| 项 | ocdroid | sync-player |
|---|---|---|
| 版本号 | git 派生（tag + short hash） | **git 派生**（`rev-list --count` + `describe`，CI 传 `-PreleaseVersion`，见 [`docs/release.md`](../release.md) §2） |
| 覆盖率门控 | kover（koverVerify） | **无 kover** |
| 产物 | APK + AAB + mapping | **APK + SHA256SUMS**（个人侧载，有意不上传 mapping，不入 Play Store） |
| Release runner | android-release（专用，Phase 5） | **android**（单 runner，共用） |
| lint 门控 | abortOnError=true（lint-clean） | **abortOnError=true**（lint-clean，lint 债务已偿清） |

---

## 1. 本机 / 远端校验边界

| 位置 | 脚本 / 命令 | 用途 |
|---|---|---|
| 本机 | `./scripts/ci/local-check.sh`（= `compileDebugKotlin + testDebugUnitTest`） | 开发期几十秒挡低级错误 |
| 本机 | `./scripts/check.sh [--full]` | 同上 + 可选 lintDebug |
| 远端分支 | `branch-check.yml` → `remote-check.sh`（`lintDebug + testDebugUnitTest + assembleDebug`） | 干净容器验证任务分支可构建 |
| 远端整合 | `integration-check.yml`（`clean + lint + test + assembleDebug`） | 验证与主干组合（无覆盖率门控；**仅手动触发**） |

---

## 2. 工作流触发

| Workflow | 触发 | 作用 |
|---|---|---|
| `branch-check.yml` | push `agent/**`、`feature/**`、`fix/**`、`ci/**` | 任务分支验证（lint + test + assembleDebug） |
| `integration-check.yml` | **仅 `workflow_dispatch`**（手动） | 全量校验（与发版无关；发版已改本地流程） |

无外部 action（Unraid 无法访问 github.com）；checkout 用内联 `git clone` 直连 Gitea。

> CI 发版 workflow 已于 2026-10-31 移除：发版改为 `./scripts/release.sh` 本地完成
> （门禁 + 签名构建 + `dist/` 产物 + tag + push），签名凭据走 `local.properties`，无需 Gitea Secrets。

---

## 4. 端到端验证

```bash
# 推一个 ci/** 分支触发 branch-check
git checkout -b ci/gitea-scaffold
git push -u origin ci/gitea-scaffold
```
到 Gitea → Actions 看 `branch-check` 是否被 runner 接单、Debug APK artifact 是否产出。绿 = 通过。

---

## 5. 验收清单

- [ ] push `ci/**` → 触发 `branch-check`，被 runner 接单。
- [ ] Debug APK artifact 可下载。
- [ ] 故意引入编译错误 → CI 红，状态绑定该 commit。

---

## 6. 回滚 / 维护

- **撤 CI**：删 `.gitea/workflows/*.yml`（仓库即时停）；runner 容器 `stop` 即停接单。
- **轮换签名**：重新生成 keystore → 跑 `./scripts/setup-signing.sh` 重建 `local.properties`（发版已本地化，无 Gitea Secrets）。
- **收紧 lint**：~~偿还 lint 债务后，把 `lint { abortOnError = false }` 改回 `true`~~——**已偿清** lint 债务，`app/build.gradle.kts` 现为 `lint { abortOnError = true }`（lint-clean）。新增 lint 问题时 CI 会直接红。
- **升级 CI 镜像**：SDK/AGP 升级时同步改 Unraid 上的 `universal-ci-android` 镜像（与 ocdroid 共享）。
- **加速 CI（Gradle 缓存）**：每个 job 是全新容器，且镜像不含 Gradle（`./gradlew` wrapper 自带），默认每次重新下载 distribution + 依赖。为避免重复下载，在 act_runner `config.yaml` 的 `container.options` 加 `-v /mnt/cache/appdata/gradle-cache:/root/.gradle`（并确保 `valid_volumes: ["**"]`），首次下完后续 job 复用。两个保留 workflow 分别使用 `/root/.gradle/{branch,integration}`，避免共享 journal lock；`gradle-wrapper.properties` 的 `networkTimeout=120000` 已为 services.gradle.org 慢响应留 2 分钟窗口。
- **宿主机维护：Gradle 缓存 TTL 淘汰**（可选）：namespaced `GRADLE_USER_HOME` 在共享卷下无界增长。**不要在 workflow 末尾清理**（会抵消缓存复用——隔离的目的就是跨 run 复用）。增长源是 `caches/`（依赖），`wrapper/dists/`（Gradle 发行包 ~150MB/版本）应保留。建议 Unraid 宿主机 cron 周期淘汰 `caches/`：
  ```bash
  # /etc/cron.d/gradle-cache-evict — 每周清理 30 天未触碰的依赖缓存（保留 wrapper/dists）
  0 4 * * 0 root find /mnt/cache/appdata/gradle-cache/*/caches -type f -mtime +30 -delete 2>/dev/null
  15 4 * * 0 root find /mnt/cache/appdata/gradle-cache -type d -empty -delete 2>/dev/null
  ```
  cron 落在 Unraid（不在 repo）；此项目依赖稳定，增长慢，清理为可选低优先维护。
