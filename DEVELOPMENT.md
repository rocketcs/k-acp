# 开发环境指南（DEVELOPMENT.md）

> 本文档面向人类开发者和 AI 编码智能体。改代码前先读这里，避免误用错误的启动方式。
> 上次更新：2026-08-29（本地去 Docker 化改造完成当天）

## 一、两种运行模式（先选对模式再动手）

| 模式 | 用途 | 应用服务 | 中间件 |
|---|---|---|---|
| **本地开发模式（默认，推荐）** | 日常写代码，改完即生效 | 本地 JVM 直接跑 | Docker |
| Docker 全栈模式 | 演示/体验/预发验证 | Docker 容器 | Docker |

**日常开发不要用 Docker 跑应用服务**：Docker 模式每次改代码都要走「容器内 Maven 多模块构建 → 打镜像 → 重建容器」，非常慢；本地 JVM 秒级重启，前端 Vite 热更新。

> ⚠️ `kacp` 命令（docker/kacp）已于 2026-08-29 移除，不要再引用。
> 中间件统一用 `docker compose -p k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml <cmd>` 管理。

## 二、本地开发模式

### 架构与端口总表

| 组件 | 运行方式 | 端口 | 凭据 / 说明 |
|---|---|---|---|
| MySQL 8 | Docker（k-acp-mysql） | **23306** | root / root，库 `apboa_next` |
| Redis 7 | Docker（k-acp-redis） | **26379** | 密码 `redis`，database 7 |
| pgvector pg16 | Docker（k-acp-pgvector） | **25433** | postgres / postgres，库 `apboa_vector` |
| runner-console | 本地 JVM | **3060** | `ConsoleApplication`，Flyway 管理表结构 |
| runner-runtime | 本地 JVM | **3061** | `RuntimeApplication` |
| runner-proxy (shell-proxy) | 本地 JVM | 3062 | 本地开发可不启动（`shell-proxy.enabled: false`） |
| runner-websocket | 本地 JVM | **3064** | `WebsocketApplication` |
| runner-file | — | — | 仅分布式 Docker 部署需要，本地不启动 |
| 前端 Vite dev | 本地 | **3030** | 热更新；访问 http://localhost:3030 |

前端代理规则（Vite 内置）：`/api` → 127.0.0.1:3060，`/api/runtime/` → 3061，`/api/ws/` → 3064。

默认账号：`admin / Admin@123.com`。JWT secret 三端一致（`docker/.env.kacp` 中的值）。

### 核心配置：application-dev.yml（必须理解）

三个后端服务默认激活 `dev` profile，读取各自的 `src/main/resources/application-dev.yml`：

- `runner-console/src/main/resources/application-dev.yml`
- `runner-runtime/src/main/resources/application-dev.yml`
- `runner-websocket/src/main/resources/application-dev.yml`

这些文件是 `application-dev.sample.yml` 的本地化副本，**git 不跟踪、只存在于本机**，改代码时不要提交或删除它们。核心配置点：

1. **中间件连接**：必须指向上面 Docker 映射端口（23306/26379/25433），不是默认 3306/6379/5432
2. **runtime 的 RAG**：`rag.store: pgvector`，JDBC URL 用 25433
3. **runtime 的 PDF 中文字体**（macOS）：`document-export.pdf-font-path: /System/Library/Fonts/Supplemental/Songti.ttc`
4. **runtime 的 ES 健康检查已禁用**：`management.health.elasticsearch.enabled: false`（engine 模块引入了 ES client 但本地栈没有 ES，不禁用会误报 DOWN）

### ⚠️ 本机大坑：macOS 系统代理注入所有 JVM

这台机器开着 Clash 类代理（系统代理 127.0.0.1:7897），**macOS 会把系统代理注入所有 JVM 进程**（连 `java -version` 都带 `http.proxyHost`），而白名单里没有 `127.0.0.1`。后果：PostgreSQL JDBC 走 SOCKS 连本机 25433 报 `UnknownHostException: 127.0.0.1`（Netty/MySQL 驱动不受影响，所以只有 pgvector 炸）。

**启动任何后端服务都必须带这三个参数清空代理：**

```bash
java -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= -jar xxx.jar
# IDEA 里：Run Configuration → VM options 填入同样三个参数
```

根治方案（可选）：把 `localhost`、`127.0.0.1` 加入 macOS 系统代理的「跳过代理」列表。

### 本地启动步骤（改代码后的完整流程）

```bash
# 1. 构建后端（增量，约 1-2 分钟）
mvn -q -DskipTests -pl runner-console,runner-runtime,runner-websocket -am package

# 2. 按顺序启动（带代理清理参数！）
PROXY_FLAGS="-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost="
mkdir -p logs/local-dev
nohup java $PROXY_FLAGS -jar runner-console/target/runner-console-1.0-SNAPSHOT.jar   > logs/local-dev/console.log   2>&1 &
nohup java $PROXY_FLAGS -jar runner-runtime/target/runner-runtime-1.0-SNAPSHOT.jar   > logs/local-dev/runtime.log   2>&1 &
nohup java $PROXY_FLAGS -jar runner-websocket/target/runner-websocket-1.0-SNAPSHOT.jar > logs/local-dev/websocket.log 2>&1 &

# 3. 启动前端（热更新，改前端代码不用重启）
cd ui && pnpm dev        # 若 pnpm 11 报 ERR_PNPM_IGNORED_BUILDS，先 pnpm approve-builds
# 兜底：node node_modules/vite/bin/vite.js

# 4. 健康检查
for p in 3060 3061 3064; do curl -s --max-time 5 http://127.0.0.1:$p/actuator/health; done
```

只改了单个服务时，用 `mvn -q -DskipTests -pl runner-runtime -am package`（**必须带 `-am`**，否则依赖模块解析失败）然后只重启那一个。

日志位置：`logs/local-dev/{console,runtime,websocket,frontend}.log`

### 停止 / 回退

```bash
# 停本地服务（中间件容器保留！）
pkill -f 'runner-.*\.jar'      # 停后端
pkill -f 'vite'                # 停前端

# 回退 Docker 全栈模式（镜像一直保留着）
docker start k-acp-console k-acp-runtime k-acp-websocket k-acp-proxy k-acp-frontend
# 然后停掉本地 JVM，避免端口/数据冲突
```

## 三、测试环境更新

测试环境是 Docker 部署（`docker/.env.console` 指向测试中间件 IP）。更新流程：

```bash
# 前提：当前在 dev 分支，且已跟踪文件的工作区干净（脚本会校验并拒绝）
cd docker
bash start-console.sh update
# 脚本行为：git pull --ff-only origin dev → 用 .env.console 构建镜像 →
#           删旧应用容器（保留数据卷）→ 强制重建 apboa-console/apboa-websocket/apboa-frontend
```

执行节点（runtime/proxy）对应 `bash start-execute.sh build|rebuild`；发布包路线用 `docker/package-x86.sh` + `upgrade-x86.sh`（保留服务器数据，只换五个应用镜像）。

**安全规则（来自 AGENTS.md，必须遵守）：**

- 访问测试/生产 SSH 或 MySQL 前，必须用 `./scripts/with-environment.sh <local|test|prod> --require <ssh|mysql> -- <命令>` 加载 `env/<环境>/.env`，禁止手抄密码
- 对测试/生产执行任何写入、迁移、删除、远程部署前，先明确报告目标环境与主机，等用户确认

## 四、历史背景（为什么配置长这样）

- 2026-08-29 起本机日常开发改为「中间件 Docker + 应用本地 JVM」；当时停掉了 k-acp-console/runtime/websocket/proxy/frontend 五个容器（镜像保留）。中间件容器属于 compose 项目 `k-acp-local`，**任何时候都不要 down 掉中间件**，本地后端依赖它们。
- 移除了 `docker/kacp`、`docker/manage-kacp-local.sh`、`docker/tests/kacp-test.sh`、`docker/QUICK-HELP.md`；保留了 `docker-compose-kacp-local.yml`、`.env.kacp`（中间件归它们管）、`package-x86.sh`/`upgrade-x86.sh`（发布打包）。
- 可观测：Langfuse 是可选 overlay（`docker-compose-langfuse.yml`），本地 JVM 模式下 runtime 的 tracing 指向容器内地址时需要确认可达，不可达不影响核心功能。
