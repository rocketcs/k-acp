#!/usr/bin/env bash
set -Eeuo pipefail

# 唯一管理测试/本地一体化 k-acp 栈：不操作其他 Compose 项目，不删除数据卷。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_NAME="k-acp-local"
ENV_FILE="$SCRIPT_DIR/.env.kacp"
COMPOSE_BASE="$SCRIPT_DIR/docker-compose-simple.yml"
COMPOSE_LOCAL="$SCRIPT_DIR/docker-compose-kacp-local.yml"
APP_SERVICES=(apboa-console apboa-runtime apboa-proxy apboa-websocket apboa-frontend)

usage() {
  cat <<'USAGE'
用法：docker/manage-kacp-local.sh <update|start|stop|status|logs>

命令：
  update  拉取 origin/dev，重新构建并重建整个 k-acp-local 前后端服务栈
  start   启动已有的 k-acp-local 容器，不拉取代码、不重新构建
  stop    停止 k-acp-local 的所有容器，保留容器、数据目录和卷
  status  查看 k-acp-local 服务状态
  logs    跟踪 k-acp-local 服务日志

安全边界：
  - 仅操作 Compose 项目 k-acp-local
  - 不执行 down -v，不删除 MySQL、Redis、pgvector 或 .apboa 数据
  - update 只接受干净的 Git 工作区，并使用 fast-forward 拉取 origin/dev
USAGE
}

select_docker() {
  if docker info >/dev/null 2>&1; then
    DOCKER=(docker)
    return
  fi

  if command -v sudo >/dev/null 2>&1; then
    sudo -v
    sudo docker info >/dev/null
    DOCKER=(sudo docker)
    return
  fi

  echo '错误：当前用户无法访问 Docker，且 sudo 不可用。' >&2
  exit 1
}

require_inputs() {
  for path in "$ENV_FILE" "$COMPOSE_BASE" "$COMPOSE_LOCAL"; do
    [[ -f "$path" ]] || { echo "错误：缺少文件：$path" >&2; exit 1; }
  done
}

compose() {
  "${DOCKER[@]}" compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_BASE" \
    -f "$COMPOSE_LOCAL" \
    "$@"
}

update() {
  command -v git >/dev/null 2>&1 || { echo '错误：未找到 git。' >&2; exit 1; }
  git -C "$REPO_DIR" diff --quiet && git -C "$REPO_DIR" diff --cached --quiet || {
    echo '错误：代码工作区存在已跟踪的未提交改动，拒绝自动更新。' >&2
    git -C "$REPO_DIR" status --short
    exit 1
  }

  echo '>> 拉取 origin/dev 最新代码...'
  git -C "$REPO_DIR" switch dev
  git -C "$REPO_DIR" pull --ff-only origin dev

  echo '>> 构建 k-acp-local 的前后端应用镜像...'
  compose build "${APP_SERVICES[@]}"

  echo '>> 重建 k-acp-local 的前后端应用服务（中间件与数据卷保持不动）...'
  compose up -d --force-recreate "${APP_SERVICES[@]}"

  compose ps "${APP_SERVICES[@]}"
}

start() {
  compose up -d
  compose ps
}

stop() {
  compose stop
  compose ps
}

main() {
  case "${1:-}" in
    -h|--help|help|'')
      usage
      return
      ;;
  esac

  require_inputs
  select_docker

  case "${1:-}" in
    update) update ;;
    start) start ;;
    stop) stop ;;
    status) compose ps ;;
    logs) compose logs -f ;;
    *)
      echo "错误：未知命令：$1" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "$@"
