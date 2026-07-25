#!/usr/bin/env bash
# scripts/gitea/publish-release.sh — CI 内把 release-check 产物上传到 Gitea Release。
# 用法: publish-release.sh <tag>     (tag 形如 v1.2.3；由 release.yml 调用)
# 环境：GITEA_TOKEN（release.yml 注入 auto github.token）、GITEA_URL。
# 产物目录：artifacts/（APK + mapping.txt + SHA256SUMS）。
# 说明：CI 镜像（universal-ci-android:35-jdk21）无 python3，故 JSON 解析仅用 POSIX
#       工具（grep/cut/head），不依赖 python3/jq。
set -euo pipefail
TAG="${1:?用法: publish-release.sh <tag>}"
REPO="mfreceiver/sync-player"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ GITEA_TOKEN 未设置"; exit 1; }
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
[[ -d artifacts && "$(ls -A artifacts 2>/dev/null)" ]] || { echo "❌ artifacts/ 为空（先跑 release-check.sh）"; exit 1; }

API="$GITEA_URL/api/v1/repos/$REPO/releases"
AUTH="Authorization: token $GITEA_TOKEN"

# 从 release JSON 提取首个 "id":<num>（顶层 release id；后续 owner/assets 的 id 被忽略）。
extract_id() { grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2; }

# 幂等：若该 tag 已有 release，先删除再重建，避免重传同名 asset 报冲突。
EXISTING=$(curl -sf -H "$AUTH" "$API/tags/$TAG" 2>/dev/null | extract_id || true)
if [[ -n "${EXISTING:-}" ]]; then
  echo "==> 删除既有 release id=$EXISTING（幂等重建）"
  curl -sf -X DELETE -H "$AUTH" "$API/$EXISTING" >/dev/null
fi

RID=$(curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
      -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"draft\":false,\"prerelease\":false}" \
      | extract_id)
[[ -n "${RID:-}" ]] || { echo "❌ 创建 release 失败（未取到 id）"; exit 1; }
echo "==> release id: $RID"

for f in artifacts/*; do
  [[ -f "$f" ]] || continue
  echo "==> 上传 $(basename "$f")"
  curl -sf -X POST "$API/$RID/assets?name=$(basename "$f")" \
    -H "$AUTH" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" >/dev/null
done
echo "✅ Release $TAG 产物上传完成 → $GITEA_URL/$REPO/releases/tag/$TAG"
