# Default Tender Test Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely promote the locally verified `default-tender` configuration to the test environment, with an independent Wenbiao key pool and reproducible evidence for search, rotation, and source-link rendering.

**Architecture:** Treat the test environment as an independent Docker Compose stack: no local API key, profile, chat key, database row ID, Docker container name, host MySQL client, database port, or backup may be reused by assumption. All operations enter the remote test host through SSH and execute through its Compose services and container network. First capture a read-only baseline and database backup from the test MySQL container, then apply an idempotent, test-specific configuration package keyed by stable names/codes, restart only the test runtime/console containers, and run controlled end-to-end cases from containers in that stack.

**Tech Stack:** K-ACP MySQL configuration tables, test SSH/Docker, `scripts/with-environment.sh`, custom Java dynamic tools, Skill packages, Workflow configuration, Markdown rendering.

## Global Constraints

- Target is **test only**; do not write to production.
- All test-host commands must use `./scripts/with-environment.sh test --require ssh -- ...`; MySQL is invoked only with `docker compose exec -T <test-mysql-service> mysql ...` on the remote test host.
- The rollout must not install or use a host MySQL client, publish a database port, copy a MySQL dump through a public endpoint, or connect directly to `MYSQL_HOST` from the operator machine.
- Do not copy local, production, user, chat, or Wenbiao API secrets into test. Test has its own key pool and runtime profile.
- Do not use local numeric IDs such as `2079122200000000401` as a test migration key; resolve test rows by stable `agent_code`, `name`, `tool_id`, `file_path`, and workflow name/version.
- Do not deploy until the operator explicitly confirms the target host, the backup location, and the planned write set.
- A source URL may be presented as original only when resolver output provides `display_url` and `link_type` of `SOURCE` or `SOURCE_UNVERIFIED`. Otherwise a nonempty `aggregate_url` is displayed only as “知了标讯详情（聚合页）”.

---

## Required rollout materials

| Material | Required content | Owner/source | Acceptance rule |
|---|---|---|---|
| Release source | Linear commits `896bcb0`, `c499e3b`, `ecb78cc`, `65999b3` and their parent baseline | Git branch `codex/tender-followup-curator` | Test deployment worktree resolves these commits and `git diff --check` passes. |
| Test environment configuration | `env/test/.env` with valid SSH values plus non-secret `TEST_COMPOSE_DIR`, `TEST_COMPOSE_PROJECT`, `TEST_MYSQL_SERVICE`, `TEST_RUNTIME_SERVICE`, and `TEST_CONSOLE_SERVICE` values | Test-environment operator | `with-environment.sh test --require ssh` validates and the Compose stack can be listed through SSH without revealing values. |
| Test database backup | Timestamped SQL dump of the exact configuration rows listed below plus a checksum/row-count manifest | Release operator | Backup is stored outside the deployment worktree and restoration command is recorded before mutation. |
| Test runtime access | Confirmed Compose services and writable mounted `wenbiao_agent.json` profile path inside the test runtime container | Test platform owner | Runtime and console health checks succeed from the container network before and after restart. |
| Test Wenbiao pool | At least two test-only usable Wenbiao keys in `wenbiao_api_key_pool`: one `ACTIVE`, one `STANDBY`; no local key import | Authorized test key owner | Counts meet the minimum; no key value appears in logs, SQL output, commits, or plan artifacts. |
| Test chat access | A valid test user session or test-only agent chat key for `default-tender` | Test platform owner | Can open the test `default-tender` chat and run a query. |
| Test evidence record | Query text, time window, tool invocation proof, rendered Markdown, and key-pool state counts before/after | Release operator | Captured without API-key values or contact/private fields. |

## Required test data

| Dataset | Minimum shape | Purpose |
|---|---|---|
| Configuration baseline | One enabled `default-tender`; `tender-search`; `commercial-tender-followup-curator`; high-recall Skill/workflow; `http_request`; `wenbiao_agent_key_pool`; `resolve_tender_source_urls_v2`; high-recall Tool | Proves every prerequisite exists and detects incompatible test drift before writes. |
| Key-pool baseline | `ACTIVE >= 1`, `STANDBY >= 1`, fingerprints and state counts only | Supports search and a controlled rotation test without exposing keys. |
| Source-link fixture A | A public, safe explicit source URL accepted by the resolver | Confirms `display_url`/`SOURCE` becomes a Markdown title link. |
| Source-link fixture B | A real search result with no original URL but a known `aggregate_url` | Confirms the UI displays “未解析到原始公告链接 · 知了标讯详情（聚合页）” with a clickable aggregate link. |
| Tender query | `查询消防设备最近一个月的招标公告，展示3条，并给出来源链接` | Exercises date handling, key-profile sync, search, resolver, rendering, and the curator card. |
| Rotation scenario | Two test-only usable keys and a restore snapshot of pool states | Allows invoking the existing rotation path once with a controlled quota-status input, followed by a successful query on the new `ACTIVE` key. |

## Task 1: Read-only test preflight and release manifest

**Files:**
- Create: `docs/operations/commercial-tender-test-rollout/preflight-test.sh`
- Create: `docs/operations/commercial-tender-test-rollout/test-config-manifest.json`
- Test: `docs/operations/commercial-tender-test-rollout/preflight-test.sh`

**Consumes:** `env/test/.env`, the four release commits, and the test MySQL/SSH endpoints.

**Produces:** A redacted readiness report with test host identity, schema compatibility, required-row counts, runtime health, and a release commit manifest.

- [ ] **Step 1: Validate the target without writing to it**

Run the following from the release worktree. The remote shell expands only the already validated non-secret Compose variables and all database work runs inside the test MySQL container:

```bash
./scripts/with-environment.sh test --require ssh -- \
  ssh -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" \
  "cd \"$TEST_COMPOSE_DIR\" && \
   docker compose --project-name \"$TEST_COMPOSE_PROJECT\" ps && \
   docker compose --project-name \"$TEST_COMPOSE_PROJECT\" exec -T \"$TEST_MYSQL_SERVICE\" \
     mysql --batch --raw --skip-column-names -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" \
     -e 'SELECT DATABASE(), CURRENT_USER(), @@version;'"
```

Expected: the database and SSH host are both the declared test target; no local Docker daemon, local container name, or direct database connection is used.

- [ ] **Step 2: Capture only required configuration identities and counts**

Run a read-only query that returns counts for: `agent_definition.agent_code='default-tender'`; the three named Skill packages; tool IDs `http_request`, `wenbiao_agent_key_pool`, `resolve_tender_source_urls_v2`, and `execute_tender_high_recall_v1`; the published tender workflow; and `ACTIVE`/`STANDBY` counts in `wenbiao_api_key_pool`.

Expected: every required object exists exactly once where uniqueness is required; `ACTIVE >= 1` and `STANDBY >= 1`.

- [ ] **Step 3: Stop if the test baseline is incompatible**

Do not apply any SQL if a required row is missing, duplicated, disabled, uses an incompatible schema, or the runtime profile mount is absent. Record each failed predicate in the readiness report and remediate the test baseline first.

## Task 2: Build a test-specific, idempotent configuration package

**Files:**
- Create: `docs/operations/commercial-tender-test-rollout/apply-test.sh`
- Create: `docs/operations/commercial-tender-test-rollout/verify-test.sh`
- Create: `docs/operations/commercial-tender-test-rollout/rollback-test.sh`
- Modify: `docs/operations/commercial-tender-followup-curator/system-prompt.md`
- Modify: `docs/operations/commercial-tender-followup-curator/skill/SKILL.md`

**Consumes:** The source prompts/Skills in the release worktree and the verified test baseline.

**Produces:** A package that updates the test configuration by stable identity, verifies it, and restores the captured test snapshot.

- [ ] **Step 1: Make the package environment-specific**

`apply-test.sh` must load only `env/test/.env`, use the test SSH command, change to `TEST_COMPOSE_DIR`, and execute every database command with `docker compose --project-name "$TEST_COMPOSE_PROJECT" exec -T "$TEST_MYSQL_SERVICE" mysql ...`. It must abort if `KACP_ENV` is not `test`. It must never call a local Docker daemon, a host MySQL client, or a direct MySQL endpoint.

- [ ] **Step 2: Resolve configuration rows by stable identity**

Use these selectors rather than local IDs:

```sql
agent_definition.agent_code = 'default-tender'
skill_package.name IN ('tender-search', 'commercial-tender-followup-curator', 'tender-high-recall-search')
tool_config.tool_id IN ('http_request', 'wenbiao_agent_key_pool', 'resolve_tender_source_urls_v2', 'execute_tender_high_recall_v1')
skill_file.file_path IN ('SKILL.md', 'references/question-patterns.md')
```

The workflow selector must use its stable workflow name plus the currently published version. The script must first assert that every selector matches exactly one intended row.

- [ ] **Step 3: Include the URL rendering contract**

Apply the `tender-search` and `default-tender` prompt rules that require one resolver call for every displayed batch of 1–20 projects. The resulting rendered behavior is:

```markdown
[项目名称](display_url)                         <!-- original source present -->
未解析到原始公告链接 · [知了标讯详情（聚合页）](aggregate_url) <!-- source absent -->
```

- [ ] **Step 4: Snapshot before mutation and implement rollback**

Before the transaction, run `mysqldump` inside `TEST_MYSQL_SERVICE` for the selected rows from `agent_definition`, `system_prompt_template`, `skill_package`, `skill_file`, `skill_tools`, `tool_config`, `workflow`, `workflow_version`, and current pool state counts. Stream the encrypted/permission-restricted backup over the SSH session to the approved release archive. `rollback-test.sh` streams it back over SSH into the same MySQL container and then restarts the same test services.

- [ ] **Step 5: Make verification read-only and fail closed**

`verify-test.sh` must assert: Skill/tool bindings; source-link prompt markers; `default-tender` routing; one active and one standby key; nonempty test runtime profile file; and healthy test console/runtime services. A failed assertion exits nonzero and blocks the end-to-end stage.

## Task 3: Controlled test deployment

**Files:**
- Create: `docs/operations/commercial-tender-test-rollout/deployment-record.md`

**Consumes:** A green preflight, review of the SQL write set, backup location, and explicit operator confirmation.

**Produces:** Updated test configuration and a reproducible deployment record.

- [ ] **Step 1: Present the exact target and write set for confirmation**

State the test SSH host, test database name, test runtime/console services to restart, backup path, and exact rows selected by the stable identities. Wait for explicit approval before running `apply-test.sh`.

- [ ] **Step 2: Apply in one transaction and restart only test services**

Run the test apply script. It must commit only after all row-count assertions pass. Restart only `TEST_RUNTIME_SERVICE` and `TEST_CONSOLE_SERVICE` with `docker compose --project-name "$TEST_COMPOSE_PROJECT" restart`; do not restart any local or production service.

- [ ] **Step 3: Run `verify-test.sh` immediately**

Expected: every configuration predicate is `OK`; if not, run the scoped rollback script and preserve the failed evidence.

## Task 4: End-to-end acceptance and key-pool restoration

**Files:**
- Modify: `docs/operations/commercial-tender-test-rollout/deployment-record.md`

**Consumes:** Test chat access, test-only key pool, and the fixture/query data above.

**Produces:** Acceptance evidence and a restored, usable test key-pool state.

- [ ] **Step 1: Validate source-link rendering with explicit-source fixture A**

Invoke `resolve_tender_source_urls_v2` from the test runtime container with one `record_key`, title, and public source URL. Expected result: nonempty `display_url`, `link_type` of `SOURCE` or `SOURCE_UNVERIFIED`, and a Markdown title link in the rendered result.

- [ ] **Step 2: Validate the aggregate fallback with fixture B**

Run the tender query through the test console/runtime container network. For a result lacking `display_url` but carrying `aggregate_url`, expected rendered text is exactly the source-state label plus a Markdown link labeled `知了标讯详情（聚合页）`. It must not call that URL an original announcement.

- [ ] **Step 3: Validate the full search route**

Run:

```text
查询消防设备最近一个月的招标公告，展示3条，并给出来源链接
```

Expected: the execution record contains `wenbiao_agent_key_pool` status, `http_request`, and `resolve_tender_source_urls_v2`; the final table contains either original title links or the aggregate fallback for every displayed item; and exactly one curator UIP card appears.

- [ ] **Step 4: Validate one controlled rotation and restore the pool**

With the two-key test snapshot approved, invoke the existing rotation path once using the permitted quota status. Expected: the previous active key is retired according to the tool contract, one standby becomes active, the runtime profile is synchronized, and the next search succeeds. Restore the original test-pool arrangement only from the pre-test snapshot if the test key owner requires it.

- [ ] **Step 5: Record and sign off**

Record timestamps, commit SHA, test host/database identities, configuration checks, pool counts before/after, resolver status distribution, rendered Markdown samples, and rollback result. Do not record API keys, auth headers, chat tokens, or contact information extracted from announcements.

## Rollback decision matrix

| Condition | Action |
|---|---|
| Preflight fails | Do not write; repair missing/duplicate/disabled test baseline. |
| SQL assertion fails before commit | Roll back transaction; no service restart. |
| Runtime/console fails health check | Restore configuration snapshot; restart only test services; stop rollout. |
| Search works but source rendering fails | Roll back prompt/Skill/workflow rows only; retain independent key-pool data unless it was changed by the controlled rotation test. |
| Controlled rotation fails or leaves no active key | Restore the approved pool snapshot and re-sync the runtime profile; do not continue E2E testing. |

## Self-review

- Scope coverage: includes configuration source, independent test secrets, key pool, runtime profile, backup, deployment confirmation, source-link paths, rotation, and rollback.
- No local Docker names or numeric local IDs are deployment dependencies.
- No test or production secret is copied into Git, logs, or the plan.
