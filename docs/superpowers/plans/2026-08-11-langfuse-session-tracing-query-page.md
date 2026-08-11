# Langfuse Session Tracing Query Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 K-ACP 中提供一个生产可用的只读“用户对话复盘”页面，按真实用户查询已经写入 `langfuse_session_tracing` 的 session，并完整展示每轮用户问题、Agent 最终回答、trace 摘要和原始入库 JSON。

**Architecture:** 页面挂在现有运维区域，前端只调用 `GET` 接口。后端放在现有 `biz-workflow` 业务模块中，通过 MyBatis 查询结果表和租约表，使用 MySQL `JSON_TABLE` 从 `envelope_json.observations[*].userId` 提取真实对话用户并关联 `account`；详情服务使用 Jackson 解析 `llm_analysis_json.turns`，原始大 JSON 仅在用户打开“入库 JSON”页签时延迟加载。

**Tech Stack:** Java 21、Spring Boot、MyBatis Plus、MySQL 8 JSON/JSON_TABLE、Flyway、Vue 3、TypeScript、Ant Design Vue、Axios、Vitest-compatible Node test runner、Playwright/Chrome browser verification。

## Global Constraints

- 页面和接口全部只读，只允许 `GET`；不允许触发工作流、调用 LLM、重新分析、重新处理、清理 session 或修改 tracing 数据。
- 页面主视角必须是“用户名称 + 对话”，`sessionId` 只出现在 Trace 技术信息和原始 JSON 中。
- 用户筛选必须是下拉选择，不使用自由文本用户搜索框。
- 多轮对话按 `llm_analysis_json.turns[]` 原顺序展示，每轮只突出 `userQuestion` 和 `agentAnswer`。
- 真实用户 ID 从 `envelope_json.observations[*].userId` 读取；禁止用 `created_by` 代替真实对话用户。
- 所有 SQL 必须显式限定当前 `tenant_id`，详情和 raw 查询必须同时使用 `id + tenant_id`。
- 默认仅租户所有者和租户管理员可访问；后端每个 GET 方法使用 `@RoleNeed({TenantRole.TENANT_ADMIN})`，前端对其他角色隐藏菜单。
- 原始 JSON 不进入分页接口和普通详情接口，只通过独立 raw 接口按需加载。
- 首版不增加 `user_id` 物化列、不修改现有工作流；达到性能阈值后再单独规划物化字段。
- 当前原型是视觉和交互基准：`docs/prototypes/langfuse-session-tracing-dashboard.html`。

---

## Confirmed Data Contract

本地库已只读核验：`langfuse_session_tracing` 当前有 5 条 `COMPLETE` 数据，真实用户包括 `管理员/admin` 和 `周智峰/zhouzhifeng`。结果表以 `(tenant_id, session_id)` 唯一，原始 JSON 列为 MySQL `json` 类型；租约表以同样键唯一。

页面请求链路：

```text
SessionTracing.vue
  -> GET /api/langfuse/session-tracing/users
  -> GET /api/langfuse/session-tracing/page
  -> GET /api/langfuse/session-tracing/{id}
  -> GET /api/langfuse/session-tracing/{id}/raw  (仅打开 JSON 页签)
  -> GET /api/langfuse/session-tracing/summary
  -> LangfuseSessionTracingController
  -> LangfuseSessionTracingService
  -> LangfuseSessionTracingMapper
  -> langfuse_session_tracing / langfuse_session_trace_cursor / account
```

### Read-only API contract

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/langfuse/session-tracing/users` | 返回当前租户存在 tracing 数据的用户下拉选项 |
| `GET` | `/api/langfuse/session-tracing/page` | 按用户、结果状态分页查询轻量会话列表 |
| `GET` | `/api/langfuse/session-tracing/{id}` | 返回用户信息、多轮问答和 trace 摘要 |
| `GET` | `/api/langfuse/session-tracing/{id}/raw` | 返回完整入库 JSON，仅按需调用 |
| `GET` | `/api/langfuse/session-tracing/summary` | 返回结果状态、租约状态和过期 PROCESSING 数量 |

`GET /users` response data:

```json
[
  {
    "userId": "1111111111111111111",
    "nickname": "管理员",
    "username": "admin",
    "email": "admin@gmail.com",
    "conversationCount": 4,
    "lastProcessedAt": "2026-08-11T09:54:00.494"
  }
]
```

`GET /page?userId=1111111111111111111&status=COMPLETE&page=1&size=20` response data:

```json
{
  "records": [
    {
      "id": "47",
      "userId": "1111111111111111111",
      "nickname": "管理员",
      "username": "admin",
      "email": "admin@gmail.com",
      "status": "COMPLETE",
      "turnCount": 1,
      "firstUserQuestion": "你好",
      "lastAgentAnswer": "你好，请问想查什么数据？",
      "traceCount": 1,
      "fullObservationCount": 8,
      "processedAt": "2026-08-11T09:54:00.494"
    }
  ],
  "total": 1,
  "size": 20,
  "current": 1,
  "pages": 1
}
```

`GET /{id}` response data:

```json
{
  "id": "47",
  "sessionId": "2086980254552993793",
  "projectId": "prj_k_acp_local",
  "user": {
    "userId": "1111111111111111111",
    "nickname": "管理员",
    "username": "admin",
    "email": "admin@gmail.com"
  },
  "status": "COMPLETE",
  "turns": [
    {
      "turn": 1,
      "userQuestion": "你好",
      "agentAnswer": "你好，请问想查什么数据？",
      "userTimestamp": "2026-08-11 08:57:21.710",
      "agentTimestamp": "2026-08-11 08:57:23.757"
    }
  ],
  "traceSummary": {
    "traceCount": 1,
    "seedObservationCount": 3,
    "fullObservationCount": 8,
    "scoreCount": 0,
    "qaPairCount": 1,
    "typeCounts": {
      "AGENT": 1,
      "GENERATION": 4,
      "TOOL": 3
    },
    "warnings": [],
    "firstObservationStartTime": "2026-08-11 08:57:21.710",
    "lastObservationEndTime": "2026-08-11 08:57:23.757",
    "processedAt": "2026-08-11T09:54:00.494"
  }
}
```

`GET /{id}/raw` response data:

```json
{
  "llmAnalysisJson": {
    "sessionId": "2086980254552993793",
    "turns": []
  },
  "qaPairsJson": [],
  "conversationJson": {},
  "envelopeJson": {
    "observations": []
  },
  "warningsJson": []
}
```

`GET /summary` response data:

```json
{
  "resultStatusCounts": {
    "COMPLETE": 5,
    "PARTIAL": 0,
    "ERROR": 0
  },
  "cursorStatusCounts": {
    "DISCOVERED": 0,
    "PROCESSING": 0,
    "COMPLETE": 5,
    "FAILED": 0
  },
  "staleProcessingCount": 0,
  "lastProcessedAt": "2026-08-11T09:54:00.494"
}
```

---

## Luna Max Execution Slicing

执行代理统一使用 `gpt-5.6-luna`、`max` 推理强度。任务按依赖关系分四个并行波次，任务完成后必须通过对应测试和任务级审查，不能只以“代码已写”作为完成标准。

| ID | 任务 | 依赖 | 可并行波次 | 独立完成条件 |
| --- | --- | --- | --- | --- |
| E1 | 基线检查：分支、脏文件、数据库结构、后端编译、前端类型检查 | 无 | 前置门禁 | 记录现状且基线无新增失败 |
| E2 | 数据库读模型：`db_init`、Flyway V5、表常量、实体 | E1 | Wave 1 | 公共模块和 console 编译通过 |
| E3 | 后端数据契约：DTO/VO、JSON assembler、单元测试 | E1 | Wave 1 | assembler 测试通过 |
| E4 | 前端数据层：类型、API 客户端、显示 helper、测试 | E1 | Wave 1 | Node 测试和类型检查通过 |
| E5 | MyBatis 查询层：用户、分页、详情、raw、summary SQL | E2、E3 | Wave 2 | 本地只读 SQL smoke 与模块编译通过 |
| E6 | 页面状态层：筛选、分页、竞态保护、raw 懒加载 | E4 | Wave 2 | 前端类型检查通过 |
| E7 | 查询 Service：租户隔离、参数校验、VO 组装 | E5 | Wave 3 | Service 单元测试通过 |
| E8 | 原型组件：列表、多轮对话、Trace/JSON、响应式布局 | E6 | Wave 3 | 前端构建通过且与原型结构一致 |
| E9 | 只读 Controller：5 个 GET、管理员权限、no-store raw | E7 | Wave 4 | Controller 测试通过且无写映射 |
| E10 | 路由导航：Ops 路由、管理员菜单、深链权限兜底 | E8 | Wave 4 | 路由类型检查和构建通过 |
| E11 | 全链路联调：API、空态、真实多用户、多轮、桌面/移动端 | E9、E10 | 集成 | 自动测试、接口 smoke、三视口截图通过 |
| E12 | 最终审查：只读边界、安全、性能、Graphify、交付 | E11 | 收尾 | 全分支审查无 Critical/Important 问题 |

关键路径为 `E1 -> E2/E3 -> E5 -> E7 -> E9 -> E11 -> E12`；前端链路 `E1 -> E4 -> E6 -> E8 -> E10` 与后端尽量并行。

---

### Task 1: Formalize the database read model

**Files:**
- Modify: `sql/db_init.sql`
- Create: `runner-console/src/main/resources/db/migration/V5__langfuse_session_tracing_query_support.sql`
- Modify: `common-base/src/main/java/com/hxh/apboa/common/consts/TableConst.java`
- Create: `common/src/main/java/com/hxh/apboa/common/entity/LangfuseSessionTracing.java`

**Interfaces:**
- Consumes: existing MySQL tables created by the tracing workflow.
- Produces: stable table constants and a read-only entity mapped to every result-table column.

- [ ] **Step 1: Re-check the target schema before editing**

Run the local read-only `SHOW CREATE TABLE` query through `./scripts/with-environment.sh local --require mysql -- ...`. Confirm that the result table has `enabled`, JSON columns, `created_by`, and the unique `(tenant_id, session_id)` key. Stop implementation if the deployed column types differ from the confirmed contract above.

- [ ] **Step 2: Add the two existing table definitions to the clean-install schema**

Append exact `CREATE TABLE IF NOT EXISTS` definitions for `langfuse_session_tracing` and `langfuse_session_trace_cursor` to `sql/db_init.sql`. Preserve all live columns and keys. Add these query indexes to the result table:

```sql
KEY `idx_langfuse_session_tracing_tenant_processed` (`tenant_id`, `processed_at`),
KEY `idx_langfuse_session_tracing_tenant_status_processed` (`tenant_id`, `status`, `processed_at`)
```

- [ ] **Step 3: Add the non-destructive Flyway migration**

Create `V5__langfuse_session_tracing_query_support.sql` with `CREATE TABLE IF NOT EXISTS` for both tables. Because the local tables already exist, add each new index conditionally through `information_schema.statistics` plus `PREPARE/EXECUTE`, matching the conditional migration pattern in `V4__align_legacy_schema.sql`.

The migration must not add a `user_id` column, update existing records, or change workflow-owned data.

- [ ] **Step 4: Add table constants and the result entity**

Add:

```java
public static final String LANGFUSE_SESSION_TRACING = "langfuse_session_tracing";
public static final String LANGFUSE_SESSION_TRACE_CURSOR = "langfuse_session_trace_cursor";
```

`LangfuseSessionTracing` extends `BaseTenantEntity`, uses `@TableName(value = TableConst.LANGFUSE_SESSION_TRACING, autoResultMap = true)`, maps all JSON columns with `JsonNodeTypeHandler`, and maps `processedAt` as `LocalDateTime`. The class has no save/update helper because this feature never writes it.

The entity fields must exactly match the live table:

```text
sessionId, projectId, langfuseBaseUrl, retrievalMethod, status,
traceCount, seedObservationCount, fullObservationCount, scoreCount, qaPairCount,
typeCountsJson, qaPairsJson, conversationJson, envelopeJson, llmAnalysisJson,
warningsJson, sourceHash, firstObservationStartTime,
lastObservationEndTime, processedAt
```

- [ ] **Step 5: Compile the mapped entity and validate migration syntax**

Run:

```bash
mvn -pl common,runner-console -am -DskipTests compile
```

Expected: Maven reactor ends with `BUILD SUCCESS`.

- [ ] **Step 6: Commit the database read model**

```bash
git add sql/db_init.sql runner-console/src/main/resources/db/migration/V5__langfuse_session_tracing_query_support.sql common-base/src/main/java/com/hxh/apboa/common/consts/TableConst.java common/src/main/java/com/hxh/apboa/common/entity/LangfuseSessionTracing.java
git commit -m "feat: formalize session tracing read model"
```

### Task 2: Build deterministic JSON parsing and response types

**Files:**
- Modify: `biz/biz-workflow/pom.xml`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/dto/LangfuseSessionTracingQuery.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseTracingUserVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseConversationTurnVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseSessionTracingListVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseSessionTracingDetailVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseSessionTracingRawVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo/LangfuseSessionTracingSummaryVO.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingAssembler.java`
- Create: `biz/biz-workflow/src/test/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingAssemblerTest.java`

**Interfaces:**
- Consumes: JSON strings/nodes from mapper rows.
- Produces: `parseTurns(JsonNode)`, `parseObjectCounts(JsonNode)`, `parseWarnings(JsonNode)` and all stable API VO types.

- [ ] **Step 1: Add the workflow-module test dependency**

Add `spring-boot-starter-test` with `test` scope to `biz/biz-workflow/pom.xml`, following `biz/biz-agent/pom.xml`.

- [ ] **Step 2: Write failing assembler tests**

Cover these exact behaviors:

```java
@Test
void parsesAllTurnsInStoredOrder() {
    JsonNode json = JsonUtils.parse("""
        {"turns":[
          {"turn":1,"userQuestion":"问题一","agentAnswer":"回答一","userTimestamp":"u1","agentTimestamp":"a1"},
          {"turn":2,"userQuestion":"问题二","agentAnswer":"回答二","userTimestamp":"u2","agentTimestamp":"a2"}
        ]}
        """);
    List<LangfuseConversationTurnVO> turns = assembler.parseTurns(json);
    assertEquals(List.of("问题一", "问题二"), turns.stream().map(LangfuseConversationTurnVO::getUserQuestion).toList());
    assertEquals(List.of("回答一", "回答二"), turns.stream().map(LangfuseConversationTurnVO::getAgentAnswer).toList());
}

@Test
void malformedOrMissingTurnsReturnEmptyList() {
    assertTrue(assembler.parseTurns(null).isEmpty());
    assertTrue(assembler.parseTurns(JsonUtils.parse("{}")) .isEmpty());
    assertTrue(assembler.parseTurns(JsonUtils.parse("{\"turns\":{}}")) .isEmpty());
}

@Test
void missingQuestionOrAnswerIsKeptAsEmptyText() {
    List<LangfuseConversationTurnVO> turns = assembler.parseTurns(
        JsonUtils.parse("{\"turns\":[{\"turn\":1}]}"));
    assertEquals("", turns.getFirst().getUserQuestion());
    assertEquals("", turns.getFirst().getAgentAnswer());
}
```

Also test type-count parsing and warnings parsing with null, array, object, and unexpected scalar inputs.

- [ ] **Step 3: Run the focused test and confirm failure**

```bash
mvn -pl biz/biz-workflow -am -Dtest=LangfuseSessionTracingAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because the assembler and VO classes do not exist.

- [ ] **Step 4: Implement response types and assembler**

Use Lombok `@Getter/@Setter`. Keep IDs as `String` in VO classes so JavaScript never loses 64-bit precision. `LangfuseSessionTracingQuery` extends `PageParams` and contains only `String userId` and `String status`.

Assembler rules:

```text
turns missing/not-array       -> []
turn field missing            -> array index + 1
question/answer missing       -> ""
timestamps missing            -> null
typeCounts missing/not-object -> {}
warnings missing/not-array    -> []
```

Do not infer, summarize, merge, or reorder turns.

- [ ] **Step 5: Run focused tests**

Run the command from Step 3.

Expected: `Tests run: 4, Failures: 0, Errors: 0` or a larger passing count after adding the type-count and warning cases.

- [ ] **Step 6: Commit parser and contracts**

```bash
git add biz/biz-workflow/pom.xml biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/dto biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/vo biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingAssembler.java biz/biz-workflow/src/test/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingAssemblerTest.java
git commit -m "feat: define session tracing query contracts"
```

### Task 3: Implement tenant-safe query mapper and service

**Files:**
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/mapper/LangfuseSessionTracingMapper.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/mapper/row/LangfuseSessionTracingDetailRow.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/mapper/row/LangfuseStatusCountRow.java`
- Create: `biz/biz-workflow/src/main/resources/com/hxh/apboa/workflowbiz/mapper/LangfuseSessionTracingMapper.xml`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/service/LangfuseSessionTracingService.java`
- Create: `biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingServiceImpl.java`
- Create: `biz/biz-workflow/src/test/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingServiceImplTest.java`

**Interfaces:**
- Consumes: `LangfuseSessionTracingQuery`, current tenant from `TenantUtils.getCurrentTenantId()`.
- Produces: `users()`, `page(query)`, `detail(id)`, `raw(id)`, and `summary()`.

- [ ] **Step 1: Write failing service tests with a mocked mapper**

Tests must set `TenantUtils.setCurrentTenant(100L, "test")` and clear it in `@AfterEach`. Verify:

```text
users/page/detail/raw/summary always pass tenantId=100 to mapper
page clamps size to 1..100 and defaults to page=1,size=20
page rejects a userId longer than 128 characters
page rejects statuses outside COMPLETE/PARTIAL/ERROR
detail/raw throw "会话追踪记录不存在" when mapper returns null
page returns only lightweight rows and never carries envelopeJson
```

- [ ] **Step 2: Run the focused tests and confirm failure**

```bash
mvn -pl biz/biz-workflow -am -Dtest=LangfuseSessionTracingServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because mapper/service classes do not exist.

- [ ] **Step 3: Define mapper methods**

```java
List<LangfuseTracingUserVO> selectUsers(@Param("tenantId") Long tenantId);
IPage<LangfuseSessionTracingListVO> selectTracingPage(IPage<?> page,
    @Param("tenantId") Long tenantId,
    @Param("query") LangfuseSessionTracingQuery query);
LangfuseSessionTracingDetailRow selectDetail(@Param("tenantId") Long tenantId, @Param("id") Long id);
LangfuseSessionTracingRawVO selectRaw(@Param("tenantId") Long tenantId, @Param("id") Long id);
List<LangfuseStatusCountRow> selectResultStatusCounts(@Param("tenantId") Long tenantId);
List<LangfuseStatusCountRow> selectCursorStatusCounts(@Param("tenantId") Long tenantId);
long countStaleProcessing(@Param("tenantId") Long tenantId);
LocalDateTime selectLastProcessedAt(@Param("tenantId") Long tenantId);
```

The XML must extract users with:

```sql
JSON_TABLE(
  t.envelope_json,
  '$.observations[*]'
  COLUMNS(user_id VARCHAR(128) PATH '$.userId' NULL ON EMPTY NULL ON ERROR)
)
```

Deduplicate by tracing record and user ID before joining `account`. For page filtering, apply `query.userId` inside an `EXISTS` subquery over the same JSON path. Always apply `t.tenant_id = #{tenantId}` and `t.enabled = 1` before status filtering. Order only by `t.processed_at DESC, t.id DESC`; do not expose arbitrary SQL sort fields.

The page SELECT returns no raw JSON columns. Compute `turn_count`, first question, and last answer from `llm_analysis_json`; if the turn array is missing, return `0`, empty question, and empty answer. The last-answer expression must address the final stored turn rather than the first:

```sql
JSON_UNQUOTE(JSON_EXTRACT(
  t.llm_analysis_json,
  CONCAT('$.turns[', JSON_LENGTH(t.llm_analysis_json, '$.turns') - 1, '].agentAnswer')
))
```

- [ ] **Step 4: Implement service assembly**

Use `MP.getPage(query)` after normalizing page and size. Accept only result statuses `COMPLETE`, `PARTIAL`, and `ERROR`; reject any other nonblank value with `不支持的 tracing 状态`. Reject `userId` values longer than 128 characters with `用户 ID 长度不能超过 128`. Detail assembly combines mapper row user metadata with assembler output. Summary assembly converts the two `LangfuseStatusCountRow` lists to ordered maps and fills absent standard statuses with zero. If account lookup is absent, return:

```text
nickname = "未知用户"
username = userId
email = null
```

Do not fall back from missing observation `userId` to `created_by`. Missing user IDs remain an “未知用户” grouping because `created_by` is the workflow executor, not necessarily the person who spoke.

- [ ] **Step 5: Run unit tests**

```bash
mvn -pl biz/biz-workflow -am -Dtest=LangfuseSessionTracingAssemblerTest,LangfuseSessionTracingServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all focused tests pass and Maven ends with `BUILD SUCCESS`.

- [ ] **Step 6: Run local read-only SQL smoke checks**

Through `./scripts/with-environment.sh local --require mysql -- ...`, execute the exact user extraction and list queries against the local DB. Confirm:

```text
管理员 / admin / 1111111111111111111
周智峰 / zhouzhifeng / 2080214710511923201
```

Run `EXPLAIN` for the unfiltered page query and status-filtered page query; confirm one of the new `(tenant_id, ... processed_at)` indexes is selected. Record the plan if MySQL chooses a full scan because the table is still tiny, but ensure the candidate index is available.

- [ ] **Step 7: Commit mapper and service**

```bash
git add biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/mapper biz/biz-workflow/src/main/resources/com/hxh/apboa/workflowbiz/mapper biz/biz-workflow/src/main/java/com/hxh/apboa/workflowbiz/service biz/biz-workflow/src/test/java/com/hxh/apboa/workflowbiz/service/impl/LangfuseSessionTracingServiceImplTest.java
git commit -m "feat: add tenant-safe session tracing queries"
```

### Task 4: Expose authenticated GET-only endpoints

**Files:**
- Modify: `runner-console/pom.xml`
- Create: `runner-console/src/main/java/com/hxh/apboa/console/ops/LangfuseSessionTracingController.java`
- Create: `runner-console/src/test/java/com/hxh/apboa/console/ops/LangfuseSessionTracingControllerTest.java`

**Interfaces:**
- Consumes: `LangfuseSessionTracingService`.
- Produces: the five `/langfuse/session-tracing` GET endpoints defined above.

- [ ] **Step 1: Add runner-console test dependency and write the failing controller test**

Use a mocked service and call controller methods directly. Assert `R.data(...)` contains the same VO returned by the service. Reflect over each endpoint method and assert it has both `@GetMapping` and `@RoleNeed({TenantRole.TENANT_ADMIN})`. Assert the controller declares no methods annotated with `PostMapping`, `PutMapping`, `PatchMapping`, or `DeleteMapping`.

- [ ] **Step 2: Run the controller test and confirm failure**

```bash
mvn -pl runner-console -am -Dtest=LangfuseSessionTracingControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the controller does not exist.

- [ ] **Step 3: Implement the controller**

Use:

```java
@RestController
@RequestMapping("/langfuse/session-tracing")
@RequiredArgsConstructor
public class LangfuseSessionTracingController {
    private final LangfuseSessionTracingService service;
}
```

Annotate every GET method with `@RoleNeed({TenantRole.TENANT_ADMIN})`, because the current interceptor checks method annotations rather than class annotations. Do not add `@SkAccess` or `@ChatKeyAccess`; tracing data must only be available through an authenticated platform JWT.

For the raw endpoint, add `Cache-Control: no-store` through `HttpServletResponse.setHeader(...)` before returning `R.data(service.raw(id))`.

- [ ] **Step 4: Run controller and service tests**

```bash
mvn -pl runner-console -am -Dtest=LangfuseSessionTracingControllerTest,LangfuseSessionTracingAssemblerTest,LangfuseSessionTracingServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests pass; Maven ends with `BUILD SUCCESS`.

- [ ] **Step 5: Commit the API layer**

```bash
git add runner-console/pom.xml runner-console/src/main/java/com/hxh/apboa/console/ops/LangfuseSessionTracingController.java runner-console/src/test/java/com/hxh/apboa/console/ops/LangfuseSessionTracingControllerTest.java
git commit -m "feat: expose read-only session tracing API"
```

### Task 5: Add typed frontend query client and display helpers

**Files:**
- Create: `ui/src/types/sessionTracing.ts`
- Create: `ui/src/api/sessionTracing.ts`
- Create: `ui/src/utils/sessionTracing.ts`
- Create: `ui/src/utils/sessionTracing.test.ts`
- Modify: `ui/package.json`

**Interfaces:**
- Consumes: the five backend contracts.
- Produces: `getTracingUsers`, `getTracingPage`, `getTracingDetail`, `getTracingRaw`, `getTracingSummary`, plus deterministic display helpers.

- [ ] **Step 1: Write failing helper tests**

```ts
test('用户标签优先显示昵称、账号和邮箱', () => {
  assert.equal(
    formatTracingUserLabel({ userId: '1', nickname: '管理员', username: 'admin', email: 'admin@gmail.com' }),
    '管理员 / admin / admin@gmail.com',
  )
})

test('账号缺失时仍显示可识别用户', () => {
  assert.equal(
    formatTracingUserLabel({ userId: '9', nickname: null, username: null, email: null }),
    '未知用户 / 9',
  )
})

test('列表状态只映射结果表状态', () => {
  assert.equal(tracingStatusTone('COMPLETE'), 'success')
  assert.equal(tracingStatusTone('PARTIAL'), 'warning')
  assert.equal(tracingStatusTone('ERROR'), 'error')
})
```

- [ ] **Step 2: Run tests and confirm failure**

```bash
node --experimental-strip-types --test ui/src/utils/sessionTracing.test.ts
```

Expected: module-not-found or missing-export failure.

- [ ] **Step 3: Implement types, API functions, and helpers**

All IDs are TypeScript `string`. `TracingPageQuery` contains only `userId?`, `status?`, `page`, and `size`. API functions use `request.get<ApiResponse<...>>()` and these exact URLs:

```ts
'/api/langfuse/session-tracing/users'
'/api/langfuse/session-tracing/page'
`/api/langfuse/session-tracing/${id}`
`/api/langfuse/session-tracing/${id}/raw`
'/api/langfuse/session-tracing/summary'
```

Add `test:session-tracing` to `ui/package.json`:

```json
"test:session-tracing": "node --experimental-strip-types --test src/utils/sessionTracing.test.ts"
```

- [ ] **Step 4: Run helper tests and typecheck**

```bash
pnpm --dir ui test:session-tracing
pnpm --dir ui type-check
```

Expected: Node reports all helper tests passed; `vue-tsc --build` exits 0.

- [ ] **Step 5: Commit frontend data contracts**

```bash
git add ui/package.json ui/src/types/sessionTracing.ts ui/src/api/sessionTracing.ts ui/src/utils/sessionTracing.ts ui/src/utils/sessionTracing.test.ts
git commit -m "feat: add session tracing query client"
```

### Task 6: Implement the query-only Vue page

**Files:**
- Create: `ui/src/views/Ops/SessionTracing.vue`
- Create: `ui/src/components/session-tracing/TracingSessionList.vue`
- Create: `ui/src/components/session-tracing/TracingConversation.vue`
- Create: `ui/src/components/session-tracing/TracingInspector.vue`

**Interfaces:**
- Consumes: typed functions from `ui/src/api/sessionTracing.ts`.
- Produces: a responsive user-conversation dashboard matching the approved prototype.

- [ ] **Step 1: Build page state and request sequencing**

On mount, request users, page data, and summary in parallel. After page data arrives, select the first row and request its detail. Use a monotonically increasing request sequence number so a slower response for an old user/status filter cannot overwrite the latest result.

State must include independent `listLoading`, `detailLoading`, `rawLoading`, `listError`, `detailError`, `selectedId`, `page`, and `summary`. Changing user or status resets `page=1`, clears `selectedId`, and reloads the list.

- [ ] **Step 2: Implement the left query column**

Use Ant Design Vue controls:

```text
ASelect: 全部用户 + “昵称 / username / email” options
ASelect: 全部结果 + COMPLETE / PARTIAL / ERROR
icon-only ReloadOutlined button with tooltip “刷新数据”
APagination: total/current/pageSize, fixed page size 20
```

Conversation list rows show nickname, username/email, first user question, processed time, turn count, observation count, and result status. Do not show `sessionId` in list rows.

- [ ] **Step 3: Implement the conversation detail**

The default tab renders every stored turn in order:

```text
第 N 轮
用户问题 + userTimestamp
Agent 最终回答 + agentTimestamp
```

Render text with normal Vue interpolation and `white-space: pre-wrap`; do not use `v-html`. A local copy icon may copy the visible conversation to the clipboard, but it must not call any backend mutation API.

- [ ] **Step 4: Implement trace and raw tabs**

Trace tab shows user identity, project, technical `sessionId`, start/end/processed time, trace/observation/score/QA counts, type distribution, and warnings.

Raw tab calls `getTracingRaw(selectedId)` only the first time that selected record opens the tab. Cache raw data by record ID only in page memory; clear the cache on full page reload. Render each JSON field with `JSON.stringify(value, null, 2)` in a read-only code block. Provide loading, retry, empty, and error states.

- [ ] **Step 5: Implement the read-only summary panel**

Show result status counts, cursor status counts, stale processing count, and last processed time. Do not show buttons for retry, rerun, cleanup, reanalysis, or workflow execution. Do not poll automatically; the single refresh button repeats GET requests only when the user asks.

- [ ] **Step 6: Implement responsive layout and accessibility**

Desktop uses three stable columns approximately `300px minmax(0, 1fr) 320px`. Tablet collapses the summary below the detail. Mobile uses one column and keeps filters above the list. Use cards only for repeated conversation/summary items, radius at most 8px in the formal page, and avoid nested cards.

All icon-only buttons need tooltips and `aria-label`. Long usernames, questions, answers, IDs, and JSON must wrap or ellipsize without overlapping neighboring content.

- [ ] **Step 7: Typecheck and build**

```bash
pnpm --dir ui type-check
pnpm --dir ui build:main
```

Expected: `vue-tsc --build` exits 0 and Vite ends with `built in ...` without errors.

- [ ] **Step 8: Commit the page**

```bash
git add ui/src/views/Ops/SessionTracing.vue ui/src/components/session-tracing
git commit -m "feat: build session tracing query page"
```

### Task 7: Register route and admin-only navigation

**Files:**
- Modify: `ui/src/router/constants.ts`
- Modify: `ui/src/router/modules/biz.ts`
- Modify: `ui/src/components/layout/SideMenu.vue`

**Interfaces:**
- Consumes: `SessionTracing.vue`.
- Produces: `/ops/session-tracing` route and visible “对话复盘” menu for tenant owner/admin.

- [ ] **Step 1: Add route constants and route record**

Add:

```ts
OPS_SESSION_TRACING: 'OpsSessionTracing'
OPS_SESSION_TRACING: 'ops/session-tracing'
```

Register a lazy route to `@/views/Ops/SessionTracing.vue` with title `用户对话复盘`, `hidden: false`, and no public/white-list entry.

- [ ] **Step 2: Add the sidebar entry**

Reuse `ui/src/assets/avatar/chat-bot.png`. Add `对话复盘` under the existing development/operations-oriented menu items. Compute `canViewSessionTracing` from `tenantRole === 'TENANT_OWNER' || tenantRole === 'TENANT_ADMIN'` and filter only this menu item for unauthorized roles.

The backend remains the source of truth: direct navigation by an editor/viewer receives the existing unauthorized response even if the route URL is known.

- [ ] **Step 3: Verify routing and type safety**

```bash
pnpm --dir ui type-check
pnpm --dir ui build:main
```

Expected: both commands exit 0 and the generated route chunk contains `SessionTracing`.

- [ ] **Step 4: Commit route and navigation**

```bash
git add ui/src/router/constants.ts ui/src/router/modules/biz.ts ui/src/components/layout/SideMenu.vue
git commit -m "feat: register session tracing navigation"
```

### Task 8: End-to-end verification and production acceptance

**Files:**
- Modify only if behavior changed during verification: `docs/prototypes/langfuse-session-tracing-dashboard.html`
- Update generated graph: `graphify-out/*`

**Interfaces:**
- Consumes: completed backend and frontend.
- Produces: verified query-only feature with recorded evidence.

- [ ] **Step 1: Run all focused automated checks**

```bash
mvn -pl runner-console -am -Dtest=LangfuseSessionTracingControllerTest,LangfuseSessionTracingAssemblerTest,LangfuseSessionTracingServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
pnpm --dir ui test:session-tracing
pnpm --dir ui type-check
pnpm --dir ui build:main
```

Record each Maven test summary, Node test pass count, and Vite build summary.

- [ ] **Step 2: Start the existing local services**

Use the repository's normal local startup commands. Verify `runner-console` is on `3060` and Vite is on `3030`; do not alter Langfuse or workflow networking for this page.

- [ ] **Step 3: Verify all five GET contracts**

With an admin JWT, call every endpoint and confirm HTTP/application success. Confirm a record from another tenant cannot be fetched by changing `{id}`. Confirm the browser network log contains no `POST`, `PUT`, `PATCH`, or `DELETE` request under `/api/langfuse/session-tracing`.

- [ ] **Step 4: Verify normal and edge data states**

Using existing local records only, verify:

```text
全部用户 includes 管理员 and 周智峰
selecting 周智峰 returns only that user's conversation
the two-turn 管理员 session renders both turns in order
sessionId is absent from the primary list and present in Trace details
raw JSON is not requested until the raw tab opens
empty filters show an empty state rather than an error
missing account metadata displays 未知用户 / userId
PARTIAL or warnings render visibly without blocking the conversation
```

- [ ] **Step 5: Verify responsive layout in a browser**

Capture screenshots at `1440x1000`, `1024x900`, and `390x844`. Check that the page has no overlap, clipped buttons, unreadable JSON, or horizontal body scroll. Confirm the user selector is a dropdown and there is no “重新分析” or workflow action.

- [ ] **Step 6: Measure query behavior**

Record response time and payload size for page, detail, and raw endpoints. Acceptance targets on the current local dataset:

```text
users/page/summary p95 < 500 ms
detail p95 < 500 ms
raw p95 < 1500 ms
page response excludes envelope_json and stays below 100 KB for 20 rows
```

If result rows exceed 100,000 or the user-filter query p95 exceeds 500 ms, stop and create a separate performance plan to materialize `user_id`, `turn_count`, `first_user_question`, and `last_agent_answer`; do not expand this implementation silently.

- [ ] **Step 7: Update Graphify after code changes**

```bash
graphify update .
```

Expected: graph update completes successfully; generated `graphify-out/` changes are retained as required by repository guidance.

- [ ] **Step 8: Final acceptance gate**

The feature is complete only when all of these are true:

```text
Only five GET endpoints were added.
No workflow, LLM, skill, scheduler, cursor, or tracing-row mutation was added.
Every backend query is tenant scoped.
Raw trace data is admin-only, no-store, and lazily loaded.
The primary UI is user name + multi-turn conversation.
The user filter is a dropdown.
No reanalysis/retry/cleanup controls exist.
Automated checks and desktop/mobile browser checks pass.
```

## Error Handling Matrix

| Situation | Backend behavior | Frontend behavior |
| --- | --- | --- |
| No tracing rows | Return empty users/page and zero summary | Show clear empty state |
| Record not in current tenant | Return normal not-found application error | Clear stale selection and reload list |
| Missing account row | Preserve extracted `userId`; use `未知用户` | Display `未知用户 / userId` |
| Missing/malformed turn structure | Return `turns: []`; keep trace summary | Show “暂无可展示问答” and allow Trace/JSON tabs |
| Missing question or answer in one turn | Return empty string for missing side | Show “未记录用户问题” or “未记录最终回答” |
| Raw JSON request fails | Do not affect loaded list/detail | Show retry inside raw tab only |
| Old list request finishes late | Backend response remains valid | Sequence guard discards stale response |
| Unauthorized editor/viewer | Existing auth interceptor returns unauthorized | Menu hidden; deep link shows permission error |
| Stale PROCESSING lease | Summary returns `staleProcessingCount > 0` | Highlight read-only warning; no retry action |
| Very large envelope | Raw isolated from page/detail | Load on demand and use scrollable code viewer |

## Explicit Non-goals

- No changes to `skills/langfuse-session-tracing/SKILL.md`.
- No changes to workflow `2092000000000000301`.
- No Langfuse API calls from this page or its backend endpoints.
- No session discovery, cooldown, leasing, deduplication, retry, or scheduling changes.
- No user-authored notes, tags, ratings, comments, or exports in this release.
- No WebSocket or automatic polling.
- No schema-level user materialization until the measured threshold requires it.
