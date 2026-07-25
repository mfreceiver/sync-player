# sync-player × Gitea Actions CI/CD

> 本仓库接入 Gitea Actions CI/CD 的落地文档。CI 镜像 / runner 与 ocdroid **共享**
> （`universal-ci-android:35-jdk21`，act_runner 标签 `android`，部署在 Unraid），
> runner 部署细节见 ocdroid 的 `docs/specs/gitea-cicd.md` §2。本文只覆盖 sync-player 侧。

---

## 0. 范围差异（vs ocdroid）

| 项 | ocdroid | sync-player |
|---|---|---|
| 版本号 | git 派生（tag + short hash） | **硬编码** versionCode/versionName |
| 覆盖率门控 | kover（koverVerify） | **无 kover** |
| 产物 | APK + AAB + mapping | **仅 APK + mapping**（个人侧载，不入 Play Store） |
| Release runner | android-release（专用，Phase 5） | **android**（单 runner，共用） |
| lint 门控 | abortOnError=true（lint-clean） | **abortOnError=false**（既有 ~31 lint 债务，信息级） |

---

## 1. 本机 / 远端校验边界

| 位置 | 脚本 / 命令 | 用途 |
|---|---|---|
| 本机 | `./scripts/ci/local-check.sh`（= `compileDebugKotlin + testDebugUnitTest`） | 开发期几十秒挡低级错误 |
| 本机 | `./scripts/check.sh [--full]` | 同上 + 可选 lintDebug |
| 远端分支 | `branch-check.yml` → `remote-check.sh`（`lintDebug + testDebugUnitTest + assembleDebug`） | 干净容器验证任务分支可构建 |
| 远端整合 | `integration-check.yml`（`clean + lint + test + assembleDebug`） | 验证与主干组合（无覆盖率门控） |
| 远端 Release | `release.yml` → `release-check.sh`（`clean + lintRelease + testRelease + assembleRelease`） | CI 签名出 APK + mapping + SHA256SUMS |

---

## 2. 工作流触发

| Workflow | 触发 | 作用 |
|---|---|---|
| `branch-check.yml` | push `agent/**`、`feature/**`、`fix/**`、`ci/**` | 任务分支验证（lint + test + assembleDebug） |
| `integration-check.yml` | push `main`、`integration/**`、`validation/**` | 主干全量校验 |
| `release.yml` | push tag `v*` | 出签名 APK，上传 Gitea Release |

无外部 action（Unraid 无法访问 github.com）；checkout 用内联 `git clone` 直连 Gitea。

---

## 3. Gitea Secrets（仅 release.yml 用）

仓库 Settings → Actions → Secrets → Add Secret：

| Secret 名 | 值 |
|---|---|
| `RELEASE_KEYSTORE` | keystore 的 base64（`base64 -w0 release.keystore`） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key alias（如 `release`） |
| `RELEASE_KEY_PASSWORD` | key 密码 |

release.yml 上传 Release 用 Gitea 自动注入的 `GITHUB_TOKEN`（contents:write）；若 asset 上传 403，再建 `RELEASE_GITEA_TOKEN`（个人 token）并在 workflow 改用它。

---

## 4. 端到端验证

```bash
# 推一个 ci/** 分支触发 branch-check
git checkout -b ci/gitea-scaffold
git push -u origin ci/gitea-scaffold
```
到 Gitea → Actions 看 `branch-check` 是否被 runner 接单、Debug APK artifact 是否产出。绿 = 通过。

**release 验证**：建 4 个 Secrets 后打测试 tag：
```bash
git tag v12.0.0-ci-test && git push origin v12.0.0-ci-test
```
看 release.yml 是否出签名 APK 并建 Gitea Release；验后删 tag/release。

---

## 5. 验收清单

- [ ] push `ci/**` → 触发 `branch-check`，被 runner 接单。
- [ ] Debug APK artifact 可下载。
- [ ] 故意引入编译错误 → CI 红，状态绑定该 commit。
- [ ] `v*` tag → `release.yml` 出**签名** APK + mapping + SHA256SUMS，建 Gitea Release。
- [ ] 普通 runner 无法读 `RELEASE_*` Secrets。

---

## 6. 回滚 / 维护

- **撤 CI**：删 `.gitea/workflows/*.yml`（仓库即时停）；runner 容器 `stop` 即停接单。
- **轮换签名**：重新生成 keystore → 覆盖 Gitea Secret `RELEASE_KEYSTORE` + 3 密码。
- **收紧 lint**：偿还 lint 债务后，把 `app/build.gradle.kts` 的 `lint { abortOnError = false }` 改回 `true`。
- **升级 CI 镜像**：SDK/AGP 升级时同步改 Unraid 上的 `universal-ci-android` 镜像（与 ocdroid 共享）。
