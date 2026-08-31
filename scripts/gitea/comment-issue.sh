#!/usr/bin/env bash
# scripts/gitea/comment-issue.sh — 向 Issue 追加评论（状态回写 / CI 结果）。
# 用法: comment-issue.sh <issue_number> <message...>
#       echo "多行内容" | comment-issue.sh <issue_number> -
set -euo pipefail
NUM="${1:?用法: comment-issue.sh <issue> <message|-|(多位置参)>}"
shift
REPO="mfreceiver/sync-player"
GITEA_URL="${GITEA_URL:-http://192.168.3.252:11330}"
: "${GITEA_TOKEN:?需要 GITEA_TOKEN}"
API="$GITEA_URL/api/v1/repos/$REPO/issues/$NUM"
AUTH="Authorization: token $GITEA_TOKEN"

if [[ "${1:-}" == "-" ]]; then BODY=$(cat); else BODY="$*"; fi
PAYLOAD=$(python3 -c 'import json,sys;print(json.dumps({"body":sys.argv[1]}))' "$BODY")
curl -sf -X POST "$API/comments" -H "$AUTH" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null
echo "✅ commented on #$NUM"
