#!/usr/bin/env bash
# ============================================================
# k-acp kylin环境(10.11.2.68)部署脚本
#
# 用法:
#   scripts/deploy-kylin.sh                          # 全量部署
#   scripts/deploy-kylin.sh --status                 # 查看状态
#   scripts/deploy-kylin.sh --prepare-offline        # 准备离线部署包
#
# 功能:
#   - 部署到kylin环境服务器
#   - 生成完整的离线Docker镜像包
#   - 生成离线安装脚本
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 加载kylin环境配置
set -a
# shellcheck disable=SC1091
source "$REPO_ROOT/env/kylin/.env"
set +a

# 配置变量
REMOTE_DIR="/opt/k-acp"
OFFLINE_OUTPUT_DIR="$REPO_ROOT/../k-acp-offline-kylin"
COMPOSE_FILES="-f docker-compose-simple.yml"
COMPOSE_ENV="--env-file .env"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OFFLINE_PACKAGE_NAME="k-acp-offline-kylin-${TIMESTAMP}.tar.gz"

# 所有需要的镜像（基础镜像 + 应用镜像）
BASE_IMAGES=(
    "docker.m.daocloud.io/library/mysql:8.0"
    "docker.m.daocloud.io/library/redis:7-alpine"
    "docker.m.daocloud.io/pgvector/pgvector:pg16"
    "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21"
    "docker.m.daocloud.io/library/eclipse-temurin:21-jre"
    "docker.m.daocloud.io/library/node:22"
    "docker.m.daocloud.io/library/nginx:alpine"
)

APP_SERVICES=(console runtime proxy websocket frontend)

# ---------- SSH连接函数 ----------
remote_exec() {
    if [[ -n "${SSH_IDENTITY_FILE:-}" && -f "$SSH_IDENTITY_FILE" ]]; then
        ssh -i "$SSH_IDENTITY_FILE" \
            -p "${SSH_PORT}" \
            -o "StrictHostKeyChecking=no" \
            "${SSH_USER}@${SSH_HOST}" "$@"
    else
        sshpass -p "${SSH_PASSWORD}" \
            ssh -p "${SSH_PORT}" \
            -o "StrictHostKeyChecking=no" \
            "${SSH_USER}@${SSH_HOST}" "$@"
    fi
}

remote_copy() {
    local src="$1"
    local dst="$2"
    if [[ -n "${SSH_IDENTITY_FILE:-}" && -f "$SSH_IDENTITY_FILE" ]]; then
        scp -i "$SSH_IDENTITY_FILE" \
            -P "${SSH_PORT}" \
            -o "StrictHostKeyChecking=no" \
            -r "$src" "${SSH_USER}@${SSH_HOST}:$dst"
    else
        sshpass -p "${SSH_PASSWORD}" \
            scp -P "${SSH_PORT}" \
            -o "StrictHostKeyChecking=no" \
            -r "$src" "${SSH_USER}@${SSH_HOST}:$dst"
    fi
}

# ---------- 状态检查 ----------
do_status() {
    echo "=== Kylin环境状态检查 (${SSH_HOST}) ==="
    echo ""
    echo ">> Docker版本:"
    remote_exec "docker --version || echo 'Docker未安装'"
    echo ""
    echo ">> Docker Compose版本:"
    remote_exec "docker compose version || echo 'Docker Compose未安装'"
    echo ""
    echo ">> 运行中的容器:"
    remote_exec "docker ps --format '{{.Names}}\t{{.Status}}' | grep apboa || echo '无apboa容器运行'"
    echo ""
    echo ">> 磁盘空间:"
    remote_exec "df -h | grep -E '(Filesystem|/$)'"
    echo ""
    echo ">> 内存状态:"
    remote_exec "free -h"
    echo ""
}

# ---------- 准备离线部署包 ----------
prepare_offline() {
    echo "=========================================="
    echo "准备kylin环境离线部署包"
    echo "=========================================="
    echo ""
    
    # 创建输出目录
    mkdir -p "$OFFLINE_OUTPUT_DIR"
    WORK_DIR="$OFFLINE_OUTPUT_DIR/k-acp-offline-${TIMESTAMP}"
    mkdir -p "$WORK_DIR"
    
    echo ">> [1/6] 构建应用Docker镜像..."
    cd "$REPO_ROOT/docker"
    
    # 构建所有应用镜像
    docker compose -f docker-compose-simple.yml build
    
    echo ""
    echo ">> [2/6] 拉取基础镜像..."
    for img in "${BASE_IMAGES[@]}"; do
        echo "  - 拉取 $img"
        docker pull "$img" || echo "警告: 无法拉取 $img"
    done
    
    echo ""
    echo ">> [3/6] 导出Docker镜像..."
    mkdir -p "$WORK_DIR/images"
    
    # 导出基础镜像
    for img in "${BASE_IMAGES[@]}"; do
        img_name=$(echo "$img" | sed 's/[\/:]/_/g')
        echo "  - 导出 $img -> ${img_name}.tar"
        docker save -o "$WORK_DIR/images/${img_name}.tar" "$img" || echo "警告: 无法导出 $img"
    done
    
    # 导出应用镜像
    for svc in "${APP_SERVICES[@]}"; do
        img="apboa-${svc}:latest"
        echo "  - 导出 $img -> apboa-${svc}.tar"
        docker save -o "$WORK_DIR/images/apboa-${svc}.tar" "$img" || echo "警告: 无法导出 $img"
    done
    
    echo ""
    echo ">> [4/6] 复制项目文件..."
    
    # 复制必要的项目文件
    mkdir -p "$WORK_DIR/app"
    cp -r "$REPO_ROOT/docker" "$WORK_DIR/app/"
    cp -r "$REPO_ROOT/sql" "$WORK_DIR/app/"
    cp "$REPO_ROOT/README.md" "$WORK_DIR/app/"
    
    # 复制环境配置示例
    cp "$REPO_ROOT/docker/.env.simple" "$WORK_DIR/app/docker/.env.example"
    
    # 清理不需要的文件
    rm -rf "$WORK_DIR/app/docker/data" "$WORK_DIR/app/docker/logs" 2>/dev/null || true
    
    echo ""
    echo ">> [5/6] 生成离线安装脚本..."
    
    cat > "$WORK_DIR/install.sh" << 'INSTALL_EOF'
#!/usr/bin/env bash
# ============================================================
# K-ACP Kylin环境离线安装脚本
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/k-acp"

echo "=========================================="
echo "K-ACP Kylin环境离线安装"
echo "=========================================="
echo ""

# 检查Docker
if ! command -v docker &> /dev/null; then
    echo "错误: Docker未安装"
    echo "请先安装Docker: https://docs.docker.com/engine/install/"
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "错误: Docker Compose未安装或版本过低"
    echo "需要Docker Compose v2+"
    exit 1
fi

echo ">> [1/5] 加载Docker镜像..."
cd "$SCRIPT_DIR"
for tar_file in images/*.tar; do
    if [[ -f "$tar_file" ]]; then
        echo "  - 加载 $(basename "$tar_file")"
        docker load -i "$tar_file"
    fi
done

echo ""
echo ">> [2/5] 创建安装目录..."
sudo mkdir -p "$INSTALL_DIR"
sudo cp -r "$SCRIPT_DIR/app/"* "$INSTALL_DIR/"
sudo chown -R $(id -u):$(id -g) "$INSTALL_DIR"

echo ""
echo ">> [3/5] 配置环境变量..."
cd "$INSTALL_DIR/docker"

if [[ ! -f .env ]]; then
    if [[ -f .env.example ]]; then
        cp .env.example .env
        echo "  已创建 .env 文件，请根据实际情况修改配置"
    fi
fi

echo ""
echo ">> [4/5] 初始化数据目录..."
mkdir -p "$INSTALL_DIR/docker/data"
mkdir -p "$INSTALL_DIR/docker/logs"

echo ""
echo ">> [5/5] 启动服务..."
docker compose -f docker-compose-simple.yml up -d

echo ""
echo "=========================================="
echo "安装完成！"
echo "=========================================="
echo ""
echo "访问地址: http://$(hostname -I | awk '{print $1}')"
echo "默认账号: admin"
echo "默认密码: Admin@123.com"
echo ""
echo "管理命令:"
echo "  查看状态: cd $INSTALL_DIR/docker && docker compose -f docker-compose-simple.yml ps"
echo "  查看日志: cd $INSTALL_DIR/docker && docker compose -f docker-compose-simple.yml logs -f"
echo "  停止服务: cd $INSTALL_DIR/docker && docker compose -f docker-compose-simple.yml stop"
echo "  启动服务: cd $INSTALL_DIR/docker && docker compose -f docker-compose-simple.yml start"
echo "  重启服务: cd $INSTALL_DIR/docker && docker compose -f docker-compose-simple.yml restart"
echo ""
INSTALL_EOF

    chmod +x "$WORK_DIR/install.sh"
    
    # 生成README
    cat > "$WORK_DIR/README.md" << 'README_EOF'
# K-ACP Kylin环境离线部署包

## 系统要求

- 操作系统: Ubuntu 20.04+ / CentOS 7+ / RHEL 7+
- CPU: 8核+
- 内存: 16GB+
- 磁盘: 100GB+
- Docker: 20.10+
- Docker Compose: v2+

## 安装步骤

### 1. 传输部署包到目标服务器

```bash
scp k-acp-offline-kylin-*.tar.gz user@10.11.2.68:/tmp/
```

### 2. 解压部署包

```bash
cd /tmp
tar -xzf k-acp-offline-kylin-*.tar.gz
cd k-acp-offline-*
```

### 3. 执行安装脚本

```bash
sudo bash install.sh
```

安装脚本会自动完成以下操作：
- 加载所有Docker镜像
- 复制应用文件到 /opt/k-acp
- 创建默认配置文件
- 启动所有服务

### 4. 访问系统

安装完成后，通过浏览器访问：

```
http://10.11.2.68
```

默认账号密码：
- 用户名: admin
- 密码: Admin@123.com

## 配置说明

主要配置文件位于: `/opt/k-acp/docker/.env`

关键配置项：

```bash
# MySQL配置
MYSQL_HOST=apboa-mysql
MYSQL_PORT=3306
MYSQL_DATABASE=apboa_next
MYSQL_USER=root
MYSQL_PASSWORD=root  # 建议修改

# Redis配置
REDIS_HOST=apboa-redis
REDIS_PORT=6379
REDIS_PASSWORD=redis  # 建议修改

# 前端端口
FRONTEND_PORT=80
```

修改配置后重启服务：

```bash
cd /opt/k-acp/docker
docker compose -f docker-compose-simple.yml restart
```

## 管理命令

```bash
# 进入部署目录
cd /opt/k-acp/docker

# 查看服务状态
docker compose -f docker-compose-simple.yml ps

# 查看服务日志
docker compose -f docker-compose-simple.yml logs -f

# 查看特定服务日志
docker compose -f docker-compose-simple.yml logs -f apboa-console

# 停止服务
docker compose -f docker-compose-simple.yml stop

# 启动服务
docker compose -f docker-compose-simple.yml start

# 重启服务
docker compose -f docker-compose-simple.yml restart

# 重启特定服务
docker compose -f docker-compose-simple.yml restart apboa-console

# 完全停止并删除容器（数据保留）
docker compose -f docker-compose-simple.yml down

# 完全重建（保留数据）
docker compose -f docker-compose-simple.yml up -d --force-recreate
```

## 数据备份

重要数据目录：

- MySQL数据: `/opt/k-acp/docker/data/mysql_data`
- Redis数据: `/opt/k-acp/docker/data/redis_data`
- pgvector数据: `/opt/k-acp/docker/data/pgvector_data`
- 应用数据: `/opt/k-acp/docker/data/.apboa`
- 日志文件: `/opt/k-acp/docker/logs`

备份命令示例：

```bash
# 停止服务
cd /opt/k-acp/docker
docker compose -f docker-compose-simple.yml stop

# 备份数据
sudo tar -czf /backup/k-acp-data-$(date +%Y%m%d).tar.gz data/

# 启动服务
docker compose -f docker-compose-simple.yml start
```

## 故障排查

### 查看容器状态

```bash
docker ps -a | grep apboa
```

### 查看容器资源使用

```bash
docker stats
```

### 检查容器健康状态

```bash
docker inspect --format='{{.State.Health.Status}}' apboa-console
docker inspect --format='{{.State.Health.Status}}' apboa-runtime
```

### 进入容器排查

```bash
docker exec -it apboa-console bash
docker exec -it apboa-runtime bash
```

### 查看详细日志

```bash
# 查看最近100行日志
docker logs --tail 100 apboa-console

# 实时跟踪日志
docker logs -f apboa-console
```

## 卸载

```bash
cd /opt/k-acp/docker
docker compose -f docker-compose-simple.yml down -v
sudo rm -rf /opt/k-acp
```

注意：使用 `-v` 参数会删除所有数据，请先备份！

## 技术支持

如遇问题，请联系技术支持团队。
README_EOF

    echo ""
    echo ">> [6/6] 打包..."
    cd "$OFFLINE_OUTPUT_DIR"
    tar -czf "$OFFLINE_PACKAGE_NAME" "k-acp-offline-${TIMESTAMP}"
    
    # 计算包大小
    PACKAGE_SIZE=$(du -h "$OFFLINE_PACKAGE_NAME" | cut -f1)
    
    echo ""
    echo "=========================================="
    echo "离线部署包生成完成！"
    echo "=========================================="
    echo ""
    echo "部署包位置: $OFFLINE_OUTPUT_DIR/$OFFLINE_PACKAGE_NAME"
    echo "部署包大小: $PACKAGE_SIZE"
    echo "工作目录: $WORK_DIR"
    echo ""
    echo "传输到kylin服务器:"
    echo "  scp $OFFLINE_OUTPUT_DIR/$OFFLINE_PACKAGE_NAME ${SSH_USER}@${SSH_HOST}:/tmp/"
    echo ""
    echo "在kylin服务器上安装:"
    echo "  cd /tmp"
    echo "  tar -xzf $OFFLINE_PACKAGE_NAME"
    echo "  cd k-acp-offline-${TIMESTAMP}"
    echo "  sudo bash install.sh"
    echo ""
    
    # 列出包内容
    echo "部署包内容:"
    tar -tzf "$OFFLINE_PACKAGE_NAME" | head -20
    echo "  ... (总共 $(tar -tzf "$OFFLINE_PACKAGE_NAME" | wc -l) 个文件)"
    echo ""
}

# ---------- 在线部署到kylin环境 ----------
deploy_online() {
    echo "=========================================="
    echo "部署到Kylin环境 (${SSH_HOST})"
    echo "=========================================="
    echo ""
    
    # 检查SSH连接
    echo ">> 检查SSH连接..."
    if ! remote_exec "echo 'SSH连接成功'"; then
        echo "错误: 无法连接到 ${SSH_HOST}"
        exit 1
    fi
    
    echo ""
    echo ">> [1/5] 检查远程环境..."
    remote_exec "docker --version && docker compose version" || {
        echo "错误: 远程服务器缺少Docker或Docker Compose"
        exit 1
    }
    
    echo ""
    echo ">> [2/5] 创建远程目录..."
    remote_exec "sudo mkdir -p $REMOTE_DIR && sudo chown -R ${SSH_USER}:${SSH_USER} $REMOTE_DIR"
    
    echo ""
    echo ">> [3/5] 同步项目文件..."
    cd "$REPO_ROOT"
    
    # 使用rsync同步（如果可用）
    if command -v rsync &> /dev/null; then
        echo "  使用rsync同步..."
        rsync -avz --delete \
            --exclude '.git' --exclude '.idea' --exclude '.DS_Store' \
            --exclude 'node_modules' --exclude 'target' --exclude 'dist' \
            --exclude 'logs' --exclude '*.log' \
            --exclude 'docker/data' --exclude 'docker/logs' \
            --exclude '.env' --exclude 'env/' \
            --exclude 'graphify-out' \
            -e "ssh -p ${SSH_PORT}" \
            "$REPO_ROOT/" "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"
    else
        echo "  rsync不可用，使用tar同步..."
        tar -czf /tmp/k-acp-sync.tar.gz \
            --exclude='.git' --exclude='.idea' --exclude='.DS_Store' \
            --exclude='node_modules' --exclude='target' --exclude='dist' \
            --exclude='logs' --exclude='*.log' \
            --exclude='docker/data' --exclude='docker/logs' \
            --exclude='.env' --exclude='env/' \
            --exclude='graphify-out' \
            -C "$REPO_ROOT" .
        remote_copy "/tmp/k-acp-sync.tar.gz" "${REMOTE_DIR}/"
        remote_exec "cd ${REMOTE_DIR} && tar -xzf k-acp-sync.tar.gz && rm k-acp-sync.tar.gz"
        rm /tmp/k-acp-sync.tar.gz
    fi
    
    echo ""
    echo ">> [4/5] 配置环境..."
    remote_exec "cd ${REMOTE_DIR}/docker && cp .env.simple .env"
    
    echo ""
    echo ">> [5/5] 构建并启动服务..."
    remote_exec "cd ${REMOTE_DIR}/docker && docker compose -f docker-compose-simple.yml up -d --build"
    
    echo ""
    echo ">> 等待服务就绪..."
    sleep 10
    
    echo ""
    echo "=========================================="
    echo "部署完成！"
    echo "=========================================="
    echo ""
    echo "访问地址: http://${SSH_HOST}"
    echo "默认账号: admin"
    echo "默认密码: Admin@123.com"
    echo ""
    echo "检查服务状态:"
    echo "  ./scripts/deploy-kylin.sh --status"
    echo ""
}

# ---------- 帮助信息 ----------
show_help() {
    cat << 'HELP_EOF'
用法: scripts/deploy-kylin.sh [选项]

选项:
  --status              查看kylin环境服务器状态
  --prepare-offline     生成离线部署包（包含所有Docker镜像）
  --deploy-online       在线部署到kylin服务器（需要网络）
  -h, --help            显示帮助信息

示例:
  # 检查kylin服务器状态
  scripts/deploy-kylin.sh --status

  # 生成离线部署包
  scripts/deploy-kylin.sh --prepare-offline

  # 在线部署（从本地构建并推送）
  scripts/deploy-kylin.sh --deploy-online

离线部署流程:
  1. 在有网络的机器上运行: scripts/deploy-kylin.sh --prepare-offline
  2. 将生成的 .tar.gz 包传输到kylin服务器
  3. 在kylin服务器上解压并运行 install.sh

HELP_EOF
}

# ---------- 主逻辑 ----------
case "${1:-}" in
    --status)
        do_status
        ;;
    --prepare-offline)
        prepare_offline
        ;;
    --deploy-online)
        deploy_online
        ;;
    -h|--help)
        show_help
        ;;
    *)
        echo "用法: $0 [--status|--prepare-offline|--deploy-online|--help]"
        echo "运行 '$0 --help' 查看详细帮助"
        exit 1
        ;;
esac
