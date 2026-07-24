## graphify

本项目的知识图谱位于 `graphify-out/`，其中包含核心节点、社区结构和跨文件关系。

当用户输入 `/graphify` 时，必须先使用已安装的 Graphify 技能或相关说明，再执行其他操作。

规则：

- 处理代码库相关问题时，如果 `graphify-out/graph.json` 已存在，应先运行 `graphify query "<问题>"`。使用 `graphify path "<A>" "<B>"` 查询关系，使用 `graphify explain "<概念>"` 聚焦解释某个概念。这些命令会返回限定范围的子图，通常比读取 `GRAPH_REPORT.md` 或直接使用 grep 搜索更精简。
- 钩子或增量更新后，`graphify-out/` 中出现未提交的变更属于正常现象；不能因此跳过 Graphify。只有当任务涉及过期或错误的图谱输出，或者用户明确要求不使用 Graphify 时，才可跳过。
- 如果 `graphify-out/wiki/index.md` 存在，进行整体浏览时应优先使用它，而不是直接遍历源代码。
- 只有在进行整体架构审查，或者 `query`、`path`、`explain` 无法提供足够上下文时，才读取 `graphify-out/GRAPH_REPORT.md`。
- 修改代码后，运行 `graphify update .` 以保持图谱为最新状态。该操作仅更新 AST，不产生 API 费用。

## Codex 环境连接

Codex 在访问 SSH 主机或 MySQL 前，必须先选择目标环境，并通过对应的环境文件加载连接参数：

| 环境 | 配置文件 | 使用范围 |
| --- | --- | --- |
| 本地 | `env/local/.env` | 本机开发与本地 Docker MySQL |
| 测试 | `env/test/.env` | 测试环境 SSH 与 MySQL |
| 生产 | `env/prod/.env` | 生产环境 SSH 与 MySQL |

- 真实 `.env` 文件仅保存在操作者本机，已被 Git 忽略；请从同目录的 `.env.example` 初始化，并将其权限设为 `0600`。
- 需要使用环境变量时，必须使用 `./scripts/with-environment.sh <local|test|prod> --require <ssh|mysql> -- <命令>`，不要手动复制密码到命令、日志、代码或文档。
- SSH 使用 `SSH_HOST`、`SSH_PORT`、`SSH_USER`，以及 `SSH_IDENTITY_FILE` 或 `SSH_PASSWORD` 二选一；可选配置 `SSH_KNOWN_HOSTS_FILE`。连接时必须保持主机密钥校验，不得关闭校验或使用 `StrictHostKeyChecking=no`。
- MySQL 使用 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_SSL_MODE` 和可选的 `MYSQL_SSL_CA`。测试、生产环境如部署提供证书，应使用 `VERIFY_CA` 或 `VERIFY_IDENTITY`；未启用 TLS 的现有部署可明确设置为 `DISABLED`，不得虚构证书配置。
- 对测试或生产执行任何写入、迁移、删除或远程部署前，先明确报告目标环境与目标主机，并等待用户确认。
