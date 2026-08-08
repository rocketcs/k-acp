# Apboa Docker 部署指南

## 架构概览

```
┌───────────────────────────────────────────────────────────────────────┐
│                          控制台节点 (Server 1)                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────────┐ │
│  │ runner-console   │  │ runner-websocket │  │ frontend (Nginx)      │ │
│  │ :3060            │  │ :3064            │  │ :80                   │ │
│  │ 管理控制台        │  │ WebSocket 推送    │  │  /api/runtime → runtime│ │
│  └─────────────────┘  └─────────────────┘  │  /api/ws      → ws     │ │
│                                             │  /api/agent   → console│ │
│                                             │  /api/*       → console│ │
│                                             │  /runtime/agui→ runtime│ │
│                                             └───────────────────────┘ │
└───────────────────────────────────────────────────────────────────────┘
                            │                        ▲
                  MySQL/Redis│                        │ 心跳上报
                            │                        │
┌───────────────────────────────────────────────────────────────────────┐
│                          执行节点 (Server 2+)                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │
│  │ runner-runtime   │  │ runner-proxy    │  │ runner-file     │       │
│  │ :3061            │─►│ :3062           │  │ (web disabled)  │       │
│  │ AI 运行时         │  │ Shell 执行代理   │  │ 文件同步服务     │       │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘       │
└───────────────────────────────────────────────────────────────────────┘
                            │
                  MySQL/Redis/pgvector
                            │
┌───────────────────────────────────────────────────────────────────────┐
│                          中间件服务器 (Server 3)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐                        │
│  │ MySQL 8  │  │ Redis 7  │  │ pgvector pg16│                        │
│  │ :3306    │  │ :6379    │  │ :5432        │                        │
│  └──────────┘  └──────────┘  └──────────────┘                        │
└───────────────────────────────────────────────────────────────────────┘
```

### Nginx 路由规则

前端 Nginx 容器通过 `envsubst` 在启动时替换 `nginx.conf` 中的地址变量，支持多节点动态路由：

| 路径 | 后端服务 | 说明 |
|------|---------|------|
| `/web/` | 静态文件 | 前端主应用 |
| `/api/runtime/` | runner-runtime | AI 运行时 API |
| `/api/ws/` | runner-websocket | WebSocket 推送 |
| `/api/agent/` | runner-console | 智能体管理 API |
| `/api/` | runner-console | 其他管理 API（兆底） |
| `/runtime/agui/` | runner-runtime | AG-UI 流式协议 |
| `/web/api/*` | 同上兼容路径 | 兼容旧版前端请求 |

## 快速开始（单机体验版）

适用于快速体验平台功能，不推荐生产使用。所有服务部署在同一台服务器上。

本地构建、重建、重启和日志命令详见 [`QUICK-HELP.md`](QUICK-HELP.md)，帮助命令为：

```bash
kacp help
```

```bash
cd docker

# 使用管理脚本（推荐，自动检测宿主机信息）
bash start-simple.sh build       # 构建并启动
bash start-simple.sh status      # 查看状态
bash start-simple.sh rebuild     # 完全重建
bash start-simple.sh stop        # 停止

# 或手动启动
cp .env.simple .env
docker compose -f docker-compose-simple.yml up -d --build
```

启动完成后访问 `http://localhost:80`，账号密码：admin / Admin@123.com

**包含的服务**（8 个容器）：
- MySQL 8.0 + Redis 7 + pgvector (pg16)
- runner-console（管理控制台）
- runner-runtime（AI 运行时）
- runner-proxy（Shell 执行代理）
- runner-websocket（WebSocket 推送服务）
- frontend（Nginx 前端）

**不包含** runner-file（单机模式无需文件同步）

## 本地 Langfuse 观测（可选）

Langfuse 是本地 AgentScope/OpenTelemetry 观测扩展，不属于 K-ACP 上游基础服务。项目中用独立 overlay 文件 `docker-compose-langfuse.yml` 接入，避免把第三方观测栈直接混进 `docker-compose-simple.yml` 或 `docker-compose-kacp-local.yml`。

集成启动方式：

```bash
cd docker

docker compose \
  --project-name k-acp-local \
  --env-file .env.kacp \
  -f docker-compose-simple.yml \
  -f docker-compose-kacp-local.yml \
  -f docker-compose-langfuse.yml \
  up -d --build
```

加载 `docker-compose-langfuse.yml` 后会额外启动：

- `langfuse-web`：Langfuse UI，默认 `http://localhost:3000`
- `langfuse-worker`：Langfuse 后台任务
- `langfuse-postgres` / `langfuse-clickhouse` / `langfuse-redis` / `langfuse-minio`：Langfuse 自己的数据依赖

overlay 会覆盖 `apboa-runtime` 的追踪地址为 `http://langfuse-web:3000/api/public/otel/v1/traces`，让 runtime 通过 Compose 内部网络写入 Langfuse。项目 ID、API key 和本地初始化账号写在 `.env.kacp` 的 Langfuse 段；不加载 overlay 时，现有 `AGENTSCOPE_TRACING_ENDPOINT` 仍可继续指向外部/独立启动的 Langfuse。

升级影响：

- 上游更新 `docker-compose-simple.yml`、`docker-compose-kacp-local.yml` 时，优先合并上游文件，继续把 `docker-compose-langfuse.yml` 作为最后一个 `-f` overlay 加载。
- 正常情况下，上游 compose 更新不会影响 Langfuse 数据卷；不要执行 `docker compose down -v`，否则会删除 Langfuse 的 Postgres、ClickHouse、Redis、MinIO 数据。
- 只有当上游改名 `apboa-runtime`、改名 `apboa_simple` 网络，或移除 `AGENTSCOPE_TRACING_*` / `LANGFUSE_*` 环境变量入口时，才需要同步调整 overlay。
- 如果机器上已经单独启动过 Langfuse 并占用 `3000`，先停止独立 Langfuse，或在 `.env.kacp` 中修改 `LANGFUSE_WEB_PORT`。

## 生产部署（多服务器）

推荐至少使用 3 台服务器，按角色分为：中间件服务器、控制台节点、执行节点。

### 步骤 1：部署中间件（Server 3）

```bash
cd docker

# 使用管理脚本（推荐）
bash start-middleware.sh build     # 拉取镜像并启动
bash start-middleware.sh status    # 查看状态

# 或手动启动
cp .env.middleware .env
# 编辑 .env 修改数据库密码等配置
docker compose -f docker-compose-middleware.yml up -d
```

### 步骤 2：部署控制台节点（Server 1）

```bash
cd docker

# 使用管理脚本（推荐）
bash start-console.sh build      # 构建并启动
bash start-console.sh update     # 测试环境：拉取 dev、构建并重建服务
bash start-console.sh status     # 查看状态

# 或手动启动
cp .env.console .env
# 编辑 .env：
#   MYSQL_HOST / REDIS_HOST → 中间件服务器 IP
#   RUNTIME_HOST → 执行节点服务器 IP
docker compose -f docker-compose-console.yml up -d --build
```

### 步骤 3：部署执行节点（Server 2+）

```bash
cd docker

# 使用管理脚本（推荐，自动注入宿主机 IP 和 hostname）
bash start-execute.sh build      # 构建并启动
bash start-execute.sh status     # 查看状态
bash start-execute.sh rebuild    # 完全重建
bash start-execute.sh stop       # 停止

# 或手动启动
cp .env.execute .env
# 编辑 .env：
#   MYSQL_HOST / REDIS_HOST / PG_HOST → 中间件服务器 IP
#   CONSOLE_HOST → 控制台服务器 IP
# APBOA_NODE_ID/APBOA_HOST_NAME/APBOA_HOST_IP 由启动脚本自动注入
docker compose -f docker-compose-execute.yml up -d --build
```

**水平扩展**：每个执行节点必须使用唯一的 `NODE_ID`（启动脚本默认使用宿主机 IP，天然唯一）。

### 多节点 HOST 配置要点

多节点部署时，各节点之间通过宿主机 IP 通信，需要在 `.env` 文件中正确配置以下 HOST 参数：

| 部署节点 | 需配置的 HOST 变量 | 指向 | 用途 |
|----------|------------------|------|------|
| 控制台节点 | `MYSQL_HOST` / `REDIS_HOST` | 中间件服务器 IP | 数据库和缓存访问 |
| 控制台节点 | `RUNTIME_HOST` | 执行节点服务器 IP | Nginx upstream 代理到 runtime |
| 控制台节点 | `WEBSOCKET_HOST` | 本机容器名 `apboa-websocket` | Nginx 代理 WebSocket（同节点） |
| 执行节点 | `MYSQL_HOST` / `REDIS_HOST` / `PG_HOST` | 中间件服务器 IP | 数据库、缓存、向量库访问 |
| 执行节点 | `CONSOLE_HOST` | 控制台服务器 IP | 心跳上报 |
| 执行节点 | `WEBSOCKET_HOST` | 控制台服务器 IP | runtime 连接 WebSocket 服务 |
| 执行节点 | `APBOA_NODE_ID` / `APBOA_HOST_IP` | 本机 IP（脚本自动注入） | 节点标识 |

## 环境变量参考

### 通用变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DOCKER_REGISTRY` | 兼容保留字段；当前 Compose 镜像统一固定为国内 DaoCloud 源 | `docker.m.daocloud.io/` |
| `JWT_SECRET` | JWT 签名密钥 | 内置默认值（生产务必修改） |
| `MYSQL_HOST` | MySQL 地址 | `apboa-mysql` |
| `MYSQL_PORT` | MySQL 端口 | `3306` |
| `MYSQL_DATABASE` | 数据库名 | `apboa_next` |
| `MYSQL_USER` | 数据库用户 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | `root` |
| `REDIS_HOST` | Redis 地址 | `apboa-redis` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | `redis` |
| `REDIS_DATABASE` | Redis 数据库编号 | `7` |
| `VECTOR_STORE_TYPE` | 向量存储类型（仅 runtime 使用） | `pgvector` |
| `PG_HOST` | pgvector 地址 | `apboa-pgvector` |
| `PG_PORT` | pgvector 端口 | `5432` |
| `PG_DATABASE` | pgvector 数据库名 | `apboa_vector` |
| `PG_USER` | pgvector 用户 | `postgres` |
| `PG_PASSWORD` | pgvector 密码 | `postgres` |
| `WEBSOCKET_ENABLED` | 是否启用 WebSocket 推送 | `true` |
| `WEBSOCKET_HOST` | WebSocket 服务地址 | `apboa-websocket` |
| `WEBSOCKET_PORT` | WebSocket 服务端口 | `3064` |
| `WORKSPACE_CAPACITY_MB` | 工作空间容量上限（MB） | `30` |

### 控制台节点变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CONSOLE_MEM_LIMIT` | console 容器内存限制 | `4g` |
| `CONSOLE_CPU_LIMIT` | console 容器 CPU 限制 | `4` |
| `CONSOLE_JAVA_HEAP_PERCENTAGE` | console JVM 堆占容器内存百分比 | `75.0` |
| `FRONTEND_PORT` | 前端 Nginx 端口 | `80` |
| `RUNTIME_HOST` | runtime 地址（Nginx upstream 代理） | `apboa-runtime` |
| `RUNTIME_PORT` | runtime 端口（Nginx upstream 代理） | `3061` |
| `VITE_APP_BASE_API` | 前端构建时 API 基础路径 | `/web` |
| `VITE_APP_CONTEXT_PATH` | 前端构建时应用上下文路径 | `/web` |
| `WEBSOCKET_MEM_LIMIT` | websocket 容器内存限制 | `1g` |
| `WEBSOCKET_CPU_LIMIT` | websocket 容器 CPU 限制 | `2` |
| `WEBSOCKET_JAVA_HEAP_PERCENTAGE` | websocket JVM 堆占容器内存百分比 | `50.0` |

### 执行节点变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CONSOLE_HOST` | 控制台服务器地址（心跳上报） | - |
| `RUNTIME_MEM_LIMIT` | runtime 容器内存限制 | `8g` |
| `RUNTIME_CPU_LIMIT` | runtime 容器 CPU 限制 | `8` |
| `RUNTIME_JAVA_HEAP_PERCENTAGE` | runtime JVM 堆占容器内存百分比 | `75.0` |
| `SHELLPROXY_MEM_LIMIT` | proxy 容器内存限制 | `1g` |
| `SHELLPROXY_CPU_LIMIT` | proxy 容器 CPU 限制 | `2` |
| `SHELLPROXY_PIDS_LIMIT` | proxy 容器最大进程数 | `200` |
| `SHELLPROXY_DEFAULT_TIMEOUT` | Shell 命令默认超时（秒） | `300` |
| `SHELLPROXY_MAX_TIMEOUT` | Shell 命令最大超时（秒） | `3600` |
| `SHELLPROXY_MAX_OUTPUT_SIZE` | Shell 命令最大输出字节数 | `52428800` |
| `SHELLPROXY_JAVA_HEAP_PERCENTAGE` | proxy JVM 堆占容器内存百分比 | `50.0` |
| `FILE_MEM_LIMIT` | file 容器内存限制 | `512m` |
| `FILE_CPU_LIMIT` | file 容器 CPU 限制 | `0.5` |
| `FILE_JAVA_HEAP_PERCENTAGE` | file JVM 堆占容器内存百分比 | `60.0` |

### 心跳标识变量（由启动脚本自动注入）

| 变量名 | 说明 | 自动获取方式 |
|--------|------|-------------|
| `APBOA_NODE_ID` | 节点唯一标识 | 宿主机 IP |
| `APBOA_HOST_NAME` | 节点主机名 | 宿主机 hostname |
| `APBOA_HOST_IP` | 节点 IP 地址 | 宿主机 IP |

## 文件结构

```
docker/
├── console/                    # runner-console Dockerfile
├── runtime/                    # runner-runtime Dockerfile
├── proxy/                      # runner-proxy Dockerfile
├── file/                       # runner-file Dockerfile
├── websocket/                  # runner-websocket Dockerfile
├── frontend/                   # 前端 Dockerfile
├── nginx/                      # Nginx 配置
├── maven/                      # Maven 私有仓库配置
├── npm/                        # NPM 私有仓库配置
├── docker-compose-simple.yml   # 单机体验版
├── docker-compose-kacp-local.yml # 本地隔离端口和运行时覆盖配置
├── docker-compose-langfuse.yml # 本地 Langfuse 观测 overlay（可选）
├── docker-compose-console.yml  # 生产-控制台节点
├── docker-compose-execute.yml  # 生产-执行节点
├── docker-compose-middleware.yml # 生产-中间件
├── start-simple.sh             # 单机管理脚本（build/rebuild/start/stop/restart/down/status）
├── start-console.sh            # 控制台节点管理脚本
├── start-execute.sh            # 执行节点管理脚本
├── start-middleware.sh         # 中间件管理脚本
├── package-x86.sh              # 当前环境一键生成 Ubuntu x86_64 发布包
├── tests/package-x86-test.sh   # 打包脚本自动化测试
├── .env.simple                 # 单机环境变量
├── .env.console                # 控制台环境变量
├── .env.execute                # 执行节点环境变量
└── .env.middleware             # 中间件环境变量
```

## 生成 x86_64 服务器发布包

默认目标地址为 `192.168.8.81`，发布物生成在仓库同级的 `releases/`，不会写入 Git 仓库：

```bash
./docker/package-x86.sh
```

常用方式：

```bash
# 先检查参数、容器和输出路径，不修改任何状态
./docker/package-x86.sh --dry-run

# 指定目标服务器和发布标签
./docker/package-x86.sh --host-ip 192.168.8.81 --tag 20260717

# 已经存在同标签的 linux/amd64 镜像时跳过构建
./docker/package-x86.sh --tag 20260717 --skip-build

# 手工指定 Buildx builder；默认会优先使用 apboa-next-dns
KACP_BUILDX_BUILDER=apboa-next-dns ./docker/package-x86.sh --tag 20260717

# 保留压缩前的发布目录，便于检查
./docker/package-x86.sh --keep-workdir
```

脚本会短暂停止当前 `k-acp-local` 的五个应用容器，备份 MySQL、pgvector、Redis 和 `.apboa` 后立即恢复；数据库容器不会停止。构建或打包失败时也会自动恢复原本运行的应用服务。脚本不会操作 `apboa-next` 等其他 Docker 项目。

运行自动化测试：

```bash
bash docker/tests/package-x86-test.sh
```

### 保留服务器数据升级

服务器已经安装过 K-ACP，并且需要保留现有数据库和 `.env` 时，不要再次运行发布包中的 `install.sh`。在旧部署目录解压，进入新发布目录后直接执行包内升级脚本：

```bash
cd /opt/k-acp
tar -xzf /tmp/k-acp-x86_64-202607173.tar.gz
cd k-acp-x86_64-202607173
sudo ./upgrade-k-acp-x86.sh
```

脚本以当前目录作为新版本目录，自动以上一级目录作为旧部署目录。仓库脚本仍兼容显式参数：

```bash
./docker/upgrade-x86.sh \
  --package /tmp/k-acp-x86_64-202607173.tar.gz \
  --install-dir /opt/k-acp
```

升级脚本只执行以下操作：校验发布包、导入五个应用镜像、备份旧 `compose.yml`、替换五个应用镜像标签并重建应用容器。它不会覆盖 `.env`、`data/`、MySQL、Redis、pgvector 或 `.apboa`。如果新应用启动失败，脚本会自动恢复旧 Compose 和旧应用镜像。

运行升级脚本测试：

```bash
bash docker/tests/upgrade-x86-test.sh
```
