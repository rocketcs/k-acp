---
name: update-runtime
description: "Use when the user asks to update/rebuild/restart/redeploy the frontend (ui) and/or backend runtime (runner-console/runner-runtime/runner-websocket) of k-acp — in any environment (本地/local, 测试/test, 麒麟/kylin). Single-command deployment: local uses JVM+Vite (never containers); all other environments use Docker containers via the existing deploy scripts. Do not ask the user which steps to run — just run the one matching command and verify health."
---

# /update-runtime

k-acp 前后端 runtime 更新，一次命令完成，不需要多轮思考。

## 铁律（先记这三条）

1. **本地(local)绝不用容器**：应用走本地 JVM(3060/3061/3064) + Vite(3030)；中间件容器(mysql/redis/pgvector)由 `k-acp-local` 管理，任何时候不得 down。
2. **其他环境(test/kylin)一律用 Docker 容器**：走仓库内既有部署脚本，不要发明新流程。
3. **对 test/kylin 的任何写入/部署前，先报告目标环境与目标主机，等用户确认**（AGENTS.md 安全规则）；本地操作无需确认。

## 一键命令（唯一入口）

```bash
scripts/update-runtime.sh <环境> [选项]
```

### 本地 local（无容器）

```bash
scripts/update-runtime.sh local              # 构建全部后端 + 重启 JVM + 确保 vite 运行（Maven: mvn -q -DskipTests -pl runner-console,runner-runtime,runner-websocket -am package）
scripts/update-runtime.sh local -s runtime   # 只更新 runtime（可选 console|runtime|websocket|frontend，逗号分隔多个）
scripts/update-runtime.sh local -s frontend  # 前端：vite 已在运行则 HMR 生效，未运行则启动
scripts/update-runtime.sh local --status     # 只读查看健康状态
scripts/update-runtime.sh local --stop       # 停止本地 JVM 与 vite（中间件容器保留）
```

本地要点（脚本已内置）：
- JVM 启动必带 `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= -Dhttp.proxyPort= -Dhttps.proxyPort= -DsocksProxyPort=`（本机 macOS 系统代理会注入所有 JVM，导致 pgvector 连不上 25433）。
- 日志在 `logs/local-dev/{runner-console,runner-runtime,runner-websocket,frontend}.log`。

### 测试环境 test（192.168.107.137，Docker 容器）

```bash
scripts/update-runtime.sh test                     # 全量：rsync 同步代码 → 服务器构建 → 重建 5 个应用容器 → 健康检查（10-15 分钟）
scripts/update-runtime.sh test -s frontend         # 只更新前端
scripts/update-runtime.sh test -s console,runtime  # 只更新指定后端
scripts/update-runtime.sh test --skip-sync         # 复用服务器现有代码重建镜像
scripts/update-runtime.sh test --status            # 容器状态 + 健康检查 + 上次部署版本戳
scripts/update-runtime.sh test --rollback          # 回退到上一次部署的镜像
```

### kylin 环境（10.11.2.68，Docker 容器）

```bash
scripts/update-runtime.sh kylin            # 在线部署：rsync 同步 → 服务器 docker compose 构建启动（需服务器可访问镜像源）
scripts/update-runtime.sh kylin --status   # 查看服务器状态
scripts/update-runtime.sh kylin --prepare-offline  # 生成离线部署包（客户网络隔离时用，另见 docs/ 相关离线文档）
```

## 执行流程（照做即可，不要展开思考）

1. **先问/确认目标环境**：用户没说环境时按 local 处理；用户提到"测试/137/服务器/test" → test；"麒麟/kylin/10.11.2.68" → kylin。
2. **远程环境先报告并确认**：test/kylin 执行 `--status` 是只读可直接跑；任何写入（local 以外）跑之前先报告目标环境与主机并等用户确认。
3. **只改前端** → 带 `-s frontend`；**只改后端** → 带 `-s console,runtime` 等；**都改** → 不带 -s。
4. 运行完脚本自带健康检查；如有失败，本地看 `logs/local-dev/*.log`，远程看容器日志：
   - test: `scripts/remote-test.sh 'docker logs --tail 50 k-acp-console'`
   - kylin: 用 `scripts/with-environment.sh kylin --require ssh -- ...` 或脚本输出的提示查看。

## 底层说明（排查时用，正常更新不需要）

| 环境 | 应用运行方式 | 端口 | 底层脚本 |
|---|---|---|---|
| local | 本地 JVM + Vite | console:3060,runtime:3061,ws:3064,前端:3030 | `scripts/update-runtime-local.sh` |
| test | Docker 容器 | console:23060,runtime:23061,前端:23080 | `scripts/deploy-test.sh`、`scripts/remote-test.sh` |
| kylin | Docker 容器 | 前端:23080 | `scripts/deploy-kylin.sh` |

- 连接/密码一律从 `env/<环境>/.env` 加载（脚本内部处理），禁止手抄密码到任何命令或文档。
- 服务器有 SSH 连接频率限制（测试环境约 5 分钟自愈），脚本已用 ControlMaster 复用连接；不要手动高频重连。

## 回滚

- test：`scripts/update-runtime.sh test --rollback`
- local：改代码后重新 `mvn` 构建重启即可；或 `git` 回退代码后 `scripts/update-runtime.sh local`