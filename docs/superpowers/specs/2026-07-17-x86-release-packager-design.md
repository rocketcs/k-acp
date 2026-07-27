# K-ACP x86_64 一键发布包脚本设计

## 目标

在 macOS ARM64 开发机上，通过一个可重复执行的 Bash 脚本，将当前 `k-acp-local` 环境打包成可部署到 Ubuntu 22.04 x86_64 服务器的完整发布包。服务器只需要 Docker、Docker Compose v2 和访问 Docker Hub 的网络，不依赖宿主机 Nginx。

脚本路径固定为 `docker/package-x86.sh`，默认生成仓库外的 `../releases/k-acp-x86_64-<时间戳>.tar.gz`，不得把发布物写入 Git 工作区。

## 使用接口

```bash
./docker/package-x86.sh [选项]
```

支持以下参数：

- `--host-ip <IP>`：目标服务器地址，默认 `192.168.8.81`。
- `--output-dir <目录>`：发布目录，默认仓库同级的 `releases/`。
- `--tag <标签>`：发布标签，默认当前时间 `YYYYMMDD-HHMMSS`。
- `--skip-build`：复用本地已有的五个 AMD64 应用镜像。
- `--dry-run`：只检查环境、容器、输入文件和将执行的步骤，不停止服务、不备份、不构建。
- `--keep-workdir`：成功后保留未压缩发布目录，默认只保留最终压缩包。
- `--help`：显示中文帮助。

重复使用同一个标签时，脚本拒绝覆盖已有发布物，避免误删或混淆历史包。

## 输入与前置检查

脚本必须从仓库根目录推导路径，不依赖调用者当前目录。输入包括：

- `docker/.env.kacp`
- `docker/docker-compose-simple.yml`
- `docker/docker-compose-kacp-local.yml`
- 当前运行的 `k-acp-local` 数据库及应用容器
- 当前 Git 提交和工作区状态，仅用于来源清单，不阻止打包

执行前检查：Bash、Docker daemon、Compose v2、Buildx、gzip、tar、sha256 工具、curl、可用磁盘空间以及八个预期容器。数据库容器必须处于运行状态；应用容器的初始运行状态需要记录，用于失败恢复。

## 打包流程

1. 创建仓库外临时目录，并注册 `EXIT`、`INT`、`TERM` 清理函数。
2. 记录 Console、Runtime、Proxy、WebSocket、Frontend 的初始运行状态。
3. 停止当时正在运行的应用容器，数据库容器保持运行。
4. 生成一致性逻辑备份：
   - MySQL：`mysqldump --single-transaction` 后 gzip。
   - pgvector：PostgreSQL custom format `pg_dump`。
   - Redis：执行 `SAVE` 后复制容器内 RDB。
   - `.apboa`：从 Console 容器归档 `/app/.apboa`；不可用时再从已确认的数据卷路径读取。
5. 备份文件落盘并通过 `gzip -t`、`pg_restore -l`、Redis RDB 文件头检查验证后，立即恢复原先运行的应用容器。
6. 使用现有 Compose 构建定义和临时覆盖配置构建五个 `linux/amd64` 镜像。镜像标签为 `k-acp-bundle/<service>:<tag>-amd64`。
7. 检查每个镜像的操作系统和架构必须为 `linux/amd64`，并对 Java/Nginx 入口执行轻量 smoke test。
8. 将五个应用镜像合并导出为 `images/k-acp-app-images.tar.gz`。MySQL、Redis、pgvector 基础镜像不导出，服务器安装时从公网拉取。
9. 生成发布文件：单一 `compose.yml`、脱敏来源清单、中文 README、安装/恢复/验证脚本和 `.env`。
10. `.env` 设置 `FRONTEND_PORT=23080`、`APBOA_HOST_IP=<host-ip>`、`PUBLIC_URL=http://<host-ip>:23080/web`，其余业务配置继承当前 `docker/.env.kacp`。
11. 生成 `checksums.sha256`，验证发布目录后压缩为最终 tar.gz，再验证压缩包完整性。

## 发布包结构

```text
k-acp-x86_64-<标签>/
├── compose.yml
├── .env
├── README.md
├── SOURCE_MANIFEST.md
├── checksums.sha256
├── images/k-acp-app-images.tar.gz
├── backups/mysql.sql.gz
├── backups/pgvector.dump
├── backups/redis.rdb
├── backups/apboa-data.tar.gz
├── scripts/install.sh
├── scripts/restore.sh
├── scripts/verify.sh
├── data/
└── logs/
```

最终包不能包含源码、Maven `target`、前端 `dist`、Graphify 输出、构建覆盖文件、`.DS_Store` 或基础镜像归档。

## 服务器恢复行为

`install.sh` 检查 x86_64、Docker 权限和内部校验和，然后调用 `restore.sh`：

1. 导入五个应用镜像。
2. 拉取三个公网基础镜像。
3. 准备 Redis RDB 与 `.apboa` 数据。
4. 启动并等待 MySQL、Redis、pgvector 健康。
5. 恢复 MySQL 和 pgvector 逻辑备份。
6. 启动所有应用服务。
7. 验证容器健康、数据库摘要、`/web` 到 `/web/` 的重定向以及 `PUBLIC_URL`。

恢复完成后写入 `.restore-complete`，拒绝重复覆盖已有数据库目录。

## 失败处理与安全性

- 清理函数必须恢复打包前处于运行状态的本地应用容器，即使构建、压缩或用户中断失败。
- 只删除本次脚本创建且位于输出根目录内的临时目录；不得清理模糊匹配路径。
- 不在日志、来源清单或终端输出中显示 `.env` 密码、JWT 等敏感值。
- `.env` 权限固定为 `0600`，安装脚本固定为 `0755`。
- 数据备份成功前不得进入镜像构建；任何备份验证失败立即退出。
- 不停止或修改 `apboa-next`、`apboa`、`data-query` 等其他 Docker 项目。

## 测试与验收

新增 `docker/tests/package-x86-test.sh`，使用临时目录和命令替身验证参数解析与安全边界，不依赖真实长时间构建。至少覆盖：

- `--help` 和 `--dry-run` 不产生发布物、不停止容器。
- 无效 IP、未知参数和重复标签会失败。
- 输出路径始终位于明确的输出根目录。
- 失败清理会恢复原先运行的应用容器。
- 生成的 Compose 可通过 `docker compose config --quiet`。
- 生成的包只有一个 `compose.yml`，且不包含禁入文件。

最终再执行一次真实 `--skip-build` 打包演练，验证数据备份、镜像导出、内部校验和、最终压缩包和本地服务恢复。完整 AMD64 重建作为可选的慢速验收执行。
