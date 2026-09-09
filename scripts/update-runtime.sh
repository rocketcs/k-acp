#!/usr/bin/env bash
# ============================================================
# k-acp 前后端 runtime 更新统一入口（一次命令完成更新）
#
# 原则:
#   - local  = 本地开发模式：应用走 JVM + Vite，绝不使用容器
#   - 其他环境(test/kylin) = Docker 容器部署
#
# 用法:
#   scripts/update-runtime.sh <local|test|kylin> [选项]
#
# local（本地，无容器）:
#   update-runtime.sh local                 # 构建全部后端并重启 JVM，前端确保 vite 运行
#   update-runtime.sh local -s runtime      # 只更新指定后端服务(console|runtime|websocket)
#   update-runtime.sh local -s frontend     # 前端：vite 已运行则热更新生效，未运行则启动
#   update-runtime.sh local --status        # 查看本地各服务健康状态
#   update-runtime.sh local --stop          # 停止本地 JVM 与 vite（中间件容器不动）
#
# test（测试环境 192.168.107.137，Docker 容器）:
#   update-runtime.sh test                  # 全量：rsync + 服务器构建 + 重建容器
#   update-runtime.sh test -s frontend      # 只更新前端容器
#   update-runtime.sh test -s console,runtime
#   update-runtime.sh test --skip-sync      # 复用服务器现有代码重建
#   update-runtime.sh test --status         # 容器状态 + 健康检查
#   update-runtime.sh test --rollback       # 回退到上一次部署的镜像
#
# kylin（麒麟环境 10.11.2.68，Docker 容器）:
#   update-runtime.sh kylin                 # 在线部署（rsync + 服务器构建启动）
#   update-runtime.sh kylin --status        # 查看服务器状态
#   update-runtime.sh kylin --prepare-offline
#
# 注意: 对 test/kylin 的任何部署都是写入操作，运行前请确认目标环境。
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { sed -n '2,40p' "$0"; exit "${1:-0}"; }
die() { echo "❌ $*" >&2; exit 1; }

ENV_NAME="${1:-}"; [[ $# -ge 1 ]] || die "缺少环境参数: local|test|kylin"
shift

case "$ENV_NAME" in
  local)   "$REPO_ROOT/scripts/update-runtime-local.sh" "$@" ;;
  test)    "$REPO_ROOT/scripts/deploy-test.sh" "$@" ;;
  kylin)
    case "${1:-}" in
      --status)        "$REPO_ROOT/scripts/deploy-kylin.sh" --status ;;
      --prepare-offline) "$REPO_ROOT/scripts/deploy-kylin.sh" --prepare-offline ;;
      ""|--deploy-online) "$REPO_ROOT/scripts/deploy-kylin.sh" --deploy-online ;;
      *)               die "kylin 仅支持: --status | --prepare-offline | --deploy-online" ;;
    esac
    ;;
  *) die "未知环境: ${ENV_NAME}（可选: local|test|kylin）" ;;
esac