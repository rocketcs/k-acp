#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$TEST_DIR/.." && pwd)"
SOURCE_KACP="$DOCKER_DIR/kacp"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/k-acp-kacp-test.XXXXXX")"
PASS_COUNT=0
FAIL_COUNT=0
OUTPUT=""
STATUS=0

cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

run_command() {
  set +e
  OUTPUT="$({ "$@"; } 2>&1)"
  STATUS=$?
  set -e
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS: %s\n' "$1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf 'FAIL: %s\n%s\n' "$1" "$OUTPUT" >&2
}

assert_success() {
  local name="$1"
  shift
  run_command "$@"
  if [[ $STATUS -eq 0 ]]; then pass "$name"; else fail "$name"; fi
}

assert_failure() {
  local name="$1"
  shift
  run_command "$@"
  if [[ $STATUS -ne 0 ]]; then pass "$name"; else fail "$name"; fi
}

assert_file_contains() {
  local name="$1"
  local file="$2"
  local expected="$3"
  if [[ -f "$file" ]] && grep -Fq "$expected" "$file"; then
    pass "$name"
  else
    if [[ -f "$file" ]]; then OUTPUT="$(<"$file")"; else OUTPUT="文件不存在：$file"; fi
    fail "${name}（缺少：${expected}）"
  fi
}

assert_file_not_contains() {
  local name="$1"
  local file="$2"
  local unexpected="$3"
  if [[ ! -f "$file" ]] || ! grep -Fq "$unexpected" "$file"; then
    pass "$name"
  else
    OUTPUT="$(<"$file")"
    fail "${name}（不应包含：${unexpected}）"
  fi
}

prepare_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/docker" "$fixture/fake-bin"
  cp "$SOURCE_KACP" "$fixture/docker/kacp"
  chmod +x "$fixture/docker/kacp"
  touch "$fixture/docker/.env.kacp" \
    "$fixture/docker/docker-compose-simple.yml" \
    "$fixture/docker/docker-compose-kacp-local.yml"

  cat > "$fixture/fake-bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  info) exit 0 ;;
  image) exit 0 ;;
  compose)
    printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
    if [[ " $* " == *" ps --status running --services "* ]]; then
      printf '%s\n' 'apboa-frontend'
    fi
    exit 0
    ;;
esac

printf 'unexpected docker command: %s\n' "$*" >&2
exit 1
FAKE_DOCKER
  chmod +x "$fixture/fake-bin/docker"
}

test_build_frontend() {
  local fixture="$TMP_ROOT/build"
  local docker_log="$fixture/docker.log"
  prepare_fixture "$fixture"

  assert_success "build frontend 成功" env \
    PATH="$fixture/fake-bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    "$fixture/docker/kacp" build frontend
  assert_file_contains "build 使用前端 Compose 服务" "$docker_log" "build apboa-frontend"
  assert_file_not_contains "build 不构建 console" "$docker_log" "build apboa-console"
}

test_rebuild_frontend() {
  local fixture="$TMP_ROOT/rebuild"
  local docker_log="$fixture/docker.log"
  prepare_fixture "$fixture"

  assert_success "rebuild frontend 成功" env \
    PATH="$fixture/fake-bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    "$fixture/docker/kacp" rebuild frontend
  assert_file_contains "rebuild 只重建前端容器" "$docker_log" "up -d --no-deps --force-recreate apboa-frontend"
  assert_file_not_contains "rebuild 不重建 console 容器" "$docker_log" "up -d --no-deps --force-recreate apboa-console"
}

test_help_documents_build_services() {
  local fixture="$TMP_ROOT/help"
  prepare_fixture "$fixture"

  assert_success "help 命令成功" "$fixture/docker/kacp" help
  if [[ "$OUTPUT" == *"不填写服务名          build、rebuild、restart、logs 默认作用于全部五个应用服务"* ]]; then
    pass "help 说明构建服务默认范围"
  else
    fail "help 说明构建服务默认范围"
  fi
}

test_start_waits_for_all_services() {
  local fixture="$TMP_ROOT/start"
  local docker_log="$fixture/docker.log"
  prepare_fixture "$fixture"

  assert_failure "start 在只有前端运行时失败" env \
    PATH="$fixture/fake-bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    KACP_STARTUP_ATTEMPTS=1 \
    "$fixture/docker/kacp" start
}

test_build_frontend
test_rebuild_frontend
test_help_documents_build_services
test_start_waits_for_all_services

printf '\n结果：%d 通过，%d 失败\n' "$PASS_COUNT" "$FAIL_COUNT"
[[ $FAIL_COUNT -eq 0 ]]
