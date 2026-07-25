#!/usr/bin/env bash
# scripts/gitea/setup-secrets.sh — 把 local.properties + keystore 注入 Gitea Actions Secrets。
# 在含 local.properties + keystore 的开发机上跑一次（轮换签名时重跑即可，幂等）。
# 用法:
#   ./scripts/gitea/setup-secrets.sh                 # 自动从 ~/.git-credentials 取 token
#   GITEA_TOKEN=xxx ./scripts/gitea/setup-secrets.sh # 显式传 token
#
# 安全:不打印任何密钥值;payload 写 mode-600 临时文件 → curl --data @file → shred。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# token:优先环境变量,兜底 ~/.git-credentials
if [[ -z "${GITEA_TOKEN:-}" ]] && [[ -f ~/.git-credentials ]]; then
  GITEA_TOKEN=$(grep 'git.vectory.cn' ~/.git-credentials | sed 's|https://[^:]*:\([^@]*\)@.*|\1|')
fi
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ 无 GITEA_TOKEN(导出它或确保 ~/.git-credentials 有 git.vectory.cn 条目)"; exit 1; }

# repo 从 git remote 推导(本机 origin → mfreceiver/sync-player)
REPO=$(git remote get-url origin | sed 's|.*://[^/]*/||; s|\.git$||')
API="https://git.vectory.cn:18443/api/v1/repos/$REPO/actions/secrets"

# 从 local.properties 读 release.*
lp=local.properties
[[ -f "$lp" ]] || { echo "❌ 无 $lp(先建:printf 'sdk.dir=/home/mar/android-sdk\n' > $lp)"; exit 1; }
get_prop() { grep "^$1=" "$lp" | head -1 | cut -d= -f2-; }
STORE_FILE=$(get_prop release.storeFile)
STORE_PW=$(get_prop release.storePassword)
KEY_ALIAS=$(get_prop release.keyAlias); [[ -z "$KEY_ALIAS" ]] && KEY_ALIAS=release
KEY_PW=$(get_prop release.keyPassword); [[ -z "$KEY_PW" ]] && KEY_PW="$STORE_PW"

[[ -n "$STORE_FILE" && -f "$STORE_FILE" ]] || { echo "❌ keystore 不存在: '${STORE_FILE:-未设}'(检查 $lp 的 release.storeFile)"; exit 1; }
[[ -n "$STORE_PW" ]] || { echo "❌ $lp 的 release.storePassword 为空"; exit 1; }

# Validate before uploading: avoid replacing working Gitea secrets with a
# password/file combination that only fails later in packageRelease.
KEYTOOL="${JAVA_HOME:-/home/mar/android-studio/jbr}/bin/keytool"
if [[ ! -x "$KEYTOOL" ]]; then KEYTOOL="$(command -v keytool || true)"; fi
[[ -n "$KEYTOOL" && -x "$KEYTOOL" ]] || { echo "❌ 找不到 keytool（设置 JAVA_HOME 后重试）"; exit 1; }
"$KEYTOOL" -list -keystore "$STORE_FILE" -storepass "$STORE_PW" >/dev/null 2>&1 || {
  echo "❌ release.storePassword 无法打开 keystore；未写入任何 Gitea Secret"; exit 1;
}
"$KEYTOOL" -list -keystore "$STORE_FILE" -storepass "$STORE_PW" -alias "$KEY_ALIAS" >/dev/null 2>&1 || {
  echo "❌ release.keyAlias='$KEY_ALIAS' 不存在于 keystore；未写入任何 Gitea Secret"; exit 1;
}

echo "==> 源: keystore=$STORE_FILE  repo=$REPO"
echo "==> 计算 keystore base64..."
KS_B64=$(base64 -w0 "$STORE_FILE")

# 安全 PUT:payload 写 mode-600 临时文件 → curl @file → shred
put_secret() {
  local name="$1" value="$2" pf
  pf=$(mktemp); chmod 600 "$pf"
  SECRET_VALUE="$value" python3 -c 'import json,os;print(json.dumps({"data":os.environ["SECRET_VALUE"]}))' > "$pf"
  curl -sf -X PUT "$API/$name" -H "Authorization: token $GITEA_TOKEN" \
    -H "Content-Type: application/json" --data-binary @"$pf" -o /dev/null
  shred -u "$pf" 2>/dev/null || rm -f "$pf"
  echo "  ✓ $name"
}

echo "==> 注入 4 个 release secrets..."
put_secret RELEASE_KEYSTORE "$KS_B64"
put_secret RELEASE_STORE_PASSWORD "$STORE_PW"
put_secret RELEASE_KEY_ALIAS "$KEY_ALIAS"
put_secret RELEASE_KEY_PASSWORD "$KEY_PW"

# 可选：企业微信通知 webhook（发版完成/失败时 ping 群；来源 pass sync-player/wecom-webhook，
# 缺失则跳过，不影响签名 secrets 上传）
if command -v pass >/dev/null 2>&1 && pass show sync-player/wecom-webhook >/dev/null 2>&1; then
  echo "==> 注入 WECOM_WEBHOOK（发版结果通知，来源: pass）..."
  put_secret WECOM_WEBHOOK "$(pass show sync-player/wecom-webhook | tr -d '\n')"
fi

echo ""
echo "==> 验证(仅名称,不回显值):"
curl -sf "$API" -H "Authorization: token $GITEA_TOKEN" | python3 -c '
import sys,json
d=json.load(sys.stdin)
secs=d if isinstance(d,list) else d.get("data",d.get("secrets",[]))
for s in secs: print("  -", s.get("name"))
'
echo ""
echo "✅ 完成。可用测试 tag 验证 release.yml(见 docs/specs/gitea-cicd.md §4)。"
