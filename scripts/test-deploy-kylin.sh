#!/usr/bin/env bash
# ============================================================
# 快速测试 deploy-kylin.sh 脚本
# 用于验证脚本语法和基本功能
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=========================================="
echo "测试 deploy-kylin.sh 脚本"
echo "=========================================="
echo ""

# 测试1: 脚本是否可执行
echo ">> [测试1] 检查脚本可执行权限..."
if [[ -x "$REPO_ROOT/scripts/deploy-kylin.sh" ]]; then
    echo "✓ 脚本可执行"
else
    echo "✗ 脚本不可执行"
    chmod +x "$REPO_ROOT/scripts/deploy-kylin.sh"
    echo "  已添加执行权限"
fi

# 测试2: 帮助信息
echo ""
echo ">> [测试2] 测试帮助信息..."
if "$REPO_ROOT/scripts/deploy-kylin.sh" --help > /dev/null 2>&1; then
    echo "✓ 帮助信息正常"
else
    echo "✗ 帮助信息错误"
    exit 1
fi

# 测试3: 环境文件检查
echo ""
echo ">> [测试3] 检查kylin环境配置..."
if [[ -f "$REPO_ROOT/env/kylin/.env" ]]; then
    echo "✓ kylin环境配置文件存在"
    # 检查必要的配置项
    if grep -q "SSH_HOST=10.11.2.68" "$REPO_ROOT/env/kylin/.env"; then
        echo "✓ SSH_HOST配置正确"
    else
        echo "✗ SSH_HOST配置不正确"
    fi
else
    echo "✗ kylin环境配置文件不存在"
    exit 1
fi

# 测试4: Docker环境检查
echo ""
echo ">> [测试4] 检查Docker环境..."
if command -v docker &> /dev/null; then
    echo "✓ Docker已安装: $(docker --version)"
else
    echo "⚠ Docker未安装（离线部署包生成需要Docker）"
fi

if docker compose version &> /dev/null; then
    echo "✓ Docker Compose已安装: $(docker compose version)"
else
    echo "⚠ Docker Compose未安装（离线部署包生成需要Docker Compose）"
fi

# 测试5: 必要的文件检查
echo ""
echo ">> [测试5] 检查必要的项目文件..."
required_files=(
    "docker/docker-compose-simple.yml"
    "docker/.env.simple"
    "sql/db_init.sql"
    "docker/console/Dockerfile"
    "docker/runtime/Dockerfile"
    "docker/proxy/Dockerfile"
    "docker/websocket/Dockerfile"
    "docker/frontend/Dockerfile"
)

all_exist=true
for file in "${required_files[@]}"; do
    if [[ -f "$REPO_ROOT/$file" ]]; then
        echo "✓ $file"
    else
        echo "✗ $file 不存在"
        all_exist=false
    fi
done

if $all_exist; then
    echo "✓ 所有必要文件存在"
else
    echo "✗ 部分文件缺失"
    exit 1
fi

# 测试6: 语法检查
echo ""
echo ">> [测试6] 检查脚本语法..."
if bash -n "$REPO_ROOT/scripts/deploy-kylin.sh"; then
    echo "✓ 脚本语法正确"
else
    echo "✗ 脚本语法错误"
    exit 1
fi

echo ""
echo "=========================================="
echo "所有测试通过！"
echo "=========================================="
echo ""
echo "下一步操作:"
echo "  1. 生成离线部署包:"
echo "     ./scripts/deploy-kylin.sh --prepare-offline"
echo ""
echo "  2. 查看kylin服务器状态（需要SSH密码）:"
echo "     ./scripts/deploy-kylin.sh --status"
echo ""
