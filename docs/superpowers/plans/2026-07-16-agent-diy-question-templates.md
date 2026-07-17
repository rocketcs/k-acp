# Agent DIY Question Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in, per-agent DIY welcome page where administrators configure shortcut cards with placeholder templates and chat users fill or select placeholder values before sending through the existing chat pipeline.

**Architecture:** Store draft and published JSON configurations in a new tenant-scoped table keyed by agent ID. Add isolated editor and chat routes; the existing Chat view fetches DIY configuration only on the DIY route, renders a configurable welcome panel for empty sessions, and delegates final text to the existing `handleSend` flow.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, MySQL 8, Vue 3, TypeScript, Ant Design Vue, Node 22 built-in test runner, VEP/vue-echarts.

## Global Constraints

- Existing `/chat/:agentId` behavior must remain unchanged.
- DIY configuration is opt-in and isolated by tenant plus agent ID.
- Templates support only `{{placeholder}}` replacement; no JavaScript or conditional expressions.
- Placeholder inputs support user text or single-select options.
- Output formats are limited to text, ECharts bar, ECharts pie, and JSON.
- Shortcut confirmation must reuse the existing session creation, persistence, and streaming send path.
- The left chat-history sidebar and current Chat layout remain unchanged.
- Draft edits do not affect the published chat page.

---

### Task 1: Template parser and renderer

**Files:**
- Create: `ui/src/utils/diy/questionTemplate.ts`
- Create: `ui/src/utils/diy/questionTemplate.test.ts`
- Modify: `ui/package.json`

**Interfaces:**
- Produces: `extractPlaceholders(template: string): string[]`
- Produces: `renderQuestionTemplate(template: string, values: Record<string, string>): { text: string; missing: string[] }`
- Produces: `buildOutputInstruction(format: DiyOutputFormat): string`

- [ ] **Step 1: Write failing Node tests**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { extractPlaceholders, renderQuestionTemplate, buildOutputInstruction } from './questionTemplate.ts'

test('extracts unique placeholders in source order', () => {
  assert.deepEqual(extractPlaceholders('查 {{公司}} 在 {{时间}} 的 {{公司}} 数据'), ['公司', '时间'])
})

test('reports missing required values without leaving silent placeholders', () => {
  assert.deepEqual(
    renderQuestionTemplate('查 {{公司}} 在 {{时间}} 的数据', { 公司: '金智维' }),
    { text: '查 金智维 在 {{时间}} 的数据', missing: ['时间'] }
  )
})

test('adds stable VEP protocol for a bar chart', () => {
  assert.match(buildOutputInstruction('ECHARTS_BAR'), /chartType.*bar/)
})
```

- [ ] **Step 2: Run the tests and confirm RED**

Run: `cd ui && node --experimental-strip-types --test src/utils/diy/questionTemplate.test.ts`
Expected: FAIL because `questionTemplate.ts` does not exist.

- [ ] **Step 3: Implement the minimal parser**

```ts
export type DiyOutputFormat = 'TEXT' | 'ECHARTS_BAR' | 'ECHARTS_PIE' | 'JSON'

const PLACEHOLDER = /{{\s*([^{}]+?)\s*}}/g

export function extractPlaceholders(template: string): string[] {
  return [...template.matchAll(PLACEHOLDER)]
    .map(match => match[1]!.trim())
    .filter((name, index, all) => all.indexOf(name) === index)
}
```

Implement replacement and output instructions using the four allowed formats.

- [ ] **Step 4: Run the tests and confirm GREEN**

Run: `cd ui && node --experimental-strip-types --test src/utils/diy/questionTemplate.test.ts`
Expected: 3 passing tests.

### Task 2: Tenant-scoped DIY persistence and API

**Files:**
- Create: `common/src/main/java/com/hxh/apboa/common/entity/AgentDiyPageConfig.java`
- Create: `common/src/main/java/com/hxh/apboa/common/vo/AgentDiyPageConfigVO.java`
- Modify: `common-base/src/main/java/com/hxh/apboa/common/consts/TableConst.java`
- Create: `biz/biz-agent/src/main/java/com/hxh/apboa/agent/mapper/AgentDiyPageConfigMapper.java`
- Create: `biz/biz-agent/src/main/java/com/hxh/apboa/agent/service/AgentDiyPageConfigService.java`
- Create: `biz/biz-agent/src/main/java/com/hxh/apboa/agent/service/impl/AgentDiyPageConfigServiceImpl.java`
- Create: `biz/biz-agent/src/test/java/com/hxh/apboa/agent/service/impl/AgentDiyPageConfigServiceImplTest.java`
- Create: `runner-console/src/main/java/com/hxh/apboa/console/agent/AgentDiyPageConfigController.java`
- Modify: `sql/once_db_init/db_init.sql`
- Create: `sql/incremental/20260716_agent_diy_page_config.sql`

**Interfaces:**
- Produces: `AgentDiyPageConfigService#getByAgentId(Long)`
- Produces: `AgentDiyPageConfigService#saveDraft(Long, JsonNode)`
- Produces: `AgentDiyPageConfigService#publish(Long)`
- Produces: `AgentDiyPageConfigService#setEnabled(Long, Boolean)`
- HTTP: `GET /agent/diy-page/{agentId}`
- HTTP: `GET /agent/diy-page/{agentId}/published`
- HTTP: `PUT /agent/diy-page/{agentId}/draft`
- HTTP: `POST /agent/diy-page/{agentId}/publish`
- HTTP: `PUT /agent/diy-page/{agentId}/enabled`

- [ ] **Step 1: Write a failing service test**

Use Mockito to verify that publishing copies `draftConfig` to `publishedConfig`, sets `publishedAt`, and persists through `AgentDiyPageConfigMapper`.

- [ ] **Step 2: Run the focused test and confirm RED**

Run: `./mvnw -pl biz/biz-agent -am -Dtest=AgentDiyPageConfigServiceImplTest test`
Expected: FAIL because the service does not exist.

- [ ] **Step 3: Add schema and entity**

Create `agent_diy_page_config` with:

```sql
id bigint primary key,
agent_definition_id bigint not null,
draft_config json null,
published_config json null,
published_at datetime null,
enabled tinyint(1) not null default 0,
created_at datetime not null default current_timestamp,
updated_at datetime not null default current_timestamp on update current_timestamp,
created_by bigint null,
updated_by bigint null,
tenant_id bigint not null,
unique key uk_agent_diy_page (tenant_id, agent_definition_id)
```

- [ ] **Step 4: Implement mapper, service, and controller**

Protect draft, publish, and enabled writes with `TENANT_ADMIN` and `TENANT_EDITOR`. Return only enabled published configuration from the chat endpoint; return `null` when absent.

- [ ] **Step 5: Run the focused test and confirm GREEN**

Run: `./mvnw -pl biz/biz-agent -am -Dtest=AgentDiyPageConfigServiceImplTest test`
Expected: PASS.

### Task 3: Frontend types and API client

**Files:**
- Create: `ui/src/types/diy.ts`
- Modify: `ui/src/types/index.ts`
- Create: `ui/src/api/agentDiy.ts`

**Interfaces:**
- Produces: `DiyPageConfig`, `DiyQuestionCard`, `DiyPlaceholderConfig`, `DiyOutputFormat`
- Produces: `getDraft(agentId)`, `getPublished(agentId)`, `saveDraft(agentId, config)`, `publish(agentId)`, `setEnabled(agentId, enabled)`

- [ ] **Step 1: Define serializable configuration types**

```ts
export interface DiyPlaceholderConfig {
  name: string
  inputType: 'INPUT' | 'SELECT'
  required: boolean
  defaultValue?: string
  options?: string[]
}
```

Define question cards with template and placeholders, and page config with headline, description, input placeholder, and ordered questions.

- [ ] **Step 2: Add API functions following existing request wrappers**

Map all five backend endpoints and return existing `ApiResponse` shapes.

- [ ] **Step 3: Run TypeScript build through the frontend Docker build**

Run: Docker Compose frontend build command.
Expected: Vite build succeeds.

### Task 4: DIY editor and agent entry

**Files:**
- Create: `ui/src/views/AgentDiy/index.vue`
- Create: `ui/src/styles/agent-diy/index.scss`
- Modify: `ui/src/router/constants.ts`
- Modify: `ui/src/router/modules/biz.ts`
- Modify: `ui/src/composables/useCardMenuItems.ts`
- Modify: `ui/src/components/agent/AgentCard.vue`
- Modify: `ui/src/views/Agent/index.vue`

**Interfaces:**
- Consumes: template parser and DIY API client.
- Produces: route `/agent/:agentId/diy` and agent-card menu event `diy`.

- [ ] **Step 1: Add a hidden editor route and card menu action**

Add route constants `AGENT_DIY` and `AGENT_DIY_EDIT`, emit `diy` from `AgentCard`, and navigate from the agent list.

- [ ] **Step 2: Build page-level settings and question-card editing**

Support headline, description, input placeholder, add/copy/delete/reorder cards, card title/description/icon/template, and enabled status.

- [ ] **Step 3: Detect placeholders from the template**

Render one configuration row per detected placeholder. Each row supports `INPUT` or `SELECT`, required, default value, and comma-separated options.

- [ ] **Step 4: Add preview, draft save, publish, and open-chat actions**

Preview performs replacement with sample/default values but never sends a chat request.

- [ ] **Step 5: Build frontend**

Run: Docker Compose frontend build command.
Expected: Vite main and docs builds succeed.

### Task 5: DIY chat welcome and confirmed send

**Files:**
- Create: `ui/src/components/chat/DiyWelcome.vue`
- Modify: `ui/src/components/chat/Welcome.vue`
- Modify: `ui/src/components/chat/ChatMain.vue`
- Modify: `ui/src/views/Chat/index.vue`
- Modify: `ui/src/router/constants.ts`
- Modify: `ui/src/router/modules/common.ts`
- Modify: `ui/src/styles/chat/index.scss`

**Interfaces:**
- Consumes: `DiyPageConfig`, template renderer, output instructions, existing `handleSend`.
- Produces: route `/chat/diy/:agentId` and event `quickSend(question: string)`.

- [ ] **Step 1: Add isolated DIY chat route**

Reuse `@/views/Chat/index.vue` under a distinct route name. Fetch published configuration only when this route is active.

- [ ] **Step 2: Render cards only for empty sessions**

Pass published config through `ChatMain` and `Welcome`. When absent, render the original welcome unchanged.

- [ ] **Step 3: Render placeholder form after card selection**

For `INPUT`, render an input; for `SELECT`, render pill-style single choice. Validate required values and prevent duplicate submission.

- [ ] **Step 4: Confirm and reuse existing send path**

On confirmation, render the template, append the system-owned output instruction, assign the result to `inputText`, and call existing `handleSend`.

- [ ] **Step 5: Add frontend utility and build verification**

Run the Node utility tests, then Docker frontend build.
Expected: utility tests pass and Vite build succeeds.

### Task 6: Local migration, initial configuration, and end-to-end verification

**Files:**
- Runtime-only: local MySQL `agent_diy_page_config` data for agent `2077682565484908546`

**Interfaces:**
- Consumes: incremental SQL and published config API.
- Produces: a working DIY page for “政企部运营问数智能体”.

- [ ] **Step 1: Apply the incremental SQL to local Docker MySQL**

Run the SQL against `k-acp-mysql`; verify the table and unique index.

- [ ] **Step 2: Insert a safe starter configuration**

Create an enabled published configuration with one editable “运营指标查询” card using `{{时间范围}}`, `{{分析指标}}`, and `{{输出格式}}`. Keep it local; do not hard-code the agent ID in product seed SQL.

- [ ] **Step 3: Rebuild and restart affected containers**

Rebuild console and frontend, recreate only those services, and confirm all existing k-acp containers remain healthy.

- [ ] **Step 4: Browser regression**

Verify editor load/save/publish, DIY card selection, required validation, direct confirmed send, history creation, new-session reset, and original `/chat/:agentId` fallback.

- [ ] **Step 5: Update Graphify and final checks**

Run `graphify update .`, `git diff --check`, focused unit tests, Docker builds, HTTP checks, and inspect the final diff.
