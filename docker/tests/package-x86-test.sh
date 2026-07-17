#!/usr/bin/env bash
set -uo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$TEST_DIR/.." && pwd)"
PACKAGER="$DOCKER_DIR/package-x86.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/k-acp-package-test.XXXXXX")"
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

assert_output_contains() {
  local name="$1"
  local expected="$2"
  if [[ "$OUTPUT" == *"$expected"* ]]; then pass "$name"; else fail "${name}（缺少：${expected}）"; fi
}

assert_path_missing() {
  local name="$1"
  local path="$2"
  if [[ ! -e "$path" ]]; then pass "$name"; else fail "${name}（意外存在：${path}）"; fi
}

assert_path_exists() {
  local name="$1"
  local path="$2"
  if [[ -e "$path" ]]; then pass "$name"; else fail "${name}（缺少：${path}）"; fi
}

assert_file_contains() {
  local name="$1"
  local file="$2"
  local expected="$3"
  if [[ -f "$file" ]] && grep -Fq "$expected" "$file"; then
    pass "$name"
  else
    OUTPUT="${OUTPUT}\n文件 $file 中缺少：$expected"
    fail "$name"
  fi
}

assert_file_not_contains() {
  local name="$1"
  local file="$2"
  local unexpected="$3"
  if [[ ! -f "$file" ]] || ! grep -Fq "$unexpected" "$file"; then
    pass "$name"
  else
    OUTPUT="${OUTPUT}\n文件 $file 中不应出现：$unexpected"
    fail "$name"
  fi
}

test_help() {
  assert_success "帮助命令成功" "$PACKAGER" --help
  assert_output_contains "帮助包含 host-ip" "--host-ip"
  assert_output_contains "帮助包含 skip-build" "--skip-build"
  assert_output_contains "帮助包含 dry-run" "--dry-run"
}

test_invalid_arguments() {
  assert_failure "拒绝无效 IPv4" "$PACKAGER" --host-ip 999.1.1.1 --dry-run
  assert_output_contains "无效 IPv4 有错误信息" "无效的 IPv4 地址"

  assert_failure "拒绝危险标签" "$PACKAGER" --tag ../bad --dry-run
  assert_output_contains "危险标签有错误信息" "无效的发布标签"

  assert_failure "拒绝未知参数" "$PACKAGER" --unknown
  assert_output_contains "未知参数有错误信息" "未知参数"
}

test_dry_run() {
  local output_root="$TMP_ROOT/releases"
  local release_dir="$output_root/k-acp-x86_64-test-001"

  assert_success "dry-run 成功" "$PACKAGER" \
    --host-ip 10.0.0.8 \
    --tag test-001 \
    --output-dir "$output_root" \
    --dry-run
  assert_output_contains "dry-run 显示目标 IP" "目标服务器 IP：10.0.0.8"
  assert_output_contains "dry-run 显示发布标签" "发布标签：test-001"
  assert_output_contains "dry-run 显示输出目录" "输出根目录：$output_root"
  assert_output_contains "dry-run 检查八个容器" "容器检查通过：8/8"
  assert_path_missing "dry-run 不创建发布目录" "$release_dir"
}

test_failure_restores_running_apps() {
  local fake_bin="$TMP_ROOT/fake-bin"
  local docker_log="$TMP_ROOT/docker.log"
  local output_root="$TMP_ROOT/failure-releases"
  mkdir -p "$fake_bin"

  cat > "$fake_bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-} ${2:-}" in
  "info "|"buildx version"|"compose version") exit 0 ;;
  "inspect --format") printf 'running\n'; exit 0 ;;
esac
exit 0
FAKE_DOCKER
  chmod +x "$fake_bin/docker"

  assert_failure "停止应用后的注入错误会失败" env \
    PATH="$fake_bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    KACP_PACKAGE_TEST_FAIL_AFTER_STOP=1 \
    "$PACKAGER" --tag restore-test --output-dir "$output_root"
  assert_output_contains "注入错误信息明确" "测试注入：应用停止后失败"
  assert_file_contains "只停止 K-ACP 应用" "$docker_log" \
    "stop k-acp-console k-acp-runtime k-acp-proxy k-acp-websocket k-acp-frontend"
  assert_file_contains "失败后恢复 K-ACP 应用" "$docker_log" \
    "start k-acp-console k-acp-runtime k-acp-proxy k-acp-websocket k-acp-frontend"
  assert_file_not_contains "不操作 apboa-next" "$docker_log" "apboa-next"
  assert_path_missing "失败后清理自有发布目录" "$output_root/k-acp-x86_64-restore-test"
}

test_signal_restores_running_apps() {
  local fake_bin="$TMP_ROOT/signal-fake-bin"
  local docker_log="$TMP_ROOT/signal-docker.log"
  local output_root="$TMP_ROOT/signal-releases"
  mkdir -p "$fake_bin"

  cat > "$fake_bin/docker" <<'FAKE_SIGNAL_DOCKER'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-} ${2:-}" in
  "info "|"buildx version"|"compose version") exit 0 ;;
  "inspect --format") printf 'running\n'; exit 0 ;;
esac
case "${1:-}" in
  inspect|start) exit 0 ;;
  stop) kill -TERM "$PPID"; exit 0 ;;
esac
exit 0
FAKE_SIGNAL_DOCKER
  chmod +x "$fake_bin/docker"

  assert_failure "TERM 中断返回失败" env \
    PATH="$fake_bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    "$PACKAGER" --tag signal-test --output-dir "$output_root"
  assert_file_contains "TERM 中断后恢复应用" "$docker_log" \
    "start k-acp-console k-acp-runtime k-acp-proxy k-acp-websocket k-acp-frontend"
  assert_path_missing "TERM 中断后清理发布目录" "$output_root/k-acp-x86_64-signal-test"
}

test_generates_complete_release_tree() {
  local fake_bin="$TMP_ROOT/release-fake-bin"
  local docker_log="$TMP_ROOT/release-docker.log"
  local output_root="$TMP_ROOT/complete-releases"
  local release_dir="$output_root/k-acp-x86_64-structure-test"
  local archive="$release_dir.tar.gz"
  local listing
  mkdir -p "$fake_bin"

  cat > "$fake_bin/docker" <<'FAKE_RELEASE_DOCKER'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-} ${2:-}" in
  "info "|"buildx version"|"compose version") exit 0 ;;
  "inspect --format") printf 'running\n'; exit 0 ;;
  "image inspect")
    if [[ -n "${FAKE_RELEASE_DIR:-}" && -d "$FAKE_RELEASE_DIR" ]]; then
      touch "$FAKE_RELEASE_DIR/.DS_Store"
    fi
    printf 'amd64/linux\n'
    exit 0
    ;;
esac
case "${1:-}" in
  inspect|stop|start|run) exit 0 ;;
  exec)
    case "$*" in
      *mysqldump*) printf '%s\n' 'CREATE TABLE sample(id int);' ;;
      *pg_dump*) printf '%s' 'PGDUMP' ;;
      *) : ;;
    esac
    exit 0
    ;;
  cp)
    source_path="${2:-}"
    target_path="${3:-}"
    if [[ "$source_path" == "k-acp-redis:/data/dump.rdb" ]]; then
      printf 'REDIS0012' > "$target_path"
    else
      mkdir -p "$target_path"
      printf 'sample' > "$target_path/sample.txt"
    fi
    exit 0
    ;;
  save)
    printf 'FAKE-DOCKER-IMAGE-ARCHIVE'
    exit 0
    ;;
esac
exit 0
FAKE_RELEASE_DOCKER
  chmod +x "$fake_bin/docker"

  assert_success "生成完整发布目录" env \
    PATH="$fake_bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    FAKE_RELEASE_DIR="$release_dir" \
    "$PACKAGER" \
    --host-ip 10.0.0.8 \
    --tag structure-test \
    --output-dir "$output_root" \
    --skip-build \
    --keep-workdir
  assert_path_exists "生成 compose.yml" "$release_dir/compose.yml"
  assert_path_exists "生成安装脚本" "$release_dir/scripts/install.sh"
  assert_path_exists "生成内部校验和" "$release_dir/checksums.sha256"
  assert_path_exists "生成最终压缩包" "$archive"

  if [[ -f "$release_dir/.env" ]]; then
    OUTPUT="$(cat "$release_dir/.env")"
  else
    OUTPUT=""
  fi
  assert_output_contains "环境配置包含外部地址" "PUBLIC_URL=http://10.0.0.8:23080/web"

  if [[ -d "$release_dir" ]]; then
    OUTPUT="$(find "$release_dir" -name compose.yml | wc -l | tr -d ' ')"
  else
    OUTPUT="0"
  fi
  if [[ "$OUTPUT" == "1" ]]; then pass "只有一个 compose.yml"; else fail "compose.yml 数量应为 1"; fi

  if [[ -f "$archive" ]]; then
    listing="$(tar -tzf "$archive")"
  else
    listing=""
  fi
  OUTPUT="$listing"
  if [[ ! "$listing" =~ \.DS_Store|/target/|/dist/|graphify-out|build-amd64 ]]; then
    pass "压缩包不包含禁入文件"
  else
    fail "压缩包包含禁入文件"
  fi
}

main() {
  set -e
  test_help
  test_invalid_arguments
  test_dry_run
  test_failure_restores_running_apps
  test_signal_restores_running_apps
  test_generates_complete_release_tree
  printf '\n测试结果：%d 通过，%d 失败\n' "$PASS_COUNT" "$FAIL_COUNT"
  [[ $FAIL_COUNT -eq 0 ]]
}

main "$@"
