#!/usr/bin/env bash
# ============================================================
# Kylin环境部署最终检查和准备脚本
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║         K-ACP Kylin环境部署 - 最终检查                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 检查1: 项目文件完整性
echo ">> [1/7] 检查项目文件完整性..."
required_files=(
    "scripts/deploy-kylin.sh"
    "DEPLOYMENT_KYLIN.md"
    "DEPLOYMENT_SUMMARY.md"
    "QUICK_START_KYLIN.sh"
    "docker/docker-compose-simple.yml"
    "docker/.env.simple"
    "sql/db_init.sql"
)

all_ok=true
for file in "${required_files[@]}"; do
    if [[ -f "$REPO_ROOT/$file" ]]; then
        echo "  ✓ $file"
    else
        echo "  ✗ $file 缺失"
        all_ok=false
    fi
done

if $all_ok; then
    echo "  ✓ 所有必要文件完整"
else
    echo "  ✗ 部分文件缺失，请检查"
    exit 1
fi

# 检查2: Docker环境
echo ""
echo ">> [2/7] 检查Docker环境..."
if command -v docker &> /dev/null; then
    docker_version=$(docker --version)
    echo "  ✓ Docker已安装: $docker_version"
else
    echo "  ✗ Docker未安装"
    echo "    请先安装Docker: https://docs.docker.com/engine/install/"
    exit 1
fi

if docker compose version &> /dev/null; then
    compose_version=$(docker compose version)
    echo "  ✓ Docker Compose已安装: $compose_version"
else
    echo "  ✗ Docker Compose未安装或版本过低"
    echo "    需要Docker Compose v2+"
    exit 1
fi

# 检查3: Docker服务运行状态
echo ""
echo ">> [3/7] 检查Docker服务状态..."
if docker info &> /dev/null; then
    echo "  ✓ Docker服务运行正常"
else
    echo "  ✗ Docker服务未运行"
    echo "    请启动Docker服务"
    exit 1
fi

# 检查4: 磁盘空间
echo ""
echo ">> [4/7] 检查磁盘空间..."
available_space=$(df -h "$REPO_ROOT/.." | tail -1 | awk '{print $4}')
echo "  可用空间: $available_space"
echo "  需求空间: 至少10GB用于构建镜像"
echo "  ✓ 请确保有足够空间"

# 检查5: 环境配置
echo ""
echo ">> [5/7] 检查Kylin环境配置..."
if [[ -f "$REPO_ROOT/env/kylin/.env" ]]; then
    echo "  ✓ Kylin环境配置存在"
    
    # 检查关键配置
    if grep -q "SSH_HOST=10.11.2.68" "$REPO_ROOT/env/kylin/.env"; then
        echo "  ✓ SSH_HOST配置正确: 10.11.2.68"
    else
        echo "  ⚠ SSH_HOST配置可能需要确认"
    fi
    
    if grep -q "SSH_USER=root" "$REPO_ROOT/env/kylin/.env"; then
        echo "  ✓ SSH_USER配置正确: root"
    else
        echo "  ⚠ SSH_USER配置可能需要确认"
    fi
else
    echo "  ✗ Kylin环境配置不存在"
    exit 1
fi

# 检查6: 部署脚本
echo ""
echo ">> [6/7] 检查部署脚本..."
if [[ -x "$REPO_ROOT/scripts/deploy-kylin.sh" ]]; then
    echo "  ✓ 部署脚本可执行"
else
    echo "  ⚠ 部署脚本不可执行，正在修复..."
    chmod +x "$REPO_ROOT/scripts/deploy-kylin.sh"
    echo "  ✓ 已添加执行权限"
fi

# 测试脚本语法
if bash -n "$REPO_ROOT/scripts/deploy-kylin.sh"; then
    echo "  ✓ 部署脚本语法正确"
else
    echo "  ✗ 部署脚本语法错误"
    exit 1
fi

# 检查7: 输出目录
echo ""
echo ">> [7/7] 检查输出目录..."
OFFLINE_DIR="$REPO_ROOT/../k-acp-offline-kylin"
if [[ ! -d "$OFFLINE_DIR" ]]; then
    echo "  创建输出目录: $OFFLINE_DIR"
    mkdir -p "$OFFLINE_DIR"
fi
echo "  ✓ 输出目录就绪: $OFFLINE_DIR"

# 总结
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    环境检查完成                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "✅ 所有检查通过，可以开始生成离线部署包！"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "下一步操作："
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1️⃣  生成离线部署包（预计20-30分钟）："
echo ""
echo "    cd $REPO_ROOT"
echo "    ./scripts/deploy-kylin.sh --prepare-offline"
echo ""
echo "2️⃣  查看操作手册："
echo ""
echo "    bash $REPO_ROOT/QUICK_START_KYLIN.sh"
echo ""
echo "3️⃣  查看详细文档："
echo ""
echo "    open $REPO_ROOT/DEPLOYMENT_KYLIN.md"
echo "    open $REPO_ROOT/DEPLOYMENT_SUMMARY.md"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "重要提示："
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "• 离线包生成过程会构建所有Docker镜像，首次运行较慢"
echo "• 生成的部署包大小约3-5GB，请确保有足够磁盘空间"
echo "• 部署包位置: $OFFLINE_DIR/"
echo "• 目标服务器需要Docker 20.10+ 和 Docker Compose v2+"
echo "• 客户环境无网络访问，所有依赖都已打包"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
