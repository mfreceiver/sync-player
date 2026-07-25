#!/usr/bin/env bash
# scripts/release.sh — cut a Gitea release: tag + push → release.yml 出签名 APK。
# 版本号由 git 派生（app/build.gradle.kts 的 gitVersionName/gitVersionCode），
# 故只需打 tag，无需手动 bump versionCode/versionName。
#
# 用法: ./scripts/release.sh <version>
#   <version> 形如 12.1.0（自动加 v 前缀）；也接受 v12.1.0。
# 前置:
#   1. 4 个 RELEASE_* Secrets 已建（./scripts/gitea/setup-secrets.sh）
#   2. 改动已在 main（tag 应打在稳定线；在分支上发版会警告）
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

VERSION="${1:?用法: release.sh <version> (如 12.1.0)}"
[[ "$VERSION" == v* ]] && VERSION="${VERSION#v}"
TAG="v$VERSION"

# 校验版本格式 x.y.z
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "❌ 版本格式应为 x.y.z，如 12.1.0"; exit 1; }

# 校验 tag 未被占用
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ tag $TAG 已存在：$(git log -1 --format='%h %s' "$TAG" 2>/dev/null)"; exit 1
fi

# 校验当前分支（建议 main）
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" ]] || echo "⚠️  当前在 '$BRANCH' 分支，建议在 main 上发版（继续中...）"

# 校验工作树干净（脏树会让 versionName 带 -dirty）
if [[ -n "$(git status --porcelain)" ]]; then
  echo "⚠️  工作树不净，versionName 会带 -dirty 后缀。建议先 commit。"
  echo "    是否仍要打 tag？[y/N]"; read -r ans
  [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "已取消"; exit 1; }
fi

# 生成 release notes（上次 tag 以来的 commits）
LAST_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
if [[ -n "$LAST_TAG" ]]; then
  NOTES="$(git log --pretty=format:'- %s' "$LAST_TAG"..HEAD 2>/dev/null)"
else
  NOTES="$(git log --pretty=format:'- %s' 2>/dev/null | head -30)"
fi

echo "==> 打注解 tag $TAG（HEAD = $(git rev-parse --short HEAD)）"
git tag -a "$TAG" -m "Release $VERSION" -m "Changes since ${LAST_TAG:-start}:" -m "$NOTES"

echo "==> push tag → 触发 release.yml"
git push origin "$TAG"

echo ""
echo "✅ tag $TAG 已推送。release.yml 将自动："
echo "   clean + lintRelease + testRelease + assembleRelease（签名）→ 上传 APK/mapping/SHA256SUMS 到 Gitea Release"
echo "   Actions: https://git.vectory.cn:18443/mfreceiver/sync-player/actions"
echo "   Release: https://git.vectory.cn:18443/mfreceiver/sync-player/releases/tag/$TAG"
