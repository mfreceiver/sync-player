#!/usr/bin/env bash
# scripts/release.sh — sync-player 发版唯一入口（本地发版，无 CI）：
#   门禁 → assembleRelease（签名）→ 本地产物 → annotated tag → push。
#
# 版本模型（git 派生，唯一来源是 git，不写任何版本文件）：
#   versionCode = git rev-list --count HEAD（单调递增，每 commit +1）
#   versionName = git describe（tag-hash[-dirty]）
#   本脚本传 -PreleaseVersion=<tag去v> 让发布的 APK 用干净 tag 版本名。
#
# 用法: ./scripts/release.sh <version> [--allow-dirty]
#   <version> 形如 12.1.0（自动加 v 前缀）；也接受 v12.1.0。
#   --allow-dirty：工作树非干净时强制发版（产物 versionName 带 -dirty 后缀）。
#
# 本脚本步骤（顺序保证：任何一步失败都不会留下已 push 的 tag）：
#   1. 校验分支=main、工作树干净、版本格式 x.y.z、tag 未占用
#   2. 本地门禁：compileDebugKotlin + testDebugUnitTest + lintDebug（破损代码连构建都不进）
#   3. 生成 changelog（上次 tag..HEAD 的 conventional commits 自动分类）
#   4. 本地 assembleRelease（签名，需 local.properties 已就绪）→ 复制到
#      dist/sync-player-<version>-<shortsha>.apk + 生成 SHA256SUMS
#   5. 创建 annotated tag（注释 = changelog）
#   6. git push origin main + tag（push 不会触发任何 CI；push 失败则回滚本地 tag）
#
# 前置:
#   1. 改动已在 main（tag 应打在稳定线；在分支上发版会警告）
#   2. 本地签名凭据已就绪（./scripts/setup-signing.sh 从 pass 生成 local.properties）
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# --- 参数解析 ---
ALLOW_DIRTY=0
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    -*) echo "❌ 未知选项: $arg"; exit 1 ;;
    *) [[ -z "$VERSION" ]] && VERSION="$arg" || { echo "❌ 多余参数: $arg"; exit 1; } ;;
  esac
done
[[ -n "$VERSION" ]] || { echo "用法: release.sh <version> (如 12.1.0) [--allow-dirty]"; exit 1; }
[[ "$VERSION" == v* ]] && VERSION="${VERSION#v}"
TAG="v$VERSION"

# --- 1. 校验 ---
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "❌ 版本格式应为 x.y.z，如 12.1.0"; exit 1; }
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ tag $TAG 已存在：$(git log -1 --format='%h %s' "$TAG" 2>/dev/null)"; exit 1
fi
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" ]] || echo "⚠️  当前在 '$BRANCH' 分支，建议在 main 上发版（继续中...）"
if [[ -n "$(git status --porcelain)" ]]; then
  if [[ "$ALLOW_DIRTY" -eq 1 ]]; then
    echo "⚠️  --allow-dirty：工作树非干净，versionName 将带 -dirty 后缀"
    git status --short
  else
    echo "❌ 工作树非干净，里程碑发版要求干净树。请 commit/stash 后重试，或加 --allow-dirty。"
    git status --short
    exit 1
  fi
fi

# --- 2. 本地门禁（编译 + 单测 + lint）---
: "${JAVA_HOME:=/home/mar/android-studio/jbr}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "❌ JAVA_HOME=$JAVA_HOME 下无 java；export JAVA_HOME=/home/mar/android-studio/jbr 后重试"
  exit 1
fi
echo "==> 本地门禁：compileDebugKotlin + testDebugUnitTest + lintDebug"
JAVA_HOME="$JAVA_HOME" ./gradlew compileDebugKotlin testDebugUnitTest lintDebug

# --- 3. changelog（上次 tag..HEAD 的 conventional commits 自动分类）---
LAST_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
CL_RANGE="${LAST_TAG:+$LAST_TAG..}HEAD"
FEATS=""; FIXES=""; DOCS=""; TESTS=""; REFACTORS=""; PERFS=""; MISC=""
cc_re='^([a-zA-Z]+)(\([^)]*\))?!?:[[:space:]](.+)$'
while IFS= read -r subject; do
  [[ -z "$subject" ]] && continue
  case "$subject" in release:*) continue ;; esac
  if [[ "$subject" =~ $cc_re ]]; then
    ctype="${BASH_REMATCH[1]}"; cdesc="${BASH_REMATCH[3]}"
  else
    ctype="other"; cdesc="$subject"
  fi
  case "$ctype" in
    feat)          FEATS+="- $cdesc"$'\n' ;;
    fix)           FIXES+="- $cdesc"$'\n' ;;
    docs)          DOCS+="- $cdesc"$'\n' ;;
    test|tests)    TESTS+="- $cdesc"$'\n' ;;
    refactor)      REFACTORS+="- $cdesc"$'\n' ;;
    perf)          PERFS+="- $cdesc"$'\n' ;;
    chore|ci|build|style|revert) MISC+="- $cdesc"$'\n' ;;
    release)       ;;
    *)             MISC+="- $cdesc"$'\n' ;;
  esac
done < <(git log --no-merges "$CL_RANGE" --pretty=tformat:"%s")

cl_emit() { [[ -z "$2" ]] && return 0; printf '### %s\n\n%s\n' "$1" "$2"; }
NOTE_FILE="$(mktemp)"
trap 'rm -f "$NOTE_FILE"' EXIT
{
  printf 'Release %s\n\n' "$VERSION"
  cl_emit "Features"      "$FEATS"
  cl_emit "Bug Fixes"     "$FIXES"
  cl_emit "Documentation" "$DOCS"
  cl_emit "Tests"         "$TESTS"
  cl_emit "Refactor"      "$REFACTORS"
  cl_emit "Performance"   "$PERFS"
  cl_emit "Miscellaneous" "$MISC"
} > "$NOTE_FILE"
echo "==> changelog 生成完成（自 ${LAST_TAG:-start} 以来）"
sed -n '1,20p' "$NOTE_FILE"

# --- 4. 本地签名构建 + 产物（assembleRelease 失败不会留下 tag）---
SHORT="$(git rev-parse --short HEAD)"
APK_NAME="sync-player-$VERSION-$SHORT.apk"
APK_SRC="app/build/outputs/apk/release/app-release.apk"
echo "==> assembleRelease -PreleaseVersion=$VERSION（本地签名）"
JAVA_HOME="$JAVA_HOME" ./gradlew assembleRelease -PreleaseVersion="$VERSION"
[[ -f "$APK_SRC" ]] || { echo "❌ 未找到 $APK_SRC"; exit 1; }
mkdir -p dist
cp "$APK_SRC" "dist/$APK_NAME"
( cd dist && sha256sum "$APK_NAME" > SHA256SUMS )
echo "==> 产物就绪：dist/$APK_NAME + dist/SHA256SUMS"
cat dist/SHA256SUMS

# --- 5. 创建 annotated tag（注释 = changelog；不 commit 任何版本文件）---
echo "==> 打注解 tag $TAG（HEAD = $SHORT）"
git tag -a "$TAG" -F "$NOTE_FILE"

# --- 6. push main + tag（不触发任何 CI；失败则回滚本地 tag）---
echo "==> push origin main + $TAG"
if ! git push origin main "$TAG"; then
  echo "❌ push 失败，回滚本地 tag $TAG（远端未收到 tag）"
  git tag -d "$TAG"
  exit 1
fi

echo ""
echo "✅ 发版完成："
echo "   tag:   $TAG（annotated，注解含分类 changelog；已推送到 origin）"
echo "   产物:  dist/$APK_NAME + dist/SHA256SUMS（gitignored）"
echo "   安装:  adb install -r dist/$APK_NAME"
