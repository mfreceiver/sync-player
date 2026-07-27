#!/usr/bin/env bash
# scripts/ci/release-check.sh — Release 构建校验（.gitea/workflows/release.yml 调用，CI 签名）。
# 干净 CI 容器：clean + lintRelease + testDebugUnitTest + assembleRelease。
# 签名：workflow 从 Gitea Secrets 解出 keystore → 写 local.properties → gradle signingConfigs.release 自动用。
# 版本：git 派生；tag 触发时传 -PreleaseVersion=<tag去v>，让 APK 使用干净 tag 版本名。
# 产物：artifacts/（APK + SHA256SUMS）。无 AAB（个人侧载应用，不入 Play Store）；
# mapping.txt 不上传（个人自用，无线上崩溃符号化需求）。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# release.yml 由 v* tag 触发；workflow_dispatch 兜底取最近 tag。
TAG="${GITHUB_REF##*/}"
[[ "$TAG" == v* ]] && VERSION="${TAG#v}" || VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo snapshot)"
SHORT="$(git rev-parse --short HEAD)"
echo "==> release-check: tag=$TAG version=$VERSION short=$SHORT"
./gradlew --no-daemon --stacktrace clean lintRelease testDebugUnitTest assembleRelease -PreleaseVersion="$VERSION"
echo "==> 归档产物"
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk "artifacts/sync-player-$VERSION-$SHORT.apk"
( cd artifacts && sha256sum * > SHA256SUMS )
echo "✅ release-check 通过 → artifacts/ 含 APK + SHA256SUMS"
