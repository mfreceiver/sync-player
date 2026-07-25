#!/usr/bin/env bash
# scripts/ci/release-check.sh — Release 构建校验（.gitea/workflows/release.yml 调用，CI 签名）。
# 干净 CI 容器：clean + lintRelease + testReleaseUnitTest + assembleRelease。
# 签名：workflow 从 Gitea Secrets 解出 keystore → 写 local.properties → gradle signingConfigs.release 自动用。
# 版本：git 派生；tag 触发时传 -PreleaseVersion=<tag去v>，让 APK 使用干净 tag 版本名。
# 产物：artifacts/（APK + mapping.txt + SHA256SUMS）。无 AAB（个人侧载应用，不入 Play Store）。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# release.yml 由 v* tag 触发；workflow_dispatch 兜底取最近 tag。
TAG="${GITHUB_REF##*/}"
[[ "$TAG" == v* ]] && VERSION="${TAG#v}" || VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo snapshot)"
SHORT="$(git rev-parse --short HEAD)"
echo "==> release-check: tag=$TAG version=$VERSION short=$SHORT"
./gradlew --no-daemon --stacktrace clean lintRelease testReleaseUnitTest assembleRelease -PreleaseVersion="$VERSION"
echo "==> 归档产物"
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk "artifacts/sync-player-$VERSION-$SHORT.apk"
cp app/build/outputs/mapping/release/mapping.txt artifacts/mapping.txt 2>/dev/null || true
( cd artifacts && sha256sum * > SHA256SUMS )
echo "✅ release-check 通过 → artifacts/ 含 APK + mapping + SHA256SUMS"
