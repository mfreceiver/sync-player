#!/usr/bin/env bash
# scripts/check.sh — 改动校验（替代 LSP 自检）
# 详见 AGENTS.md「改动校验」。
#
# 用法:
#   ./scripts/check.sh           # 编译 + 单测（默认，每次改动必跑）
#   ./scripts/check.sh --lint    # 额外跑 lintDebug
#   ./scripts/check.sh --full    # 编译 + 单测 + lint
#
# sync-player 未接入 kover 覆盖率插件，故 --full 不含覆盖率门控（与 ocdroid 不同）。
# 本工作区 opencode 服务端已关闭 LSP，编辑后必须主动跑此脚本确认无编译/测试错误。

set -euo pipefail
source "$(dirname "$0")/env.sh"

MODE="${1:-default}"
GRADLE="./gradlew"; [[ "${OC_GRADLE_DAEMON:-0}" == "1" ]] || GRADLE="./gradlew --no-daemon"

echo "==> compileDebugKotlin"
$GRADLE compileDebugKotlin

echo "==> testDebugUnitTest"
$GRADLE testDebugUnitTest

case "$MODE" in
  --lint|--full)
    echo "==> lintDebug"
    $GRADLE lintDebug
    ;;
  default|"")
    ;;
  *)
    echo "用法: check.sh [--lint|--full]"; exit 1 ;;
esac

echo "✅ check.sh 通过"
