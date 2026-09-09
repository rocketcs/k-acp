#!/usr/bin/env bash
# ============================================================
# K-ACP 完整离线部署包生成脚本
# 
# 特点：
# 1. 包含所有Docker镜像（基础镜像 + 应用镜像）
# 2. 完全离线安装，客户环境无需网络
# 3. 自动化安装脚本
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OFFLINE_OUTPUT_DIR="$REPO_ROOT/../k-acp-offline-kylin"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
WORK_DIR="$OFFLINE_OUTPUT_DIR/k-acp-offline-${TIMESTAMP}"
PACKAGE_NAME="k-acp-offline-kylin-${TIMESTAMP}.tar.gz"

# 所有需要的基础镜像
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

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║      K-ACP 完整离线部署包生成（客户环境无网络）                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "目标: 生成包含所有Docker镜像的完整离线包"
echo "输出: $OFFLINE_OUTPUT_DIR/"
echo ""

# ============================================================
# 第1步：检查Docker环境
# ============================================================
echo ">> [1/7] 检查Docker环境..."
if ! docker info >/dev/null 2>&1; then
    echo "✗ Docker未运行，请启动Docker"
    exit 1
fi
echo "  ✓ Docker运行正常"

# ============================================================
# 第2步：拉取所有基础镜像
# ============================================================
echo ""
echo ">> [2/7] 拉取基础镜像（需要网络）..."
failed_images=()
for img in "${BASE_IMAGES[@]}"; do
    echo "  拉取: $img"
    if docker pull "$img"; then
        echo "    ✓ 成功"
    else
        echo "    ✗ 失败"
        failed_images+=("$img")
    fi
done

if [ ${#failed_images[@]} -gt 0 ]; then
    echo ""
    echo "✗ 以下镜像拉取失败:"
    for img in "${failed_images[@]}"; do
        echo "  - $img"
    done
    echo ""
    echo "请检查网络连接后重试"
    exit 1
fi
echo "  ✓ 所有基础镜像拉取成功"

# ============================================================
# 第3步：构建应用镜像
# ============================================================
echo ""
echo ">> [3/7] 构建应用Docker镜像（约15-20分钟）..."
cd "$REPO_ROOT/docker"
if docker compose -f docker-compose-simple.yml build; then
    echo "  ✓ 应用镜像构建成功"
else
    echo "  ✗ 应用镜像构建失败"
    exit 1
fi

# ============================================================
# 第4步：验证所有镜像存在
# ============================================================
echo ""
echo ">> [4/7] 验证镜像完整性..."

# 验证基础镜像
echo "  检查基础镜像..."
for img in "${BASE_IMAGES[@]}"; do
    if docker image inspect "$img" >/dev/null 2>&1; then
        echo "    ✓ $img"
    else
        echo "    ✗ $img 不存在"
        exit 1
    fi
done

# 验证应用镜像
echo "  检查应用镜像..."
for svc in "${APP_SERVICES[@]}"; do
    # 尝试多种可能的镜像名
    found=false
    for prefix in "" "docker-" "k-acp-local-"; do
        img="${prefix}apboa-${svc}:latest"
        if docker image inspect "$img" >/dev/null 2>&1; then
            echo "    ✓ $img"
            found=true
            break
        fi
    done
    
    if ! $found; then
        echo "    ✗ 未找到 $svc 的镜像"
        echo "    尝试的名称: apboa-${svc}:latest, docker-apboa-${svc}:latest, k-acp-local-apboa-${svc}:latest"
        exit 1
    fi
done
echo "  ✓ 所有镜像验证通过"

# ============================================================
# 第5步：导出所有Docker镜像
# ============================================================
echo ""
echo ">> [5/7] 导出Docker镜像到tar文件..."
mkdir -p "$WORK_DIR/images"

# 导出基础镜像
echo "  导出基础镜像..."
for img in "${BASE_IMAGES[@]}"; do
    img_name=$(echo "$img" | sed 's/[\/:]/_/g')
    tar_file="$WORK_DIR/images/${img_name}.tar"
    echo "    导出: $img"
    docker save -o "$tar_file" "$img"
    size=$(du -h "$tar_file" | cut -f1)
    echo "      ✓ 大小: $size"
done

# 导出应用镜像
echo "  导出应用镜像..."
for svc in "${APP_SERVICES[@]}"; do
    # 找到实际存在的镜像名
    actual_img=""
    for prefix in "" "docker-" "k-acp-local-"; do
        img="${prefix}apboa-${svc}:latest"
        if docker image inspect "$img" >/dev/null 2>&1; then
            actual_img="$img"
            break
        fi
    done
    
    tar_file="$WORK_DIR/images/apboa-${svc}.tar"
    echo "    导出: $actual_img"
    docker save -o "$tar_file" "$actual_img"
    size=$(du -h "$tar_file" | cut -f1)
    echo "      ✓ 大小: $size"
done

echo "  ✓ 所有镜像导出完成"

# ============================================================
# 第6步：复制应用文件
# ============================================================
echo ""
echo ">> [6/7] 复制应用文件..."
mkdir -p "$WORK_DIR/app"

# 复制Docker配置
cp -r "$REPO_ROOT/docker" "$WORK_DIR/app/"
# 复制SQL脚本
cp -r "$REPO_ROOT/sql" "$WORK_DIR/app/"
# 复制README
cp "$REPO_ROOT/README.md" "$WORK_DIR/app/"

# 创建环境变量示例文件
cp "$REPO_ROOT/docker/.env.simple" "$WORK_DIR/app/docker/.env.example"

# 清理不需要的文件
rm -rf "$WORK_DIR/app/docker/data" "$WORK_DIR/app/docker/logs" 2>/dev/null || true

echo "  ✓ 应用文件复制完成"

# ============================================================
# 第7步：生成安装脚本和文档
# ============================================================
echo ""
echo ">> [7/7] 生成安装脚本和文档..."

# 生成install.sh
cat > "$WORK_DIR/install.sh" << 'INSTALL_EOF'
#!/usr/bin/env bash
# ============================================================
# K-ACP Kylin环境离线安装脚本
# 完全离线，无需网络访问
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/k-acp"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║         K-ACP Kylin环境离线安装                               ║"
echo "║         完全离线，无需网络访问                                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 检查Docker
echo ">> [1/6] 检查Docker环境..."
if ! command -v docker &> /dev/null; then
    echo "✗ Docker未安装"
    echo "请先安装Docker: https://docs.docker.com/engine/install/"
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "✗ Docker Compose未安装或版本过低"
    echo "需要Docker Compose v2+"
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "✗ Docker服务未运行"
    echo "请启动Docker服务"
    exit 1
fi
echo "  ✓ Docker环境正常"

# 加载Docker镜像
echo ""
echo ">> [2/6] 加载Docker镜像（5-10分钟）..."
cd "$SCRIPT_DIR"
total_images=$(ls images/*.tar | wc -l)
current=0
for tar_file in images/*.tar; do
    current=$((current + 1))
    filename=$(basename "$tar_file")
    echo "  [$current/$total_images] 加载: $filename"
    if docker load -i "$tar_file"; then
        echo "    ✓ 成功"
    else
        echo "    ✗ 失败"
        exit 1
    fi
done
echo "  ✓ 所有镜像加载完成"

# 创建安装目录
echo ""
echo ">> [3/6] 创建安装目录..."
sudo mkdir -p "$INSTALL_DIR"
sudo cp -r "$SCRIPT_DIR/app/"* "$INSTALL_DIR/"
sudo chown -R $(id -u):$(id -g) "$INSTALL_DIR"
echo "  ✓ 安装目录: $INSTALL_DIR"

# 配置环境变量
echo ""
echo ">> [4/6] 配置环境..."
cd "$INSTALL_DIR/docker"
if [[ ! -f .env ]]; then
    if [[ -f .env.example ]]; then
        cp .env.example .env
        echo "  ✓ 已创建 .env 文件"
    fi
fi

# 初始化数据目录
echo ""
echo ">> [5/6] 初始化数据目录..."
mkdir -p "$INSTALL_DIR/docker/data/mysql_data"
mkdir -p "$INSTALL_DIR/docker/data/redis_data"
mkdir -p "$INSTALL_DIR/docker/data/pgvector_data"
mkdir -p "$INSTALL_DIR/docker/data/.apboa"
mkdir -p "$INSTALL_DIR/docker/logs"
echo "  ✓ 数据目录已创建"

# 启动服务
echo ""
echo ">> [6/6] 启动所有服务（3-5分钟）..."
cd "$INSTALL_DIR/docker"
docker compose -f docker-compose-simple.yml up -d

echo ""
echo ">> 等待服务就绪..."
sleep 10

# 检查服务状态
echo ""
echo ">> 检查服务状态..."
docker compose -f docker-compose-simple.yml ps

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    安装完成！                                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
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

## 📦 包说明

这是一个**完全离线**的Docker部署包，包含所有必需的Docker镜像和应用文件。
客户环境**无需网络访问**，即可完成完整部署。

## 📋 包含内容

### Docker镜像（12个）
- MySQL 8.0
- Redis 7-alpine
- pgvector (PostgreSQL 16)
- Maven 3.9-eclipse-temurin-21
- Eclipse Temurin 21-jre
- Node 22
- Nginx alpine
- apboa-console (管理控制台)
- apboa-runtime (AI运行时)
- apboa-proxy (Shell执行代理)
- apboa-websocket (WebSocket推送)
- apboa-frontend (前端界面)

### 应用文件
- Docker Compose配置
- 数据库初始化脚本
- Nginx配置
- 环境变量模板

## 🚀 快速安装

### 系统要求
- CPU: 8核+
- 内存: 16GB+
- 磁盘: 100GB+
- 操作系统: Ubuntu 20.04+ / CentOS 7+ / RHEL 7+
- Docker: 20.10+
- Docker Compose: v2+

### 安装步骤

1. **解压部署包**
```bash
cd /tmp
tar -xzf k-acp-offline-kylin-*.tar.gz
cd k-acp-offline-*
```

2. **执行安装**
```bash
sudo bash install.sh
```

安装过程约15-20分钟，包括：
- [1/6] 检查Docker环境
- [2/6] 加载Docker镜像（5-10分钟）
- [3/6] 创建安装目录
- [4/6] 配置环境
- [5/6] 初始化数据目录
- [6/6] 启动所有服务（3-5分钟）

3. **访问系统**
```
http://10.11.2.68
用户名: admin
密码: Admin@123.com
```

## 🔧 管理命令

所有命令在 `/opt/k-acp/docker` 目录执行：

```bash
cd /opt/k-acp/docker

# 查看状态
docker compose -f docker-compose-simple.yml ps

# 查看日志
docker compose -f docker-compose-simple.yml logs -f

# 停止服务
docker compose -f docker-compose-simple.yml stop

# 启动服务
docker compose -f docker-compose-simple.yml start

# 重启服务
docker compose -f docker-compose-simple.yml restart

# 完全重建（保留数据）
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d
```

## 📊 服务架构

8个容器服务：
- apboa-mysql (3306) - MySQL数据库
- apboa-redis (6379) - Redis缓存
- apboa-pgvector (5432) - 向量数据库
- apboa-console (3060) - 管理控制台
- apboa-runtime (3061) - AI运行时
- apboa-proxy (3062) - Shell执行代理
- apboa-websocket (3064) - WebSocket推送
- apboa-frontend (80) - Nginx前端（对外访问）

## 🆘 故障排查

### 查看容器状态
```bash
docker ps -a
```

### 查看容器日志
```bash
docker logs --tail 100 apboa-console
docker logs --tail 100 apboa-runtime
```

### 检查端口占用
```bash
netstat -tulnp | grep -E '(80|3306|6379|5432)'
```

### 检查资源使用
```bash
docker stats
free -h
df -h
```

## 📝 配置说明

主配置文件: `/opt/k-acp/docker/.env`

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

# 资源限制
CONSOLE_MEM_LIMIT=2g
RUNTIME_MEM_LIMIT=4g
```

修改配置后重启服务生效。

## 💾 数据备份

重要数据目录：
- `/opt/k-acp/docker/data/mysql_data` - MySQL数据
- `/opt/k-acp/docker/data/redis_data` - Redis数据
- `/opt/k-acp/docker/data/pgvector_data` - 向量库数据
- `/opt/k-acp/docker/data/.apboa` - 应用数据
- `/opt/k-acp/docker/logs` - 日志文件

备份命令：
```bash
cd /opt/k-acp/docker
docker compose -f docker-compose-simple.yml stop
sudo tar -czf /backup/k-acp-data-$(date +%Y%m%d).tar.gz data/
docker compose -f docker-compose-simple.yml start
```

## ⚠️ 重要提示

1. **完全离线** - 本部署包包含所有镜像，无需网络访问
2. **数据持久化** - 数据存储在data目录，重建容器不会丢失
3. **默认密码** - 生产环境务必修改默认密码
4. **资源配置** - 根据服务器配置调整资源限制
5. **防火墙** - 确保80端口可访问

## 📞 技术支持

如遇问题，请检查：
1. Docker服务是否运行
2. 端口是否被占用
3. 磁盘空间是否充足
4. 容器日志中的错误信息

---

**部署包版本**: K-ACP 1.0-SNAPSHOT  
**生成时间**: 2024-09-02  
**适用环境**: Kylin (10.11.2.68)
README_EOF

echo "  ✓ 安装脚本和文档生成完成"

# ============================================================
# 打包
# ============================================================
echo ""
echo ">> 打包离线部署包..."
cd "$OFFLINE_OUTPUT_DIR"
tar -czf "$PACKAGE_NAME" "k-acp-offline-${TIMESTAMP}"

# 计算大小和校验和
PACKAGE_PATH="$OFFLINE_OUTPUT_DIR/$PACKAGE_NAME"
PACKAGE_SIZE=$(du -h "$PACKAGE_PATH" | cut -f1)
PACKAGE_MD5=$(md5 -q "$PACKAGE_PATH" 2>/dev/null || md5sum "$PACKAGE_PATH" | cut -d' ' -f1)

echo "  ✓ 打包完成"

# ============================================================
# 生成清单文件
# ============================================================
cat > "$OFFLINE_OUTPUT_DIR/package-info.txt" << INFO_EOF
K-ACP Kylin环境离线部署包信息
═══════════════════════════════════════════════════════════════

生成时间: $(date '+%Y-%m-%d %H:%M:%S')
包文件名: $PACKAGE_NAME
包大小: $PACKAGE_SIZE
MD5校验: $PACKAGE_MD5

目标环境:
  服务器: 10.11.2.68
  用户: root
  部署目录: /opt/k-acp
  访问地址: http://10.11.2.68

包含镜像 (12个):
$(for img in "${BASE_IMAGES[@]}"; do echo "  • $img"; done)
  • apboa-console:latest
  • apboa-runtime:latest
  • apboa-proxy:latest
  • apboa-websocket:latest
  • apboa-frontend:latest

传输方式:
  方式1: U盘拷贝（推荐，客户环境无网络）
  方式2: scp传输（如有临时网络）

安装命令:
  cd /tmp
  tar -xzf $PACKAGE_NAME
  cd k-acp-offline-${TIMESTAMP}
  sudo bash install.sh

═══════════════════════════════════════════════════════════════
INFO_EOF

# ============================================================
# 完成
# ============================================================
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║              离线部署包生成完成！                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "部署包信息:"
echo "  位置: $PACKAGE_PATH"
echo "  大小: $PACKAGE_SIZE"
echo "  MD5:  $PACKAGE_MD5"
echo ""
echo "包含内容:"
echo "  • 12个Docker镜像（完全离线）"
echo "  • 完整应用代码和配置"
echo "  • 自动化安装脚本"
echo "  • 部署文档"
echo ""
echo "传输到Kylin服务器:"
echo "  方式1（U盘）:"
echo "    cp $PACKAGE_PATH /Volumes/YOUR_USB/"
echo ""
echo "  方式2（网络）:"
echo "    scp $PACKAGE_PATH root@10.11.2.68:/tmp/"
echo ""
echo "在Kylin服务器上安装:"
echo "  cd /tmp"
echo "  tar -xzf $PACKAGE_NAME"
echo "  cd k-acp-offline-${TIMESTAMP}"
echo "  sudo bash install.sh"
echo ""
echo "工作目录（可选删除）:"
echo "  $WORK_DIR"
echo ""
cat "$OFFLINE_OUTPUT_DIR/package-info.txt"
echo ""
