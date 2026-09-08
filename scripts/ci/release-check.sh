#!/usr/bin/env bash
# scripts/ci/release-check.sh — Release 构建校验（.gitea/workflows/release.yml 调用，CI 签名）。
# 干净 CI 容器：clean + lintRelease + testDebugUnitTest + assembleRelease。
# 签名：workflow 从 Gitea Secrets 解出 keystore → 写 local.properties → gradle signingConfigs.release 自动用。
# 版本：git 派生；tag 触发时传 -PreleaseVersion=<tag去v>，让 APK 使用干净 tag 版本名。
# 产物：artifacts/（APK + SHA256SUMS + BUILD_INFO 构建清单，发布绑定凭证）。
# 无 AAB（个人侧载应用，不入 Play Store）；
# mapping.txt 不上传（个人自用，无线上崩溃符号化需求）。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# 发布绑定（F11）：发布必须绑定明确的 v* tag，绝不猜测「最近 tag」。
# TAG 来源：优先 workflow Checkout 步骤校验后经 GITHUB_ENV 传入的 RELEASE_TAG
# （dispatch 场景 GITHUB_REF 是发起分支名，不可用）；本地直跑回退要求 HEAD 精确位于 v* tag。
TAG="${RELEASE_TAG:-}"
if [[ -z "$TAG" ]]; then
  TAG="$(git describe --tags --exact-match HEAD 2>/dev/null || true)"
fi
if [[ "$TAG" != v* ]]; then
  {
    echo "❌ release-check 需要明确的 v* tag（当前：RELEASE_TAG=${RELEASE_TAG:-<未设置>}，"
    echo "   GITHUB_REF=${GITHUB_REF:-<未设置>}，HEAD exact-match=${TAG:-<无>}）。"
    echo "   手动补发请走 workflow_dispatch 填入已存在的 v* tag（workflow 会 checkout 该 tag）。"
  } >&2
  exit 1
fi
# CI 路径（RELEASE_TAG 已设置）：仅 v* 前缀不够，还必须满足发布绑定闭环——
# ① tag 真实存在且能解引用到 commit（^{commit} 剥 annotated tag 壳）；
# ② 该 commit == 当前 HEAD（workflow 已 checkout 该 tag；若不等说明 workspace
#    与 tag 错位，本次构建产物绝不能冒充该 tag 发布）。
if [[ -n "${RELEASE_TAG:-}" ]]; then
  TAG_COMMIT="$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" 2>/dev/null)" \
    || { echo "❌ RELEASE_TAG '$TAG' 不存在或无法解析为 commit（refs/tags/$TAG^{commit}）" >&2; exit 1; }
  HEAD_COMMIT="$(git rev-parse HEAD)"
  if [[ "$TAG_COMMIT" != "$HEAD_COMMIT" ]]; then
    {
      echo "❌ RELEASE_TAG '$TAG' 指向 commit ${TAG_COMMIT:0:10}，但当前 HEAD 是 ${HEAD_COMMIT:0:10}。"
      echo "   发布绑定失败：产物必须与 tag commit 严格一致，拒绝构建。"
    } >&2
    exit 1
  fi
fi
VERSION="${TAG#v}"
SHORT="$(git rev-parse --short HEAD)"
echo "==> release-check: tag=$TAG version=$VERSION short=$SHORT"
# 防残留（rev-1）：清空产物必须在 Gradle **之前**——若构建失败，workspace 不留
# 任何旧产物集合（fail-closed：失败构建不可留下能被 publish 误认的有效产物）。
rm -rf artifacts
./gradlew --no-daemon --stacktrace clean lintRelease testDebugUnitTest assembleRelease -PreleaseVersion="$VERSION"
echo "==> 归档产物"
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk "artifacts/sync-player-$VERSION-$SHORT.apk"
( cd artifacts && sha256sum * > SHA256SUMS )
# 构建清单（rev-1）：发布侧凭此做产物↔tag/commit 绑定校验。在 SHA256SUMS 之后
# 写入——清单本身是本地凭证而非发布资产，不进校验集，上传时被跳过。
printf 'TAG=%s\nCOMMIT=%s\n' "$TAG" "$(git rev-parse HEAD)" > artifacts/BUILD_INFO
echo "✅ release-check 通过 → artifacts/ 含 APK + SHA256SUMS + BUILD_INFO"
