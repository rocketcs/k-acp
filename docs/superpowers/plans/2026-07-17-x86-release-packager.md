# K-ACP x86_64 一键发布脚本实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一个可重复运行的 Bash 脚本，把当前 `k-acp-local` 环境构建为 Ubuntu 22.04 x86_64 完整发布包。

**Architecture:** `docker/package-x86.sh` 负责参数解析、环境检查、容器状态保护、数据逻辑备份、AMD64 构建、发布文件生成和最终校验。`docker/tests/package-x86-test.sh` 使用纯 Bash 临时目录验证 CLI、安全边界和 dry-run，不依赖第三方测试框架；真实 Docker 流程通过一次 `--skip-build` 演练验收。

**Tech Stack:** Bash 3.2+、Docker、Docker Compose v2、Buildx、tar、gzip、sha256sum/shasum、curl。

## Global Constraints

- 默认目标 IP 为 `192.168.8.81`，默认前端端口为 `23080`。
- 应用镜像必须是 `linux/amd64`，目标服务器为 Ubuntu 22.04 x86_64。
- 发布目录必须位于仓库外，不能提交源码、构建目录、Graphify 输出或基础镜像。
- MySQL、pgvector、Redis 和 `.apboa` 必须来自当前 `k-acp-local` 环境。
- 任何失败或中断都必须恢复打包前处于运行状态的五个应用服务。
- 不得停止或修改其他 Docker Compose 项目。
- 所有用户可见说明使用中文，敏感配置不得输出到终端或来源清单。

---

### Task 1: CLI、参数验证和 dry-run 安全边界

**Files:**
- Create: `docker/tests/package-x86-test.sh`
- Create: `docker/package-x86.sh`

**Interfaces:**
- Consumes: `docker/.env.kacp` 和仓库根目录。
- Produces: `package-x86.sh --help|--dry-run|--host-ip|--output-dir|--tag|--skip-build|--keep-workdir`。

- [ ] **Step 1: 编写失败测试**

测试脚本以临时目录作为输出根，断言：帮助文本包含所有参数；无效 IP、未知参数、包含 `/` 的标签失败；dry-run 输出解析后的 IP、标签和目录，且不创建发布目录。

```bash
assert_success "$PACKAGER" --help
assert_contains "$OUTPUT" "--host-ip"
assert_failure "$PACKAGER" --host-ip 999.1.1.1 --dry-run
assert_failure "$PACKAGER" --tag '../bad' --dry-run
assert_success "$PACKAGER" --host-ip 10.0.0.8 --tag test-001 --output-dir "$TMP/out" --dry-run
assert_not_exists "$TMP/out/k-acp-x86_64-test-001"
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `bash docker/tests/package-x86-test.sh`

Expected: FAIL，原因是 `docker/package-x86.sh` 尚不存在。

- [ ] **Step 3: 实现最小 CLI**

脚本使用 `set -Eeuo pipefail`，定义 `usage`、`die`、`validate_ipv4`、`parse_args`、`resolve_paths` 和 `dry_run_summary`。所有路径通过脚本自身位置推导；拒绝已存在的工作目录或压缩包。

```bash
HOST_IP="192.168.8.81"
OUTPUT_ROOT="$(cd "$REPO_ROOT/.." && pwd)/releases"
TAG="$(date +%Y%m%d-%H%M%S)"
DRY_RUN=false
SKIP_BUILD=false
KEEP_WORKDIR=false
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `bash docker/tests/package-x86-test.sh`

Expected: 全部 CLI 测试通过。

### Task 2: 数据备份、AMD64 构建和失败恢复

**Files:**
- Modify: `docker/tests/package-x86-test.sh`
- Modify: `docker/package-x86.sh`

**Interfaces:**
- Consumes: 容器 `k-acp-mysql`、`k-acp-pgvector`、`k-acp-redis`、`k-acp-console`、`k-acp-runtime`、`k-acp-proxy`、`k-acp-websocket`、`k-acp-frontend`。
- Produces: 四个验证过的数据归档和五个 `k-acp-bundle/<service>:<tag>-amd64` 镜像。

- [ ] **Step 1: 扩充失败测试**

通过 PATH 中的 Docker 命令替身记录调用，设置 `KACP_PACKAGE_TEST_FAIL_AFTER_STOP=1`，断言脚本停止应用后失败时只重新启动最初处于运行状态的应用服务，不调用任何其他项目容器。

```bash
assert_failure env PATH="$FAKE_BIN:$PATH" KACP_PACKAGE_TEST_FAIL_AFTER_STOP=1 \
  "$PACKAGER" --tag restore-test --output-dir "$TMP/out"
assert_file_contains "$DOCKER_LOG" "stop k-acp-console k-acp-runtime"
assert_file_contains "$DOCKER_LOG" "start k-acp-console k-acp-runtime"
assert_file_not_contains "$DOCKER_LOG" "apboa-next"
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `bash docker/tests/package-x86-test.sh`

Expected: 新增失败恢复测试 FAIL。

- [ ] **Step 3: 实现备份和构建流水线**

新增 `record_app_state`、`restore_app_state`、`preflight`、`stop_running_apps`、`backup_mysql`、`backup_pgvector`、`backup_redis`、`backup_apboa_data`、`validate_backups`、`build_amd64_images`、`validate_images`、`export_images`。注册以下清理入口：

```bash
cleanup() {
  local exit_code=$?
  restore_app_state || true
  remove_build_override || true
  if (( exit_code != 0 )); then
    remove_owned_workdir || true
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM
```

MySQL 使用 `mysqldump --single-transaction --routines --events --triggers`；pgvector 使用 `pg_dump -Fc`；Redis 使用 `SAVE` 和 `docker cp`；`.apboa` 使用临时 Alpine 容器从 Compose 绑定目录生成 tar.gz。构建通过现有三个 Compose 文件与临时覆盖文件执行，只指定五个应用服务。

- [ ] **Step 4: 运行测试并确认通过**

Run: `bash docker/tests/package-x86-test.sh`

Expected: CLI 与失败恢复测试全部通过。

### Task 3: 生成服务器发布物并完成真实验收

**Files:**
- Modify: `docker/tests/package-x86-test.sh`
- Modify: `docker/package-x86.sh`
- Modify: `docker/README.md`

**Interfaces:**
- Consumes: Task 2 的备份和 AMD64 镜像。
- Produces: `k-acp-x86_64-<tag>.tar.gz` 及其 SHA-256。

- [ ] **Step 1: 扩充发布结构失败测试**

在测试模式生成小型发布目录，断言只有一个 `compose.yml`，`.env` 包含目标地址，恢复脚本可通过 `bash -n`，且发布清单不包含 `.DS_Store`、`target`、`dist`、`graphify-out` 或临时构建覆盖文件。

```bash
assert_file_contains "$RELEASE_DIR/.env" "PUBLIC_URL=http://10.0.0.8:23080/web"
assert_equals 1 "$(find "$RELEASE_DIR" -name compose.yml | wc -l | tr -d ' ')"
bash -n "$RELEASE_DIR/scripts/"*.sh
assert_archive_excludes "$ARCHIVE" '.DS_Store|/target/|/dist/|graphify-out|build-amd64'
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `bash docker/tests/package-x86-test.sh`

Expected: 发布结构测试 FAIL。

- [ ] **Step 3: 实现发布文件生成**

新增 `write_compose`、`write_restore_scripts`、`write_readme`、`write_source_manifest`、`write_checksums`、`validate_release_tree`、`create_archive`。模板通过带单引号的 heredoc 写入，并对标签、IP 和端口使用受控占位符替换。内部校验使用 Linux 兼容的双空格 SHA-256 格式，macOS 优先使用 `sha256sum`，缺失时回退 `shasum -a 256`。

- [ ] **Step 4: 运行自动化测试**

Run: `bash docker/tests/package-x86-test.sh`

Expected: 所有测试通过，退出码 0。

- [ ] **Step 5: 执行静态检查与 dry-run**

Run:

```bash
bash -n docker/package-x86.sh docker/tests/package-x86-test.sh
./docker/package-x86.sh --host-ip 192.168.8.81 --tag verification --dry-run
```

Expected: 语法检查通过；dry-run 显示八个容器、五个 AMD64 镜像和仓库外输出路径，不产生发布物。

- [ ] **Step 6: 执行真实快速打包演练**

Run:

```bash
./docker/package-x86.sh --host-ip 192.168.8.81 --tag verification --skip-build --keep-workdir
```

Expected: 生成发布目录和 tar.gz；内部校验、Compose 配置、备份检查及压缩包检查全部通过；本地原有应用服务恢复。

- [ ] **Step 7: 更新 Graphify 并提交**

Run:

```bash
graphify update .
git add docker/package-x86.sh docker/tests/package-x86-test.sh docker/README.md docs/superpowers/plans/2026-07-17-x86-release-packager.md
git commit -m "feat: add reusable x86 release packager"
```

Expected: 只提交本功能文件，不包含用户已有的其他未提交修改。
