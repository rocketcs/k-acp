# K-ACP 本地容器快速管理 Help

## 一条帮助命令

在仓库根目录执行：

```bash
kacp help
```

脚本默认读取 `docker/.env.kacp`，并且只操作 Compose 项目 `k-acp-local`。

## 命令参数

| 命令 | 参数 | 作用 | 是否构建镜像 | 是否操作数据容器 |
|---|---|---|---:|---:|
| `help` | 无 | 显示帮助 | 否 | 否 |
| `build` | `--no-cache` 可选 | 构建 console、runtime、proxy、websocket、frontend 五个应用镜像 | 是 | 否 |
| `rebuild` | `--no-cache` 可选 | 构建镜像并强制重建五个应用容器 | 是 | 否 |
| `restart` | 服务名，可多个 | 重启已有应用容器，不重新构建 | 否 | 否 |
| `start` | 无 | 启动完整本地栈，包括中间件和应用 | 镜像缺失时由 Compose 决定 | 是（启动） |
| `stop` | 无 | 停止完整本地栈 | 否 | 是（停止） |
| `status` | 无 | 查看所有服务状态 | 否 | 否 |
| `logs` | 服务名，可多个 | 跟踪应用日志，默认保留最近 200 行 | 否 | 否 |
| `release` | `package-x86.sh` 参数 | 生成 Ubuntu x86_64 发布包 | 是 | 会备份数据 |

## 应用服务参数

`restart` 和 `logs` 使用下面的短名称：

| 短名称 | Compose 服务 | 容器 |
|---|---|---|
| `console` | `apboa-console` | `k-acp-console` |
| `runtime` | `apboa-runtime` | `k-acp-runtime` |
| `proxy` | `apboa-proxy` | `k-acp-proxy` |
| `websocket` | `apboa-websocket` | `k-acp-websocket` |
| `frontend` | `apboa-frontend` | `k-acp-frontend` |

不传服务名时，`restart` 和 `logs` 默认作用于全部五个应用服务。

## 常用命令

```bash
# 查看帮助
kacp help

# 快速构建五个应用镜像
kacp build

# 不使用构建缓存重新构建并重建应用容器
kacp rebuild --no-cache

# 重启全部应用容器
kacp restart

# 只重启前端
kacp restart frontend

# 查看状态
kacp status

# 查看 runtime 日志
kacp logs runtime

# 查看前端和 WebSocket 日志
kacp logs frontend websocket
```

## 环境参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `KACP_ENV_FILE` | `docker/.env.kacp` | 指定 Compose 环境文件 |
| `DOCKER_REGISTRY` | `docker.m.daocloud.io/` | 脚本强制使用国内 DaoCloud 源；Compose 文件中的基础镜像也已固定为该源 |

示例：

```bash
KACP_ENV_FILE=docker/.env.simple kacp status
```

## x86_64 发布参数

执行：

```bash
kacp release [参数]
```

发布参数来自 `docker/package-x86.sh`：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--host-ip <IPv4>` | `192.168.8.81` | 发布后访问地址使用的服务器 IP |
| `--output-dir <目录>` | 仓库同级 `releases/` | 发布包输出目录 |
| `--tag <标签>` | 当前时间戳 | 发布包和镜像标签 |
| `--skip-build` | 关闭 | 复用已有 AMD64 应用镜像 |
| `--dry-run` | 关闭 | 只检查输入，不停止容器、不生成发布物 |
| `--keep-workdir` | 关闭 | 保留未压缩的发布目录 |
| `-h, --help` | - | 显示发布脚本帮助 |

示例：

```bash
# 只检查，不停止容器
kacp release --dry-run

# 生成指定服务器地址和标签的发布包
kacp release \
  --host-ip 192.168.8.81 \
  --tag 20260804
```

## 国内源与安全边界

- Docker 基础镜像和中间件镜像固定使用 `docker.m.daocloud.io`。
- Maven 使用阿里云公共仓库，npm/Corepack 使用 `registry.npmmirror.com`。
- Runtime/Proxy 的 Debian 软件源使用阿里云镜像，不再访问 NodeSource。
- `build`、`rebuild`、`restart`、`logs` 不执行 `down -v`，不会删除 MySQL、Redis、pgvector 或 `.apboa` 数据。
- `release` 会为一致性备份短暂停止五个应用容器；数据库容器不停止，失败时会尝试恢复原运行状态。

国内源可以降低跨境访问导致的失败概率，但无法保证服务器本地网络、DNS 或镜像站在任何时刻绝对稳定。
