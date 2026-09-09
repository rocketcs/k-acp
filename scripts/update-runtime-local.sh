#!/usr/bin/env bash
# ============================================================
# 本地开发模式的前后端 runtime 更新（应用绝不使用容器）
#
# 用法:
#   scripts/update-runtime-local.sh                       # 构建全部后端 + 重启 JVM + 确保 vite
#   scripts/update-runtime-local.sh -s runtime            # 只更新指定后端(console|runtime|websocket)
#   scripts/update-runtime-local.sh -s frontend           # 前端：vite 在跑即热更新，否则启动
#   scripts/update-runtime-local.sh --status              # 只查看状态
#   scripts/update-runtime-local.sh --stop                # 只停止本地 JVM 与 vite
#
# 规则:
#   - 应用服务 = 本地 JVM(3060/3061/3064) + Vite(3030)，不用/不碰 Docker 容器
#   - 中间件容器(mysql/redis/pgvector 等)由 k-acp-local 管理，绝不停
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$REPO_ROOT/logs/local-dev"
mkdir -p "$LOG_DIR"

BACKEND_SERVICES=(console runtime websocket)
FRONTEND_PORT=3030
PROXY_FLAGS="-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= -Dhttp.proxyPort= -Dhttps.proxyPort= -DsocksProxyPort="

usage() { sed -n '2,16p' "$0"; exit "${1:-0}"; }
die() { echo "❌ $*" >&2; exit 1; }

svc_module() { case "$1" in console) echo runner-console;; runtime) echo runner-runtime;; websocket) echo runner-websocket;; esac; }
svc_port()  { case "$1" in console) echo 3060;; runtime) echo 3061;; websocket) echo 3064;; esac; }
svc_jar()   { echo "runner-$(svc_module "$1")-1.0-SNAPSHOT.jar"; }

is_running() { pgrep -f "$1" >/dev/null 2>&1; }

# ---------- 参数解析 ----------
SERVICES=()
MODE="update"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--services) [[ -n "${2:-}" ]] || die "-s 需要服务列表"; IFS=',' read -ra _tmp <<< "$2"; SERVICES+=("${_tmp[@]}"); shift 2 ;;
    --status) MODE="status"; shift ;;
    --stop)   MODE="stop"; shift ;;
    -h|--help) usage 0 ;;
    *) die "未知参数: ${1}（可用: -s <services> | --status | --stop）" ;;
  esac
done
if [[ ${#SERVICES[@]} -eq 0 && "$MODE" == "update" ]]; then
  SERVICES=("${BACKEND_SERVICES[@]}" frontend)
fi
for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
  [[ "$s" == "frontend" || " ${BACKEND_SERVICES[*]} " == *" $s "* ]] || die "未知服务: ${s}（可选: ${BACKEND_SERVICES[*]} frontend）"
done

# ---------- 状态 ----------
do_status() {
  echo "=== 本地开发服务状态（应用 JVM，无容器）==="
  local name port code
  for pair in "console:3060" "runtime:3061" "websocket:3064"; do
    name="${pair%%:*}"; port="${pair##*:}"
    if is_running "runner-$(svc_module "$name")-.*\.jar"; then
      code=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/actuator/health" || true)
      echo "  $name: JVM 运行中, health=$code (端口 $port)"
    else
      echo "  $name: 未运行 (端口 $port)"
    fi
  done
  if is_running 'vite'; then
    code=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$FRONTEND_PORT/" || true)
    echo "  frontend: vite 运行中, http=$code (HMR 生效)"
  else
    echo "  frontend: vite 未运行"
  fi
  # 仅提示：Docker 全栈模式容器与本地 JVM 端口不冲突(23060+)，是否停用由操作者决定
  local running_containers
  running_containers=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E '^k-acp-(console|runtime|websocket|proxy|frontend)$' || true)
  if [[ -n "$running_containers" ]]; then
    echo "  ⚠ 检测到 Docker 应用容器在运行（不影响本地 JVM，如需纯本地请自行停止）: $running_containers"
  fi
}

# ---------- 停止 ----------
do_stop() {
  echo ">> 停止本地 JVM 服务（中间件容器保留）..."
  local s
  for s in "${BACKEND_SERVICES[@]}"; do
    pkill -f "runner-$(svc_module "$s")-1.0-SNAPSHOT\.jar" 2>/dev/null && echo "  已停止 $s" || echo "  $s 未在运行"
  done
  echo ">> 停止 vite..."
  pkill -f 'vite/bin/vite' 2>/dev/null && echo "  已停止 vite" || echo "  vite 未在运行"
}

# ---------- 构建后端 ----------
build_backend() { # $@ = backend service names
  local modules=() m=""
  for s in "$@"; do modules+=("$(svc_module "$s")"); done
  m="${modules[0]}"
  for i in "${modules[@]:1}"; do m="$m,$i"; done
  echo ">> [1/3] Maven 构建: $m （必须带 -am）..."
  (cd "$REPO_ROOT" && mvn -q -DskipTests -pl "$m" -am package)
}

# ---------- 重启单个服务 ----------
restart_service() { # $1 = service name
  local name="$1" module jar port
  module="$(svc_module "$name")"; port="$(svc_port "$name")"
  jar="$(svc_jar "$name")"
  echo ">> [2/3] 重启 $name ($jar) -> 127.0.0.1:$port ..."
  pkill -f "runner-${module}-1.0-SNAPSHOT\.jar" 2>/dev/null || true
  sleep 1
  (cd "$REPO_ROOT" && nohup java $PROXY_FLAGS -jar "$REPO_ROOT/$module/target/$jar" > "$LOG_DIR/$module.log" 2>&1 &)
  echo "  已启动，日志: $LOG_DIR/$module.log"
}

wait_health() { # $1=service $2=timeout_秒
  local name="$1" port t i
  port="$(svc_port "$name")"; t="${2:-60}"
  echo ">> [3/3] 等待 $name 健康 (127.0.0.1:$port/actuator/health, ≤${t}s)..."
  for i in $(seq 1 "$t"); do
    if curl -sf --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      echo "  ✅ $name 就绪"
      return 0
    fi
    sleep 1
  done
  echo "  ❌ $name 健康检查超时，日志: $LOG_DIR/$(svc_module "$name").log"
  return 1
}

ensure_frontend() {
  local i
  if is_running 'vite'; then
    echo "✅ 前端 vite 已在运行（端口 ${FRONTEND_PORT}），代码改动由 HMR 自动生效，无需重启"
    return 0
  fi
  echo ">> 启动前端 vite dev (端口 $FRONTEND_PORT)..."
  (cd "$REPO_ROOT/ui" && nohup pnpm dev > "$LOG_DIR/frontend.log" 2>&1 &)
  sleep 1
  for i in $(seq 1 30); do
    if curl -sf --max-time 2 "http://127.0.0.1:$FRONTEND_PORT/" >/dev/null 2>&1; then
      echo "  ✅ vite 就绪: http://localhost:$FRONTEND_PORT"
      return 0
    fi
    sleep 1
  done
  echo "  ❌ vite 启动超时，日志: $LOG_DIR/frontend.log"
  return 1
}

# ---------- 主流程 ----------
case "$MODE" in
  status) do_status; exit 0 ;;
  stop)   do_stop; exit 0 ;;
esac

backend=(); has_frontend=0
for s in "${SERVICES[@]}"; do
  if [[ "$s" == "frontend" ]]; then has_frontend=1; else backend+=("$s"); fi
done

if [[ ${#backend[@]} -gt 0 ]]; then
  build_backend "${backend[@]}"
  for s in "${backend[@]}"; do restart_service "$s"; done
  local ok=1 s
  for s in "${backend[@]}"; do wait_health "$s" 90 || ok=0; done
  [[ $ok -eq 1 ]] || die "有后端服务未就绪，请查看 $LOG_DIR 下日志"
fi
if [[ $has_frontend -eq 1 ]]; then
  ensure_frontend || die "前端启动失败"
fi

echo "🎉 本地更新完成。访问 http://localhost:3030 （vite 代理 /api -> 3060, /api/runtime -> 3061, /api/ws -> 3064）"