#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCKER_DIR="$ROOT/docker"
OUT="$ROOT/../k-acp-kylin-core-dm8-amd64"
TS="$(date +%Y%m%d-%H%M%S)"
PKG_DIR="$OUT/$TS"
mkdir -p "$PKG_DIR/images" "$PKG_DIR/meta"

# 只保留核心8容器 + DM8，端口按 Kylin 规范
cp "$DOCKER_DIR/docker-compose-simple.yml" "$PKG_DIR/"
cp "$DOCKER_DIR/docker-compose-kylin-core-dm8.yml" "$PKG_DIR/"
cp "$DOCKER_DIR/.env.kylin.core-dm8" "$PKG_DIR/.env"
cp "$ROOT/sql/db_init.sql" "$PKG_DIR/"

# 生成离线安装脚本
cat > "$PKG_DIR/install.sh" << 'EOS'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker load -i images/*.tar
docker-compose -f docker-compose-simple.yml -f docker-compose-kylin-core-dm8.yml --env-file .env up -d
EOS
chmod +x "$PKG_DIR/install.sh"

# 拉取基础镜像（amd64）
export DOCKER_DEFAULT_PLATFORM=linux/amd64
BASE_IMAGES=(
  "docker.m.daocloud.io/library/mysql:8.0"
  "docker.m.daocloud.io/library/redis:7-alpine"
  "docker.m.daocloud.io/pgvector/pgvector:pg16"
  "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21"
  "docker.m.daocloud.io/library/eclipse-temurin:21-jre"
  "docker.m.daocloud.io/library/node:22"
  "docker.m.daocloud.io/library/nginx:alpine"
  "changchun/dm8:aug"
)
for img in "${BASE_IMAGES[@]}"; do
  docker pull "$img"
  docker save -o "$PKG_DIR/images/$(echo "$img" | sed 's#[/:]#_#g').tar" "$img"
done

# 构建业务镜像（amd64）
cd "$DOCKER_DIR"
docker build --platform linux/amd64 -f console/Dockerfile -t k-acp-console:amd64 \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre .
docker build --platform linux/amd64 -f runtime/Dockerfile -t k-acp-runtime:amd64 \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre .
docker build --platform linux/amd64 -f proxy/Dockerfile -t k-acp-proxy:amd64 \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre .
docker build --platform linux/amd64 -f websocket/Dockerfile -t k-acp-websocket:amd64 \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre .
docker build --platform linux/amd64 -f frontend/Dockerfile -t k-acp-frontend:amd64 \
  --build-arg NODE_IMAGE=docker.m.daocloud.io/library/node:22 \
  --build-arg NGINX_IMAGE=docker.m.daocloud.io/library/nginx:alpine .

for img in k-acp-console:amd64 k-acp-runtime:amd64 k-acp-proxy:amd64 k-acp-websocket:amd64 k-acp-frontend:amd64; do
  docker save -o "$PKG_DIR/images/${img}.tar" "$img"
done

# 生成清单
cat > "$PKG_DIR/meta/manifest.txt" << EOF2
Kylin核心+DM8 amd64包
时间: $(date)
平台: linux/amd64
EOF2

echo "PACKAGE_DIR=$PKG_DIR"
echo "DONE"
