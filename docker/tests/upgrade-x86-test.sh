#!/usr/bin/env bash
set -uo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPGRADER="$(cd "$TEST_DIR/.." && pwd)/upgrade-x86.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/k-acp-upgrade-test.XXXXXX")"
PASS_COUNT=0
FAIL_COUNT=0
OUTPUT=""
STATUS=0

cleanup() { rm -rf "$TMP_ROOT"; }
trap cleanup EXIT

pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf 'PASS: %s\n' "$1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); printf 'FAIL: %s\n%s\n' "$1" "$OUTPUT" >&2; }

run_command() {
  set +e
  OUTPUT="$({ "$@"; } 2>&1)"
  STATUS=$?
  set -e
}

assert_success() {
  local name="$1"; shift
  run_command "$@"
  [[ $STATUS -eq 0 ]] && pass "$name" || fail "$name"
}

assert_failure() {
  local name="$1"; shift
  run_command "$@"
  [[ $STATUS -ne 0 ]] && pass "$name" || fail "$name"
}

assert_contains() {
  local name="$1" file="$2" expected="$3"
  if [[ -f "$file" ]] && grep -Fq "$expected" "$file"; then pass "$name"; else OUTPUT="文件缺少：$expected"; fail "$name"; fi
}

assert_not_contains() {
  local name="$1" file="$2" unexpected="$3"
  if [[ ! -f "$file" ]] || ! grep -Fq "$unexpected" "$file"; then pass "$name"; else OUTPUT="文件不应包含：$unexpected"; fail "$name"; fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}

make_package() {
  local root="$1/release-new"
  mkdir -p "$root/images"
  cat > "$root/compose.yml" <<'COMPOSE'
services:
  apboa-console:
    image: k-acp-bundle/console:new-amd64
  apboa-runtime:
    image: k-acp-bundle/runtime:new-amd64
  apboa-proxy:
    image: k-acp-bundle/proxy:new-amd64
  apboa-websocket:
    image: k-acp-bundle/websocket:new-amd64
  apboa-frontend:
    image: k-acp-bundle/frontend:new-amd64
COMPOSE
  printf 'fake-images' | gzip -1 > "$root/images/k-acp-app-images.tar.gz"
  (
    cd "$root"
    sha256_file compose.yml
    sha256_file images/k-acp-app-images.tar.gz
  ) > "$root/checksums.sha256"
  tar -czf "$1/package.tar.gz" -C "$1" release-new
  printf '%s' "$1/package.tar.gz"
}

make_install_dir() {
  local dir="$1"
  mkdir -p "$dir/data/mysql" "$dir/data/pgvector"
  printf 'KEEP_DATABASE' > "$dir/data/mysql/sentinel"
  printf 'KEEP_ENV' > "$dir/.env"
  cat > "$dir/compose.yml" <<'COMPOSE'
services:
  apboa-console:
    image: k-acp-bundle/console:old-amd64
  apboa-runtime:
    image: k-acp-bundle/runtime:old-amd64
  apboa-proxy:
    image: k-acp-bundle/proxy:old-amd64
  apboa-websocket:
    image: k-acp-bundle/websocket:old-amd64
  apboa-frontend:
    image: k-acp-bundle/frontend:old-amd64
COMPOSE
}

make_fake_docker() {
  local bin="$1"
  mkdir -p "$bin"
  cat > "$bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-} ${2:-}" in
  "info "|"compose version"|"compose config"|"compose ps") exit 0 ;;
esac
if [[ "${1:-}" == load ]]; then cat >/dev/null; exit 0; fi
if [[ " $* " == *" up "* ]]; then
  [[ "${FAKE_FAIL_UP:-0}" != 1 ]] || exit 42
  exit 0
fi
exit 0
FAKE
  chmod +x "$bin/docker"
  cat > "$bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
exit 0
FAKE_CURL
  chmod +x "$bin/curl"
}

test_help() {
  assert_success "帮助命令成功" "$UPGRADER" --help
  [[ "$OUTPUT" == *"--package"* && "$OUTPUT" == *"--install-dir"* ]] && pass "帮助包含必要参数" || fail "帮助包含必要参数"
}

test_upgrade_preserves_data() {
  local case_dir="$TMP_ROOT/success" install_dir package fake_bin docker_log
  mkdir -p "$case_dir"
  install_dir="$case_dir/install"
  make_install_dir "$install_dir"
  package="$(make_package "$case_dir")"
  fake_bin="$case_dir/bin"
  docker_log="$case_dir/docker.log"
  make_fake_docker "$fake_bin"

  assert_success "保留数据升级成功" env PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$docker_log" \
    "$UPGRADER" --package "$package" --install-dir "$install_dir"
  assert_contains "Compose 更新为新镜像" "$install_dir/compose.yml" "k-acp-bundle/frontend:new-amd64"
  assert_contains "数据库文件保持不变" "$install_dir/data/mysql/sentinel" "KEEP_DATABASE"
  assert_contains "环境配置保持不变" "$install_dir/.env" "KEEP_ENV"
  assert_not_contains "不启动数据库服务" "$docker_log" "apboa-mysql"
  assert_not_contains "不启动 Redis 服务" "$docker_log" "apboa-redis"
  assert_not_contains "不启动 pgvector 服务" "$docker_log" "apboa-pgvector"
}

test_failure_rolls_back_compose() {
  local case_dir="$TMP_ROOT/rollback" install_dir package fake_bin docker_log
  mkdir -p "$case_dir"
  install_dir="$case_dir/install"
  make_install_dir "$install_dir"
  package="$(make_package "$case_dir")"
  fake_bin="$case_dir/bin"
  docker_log="$case_dir/docker.log"
  make_fake_docker "$fake_bin"

  assert_failure "应用启动失败时升级失败" env PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$docker_log" FAKE_FAIL_UP=1 \
    "$UPGRADER" --package "$package" --install-dir "$install_dir"
  assert_contains "失败后恢复旧 Compose" "$install_dir/compose.yml" "k-acp-bundle/frontend:old-amd64"
  assert_contains "失败后数据库文件保持不变" "$install_dir/data/mysql/sentinel" "KEEP_DATABASE"
}

main() {
  set -e
  test_help
  test_upgrade_preserves_data
  test_failure_rolls_back_compose
  printf '\n测试结果：%d 通过，%d 失败\n' "$PASS_COUNT" "$FAIL_COUNT"
  [[ $FAIL_COUNT -eq 0 ]]
}

main "$@"
