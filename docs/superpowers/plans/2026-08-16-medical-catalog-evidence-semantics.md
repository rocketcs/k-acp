# Medical Catalog Evidence Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the medical-catalog evidence graph explain relationships and provenance in consistent Chinese while requiring the query agent to retrieve the fields needed to support those statements.

**Architecture:** A new pure frontend presentation module converts existing evidence nodes and edges into short edge labels, complete relation sentences, and a capped summary. The existing graph model and right-panel summary consume that module without changing layout, types, or interactions. The medical-catalog skill declares required evidence projections, and the local agent prompt enforces those projections and forbids invented graph facts.

**Tech Stack:** Vue 3, TypeScript, Cytoscape element definitions, Node built-in test runner, Markdown skill package, local MySQL-backed agent configuration.

## Global Constraints

- Preserve the current right-panel and whole-page layout, existing theme colors, graph layout, MDL Tab, and graph interactions.
- Do not change `GraphifyEvidenceEnvelope`, MCP tools, Wren MDL, Neo4j data, Cypher, or backend services.
- All user-visible graph wording must be derived only from existing `evidence.nodes`, `evidence.edges`, and readable source-node labels.
- Never expose a raw relation code, internal node ID, import-batch ID, hash, or physical table name as business-facing graph text.
- Update only the local agent configuration. Use `./scripts/with-environment.sh local --require mysql -- ...` for every local MySQL operation; do not target test or production.
- The workspace already has unrelated uncommitted changes under `ui/src/features/graphify-data-query/`; preserve them. When a task modifies an already-dirty file, stage only this task's hunk with `git add -p`; never stage the whole file.

---

## Target File Structure

- Create: `ui/src/features/graphify-data-query/evidencePresentation.ts` — pure relation label, sentence, and summary functions.
- Create: `ui/src/features/graphify-data-query/evidencePresentation.test.ts` — behavioral tests for the presentation contract.
- Modify: `ui/src/features/graphify-data-query/evidenceGraphModel.ts` — use semantic edge labels in Cytoscape elements.
- Modify: `ui/src/features/graphify-data-query/evidenceGraphModel.test.ts` — assert graph element labels use the presentation contract.
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue` — render the fixed-location relation summary and use complete relation sentences in the existing selected-node summary.
- Modify: `skills/skills/medical-catalog-question-semantics/SKILL.md` — declare evidence projections for medical-catalog intents.
- Modify local configuration only: `skill_file` row bound to `medical-catalog-question-semantics` and `agent_definition.system_prompt` for `default-graphify-data-query`.

## Task 1: Evidence Presentation Contract

**Files:**

- Create: `ui/src/features/graphify-data-query/evidencePresentation.ts`
- Test: `ui/src/features/graphify-data-query/evidencePresentation.test.ts`
- Modify: `ui/package.json`

**Interfaces:**

```ts
export function graphEdgeLabel(
  edge: GraphifyEvidenceEdge,
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): string

export function graphRelationSentence(
  edge: GraphifyEvidenceEdge,
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): string | null

export function graphRelationSummary(
  envelope: GraphifyEvidenceEnvelope,
  maxSentences?: number,
): string | null
```

- [ ] **Step 1: Write the failing test**

Create `evidencePresentation.test.ts` with an envelope containing product `覆膜气管支架`, registration `国械注准20173134669`, organization `淮安市西格玛医用实业有限公司`, source file `耗材谈判记录`, and these edges:

```ts
test('describes a product to registration relation with a business label', () => {
  assert.equal(graphEdgeLabel(edges[0]!, nodeById), '对应注册备案')
  assert.equal(
    graphRelationSentence(edges[0]!, nodeById),
    '覆膜气管支架对应注册备案号：国械注准20173134669。',
  )
})

test('describes a product to organization relation as production', () => {
  assert.equal(graphEdgeLabel(edges[1]!, nodeById), '生产企业')
  assert.equal(
    graphRelationSentence(edges[1]!, nodeById),
    '覆膜气管支架由淮安市西格玛医用实业有限公司生产。',
  )
})

test('summarizes at most three readable relations and hides internal lineage ids', () => {
  const summary = graphRelationSummary(envelope)
  assert.match(summary ?? '', /覆膜气管支架对应注册备案号/)
  assert.match(summary ?? '', /耗材谈判记录/)
  assert.doesNotMatch(summary ?? '', /record:C1|import:batch|[a-f0-9]{32}/i)
})

test('returns a safe fallback for an unknown readable relation', () => {
  const unknown = { id: 'unknown', source: 'product', target: 'organization', label: 'CUSTOM_LINK', kind: 'business' } as const
  assert.equal(graphEdgeLabel(unknown, nodeById), '相关')
  assert.equal(graphRelationSentence(unknown, nodeById), '覆膜气管支架与淮安市西格玛医用实业有限公司相关。')
})

test('does not create a sentence when a relation endpoint is not readable', () => {
  const hidden = { id: 'hidden', source: 'product', target: 'missing', label: '对应', kind: 'business' } as const
  assert.equal(graphRelationSentence(hidden, nodeById), null)
})
```

Add the test file to the existing `test:graphify-data-query` script in `ui/package.json`.

- [ ] **Step 2: Run the test to verify it fails**

Run from `ui/`:

```bash
node --experimental-strip-types --test src/features/graphify-data-query/evidencePresentation.test.ts
```

Expected: FAIL because `./evidencePresentation.ts` does not exist.

- [ ] **Step 3: Implement the presentation module**

Create `evidencePresentation.ts` with these rules:

```ts
const NODE_HEADINGS: Record<string, string> = {
  registration: '注册备案号',
  organization: '生产企业',
  base: '基础耗材',
  concept: '映射概念',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
}

const readable = (node: GraphifyEvidenceNode | undefined): node is GraphifyEvidenceNode =>
  Boolean(node?.label.trim()) && !/^(record:|source:|import:|[a-f0-9]{32,})/i.test(node!.label)
```

Implement `graphEdgeLabel` with this ordered mapping:

```ts
if (edge.kind === 'provenance') {
  return target?.kind === 'source_file' ? '收录于来源工作簿' : '原始记录佐证'
}
if (edge.label === '对应' && target?.kind === 'registration') return '对应注册备案'
if (edge.label === '生产' || target?.kind === 'organization') return '生产企业'
if (edge.label === '归类' || target?.kind === 'base') return '归属分类'
if (edge.label.includes('映射') || target?.kind === 'concept') return '映射至'
return edge.kind === 'business' ? '相关' : edge.label
```

Implement `graphRelationSentence` using only readable endpoints. Prefer the source-node label for provenance edges. Use the following exact sentence forms:

```ts
// registration
`${source.label}对应注册备案号：${target.label}。`
// organization
`${source.label}由${target.label}生产。`
// base
`${source.label}归属${target.label}。`
// concept
`${source.label}映射至${target.label}。`
// provenance
`该信息由${target.label}佐证。`
// fallback
`${source.label}与${target.label}相关。`
```

Implement `graphRelationSummary` by building a node map, sorting business edges before provenance edges, de-duplicating identical sentences, keeping `maxSentences` (default `3`), and joining sentences with no invented text. When extra readable relations remain, append `另有 N 条关联。`.

- [ ] **Step 4: Run the focused test to verify it passes**

```bash
node --experimental-strip-types --test src/features/graphify-data-query/evidencePresentation.test.ts
```

Expected: PASS with five tests.

- [ ] **Step 5: Run the existing graph tests**

```bash
npm run test:graphify-data-query
```

Expected: PASS; no existing evidence parsing test changes behavior.

- [ ] **Step 6: Commit the isolated module**

```bash
git add ui/src/features/graphify-data-query/evidencePresentation.ts ui/src/features/graphify-data-query/evidencePresentation.test.ts ui/package.json
git commit -m "feat: add evidence relation presentation"
```

## Task 2: Render Semantic Labels in the Existing Right Panel

**Files:**

- Modify: `ui/src/features/graphify-data-query/evidenceGraphModel.ts`
- Modify: `ui/src/features/graphify-data-query/evidenceGraphModel.test.ts`
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue`

**Interfaces:**

- Consumes `graphEdgeLabel`, `graphRelationSentence`, and `graphRelationSummary` from Task 1.
- Keeps the existing `evidenceGraphModel(envelope, opts): ElementDefinition[]` signature unchanged.
- Keeps the existing `GraphifyEvidenceGraph` props, Cytoscape configuration, CSS palette, layout, and interactions unchanged.

- [ ] **Step 1: Write failing model assertions**

Append to `evidenceGraphModel.test.ts`:

```ts
test('uses readable semantic labels for business and provenance edges', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  const labels = elements.filter((item) => item.data?.source).map((item) => String(item.data?.label))
  assert.ok(labels.includes('对应注册备案'))
  assert.ok(labels.includes('原始记录佐证'))
  assert.equal(labels.includes('业务关联'), false)
})
```

- [ ] **Step 2: Verify the assertion fails**

```bash
node --experimental-strip-types --test src/features/graphify-data-query/evidenceGraphModel.test.ts
```

Expected: FAIL because the current graph element uses raw edge label `对应`.

- [ ] **Step 3: Apply the graph-model integration**

In `evidenceGraphModel.ts`, build `const nodeById = new Map(nodes.map((node) => [node.id, node]))` after the final visible-node list. Import `graphEdgeLabel`, and replace only the edge element `data.label` assignment:

```ts
label: graphEdgeLabel(edge, nodeById),
```

Do not alter node positions, Cytoscape style selectors, view filtering, or converted model query edges.

In `GraphifyDataQueryPage.vue`:

1. Import `graphRelationSentence` and `graphRelationSummary`.
2. Add `const relationSummary = computed(() => activeEvidence.value ? graphRelationSummary(activeEvidence.value) : null)`.
3. Replace the existing construction of each `selectedNodeSummary.relations` text with `graphRelationSentence(edge, nodeById)`, filtering nulls and preserving the existing four-item cap.
4. Add exactly one conditional line inside the existing `.question-summary`, after the result-count `span`:

```vue
<p v-if="relationSummary" class="relation-summary">关系摘要：{{ relationSummary }}</p>
```

5. Add only `.relation-summary` typography rules that inherit the existing panel colors and spacing; do not change existing layout, palette, dimensions, or selectors.

- [ ] **Step 4: Verify model and presentation tests pass**

```bash
npm run test:graphify-data-query
```

Expected: PASS, including the new semantic-label assertion and Task 1 tests.

- [ ] **Step 5: Run static frontend checks**

```bash
npm run type-check
npm run build:main
```

Expected: both commands exit `0`.

- [ ] **Step 6: Manually verify the existing local UI**

Open `http://127.0.0.1:23080/web/#/chat/diy/graphify-data-query`, submit a query returning a registration relation, and verify:

1. The right-panel layout and theme colors are unchanged.
2. The edge says `对应注册备案`.
3. The question summary says `关系摘要：…` and does not expose an internal ID.
4. Selecting the registration node uses a complete Chinese relation sentence.

- [ ] **Step 7: Commit only integration files**

```bash
git add ui/src/features/graphify-data-query/evidenceGraphModel.ts ui/src/features/graphify-data-query/evidenceGraphModel.test.ts
git add -p ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue
git commit -m "feat: render readable evidence graph semantics"
```

## Task 3: Require Evidence Projections in the Medical-Catalog Skill

**Files:**

- Modify: `skills/skills/medical-catalog-question-semantics/SKILL.md`
- Modify local configuration only: the `SKILL_MD` `skill_file` bound to the `medical-catalog-question-semantics` package for `default-graphify-data-query`.

**Interfaces:**

The skill JSON output keeps every existing field and adds:

```json
"evidence_columns": ["catalog_name", "registration_no", "source_record_id"]
```

`evidence_columns` must be a subset of `published_columns` and contain only the existing published-field whitelist values.

- [ ] **Step 1: Add a failing documentation-contract test**

Create `skills/skills/medical-catalog-question-semantics/skill-contract.test.mjs`:

```js
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const skill = await readFile(new URL('./SKILL.md', import.meta.url), 'utf8')

test('medical catalog skill requires evidence projections for relationship queries', () => {
  assert.match(skill, /evidence_columns/)
  assert.match(skill, /registration_no.*source_record_id/s)
  assert.match(skill, /consumable_enterprise.*source_record_id/s)
  assert.match(skill, /category_level_1.*source_record_id/s)
})
```

- [ ] **Step 2: Verify the contract test fails**

```bash
node --test skills/skills/medical-catalog-question-semantics/skill-contract.test.mjs
```

Expected: FAIL because the current skill has no `evidence_columns` section.

- [ ] **Step 3: Update the checked-in skill source**

After the existing field-mapping workflow, add an “证据字段要求” section with this exact table:

| 问题语义 | `evidence_columns` |
|---|---|
| 注册备案号/批准文号 | `catalog_name`, `registration_no`, `source_record_id` |
| 药品/耗材企业 | `catalog_name`, `manufacturer` or `consumable_enterprise`, `source_record_id` |
| 耗材分类 | `catalog_name`, `category_level_1`, `category_level_2`, `category_level_3`, `source_record_id` |
| 支付类别/限额/价格 | `catalog_name`, asked field, `source_record_id`; prices also require `price_semantics` |
| 有效期/政策 | `catalog_name`, `valid_from`, `valid_to` or `policy_no`, `source_record_id` |

State that inaccessible `source_record_id` must be reported as unavailable and never fabricated. Amend the JSON example so `published_columns` contains the same fields and includes `evidence_columns` with the exact registration sample shown above.

- [ ] **Step 4: Verify the source contract passes**

```bash
node --test skills/skills/medical-catalog-question-semantics/skill-contract.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Publish the checked-in skill to the local bound package**

Run this local-only, idempotent update:

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  content=$(base64 < skills/skills/medical-catalog-question-semantics/SKILL.md | tr -d "\n")
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw -e "
    UPDATE skill_file sf
    JOIN agent_skill_packages asp ON asp.skill_package_id = sf.skill_id
    JOIN agent_definition ad ON ad.id = asp.agent_definition_id
    SET sf.content = FROM_BASE64('\''$content'\'')
    WHERE ad.agent_code = '\''default-graphify-data-query'\''
      AND sf.file_type = '\''SKILL_MD'\''
      AND sf.file_name = '\''SKILL.md'\''
      AND sf.enabled = 1;
  "
'
```

- [ ] **Step 6: Verify the published skill matches the checked-in source**

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  local_sha=$(shasum -a 256 skills/skills/medical-catalog-question-semantics/SKILL.md | cut -d " " -f 1)
  db_sha=$(docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --skip-column-names -e "
    SELECT SHA2(sf.content, 256)
    FROM skill_file sf
    JOIN agent_skill_packages asp ON asp.skill_package_id = sf.skill_id
    JOIN agent_definition ad ON ad.id = asp.agent_definition_id
    WHERE ad.agent_code = '\''default-graphify-data-query'\''
      AND sf.file_type = '\''SKILL_MD'\'' AND sf.file_name = '\''SKILL.md'\'' AND sf.enabled = 1;
  ")
  test "$local_sha" = "$db_sha"
'
```

Expected: exit `0`.

- [ ] **Step 7: Commit only checked-in skill source and its contract test**

```bash
git add skills/skills/medical-catalog-question-semantics/SKILL.md skills/skills/medical-catalog-question-semantics/skill-contract.test.mjs
git commit -m "feat: require medical catalog query evidence fields"
```

## Task 4: Enforce Evidence Discipline in the Local Agent Prompt

**Files:**

- Modify local configuration only: `agent_definition.system_prompt` where `agent_code = 'default-graphify-data-query'`.

**Interfaces:**

The existing inline prompt remains authoritative. Add a versioned, idempotent block; do not replace unrelated prompt content or change `follow_template`.

- [ ] **Step 1: Capture a local prompt backup and write a failing verification query**

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw -e "
    SELECT id, agent_code, follow_template, system_prompt
    FROM agent_definition
    WHERE agent_code = '\''default-graphify-data-query'\'';
  " > /tmp/default-graphify-data-query-system-prompt-before.sql.tsv
'
```

Then verify the marker is absent:

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --skip-column-names -e "
    SELECT LOCATE('\''<!-- medical-catalog-evidence-semantics:v1 -->'\'', system_prompt)
    FROM agent_definition
    WHERE agent_code = '\''default-graphify-data-query'\'';
  "
'
```

Expected: `0` before this task.

- [ ] **Step 2: Add the idempotent prompt block**

Execute in the local environment only:

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 <<'\''SQL'\''
UPDATE agent_definition
SET system_prompt = CONCAT(
  REGEXP_REPLACE(
    COALESCE(system_prompt, '\'''\''),
    '\''(?s)<!-- medical-catalog-evidence-semantics:v1 -->.*?<!-- /medical-catalog-evidence-semantics:v1 -->'\'',
    '\'''\''
  ),
  '\''\n\n<!-- medical-catalog-evidence-semantics:v1 -->\n## 医疗目录证据完整性\n\n- 涉及具体医疗目录事实时，必须执行 `run_template_query` 或经 `query_preflight` 允许的 `query`；不得仅凭 `semantic_context` 回答。\n- 最终查询除回答字段外，必须投影语义 skill 的 `evidence_columns`。\n- `catalog_name` 是业务主体；缺少该字段时，不得声称结果具有可读业务关系。\n- `source_record_id` 仅用于生成来源追溯，不得在业务回答、图谱节点或摘要中直接展示。\n- 不得编造节点、关系、来源文件、工作表或行号；未返回来源时明确说明“本次结果未提供可追溯来源”。\n<!-- /medical-catalog-evidence-semantics:v1 -->'\''
)
WHERE agent_code = '\''default-graphify-data-query'\'';
SQL
'
```

- [ ] **Step 3: Verify the local prompt contains exactly one active constraint block**

```bash
./scripts/with-environment.sh local --require mysql -- sh -c '
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" k-acp-mysql mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --skip-column-names -e "
    SELECT
      (CHAR_LENGTH(system_prompt) - CHAR_LENGTH(REPLACE(system_prompt, '\''<!-- medical-catalog-evidence-semantics:v1 -->'\'', '\'''\''))) / CHAR_LENGTH('\''<!-- medical-catalog-evidence-semantics:v1 -->'\''),
      LOCATE('\''evidence_columns'\'', system_prompt) > 0,
      LOCATE('\''不得编造节点、关系、来源文件、工作表或行号'\'', system_prompt) > 0
    FROM agent_definition
    WHERE agent_code = '\''default-graphify-data-query'\'';
  "
'
```

Expected: one opening marker, `1`, `1`.

- [ ] **Step 4: Validate the end-to-end local agent behavior**

In `http://127.0.0.1:23080/web/#/chat/diy/graphify-data-query`, submit these three questions in new conversations:

1. `覆膜气管支架的注册备案号是什么？`
2. `覆膜气管支架的生产企业是什么？`
3. `覆膜气管支架属于什么耗材分类？`

For each completed turn, confirm the final tool result is an executed evidence envelope, the result includes `catalog_name`, the requested relation field, and `source_record_id` where available, and the right panel contains no raw source record ID in user-facing text.

- [ ] **Step 5: Record local configuration verification without staging database state**

```bash
git status --short
```

Expected: only the checked-in files from Tasks 1–3 and no generated database dump or credential-bearing file are staged.

## Final Verification

- [ ] Run `npm run test:graphify-data-query`, `npm run type-check`, and `npm run build:main` from `ui/`.
- [ ] Run `node --test skills/skills/medical-catalog-question-semantics/skill-contract.test.mjs`.
- [ ] Run the Task 3 database checksum verification and Task 4 prompt-marker verification against `local` only.
- [ ] Re-run the three browser queries and confirm no layout or theme changes.
- [ ] Run `git diff --check` and `git status --short`; do not stage user-owned modifications outside task file lists.

## Plan Self-Review

- Spec coverage: Task 1 and Task 2 implement the fixed frontend relationship vocabulary, sentences, summary, and no-layout/no-theme constraint. Task 3 requires evidence fields from the skill. Task 4 enforces final-query and no-invention discipline in the local agent prompt.
- Placeholder scan: no `TODO`, `TBD`, or unspecified command remains.
- Type consistency: Task 1 exports are the exact imports consumed by Task 2; no evidence-envelope type or MCP contract changes are introduced.
