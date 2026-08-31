#!/usr/bin/env bash
# 在测试环境(192.168.107.137)上执行命令。
# 前提：~/.ssh/config 中已配置 Host kacp-test（走 Clash 代理 + 连接复用），
#       密码读自 env/test/.env（不写入命令行日志之外的任何地方）。
# 用法:
#   scripts/remote-test.sh "<远程命令>"
#   scripts/remote-test.sh --push [rsync选项] <本地路径> <远程路径>
#   scripts/remote-test.sh --pull [rsync选项] <远程路径> <本地路径>
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
set -a; source "$REPO_ROOT/env/test/.env"; set +a

# 打开/复用主连接并认证（SSH_ASKPASS 方式，兼容 ssh -f 后台驻留）
ssh -S /tmp/kacp-cm-%r@%h -O check kacp-test >/dev/null 2>&1 || {
  KACP_SSH_PW="$SSH_PASSWORD" SSH_ASKPASS=/tmp/kacp-askpass.sh SSH_ASKPASS_REQUIRE=force DISPLAY=:0 \
    ssh -MNf -F ~/.ssh/config kacp-test
}
[[ -f /tmp/kacp-askpass.sh ]] || { echo '#!/bin/sh' > /tmp/kacp-askpass.sh; echo 'echo "$KACP_SSH_PW"' >> /tmp/kacp-askpass.sh; chmod 700 /tmp/kacp-askpass.sh; }

case "${1:-}" in
  --push)
    shift
    [[ $# -ge 2 ]] || { echo "用法: $0 --push [rsync选项] <本地> <远程>" >&2; exit 64; }
    local_path="${@: -2:1}"; remote_path="${@: -1}"
    flags=("${@:1:$#-2}")
    exec rsync -az --stats "${flags[@]}" -e "ssh" "$local_path" "kacp-test:$remote_path"
    ;;
  --pull)
    shift
    [[ $# -ge 2 ]] || { echo "用法: $0 --pull [rsync选项] <远程> <本地>" >&2; exit 64; }
    remote_path="${@: -2:1}"; local_path="${@: -1}"
    flags=("${@:1:$#-2}")
    exec rsync -az --stats "${flags[@]}" -e "ssh" "kacp-test:$remote_path" "$local_path"
    ;;
  "")
    echo "用法: $0 \"<远程命令>\" | --push [opts] <本地> <远程> | --pull [opts] <远程> <本地>" >&2; exit 64
    ;;
  *)
    exec ssh kacp-test "$1"
    ;;
esac
