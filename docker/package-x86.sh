#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HOST_IP="192.168.8.81"
OUTPUT_ROOT="$(cd "$REPO_ROOT/.." && pwd)/releases"
TAG="$(date +%Y%m%d-%H%M%S)"
DRY_RUN=false
SKIP_BUILD=false
KEEP_WORKDIR=false
ENV_FILE="$SCRIPT_DIR/.env.kacp"
COMPOSE_BASE="$SCRIPT_DIR/docker-compose-simple.yml"
COMPOSE_LOCAL="$SCRIPT_DIR/docker-compose-kacp-local.yml"
APP_CONTAINERS=(k-acp-console k-acp-runtime k-acp-proxy k-acp-websocket k-acp-frontend)
DB_CONTAINERS=(k-acp-mysql k-acp-redis k-acp-pgvector)
IMAGE_SERVICES=(console runtime proxy websocket frontend)
RUNNING_APPS=()
APPS_STOPPED=false
WORKDIR_CREATED=false
BUILD_OVERRIDE=""

usage() {
  cat <<'USAGE'
用法：./docker/package-x86.sh [选项]

将当前 k-acp-local 环境打包为 Ubuntu 22.04 x86_64 发布包。

选项：
  --host-ip <IPv4>      目标服务器 IP（默认：192.168.8.81）
  --output-dir <目录>   发布包输出根目录（默认：仓库同级 releases）
  --tag <标签>          发布标签（默认：YYYYMMDD-HHMMSS）
  --skip-build          复用对应标签的本地 AMD64 应用镜像
  --dry-run             只检查参数和输入，不停止服务、不创建发布物
  --keep-workdir        成功后保留未压缩发布目录
  -h, --help            显示帮助
USAGE
}

die() {
  printf '错误：%s\n' "$*" >&2
  exit 1
}

validate_ipv4() {
  local value="$1"
  local old_ifs="$IFS"
  local parts=()
  local part

  [[ "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  IFS='.' read -r -a parts <<< "$value"
  IFS="$old_ifs"
  [[ ${#parts[@]} -eq 4 ]] || return 1
  for part in "${parts[@]}"; do
    [[ "$part" =~ ^[0-9]+$ ]] || return 1
    (( 10#$part >= 0 && 10#$part <= 255 )) || return 1
  done
}

validate_tag() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
}

require_value() {
  local option="$1"
  local value="${2:-}"
  [[ -n "$value" ]] || die "$option 缺少参数值"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --host-ip)
        require_value "$1" "${2:-}"
        HOST_IP="$2"
        shift 2
        ;;
      --output-dir)
        require_value "$1" "${2:-}"
        OUTPUT_ROOT="$2"
        shift 2
        ;;
      --tag)
        require_value "$1" "${2:-}"
        TAG="$2"
        shift 2
        ;;
      --skip-build)
        SKIP_BUILD=true
        shift
        ;;
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --keep-workdir)
        KEEP_WORKDIR=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "未知参数：$1"
        ;;
    esac
  done
}

resolve_paths() {
  validate_ipv4 "$HOST_IP" || die "无效的 IPv4 地址：$HOST_IP"
  validate_tag "$TAG" || die "无效的发布标签：$TAG"

  if [[ "$OUTPUT_ROOT" != /* ]]; then
    OUTPUT_ROOT="$(pwd)/$OUTPUT_ROOT"
  fi

  RELEASE_NAME="k-acp-x86_64-$TAG"
  RELEASE_DIR="$OUTPUT_ROOT/$RELEASE_NAME"
  ARCHIVE_PATH="$OUTPUT_ROOT/$RELEASE_NAME.tar.gz"

  [[ ! -e "$RELEASE_DIR" ]] || die "发布目录已存在：$RELEASE_DIR"
  [[ ! -e "$ARCHIVE_PATH" ]] || die "发布压缩包已存在：$ARCHIVE_PATH"
}

dry_run_summary() {
  printf 'K-ACP x86_64 打包检查\n'
  printf '  仓库目录：%s\n' "$REPO_ROOT"
  printf '  目标服务器 IP：%s\n' "$HOST_IP"
  printf '  发布标签：%s\n' "$TAG"
  printf '  输出根目录：%s\n' "$OUTPUT_ROOT"
  printf '  最终压缩包：%s\n' "$ARCHIVE_PATH"
  printf '  构建镜像：%s\n' "$([[ "$SKIP_BUILD" == true ]] && printf '跳过' || printf '执行')"
  printf '  容器检查通过：8/8\n'
  printf 'dry-run 完成，未停止容器，未创建发布物。\n'
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

container_is_running() {
  local state
  state="$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)"
  [[ "$state" == "true" || "$state" == "running" ]]
}

preflight() {
  local path container
  for path in "$ENV_FILE" "$COMPOSE_BASE" "$COMPOSE_LOCAL"; do
    [[ -f "$path" ]] || die "缺少输入文件：$path"
  done

  for path in docker tar gzip curl; do
    require_command "$path"
  done
  docker info >/dev/null 2>&1 || die "当前用户无法访问 Docker daemon"
  docker compose version >/dev/null 2>&1 || die "需要 Docker Compose v2"
  docker buildx version >/dev/null 2>&1 || die "需要 Docker Buildx"

  for container in "${DB_CONTAINERS[@]}"; do
    container_is_running "$container" || die "数据库容器未运行：$container"
  done
  for container in "${APP_CONTAINERS[@]}"; do
    docker inspect "$container" >/dev/null 2>&1 || die "应用容器不存在：$container"
  done
}

record_app_state() {
  local container
  RUNNING_APPS=()
  for container in "${APP_CONTAINERS[@]}"; do
    if container_is_running "$container"; then
      RUNNING_APPS+=("$container")
    fi
  done
}

restore_app_state() {
  if [[ "$APPS_STOPPED" == true && ${#RUNNING_APPS[@]} -gt 0 ]]; then
    printf '恢复本地应用服务...\n'
    docker start "${RUNNING_APPS[@]}" >/dev/null
    APPS_STOPPED=false
  fi
}

remove_build_override() {
  if [[ -n "$BUILD_OVERRIDE" && -f "$BUILD_OVERRIDE" ]]; then
    rm -f "$BUILD_OVERRIDE"
  fi
}

remove_owned_workdir() {
  if [[ "$WORKDIR_CREATED" == true && -n "${RELEASE_DIR:-}" && -d "$RELEASE_DIR" ]]; then
    case "$RELEASE_DIR" in
      "$OUTPUT_ROOT"/k-acp_x86_64-*) rm -rf "$RELEASE_DIR" ;;
      "$OUTPUT_ROOT"/k-acp-x86_64-*) rm -rf "$RELEASE_DIR" ;;
      *) printf '拒绝清理非预期目录：%s\n' "$RELEASE_DIR" >&2 ;;
    esac
  fi
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  restore_app_state || true
  remove_build_override || true
  if (( exit_code != 0 )); then
    remove_owned_workdir || true
  fi
  exit "$exit_code"
}

initialize_workdir() {
  mkdir -p "$OUTPUT_ROOT"
  mkdir -p "$RELEASE_DIR"/{backups,images,scripts,data,logs}
  WORKDIR_CREATED=true
}

stop_running_apps() {
  if [[ ${#RUNNING_APPS[@]} -gt 0 ]]; then
    printf '停止本地应用服务以生成一致性备份...\n'
    APPS_STOPPED=true
    docker stop "${RUNNING_APPS[@]}" >/dev/null
  fi
}

backup_mysql() {
  printf '[备份] MySQL\n'
  docker exec k-acp-mysql sh -c \
    'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --triggers --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
    | gzip -1 > "$RELEASE_DIR/backups/mysql.sql.gz"
}

backup_pgvector() {
  printf '[备份] pgvector\n'
  docker exec k-acp-pgvector sh -c \
    'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
    > "$RELEASE_DIR/backups/pgvector.dump"
}

backup_redis() {
  printf '[备份] Redis\n'
  docker exec k-acp-redis sh -c \
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning SAVE >/dev/null'
  docker cp k-acp-redis:/data/dump.rdb "$RELEASE_DIR/backups/redis.rdb" >/dev/null
  chmod 600 "$RELEASE_DIR/backups/redis.rdb"
}

backup_apboa_data() {
  local temp_dir="$RELEASE_DIR/.apboa-export"
  printf '[备份] .apboa 共享数据\n'
  mkdir -p "$temp_dir"
  docker cp k-acp-console:/app/.apboa/. "$temp_dir/" >/dev/null
  tar -czf "$RELEASE_DIR/backups/apboa-data.tar.gz" -C "$temp_dir" .
  rm -rf "$temp_dir"
}

validate_backups() {
  printf '验证数据备份...\n'
  gzip -t "$RELEASE_DIR/backups/mysql.sql.gz"
  gzip -t "$RELEASE_DIR/backups/apboa-data.tar.gz"
  docker exec -i k-acp-pgvector pg_restore -l \
    < "$RELEASE_DIR/backups/pgvector.dump" >/dev/null
  [[ "$(LC_ALL=C head -c 5 "$RELEASE_DIR/backups/redis.rdb")" == "REDIS" ]] \
    || die "Redis RDB 文件头无效"
  [[ -s "$RELEASE_DIR/backups/mysql.sql.gz" ]] || die "MySQL 备份为空"
  [[ -s "$RELEASE_DIR/backups/pgvector.dump" ]] || die "pgvector 备份为空"
}

image_ref() {
  printf 'k-acp-bundle/%s:%s-amd64' "$1" "$TAG"
}

write_build_override() {
  BUILD_OVERRIDE="$RELEASE_DIR/.build-amd64.yml"
  {
    printf 'services:\n'
    local service
    for service in "${IMAGE_SERVICES[@]}"; do
      printf '  apboa-%s:\n' "$service"
      printf '    image: %s\n' "$(image_ref "$service")"
      printf '    platform: linux/amd64\n'
    done
  } > "$BUILD_OVERRIDE"
}

build_amd64_images() {
  local services=(apboa-console apboa-runtime apboa-proxy apboa-websocket apboa-frontend)
  if [[ "$SKIP_BUILD" == true ]]; then
    printf '跳过 AMD64 构建，复用本地镜像。\n'
    return
  fi

  printf '构建五个 linux/amd64 应用镜像...\n'
  write_build_override
  DOCKER_DEFAULT_PLATFORM=linux/amd64 docker compose \
    --env-file "$ENV_FILE" \
    -p k-acp-amd64-build \
    -f "$COMPOSE_BASE" \
    -f "$COMPOSE_LOCAL" \
    -f "$BUILD_OVERRIDE" \
    build "${services[@]}"
}

validate_images() {
  local service image platform
  printf '验证应用镜像架构和运行时...\n'
  for service in "${IMAGE_SERVICES[@]}"; do
    image="$(image_ref "$service")"
    platform="$(docker image inspect --format '{{.Architecture}}/{{.Os}}' "$image" 2>/dev/null || true)"
    [[ "$platform" == "amd64/linux" ]] || die "镜像架构错误或镜像不存在：$image ($platform)"
  done

  for service in console runtime proxy websocket; do
    docker run --rm --platform linux/amd64 --entrypoint java "$(image_ref "$service")" -version >/dev/null 2>&1
  done
  docker run --rm --platform linux/amd64 --entrypoint nginx "$(image_ref frontend)" -v >/dev/null 2>&1
}

export_images() {
  local images=()
  local service
  for service in "${IMAGE_SERVICES[@]}"; do
    images+=("$(image_ref "$service")")
  done
  printf '导出五个应用镜像...\n'
  docker save "${images[@]}" | gzip -1 > "$RELEASE_DIR/images/k-acp-app-images.tar.gz"
  gzip -t "$RELEASE_DIR/images/k-acp-app-images.tar.gz"
}

set_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temp="$file.tmp"
  awk -v key="$key" -v value="$value" '
    BEGIN { found = 0 }
    index($0, key "=") == 1 { print key "=" value; found = 1; next }
    { print }
    END { if (!found) print key "=" value }
  ' "$file" > "$temp"
  mv "$temp" "$file"
}

write_environment() {
  cp "$ENV_FILE" "$RELEASE_DIR/.env"
  set_env_value "$RELEASE_DIR/.env" DATA_PATH ./data
  set_env_value "$RELEASE_DIR/.env" LOG_PATH ./logs
  set_env_value "$RELEASE_DIR/.env" FRONTEND_PORT 23080
  set_env_value "$RELEASE_DIR/.env" APBOA_NODE_ID k-acp-local
  set_env_value "$RELEASE_DIR/.env" APBOA_HOST_NAME k-acp-local
  set_env_value "$RELEASE_DIR/.env" APBOA_HOST_IP "$HOST_IP"
  set_env_value "$RELEASE_DIR/.env" PUBLIC_URL "http://$HOST_IP:23080/web"
  chmod 600 "$RELEASE_DIR/.env"
}

write_compose() {
  cat > "$RELEASE_DIR/compose.yml" <<'COMPOSE_EOF'
name: k-acp-local

x-app-common: &app-common
  restart: unless-stopped
  platform: linux/amd64
  extra_hosts:
    - "host.docker.internal:host-gateway"
  networks: [k-acp]

services:
  apboa-mysql:
    image: mysql:8.0
    container_name: k-acp-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      TZ: Asia/Shanghai
    command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci", "--default-time-zone=+08:00"]
    ports: ["${MYSQL_PORT:-23306}:3306"]
    volumes: ["./data/mysql:/var/lib/mysql"]
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -u root -p\"$$MYSQL_ROOT_PASSWORD\""]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s
    networks: [k-acp]

  apboa-redis:
    image: redis:7-alpine
    container_name: k-acp-redis
    restart: unless-stopped
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      TZ: Asia/Shanghai
    command: sh -c "redis-server --requirepass $$REDIS_PASSWORD"
    ports: ["${REDIS_PORT:-26379}:6379"]
    volumes: ["./data/redis:/data"]
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_PASSWORD\" ping"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks: [k-acp]

  apboa-pgvector:
    image: pgvector/pgvector:pg16
    container_name: k-acp-pgvector
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${PG_DATABASE}
      POSTGRES_USER: ${PG_USER}
      POSTGRES_PASSWORD: ${PG_PASSWORD}
      TZ: Asia/Shanghai
    ports: ["${PG_PORT:-25433}:5432"]
    volumes: ["./data/pgvector:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 10s
      timeout: 5s
      retries: 10
    networks: [k-acp]

  apboa-console:
    <<: *app-common
    image: k-acp-bundle/console:__TAG__-amd64
    container_name: k-acp-console
    cap_drop: [ALL]
    cap_add: [SETUID, SETGID, CHOWN]
    security_opt: [no-new-privileges:true]
    mem_limit: ${CONSOLE_MEM_LIMIT:-2g}
    cpus: ${CONSOLE_CPU_LIMIT:-4}
    environment:
      SPRING_PROFILES_ACTIVE: docker
      MYSQL_HOST: apboa-mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      REDIS_HOST: apboa-redis
      REDIS_PORT: "6379"
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      REDIS_DATABASE: ${REDIS_DATABASE}
      JWT_SECRET: ${JWT_SECRET}
      CONSOLE_JAVA_HEAP_PERCENTAGE: ${CONSOLE_JAVA_HEAP_PERCENTAGE:-75.0}
      WEBSOCKET_ENABLED: ${WEBSOCKET_ENABLED:-true}
      WEBSOCKET_HOST: apboa-websocket
      WEBSOCKET_PORT: "3064"
      TZ: Asia/Shanghai
    ports: ["23060:3060"]
    volumes: ["./logs/console:/app/logs", "./data/apboa:/app/.apboa"]
    depends_on:
      apboa-mysql: {condition: service_healthy}
      apboa-redis: {condition: service_healthy}

  apboa-runtime:
    <<: *app-common
    image: k-acp-bundle/runtime:__TAG__-amd64
    container_name: k-acp-runtime
    cap_drop: [ALL]
    cap_add: [SETUID, SETGID, CHOWN]
    security_opt: [no-new-privileges:true]
    mem_limit: ${RUNTIME_MEM_LIMIT:-4g}
    cpus: ${RUNTIME_CPU_LIMIT:-4}
    environment:
      SPRING_PROFILES_ACTIVE: docker
      MYSQL_HOST: apboa-mysql
      MYSQL_PORT: "3306"
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      REDIS_HOST: apboa-redis
      REDIS_PORT: "6379"
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      REDIS_DATABASE: ${REDIS_DATABASE}
      JWT_SECRET: ${JWT_SECRET}
      VECTOR_STORE_TYPE: ${VECTOR_STORE_TYPE}
      PG_HOST: apboa-pgvector
      PG_PORT: "5432"
      PG_DATABASE: ${PG_DATABASE}
      PG_USER: ${PG_USER}
      PG_PASSWORD: ${PG_PASSWORD}
      SHELL_PROXY_ENABLED: "true"
      PROXY_HOST: apboa-proxy
      PROXY_PORT: "3062"
      CONSOLE_HOST: apboa-console
      RUNTIME_JAVA_HEAP_PERCENTAGE: ${RUNTIME_JAVA_HEAP_PERCENTAGE:-75.0}
      WEBSOCKET_ENABLED: ${WEBSOCKET_ENABLED:-true}
      WEBSOCKET_HOST: apboa-websocket
      WEBSOCKET_PORT: "3064"
      WORKSPACE_CAPACITY_MB: ${WORKSPACE_CAPACITY_MB:-30}
      APBOA_NODE_ID: ${APBOA_NODE_ID:-k-acp-local}
      APBOA_HOST_NAME: ${APBOA_HOST_NAME:-k-acp-local}
      APBOA_HOST_IP: ${APBOA_HOST_IP}
      TZ: Asia/Shanghai
    ports: ["23061:3061"]
    volumes: ["./logs/runtime:/app/logs", "./data/apboa:/app/.apboa"]
    depends_on:
      apboa-mysql: {condition: service_healthy}
      apboa-redis: {condition: service_healthy}
      apboa-pgvector: {condition: service_healthy}

  apboa-proxy:
    <<: *app-common
    image: k-acp-bundle/proxy:__TAG__-amd64
    container_name: k-acp-proxy
    cap_drop: [ALL]
    cap_add: [SETUID, SETGID, CHOWN]
    security_opt: [no-new-privileges:true]
    read_only: true
    tmpfs: ["/tmp:exec"]
    working_dir: /tmp
    mem_limit: ${SHELLPROXY_MEM_LIMIT:-1g}
    cpus: ${SHELLPROXY_CPU_LIMIT:-2}
    pids_limit: ${SHELLPROXY_PIDS_LIMIT:-200}
    environment:
      SPRING_PROFILES_ACTIVE: docker
      JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp -Dspring.pid.file=
      CONSOLE_HOST: apboa-console
      SHELL_DEFAULT_TIMEOUT: ${SHELLPROXY_DEFAULT_TIMEOUT:-300}
      SHELL_MAX_TIMEOUT: ${SHELLPROXY_MAX_TIMEOUT:-3600}
      SHELL_MAX_OUTPUT_SIZE: ${SHELLPROXY_MAX_OUTPUT_SIZE:-52428800}
      SHELLPROXY_JAVA_HEAP_PERCENTAGE: ${SHELLPROXY_JAVA_HEAP_PERCENTAGE:-50.0}
      APBOA_NODE_ID: ${APBOA_NODE_ID:-k-acp-local}
      APBOA_HOST_NAME: ${APBOA_HOST_NAME:-k-acp-local}
      APBOA_HOST_IP: ${APBOA_HOST_IP}
      TZ: Asia/Shanghai
    ports: ["23062:3062"]
    volumes: ["./logs/proxy:/app/logs", "./data/apboa:/app/.apboa"]
    depends_on:
      apboa-console: {condition: service_started}

  apboa-websocket:
    <<: *app-common
    image: k-acp-bundle/websocket:__TAG__-amd64
    container_name: k-acp-websocket
    mem_limit: ${WEBSOCKET_MEM_LIMIT:-1g}
    cpus: ${WEBSOCKET_CPU_LIMIT:-2}
    environment:
      SPRING_PROFILES_ACTIVE: docker
      REDIS_HOST: apboa-redis
      REDIS_PORT: "6379"
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      REDIS_DATABASE: ${REDIS_DATABASE}
      CONSOLE_HOST: apboa-console
      WEBSOCKET_JAVA_HEAP_PERCENTAGE: ${WEBSOCKET_JAVA_HEAP_PERCENTAGE:-50.0}
      APBOA_NODE_ID: k-acp-local-websocket
      APBOA_HOST_NAME: k-acp-local-websocket
      APBOA_HOST_IP: ${APBOA_HOST_IP}
      TZ: Asia/Shanghai
    ports: ["23064:3064"]
    depends_on:
      apboa-redis: {condition: service_healthy}

  apboa-frontend:
    <<: *app-common
    image: k-acp-bundle/frontend:__TAG__-amd64
    container_name: k-acp-frontend
    environment:
      RUNTIME_HOST: apboa-runtime
      RUNTIME_PORT: "3061"
      WEBSOCKET_HOST: apboa-websocket
      WEBSOCKET_PORT: "3064"
    entrypoint: >
      /bin/sh -c "
        envsubst '$$RUNTIME_HOST $$RUNTIME_PORT $$WEBSOCKET_HOST $$WEBSOCKET_PORT' < /etc/nginx/conf.d/default.conf > /etc/nginx/conf.d/default.conf.tmp &&
        mv /etc/nginx/conf.d/default.conf.tmp /etc/nginx/conf.d/default.conf &&
        nginx -g 'daemon off;'
      "
    ports: ["${FRONTEND_PORT:-23080}:80"]
    volumes: ["./logs/frontend:/var/log/nginx"]
    depends_on:
      apboa-console: {condition: service_started}
      apboa-runtime: {condition: service_started}
      apboa-websocket: {condition: service_started}

networks:
  k-acp:
    name: k-acp-local
    driver: bridge
COMPOSE_EOF
  sed "s/__TAG__/$TAG/g" "$RELEASE_DIR/compose.yml" > "$RELEASE_DIR/compose.yml.tmp"
  mv "$RELEASE_DIR/compose.yml.tmp" "$RELEASE_DIR/compose.yml"
}

write_install_script() {
  cat > "$RELEASE_DIR/scripts/install.sh" <<'INSTALL_EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

case "$(uname -m)" in
  x86_64|amd64) ;;
  *) echo "错误：此发布包只支持 x86_64/amd64" >&2; exit 1 ;;
esac
command -v docker >/dev/null || { echo "错误：未安装 Docker" >&2; exit 1; }
docker info >/dev/null || { echo "错误：当前用户无法访问 Docker" >&2; exit 1; }
docker compose version >/dev/null || { echo "错误：需要 Docker Compose v2" >&2; exit 1; }
sha256sum -c checksums.sha256
exec "$ROOT_DIR/scripts/restore.sh"
INSTALL_EOF
}

write_restore_script() {
  cat > "$RELEASE_DIR/scripts/restore.sh" <<'RESTORE_EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

[[ ! -f .restore-complete ]] || { echo "错误：数据已经恢复，拒绝重复导入" >&2; exit 1; }
for file in images/k-acp-app-images.tar.gz backups/mysql.sql.gz backups/pgvector.dump backups/redis.rdb backups/apboa-data.tar.gz .env compose.yml; do
  [[ -f "$file" ]] || { echo "错误：缺少 $file" >&2; exit 1; }
done
mkdir -p data/mysql data/pgvector data/redis data/apboa logs/{console,runtime,proxy,frontend}
if find data/mysql data/pgvector -mindepth 1 -print -quit | grep -q .; then
  echo "错误：MySQL 或 pgvector 数据目录不是空目录" >&2
  exit 1
fi

echo '[1/6] 导入应用镜像'
gzip -dc images/k-acp-app-images.tar.gz | docker load
echo '[2/6] 准备 Redis 和 .apboa 数据'
cp backups/redis.rdb data/redis/dump.rdb
tar -xzf backups/apboa-data.tar.gz -C data/apboa
echo '[3/6] 拉取公网基础镜像'
docker compose -f compose.yml pull apboa-mysql apboa-redis apboa-pgvector
echo '[4/6] 启动数据库'
docker compose -f compose.yml up -d apboa-mysql apboa-redis apboa-pgvector

wait_healthy() {
  local container="$1" status=""
  for _ in $(seq 1 90); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
    [[ "$status" == healthy ]] && return 0
    sleep 2
  done
  docker logs --tail 100 "$container" >&2 || true
  echo "错误：$container 未就绪，状态为 $status" >&2
  return 1
}
wait_healthy k-acp-mysql
wait_healthy k-acp-redis
wait_healthy k-acp-pgvector

echo '[5/6] 恢复 MySQL 和 pgvector'
gzip -dc backups/mysql.sql.gz | docker exec -i k-acp-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
docker exec -i k-acp-pgvector sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges' < backups/pgvector.dump
echo '[6/6] 启动全部应用'
docker compose -f compose.yml up -d
touch .restore-complete
exec "$ROOT_DIR/scripts/verify.sh"
RESTORE_EOF
}

write_verify_script() {
  cat > "$RELEASE_DIR/scripts/verify.sh" <<'VERIFY_EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
docker compose -f compose.yml ps

for image in console runtime proxy websocket frontend; do
  ref="$(docker compose -f compose.yml config --images | grep "/$image:" | head -n 1)"
  arch="$(docker image inspect --format '{{.Architecture}}' "$ref")"
  [[ "$arch" == amd64 ]] || { echo "错误：$ref 架构为 $arch" >&2; exit 1; }
done
for container in k-acp-mysql k-acp-redis k-acp-pgvector; do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$container")"
  [[ "$status" == healthy ]] || { echo "错误：$container 状态为 $status" >&2; exit 1; }
done

for _ in $(seq 1 60); do
  if curl -fsS -L --max-time 10 http://127.0.0.1:23080/web >/dev/null; then
    break
  fi
  sleep 2
done
curl -fsS -L --max-time 10 http://127.0.0.1:23080/web >/dev/null || { echo '错误：本机前端未就绪' >&2; exit 1; }
public_url="$(sed -n 's/^PUBLIC_URL=//p' .env | head -n 1)"
curl -fsS -L --max-time 10 "$public_url" >/dev/null || { echo "错误：外部地址无法访问：$public_url" >&2; exit 1; }
echo "部署验证通过：$public_url"
VERIFY_EOF
}

write_readme() {
  cat > "$RELEASE_DIR/README.md" <<README_EOF
# K-ACP x86_64 发布包

目标系统：Ubuntu 22.04 x86_64。访问地址：\`http://$HOST_IP:23080/web\`。

本包包含五个当前代码构建的 linux/amd64 应用镜像，以及当前 MySQL、pgvector、Redis 和 .apboa 数据。MySQL、Redis、pgvector 基础镜像在安装时从 Docker Hub 拉取，服务器不需要预装 Nginx。

## 安装

\`\`\`bash
tar -xzf $RELEASE_NAME.tar.gz
cd $RELEASE_NAME
chmod 600 .env
sudo ./scripts/install.sh
\`\`\`

服务器防火墙需要允许 TCP 23080：\`sudo ufw allow 23080/tcp\`。

注意：\`.env\` 包含当前环境密码和 JWT，只能作为敏感部署文件保存，不得上传代码仓库。
README_EOF
}

write_source_manifest() {
  local commit dirty service image metadata
  commit="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || printf unknown)"
  if [[ -n "$(git -C "$REPO_ROOT" status --short 2>/dev/null || true)" ]]; then dirty=yes; else dirty=no; fi
  {
    printf '# K-ACP 发布来源清单\n\n'
    printf -- '- 生成时间：%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
    printf -- '- 源代码提交：`%s`\n' "$commit"
    printf -- '- 构建时工作区有未提交修改：`%s`\n' "$dirty"
    printf -- '- 目标平台：`linux/amd64`\n'
    printf -- '- 外部地址：`http://%s:23080/web`\n\n' "$HOST_IP"
    printf '## 应用镜像\n\n'
    for service in "${IMAGE_SERVICES[@]}"; do
      image="$(image_ref "$service")"
      metadata="$(docker image inspect --format '{{.Architecture}}/{{.Os}} | {{.Id}} | {{.Size}} bytes' "$image")"
      printf -- '- `%s`：%s\n' "$image" "$metadata"
    done
    printf '\n基础镜像 `mysql:8.0`、`redis:7-alpine`、`pgvector/pgvector:pg16` 不随包导出。\n'
  } > "$RELEASE_DIR/SOURCE_MANIFEST.md"
}

sha256_line() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file"
  else
    shasum -a 256 "$file"
  fi
}

write_checksums() {
  local files=(
    .env compose.yml README.md SOURCE_MANIFEST.md
    scripts/install.sh scripts/restore.sh scripts/verify.sh
    backups/mysql.sql.gz backups/pgvector.dump backups/redis.rdb backups/apboa-data.tar.gz
    images/k-acp-app-images.tar.gz
  )
  local file
  : > "$RELEASE_DIR/checksums.sha256"
  for file in "${files[@]}"; do
    (cd "$RELEASE_DIR" && sha256_line "$file") >> "$RELEASE_DIR/checksums.sha256"
  done
}

remove_macos_metadata() {
  find "$RELEASE_DIR" -type f -name .DS_Store -delete
}

verify_checksums() {
  local expected file actual
  while read -r expected file; do
    file="${file#\*}"
    actual="$(sha256_line "$RELEASE_DIR/$file" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || die "校验和不匹配：$file"
  done < "$RELEASE_DIR/checksums.sha256"
}

write_release_files() {
  printf '生成 Compose、恢复脚本和文档...\n'
  write_environment
  write_compose
  write_install_script
  write_restore_script
  write_verify_script
  write_readme
  write_source_manifest
  chmod 755 "$RELEASE_DIR/scripts/"*.sh
  remove_build_override
  remove_macos_metadata
  write_checksums
}

validate_release_tree() {
  local forbidden
  remove_macos_metadata
  bash -n "$RELEASE_DIR/scripts/install.sh" "$RELEASE_DIR/scripts/restore.sh" "$RELEASE_DIR/scripts/verify.sh"
  docker compose --env-file "$RELEASE_DIR/.env" -f "$RELEASE_DIR/compose.yml" config --quiet
  verify_checksums
  gzip -t "$RELEASE_DIR/images/k-acp-app-images.tar.gz"
  forbidden="$(find "$RELEASE_DIR" \( -name .DS_Store -o -name target -o -name dist -o -name graphify-out -o -name '.build-amd64.yml' \) -print)"
  [[ -z "$forbidden" ]] || die "发布目录包含禁入文件：$forbidden"
  [[ "$(find "$RELEASE_DIR" -name compose.yml | wc -l | tr -d ' ')" == 1 ]] || die "发布包必须只有一个 compose.yml"
}

create_archive() {
  printf '生成最终压缩包...\n'
  remove_macos_metadata
  tar -czf "$ARCHIVE_PATH" -C "$OUTPUT_ROOT" "$RELEASE_NAME"
  gzip -t "$ARCHIVE_PATH"
  if [[ "$KEEP_WORKDIR" != true ]]; then
    rm -rf "$RELEASE_DIR"
    WORKDIR_CREATED=false
  fi
}

finish_release() {
  write_release_files
  validate_release_tree
  create_archive
  printf '\n打包完成：%s\n' "$ARCHIVE_PATH"
  printf 'SHA-256：%s\n' "$(sha256_line "$ARCHIVE_PATH" | awk '{print $1}')"
  printf '访问地址：http://%s:23080/web\n' "$HOST_IP"
}

run_backup_and_build() {
  preflight
  initialize_workdir
  record_app_state
  stop_running_apps

  if [[ "${KACP_PACKAGE_TEST_FAIL_AFTER_STOP:-0}" == "1" ]]; then
    die "测试注入：应用停止后失败"
  fi

  backup_mysql
  backup_pgvector
  backup_redis
  backup_apboa_data
  validate_backups
  restore_app_state
  build_amd64_images
  validate_images
  export_images
}

main() {
  parse_args "$@"
  resolve_paths

  if [[ "$DRY_RUN" == true ]]; then
    preflight
    dry_run_summary
    return 0
  fi

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  run_backup_and_build
  finish_release
}

main "$@"
