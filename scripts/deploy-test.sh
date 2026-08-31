#!/usr/bin/env bash
# ============================================================
# k-acp 测试环境(192.168.107.137)前后端一键部署脚本
#
# 用法:
#   scripts/deploy-test.sh                          # 全量：同步源码 + 重建 5 个应用容器
#   scripts/deploy-test.sh -s console,frontend      # 快速迭代：只同步 + 重建指定服务
#   scripts/deploy-test.sh --skip-sync              # 不同步源码，只用服务器现有代码重建
#   scripts/deploy-test.sh --status                 # 查看容器状态与健康
#   scripts/deploy-test.sh --rollback               # 回退到上一次部署的镜像
#
# 行为与安全边界：
#   - 只动 5 个应用容器(console/runtime/websocket/proxy/frontend)
#   - 中间件(mysql/redis/pgvector/neo4j)、Langfuse、数据卷、bind mount 一律不碰
#   - 每次部署前自动把当前镜像打 rollback 标签，可一键回退
# ============================================================
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
set -a; source "$REPO_ROOT/env/test/.env"; set +a

REMOTE_DIR="${TEST_REMOTE_DIR:-/home/lzd/k-acp-2517034}"
COMPOSE_FILES="-f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml -f docker/docker-compose-langfuse.yml"
COMPOSE_ENV="--env-file docker/.env.kacp"
ALL_SERVICES=(console runtime websocket proxy frontend)  # 对外短名
COMPOSE_PREFIX="apboa-"  # compose 服务名前缀
STAMP_FILE=".deploy-stamp"

# ---------- 复用 remote-test.sh 的连接 ----------
remote() { "$REPO_ROOT/scripts/remote-test.sh" "$@"; }

# ---------- 参数解析 ----------
SERVICES=()
SKIP_SYNC=0
MODE="deploy"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--services) IFS=',' read -ra SERVICES <<< "$2"; shift 2 ;;
    --skip-sync)   SKIP_SYNC=1; shift ;;
    --status)      MODE="status"; shift ;;
    --rollback)    MODE="rollback"; shift ;;
    -h|--help)     sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 64 ;;
  esac
done
[[ ${#SERVICES[@]} -eq 0 ]] && SERVICES=("${ALL_SERVICES[@]}")
for s in "${SERVICES[@]}"; do
  [[ " ${ALL_SERVICES[*]} " == *" $s "* ]] || { echo "未知服务: $s（可选: ${ALL_SERVICES[*]}）" >&2; exit 64; }
done

# ---------- 工具函数 ----------

do_status() {
  echo "=== 容器状态 ==="
  remote "docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'k-acp-(console|runtime|websocket|proxy|frontend)'"
  echo "=== 健康检查 ==="
  remote "curl -sf --max-time 5 http://localhost:23060/actuator/health && echo ' <- console:23060' || echo 'console: FAIL'
curl -sf --max-time 5 http://localhost:23061/actuator/health && echo ' <- runtime:23061' || echo 'runtime: FAIL'
curl -sf --max-time 5 -o /dev/null -w '%{http_code} <- frontend:23080\n' http://localhost:23080/"
  echo "=== 上次部署 ==="
  remote "cat $REMOTE_DIR/$STAMP_FILE 2>/dev/null || echo '(无记录)'"
}

do_rollback() {
  echo ">> 回退 5 个应用镜像到 rollback 标签..."
  remote "cd $REMOTE_DIR && for s in ${ALL_SERVICES[*]}; do
    img=k-acp-local-apboa-\$s:rollback
    docker image inspect \$img >/dev/null 2>&1 || { echo \"跳过 \$s（无 rollback 镜像）\"; continue; }
    docker tag \$img k-acp-local-apboa-\$s:latest
  done
  docker compose --project-name k-acp-local $COMPOSE_ENV $COMPOSE_FILES up -d --no-deps --force-recreate ${ALL_SERVICES[*]/#/apboa-}"
  echo ">> 回退完成，执行 --status 验证"
}

# ---------- 部署主流程 ----------
if [[ "$MODE" == "deploy" ]]; then
  branch=$(git -C "$REPO_ROOT" branch --show-current)
  sha=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
  dirty=$(git -C "$REPO_ROOT" status --porcelain | wc -l | tr -d ' ')
  ts=$(date '+%Y-%m-%d %H:%M:%S')
  echo ">> 部署目标: $SSH_USER@$SSH_HOST ($REMOTE_DIR)"
  echo ">> 版本: $branch @ $sha (未提交变更 $dirty 个文件)  服务: ${SERVICES[*]}"

  if [[ $SKIP_SYNC -eq 0 ]]; then
    echo ">> [1/4] rsync 同步源码（不含 .git/node_modules/target/数据/日志/.env）..."
    # shellcheck disable=SC2046
    remote --push --delete \
      --exclude '.git' --exclude '.idea' --exclude '.DS_Store' \
      --exclude 'node_modules' --exclude 'target' --exclude 'dist' \
      --exclude 'logs' --exclude '*.log' \
      --exclude 'docker/data' --exclude 'docker/logs' \
      --exclude '.env' --exclude '.env.*.local' \
      --exclude 'docker/.env.*' --exclude 'env/' \
      --exclude 'graphify-out' \
      "$REPO_ROOT/" "$REMOTE_DIR/"
  else
    echo ">> [1/4] 跳过源码同步"
  fi

  echo ">> [2/4] 为现有镜像打 rollback 标签..."
  remote "cd $REMOTE_DIR && for s in ${SERVICES[*]}; do
    docker image inspect k-acp-local-apboa-\$s:latest >/dev/null 2>&1 \
      && docker tag k-acp-local-apboa-\$s:latest k-acp-local-apboa-\$s:rollback || true
  done"

  echo ">> [3/4] 服务器构建镜像: ${SERVICES[*]} （首次全量约 10-15 分钟）..."
  remote "cd $REMOTE_DIR && docker compose --project-name k-acp-local $COMPOSE_ENV $COMPOSE_FILES build ${SERVICES[*]/#/apboa-}"

  echo ">> [4/4] 重建应用容器（不触碰中间件/数据卷）..."
  remote "cd $REMOTE_DIR && docker compose --project-name k-acp-local $COMPOSE_ENV $COMPOSE_FILES up -d --no-deps --force-recreate ${SERVICES[*]/#/apboa-}"

  echo ">> 记录版本戳..."
  remote "printf 'time=%s\nbranch=%s\nsha=%s\ndirty=%s\nservices=%s\n' '$ts' '$branch' '$sha' '$dirty' '${SERVICES[*]}' > $REMOTE_DIR/$STAMP_FILE && cat $REMOTE_DIR/$STAMP_FILE"

  echo ">> 健康检查（最多等 120 秒）..."
  ok=1
  for i in $(seq 1 24); do
    sleep 5
    out=$(remote "curl -sf --max-time 5 http://localhost:23060/actuator/health >/dev/null && echo console-ok || true
curl -sf --max-time 5 http://localhost:23061/actuator/health >/dev/null && echo runtime-ok || true
code=\$(curl -sf -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:23080/ || true); [ \"\$code\" = 200 ] && echo frontend-ok || true" || true)
    missing=()
    [[ " ${SERVICES[*]} " == *" console "* && "$out" != *console-ok* ]] && missing+=(console)
    [[ " ${SERVICES[*]} " == *" runtime "* && "$out" != *runtime-ok* ]] && missing+=(runtime)
    [[ " ${SERVICES[*]} " == *" frontend "* && "$out" != *frontend-ok* ]] && missing+=(frontend)
    if [[ ${#missing[@]} -eq 0 ]]; then
      echo "✅ 全部就绪: ${SERVICES[*]}"
      ok=0; break
    fi
    echo "  ...等待 ${missing[*]} (${i}/24)"
  done
  [[ $ok -ne 0 ]] && { echo "❌ 健康检查超时，检查: ./scripts/remote-test.sh 'docker logs --tail 50 k-acp-console'"; exit 1; }
  echo ">> 完成。访问 http://192.168.107.137:23080  回退: scripts/deploy-test.sh --rollback"
fi
