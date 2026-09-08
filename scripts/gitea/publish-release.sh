#!/usr/bin/env bash
# scripts/gitea/publish-release.sh — CI 内把 release-check 产物上传到 Gitea Release。
# 用法: publish-release.sh <tag>     (tag 形如 v1.2.3；由 release.yml 调用)
# 环境：GITEA_TOKEN（release.yml 注入 auto github.token）、GITEA_URL。
# 产物目录：artifacts/（APK + SHA256SUMS + BUILD_INFO 构建清单；清单仅本地凭证，不上传）。
# 说明：CI 镜像（universal-ci-android:35-jdk21）无 python3/jq，故 JSON 解析仅用 POSIX
#       工具（grep/cut/head），release body 的 JSON 转义仅用 awk。
set -euo pipefail
TAG="${1:?用法: publish-release.sh <tag>}"
REPO="mfreceiver/sync-player"
GITEA_URL="${GITEA_URL:-http://192.168.3.252:11330}"
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ GITEA_TOKEN 未设置"; exit 1; }
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# 发布绑定校验（F11）：tag 必须真实存在且能解引用到 commit——
# annotated tag 的 tag object ≠ commit，必须 ^{commit} 剥壳；分支名/缺失 tag 在此拒绝。
TAG_COMMIT="$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" 2>/dev/null)" \
  || { echo "❌ tag '$TAG' 不存在或无法解析为 commit（refs/tags/$TAG^{commit}）"; exit 1; }
echo "==> tag $TAG → commit ${TAG_COMMIT:0:10}"

# 发布绑定校验（F11）：workspace HEAD 必须严格位于 tag commit 上——
# 不一致时 artifacts/ 可能是错位/残留构建的产物，上传会把它们冒充成该 tag 发布。
HEAD_COMMIT="$(git rev-parse HEAD)"
if [[ "$TAG_COMMIT" != "$HEAD_COMMIT" ]]; then
  echo "❌ HEAD (${HEAD_COMMIT:0:10}) 不在 tag '$TAG' 的 commit (${TAG_COMMIT:0:10}) 上，拒绝发布（防残留产物发布到另一有效 tag）。"
  exit 1
fi

[[ -d artifacts && "$(ls -A artifacts 2>/dev/null)" ]] || { echo "❌ artifacts/ 为空（先跑 release-check.sh）"; exit 1; }

# 产物↔构建来源绑定（rev-1）：HEAD 绑定仍不够——持久化 workspace 可能把另一
# tag 的旧产物带过来（如换 tag 后未重跑 release-check、或上次构建失败未重建）。
# 构建清单是发布前置凭证：缺失或与请求 tag/commit 不匹配即拒绝。
BUILD_INFO_FILE="artifacts/BUILD_INFO"
[[ -f "$BUILD_INFO_FILE" ]] || { echo "❌ 缺少 $BUILD_INFO_FILE 构建清单——产物来源不明（先对当前 tag 跑 release-check.sh）"; exit 1; }
INFO_TAG="$(sed -n 's/^TAG=//p' "$BUILD_INFO_FILE")"
INFO_COMMIT="$(sed -n 's/^COMMIT=//p' "$BUILD_INFO_FILE")"
if [[ "$INFO_TAG" != "$TAG" || "$INFO_COMMIT" != "$TAG_COMMIT" ]]; then
  echo "❌ 产物来源不匹配：BUILD_INFO(TAG=${INFO_TAG:-<空>}, COMMIT=${INFO_COMMIT:0:10}) 但请求发布 tag '$TAG'（commit ${TAG_COMMIT:0:10}）。"
  echo "   拒绝发布：请对当前 tag 重新执行 release-check.sh 生成配套产物。"
  exit 1
fi
# 完整性校验（rev-1）：归档后被篡改/替换的产物在此拦截。
( cd artifacts && sha256sum -c SHA256SUMS ) >/dev/null \
  || { echo "❌ artifacts 完整性校验失败（SHA256SUMS 缺失或不匹配——产物可能被篡改/替换）"; exit 1; }
echo "==> 构建清单校验通过：产物绑定 (TAG=$INFO_TAG, COMMIT=${INFO_COMMIT:0:10})，完整性 OK"

API="$GITEA_URL/api/v1/repos/$REPO/releases"
AUTH="Authorization: token $GITEA_TOKEN"

# 从 release JSON 提取首个 "id":<num>（顶层 release id；后续 owner/assets 的 id 被忽略）。
extract_id() { grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2; }

# JSON 字符串转义（POSIX awk，无需 python3/jq）：转义 \ 与 "，行间插入字面 \n，压成单行。
json_escape() {
  awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1)printf "\\n"; printf "%s",$0}'
}

# 幂等：若该 tag 已有 release，先删除再重建，避免重传同名 asset 报冲突。
EXISTING=$(curl -sf -H "$AUTH" "$API/tags/$TAG" 2>/dev/null | extract_id || true)
if [[ -n "${EXISTING:-}" ]]; then
  echo "==> 删除既有 release id=$EXISTING（幂等重建）"
  curl -sf -X DELETE -H "$AUTH" "$API/$EXISTING" >/dev/null
fi

# release body 取自 annotated tag 注解（release.sh 生成的分类 changelog）。
# lightweight tag 无注解时 body 为空，创建无 body 的 release。
TAG_BODY="$(git tag -l --format='%(contents)' "$TAG" 2>/dev/null | json_escape || true)"
if [[ -n "$TAG_BODY" ]]; then
  PAYLOAD="{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"body\":\"$TAG_BODY\",\"draft\":false,\"prerelease\":false}"
else
  PAYLOAD="{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"draft\":false,\"prerelease\":false}"
fi

RID=$(curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" -d "$PAYLOAD" | extract_id)
[[ -n "${RID:-}" ]] || { echo "❌ 创建 release 失败（未取到 id）"; exit 1; }
echo "==> release id: $RID（body: $([ -n "$TAG_BODY" ] && echo 来自 tag 注解 || echo 无)）"

for f in artifacts/*; do
  [[ -f "$f" ]] || continue
  [[ "$(basename "$f")" == "BUILD_INFO" ]] && continue # 本地构建清单，不是发布资产
  echo "==> 上传 $(basename "$f")"
  curl -sf -X POST "$API/$RID/assets?name=$(basename "$f")" \
    -H "$AUTH" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" >/dev/null
done
echo "✅ Release $TAG 产物上传完成 → $GITEA_URL/$REPO/releases/tag/$TAG"
