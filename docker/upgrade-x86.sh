#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE=""
INSTALL_DIR=""
NEW_DIR=""
FRONTEND_URL="http://127.0.0.1:23080/web"
TEMP_DIR=""
SOURCE_IS_TEMP=false
BACKUP_COMPOSE=""
UPGRADE_APPLIED=false
COMPLETED=false
DOCKER=(docker)
APP_SERVICES=(apboa-console apboa-runtime apboa-proxy apboa-websocket apboa-frontend)
IMAGE_NAMES=(console runtime proxy websocket frontend)

usage() {
  cat <<'USAGE'
用法一（推荐）：
  cd <旧部署目录>
  tar -xzf k-acp-x86_64-*.tar.gz
  cd k-acp-x86_64-*
  ./upgrade-k-acp-x86.sh

用法二（兼容）：
  ./upgrade-x86.sh --package <发布包.tar.gz> --install-dir <旧部署目录> [选项]

保留服务器现有 MySQL、Redis、pgvector、.apboa 和 .env，只更新五个应用镜像。

参数：
  --package <文件>       新的 k-acp-x86_64-*.tar.gz（不传时使用当前解压目录）
  --install-dir <目录>   旧版本部署目录（不传时为当前解压目录的上一级）
  --frontend-url <URL>   更新后健康检查地址（默认：http://127.0.0.1:23080/web）
  -h, --help             显示帮助

兼容示例：
  ./upgrade-x86.sh \
    --package /tmp/k-acp-x86_64-202607173.tar.gz \
    --install-dir /opt/k-acp
USAGE
}

die() {
  printf '错误：%s\n' "$*" >&2
  exit 1
}

require_value() {
  [[ -n "${2:-}" ]] || die "$1 缺少参数值"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --package)
        require_value "$1" "${2:-}"
        PACKAGE="$2"
        shift 2
        ;;
      --install-dir)
        require_value "$1" "${2:-}"
        INSTALL_DIR="$2"
        shift 2
        ;;
      --frontend-url)
        require_value "$1" "${2:-}"
        FRONTEND_URL="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *) die "未知参数：$1" ;;
    esac
  done
}

select_docker() {
  if docker info >/dev/null 2>&1; then
    DOCKER=(docker)
  elif command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
    DOCKER=(sudo -n docker)
  else
    die "当前用户无法访问 Docker；请使用有 Docker 权限的账号或配置免密 sudo"
  fi
  "${DOCKER[@]}" compose version >/dev/null 2>&1 || die "需要 Docker Compose v2"
}

validate_inputs() {
  if [[ -n "$PACKAGE" ]]; then
    [[ -f "$PACKAGE" ]] || die "发布包不存在：$PACKAGE"
    PACKAGE="$(cd "$(dirname "$PACKAGE")" && pwd)/$(basename "$PACKAGE")"
    gzip -t "$PACKAGE"
    [[ -n "$INSTALL_DIR" ]] || INSTALL_DIR="$(pwd)"
  else
    NEW_DIR="$(pwd)"
    [[ -n "$INSTALL_DIR" ]] || INSTALL_DIR="$(cd .. && pwd)"
  fi

  [[ -d "$INSTALL_DIR" ]] || die "旧部署目录不存在：$INSTALL_DIR"
  INSTALL_DIR="$(cd "$INSTALL_DIR" && pwd)"

  [[ -f "$INSTALL_DIR/compose.yml" ]] || die "旧部署目录缺少 compose.yml"
  [[ -f "$INSTALL_DIR/.env" ]] || die "旧部署目录缺少 .env"
  [[ -d "$INSTALL_DIR/data" ]] || die "旧部署目录缺少 data/，拒绝升级"

  if [[ -z "$PACKAGE" ]]; then
    [[ "$NEW_DIR" != "$INSTALL_DIR" ]] || die "新发布目录不能与旧部署目录相同"
    validate_release_dir
  fi
}

validate_release_dir() {
  [[ -f "$NEW_DIR/compose.yml" ]] || die "新发布目录缺少 compose.yml：$NEW_DIR"
  [[ -f "$NEW_DIR/checksums.sha256" ]] || die "新发布目录缺少 checksums.sha256：$NEW_DIR"
  [[ -f "$NEW_DIR/images/k-acp-app-images.tar.gz" ]] || die "新发布目录缺少应用镜像归档"
  (cd "$NEW_DIR" && sha256sum -c checksums.sha256)
}

safe_extract_package() {
  local listing top_count top_dir
  listing="$(tar -tzf "$PACKAGE")"
  [[ -n "$listing" ]] || die "发布包为空"
  if printf '%s\n' "$listing" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    die "发布包包含不安全路径"
  fi

  top_count="$(printf '%s\n' "$listing" | awk -F/ 'NF {print $1}' | sort -u | wc -l | tr -d ' ')"
  [[ "$top_count" == 1 ]] || die "发布包必须只有一个顶层目录"
  top_dir="$(printf '%s\n' "$listing" | awk -F/ 'NF {print $1; exit}')"

  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/k-acp-upgrade.XXXXXX")"
  SOURCE_IS_TEMP=true
  tar -xzf "$PACKAGE" -C "$TEMP_DIR"
  NEW_DIR="$TEMP_DIR/$top_dir"
  validate_release_dir
}

extract_new_image_ref() {
  local image_name="$1" ref count
  count="$(grep -Ec "image:[[:space:]]*k-acp-bundle/${image_name}:[A-Za-z0-9._-]+" "$NEW_DIR/compose.yml")"
  [[ "$count" == 1 ]] || die "新 Compose 中 ${image_name} 镜像配置数量异常：$count"
  ref="$(grep -E "image:[[:space:]]*k-acp-bundle/${image_name}:[A-Za-z0-9._-]+" "$NEW_DIR/compose.yml" | sed -E 's/^[[:space:]]*image:[[:space:]]*//')"
  [[ "$ref" =~ ^k-acp-bundle/${image_name}:[A-Za-z0-9._-]+$ ]] || die "无效镜像引用：$ref"
  printf '%s' "$ref"
}

prepare_candidate_compose() {
  local image_name ref candidate temp
  candidate="$INSTALL_DIR/.compose.upgrade.$$"
  cp "$INSTALL_DIR/compose.yml" "$candidate"

  for image_name in "${IMAGE_NAMES[@]}"; do
    ref="$(extract_new_image_ref "$image_name")"
    [[ "$(grep -Ec "k-acp-bundle/${image_name}:[A-Za-z0-9._-]+" "$candidate")" == 1 ]] \
      || die "旧 Compose 中 ${image_name} 镜像配置数量异常"
    temp="$candidate.tmp"
    awk -v name="$image_name" -v replacement="$ref" '
      {
        pattern = "k-acp-bundle/" name ":[A-Za-z0-9._-]+"
        gsub(pattern, replacement)
        print
      }
    ' "$candidate" > "$temp"
    mv "$temp" "$candidate"
  done

  "${DOCKER[@]}" compose --env-file "$INSTALL_DIR/.env" -f "$candidate" config --quiet
  CANDIDATE_COMPOSE="$candidate"
}

rollback() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ "$UPGRADE_APPLIED" == true && "$COMPLETED" != true && -f "$BACKUP_COMPOSE" ]]; then
    printf '升级失败，恢复旧 Compose 和旧应用镜像...\n' >&2
    cp "$BACKUP_COMPOSE" "$INSTALL_DIR/compose.yml"
    "${DOCKER[@]}" compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/compose.yml" \
      up -d --no-deps "${APP_SERVICES[@]}" >/dev/null 2>&1 || true
  fi
  if [[ "$SOURCE_IS_TEMP" == true && -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
  [[ -z "${CANDIDATE_COMPOSE:-}" || ! -f "$CANDIDATE_COMPOSE" ]] || rm -f "$CANDIDATE_COMPOSE"
  exit "$exit_code"
}

perform_upgrade() {
  local timestamp
  printf '[1/5] 校验并导入五个应用镜像\n'
  gzip -dc "$NEW_DIR/images/k-acp-app-images.tar.gz" | "${DOCKER[@]}" load

  printf '[2/5] 生成只替换镜像标签的 Compose\n'
  prepare_candidate_compose

  timestamp="$(date +%Y%m%d-%H%M%S)"
  BACKUP_COMPOSE="$INSTALL_DIR/compose.yml.bak.$timestamp"
  cp -p "$INSTALL_DIR/compose.yml" "$BACKUP_COMPOSE"
  mv "$CANDIDATE_COMPOSE" "$INSTALL_DIR/compose.yml"
  CANDIDATE_COMPOSE=""
  UPGRADE_APPLIED=true

  printf '[3/5] 只重建五个应用容器\n'
  "${DOCKER[@]}" compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/compose.yml" \
    up -d --no-deps "${APP_SERVICES[@]}"

  printf '[4/5] 检查容器状态\n'
  "${DOCKER[@]}" compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/compose.yml" ps

  printf '[5/5] 检查前端访问\n'
  local ready=false
  for _ in $(seq 1 60); do
    if curl -fsSL --max-time 10 "$FRONTEND_URL" >/dev/null; then
      ready=true
      break
    fi
    sleep 2
  done
  [[ "$ready" == true ]] || die "前端在 120 秒内未就绪：$FRONTEND_URL"

  COMPLETED=true
  printf '\n升级完成：%s\n' "$FRONTEND_URL"
  printf '旧 Compose 备份：%s\n' "$BACKUP_COMPOSE"
  printf '现有 .env、data/ 和数据库容器均未覆盖。\n'
}

main() {
  parse_args "$@"
  validate_inputs
  select_docker
  trap rollback EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if [[ -n "$PACKAGE" ]]; then
    safe_extract_package
  fi
  perform_upgrade
}

main "$@"
