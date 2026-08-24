# Graphify ECharts Graph Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current医保问数知识图谱 Cytoscape rendering with an ECharts graph renderer while keeping the existing answer-level entry point and chat flow unchanged.

**Architecture:** Preserve the current graphify chat pipeline (`evidence_subgraph` -> answer-bound graph button -> modal) and only refactor the display stack. Introduce a renderer-neutral `GraphView` protocol, adapt existing `GraphifyEvidenceEnvelope` data into `GraphView`, then map `GraphView` into ECharts `graph` series data. Keep the current modal entry and answer binding so only the graph presentation changes.

**Tech Stack:** Vue 3, TypeScript, Ant Design Vue, vue-echarts / ECharts graph, existing graphify-data-query feature modules, Node built-in test runner, vue-tsc.

## Global Constraints

- Keep the current医保问数问答流程, answer binding, and `查看知识图谱` entry unchanged.
- Do not change backend APIs, `evidence_subgraph` tool contracts, or session evidence reconstruction.
- Scope all behavior changes to `ui/src/features/graphify-data-query/` and explicitly opt-in wiring so other agents/chats remain unchanged.
- Preserve the current “answer-level graph” semantics: one answer opens only its own graph.
- First version must use ECharts-compatible graph data with stable precomputed coordinates; do not switch to force layout in phase 1.
- Reuse existing graph filtering semantics (drop query-process noise, drop isolated nodes, preserve focused/full view logic).
- Use TDD: each new adapter/behavior needs a failing test first.
- After code changes run `cd ui && npm run test:graphify-data-query`, `cd ui && npm run type-check`, and `graphify update .`.

---

## File Structure

### New files
- `ui/src/features/graphify-data-query/graphView.ts`
  - Renderer-neutral graph protocol used by future display layers.
- `ui/src/features/graphify-data-query/graphViewAdapter.ts`
  - Converts `GraphifyEvidenceEnvelope` + view options into `GraphView`.
- `ui/src/features/graphify-data-query/echartsGraphAdapter.ts`
  - Converts `GraphView` into ECharts `graph` series data.
- `ui/src/features/graphify-data-query/graphViewAdapter.test.ts`
  - Pure tests for GraphView generation.
- `ui/src/features/graphify-data-query/echartsGraphAdapter.test.ts`
  - Pure tests for ECharts mapping.
- `ui/src/features/graphify-data-query/GraphifyEchartsGraph.vue`
  - New ECharts renderer component used inside the existing modal.

### Modified files
- `ui/src/features/graphify-data-query/evidenceGraphModel.ts`
  - Keep selection logic, optionally expose reusable position helpers or move them to adapter-safe exports.
- `ui/src/features/graphify-data-query/GraphifyGraphView.vue`
  - Switch modal body from Cytoscape component to ECharts component.
- `ui/src/features/graphify-data-query/GraphifyAssistantMessage.vue`
  - Keep button entry, pass adapted graph payload to modal.
- `ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`
  - Extend structural assertions to verify the new display wiring.
- `ui/package.json`
  - Add new pure test files to `test:graphify-data-query` if needed.

### Existing files intentionally preserved
- `ui/src/features/graphify-data-query/GraphifyDataQueryChat.vue`
- `ui/src/features/graphify-data-query/sessionEvidence.ts`
- `ui/src/features/graphify-data-query/turnEvidence.ts`

These continue to own runtime tool binding and answer-level graph provenance.

## Task 1: Define renderer-neutral GraphView protocol

**Files:**
- Create: `ui/src/features/graphify-data-query/graphView.ts`
- Test: none (types only; covered indirectly by later tests)

**Interfaces:**
- Consumes: existing graphify node/edge semantics from `ui/src/features/graphify-data-query/types.ts`
- Produces:
  - `export type GraphView`
  - `export type GraphViewNode`
  - `export type GraphViewEdge`
  - `export type GraphViewCategory`
  - `export type GraphViewBuildOptions`

- [ ] **Step 1: Create the GraphView type file**

```ts
export type GraphViewBuildOptions = {
  viewMode: 'focused' | 'full'
  showFields?: boolean
  visibleIds?: ReadonlySet<string>
}

export type GraphViewStats = {
  nodeCount: number
  edgeCount: number
  truncated: boolean
  limit?: number
}

export type GraphViewCategory = {
  key: string
  name: string
  color?: string
  icon?: string
}

export type GraphViewNode = {
  id: string
  entityType: string
  label: string
  subtitle?: string
  badges?: string[]
  rawKind: string
  domain?: string
  displayProperties?: Array<{ label: string; value: string }>
  expandable?: boolean
  style?: {
    color?: string
    borderColor?: string
    symbolSize?: number
    categoryKey?: string
  }
  position?: { x: number; y: number }
}

export type GraphViewEdge = {
  id: string
  source: string
  target: string
  relationType: string
  label: string
  style?: {
    color?: string
    lineType?: 'solid' | 'dashed' | 'dotted'
    width?: number
  }
}

export type GraphView = {
  id: string
  title: string
  summary?: string
  nodes: GraphViewNode[]
  edges: GraphViewEdge[]
  categories: GraphViewCategory[]
  stats: GraphViewStats
  warnings?: Array<{ code: string; message: string }>
}
```

- [ ] **Step 2: Commit**

```bash
git add ui/src/features/graphify-data-query/graphView.ts
git commit -m "feat: define graph view protocol for graphify"
```

## Task 2: Build GraphView from GraphifyEvidenceEnvelope

**Files:**
- Create: `ui/src/features/graphify-data-query/graphViewAdapter.ts`
- Create: `ui/src/features/graphify-data-query/graphViewAdapter.test.ts`
- Modify: `ui/src/features/graphify-data-query/evidenceGraphModel.ts`

**Interfaces:**
- Consumes:
  - `GraphifyEvidenceEnvelope` from `ui/src/features/graphify-data-query/types.ts`
  - `selectGraph(...)` from `ui/src/features/graphify-data-query/evidenceGraphModel.ts`
  - `GraphViewBuildOptions`, `GraphView` from `ui/src/features/graphify-data-query/graphView.ts`
- Produces:
  - `export function buildGraphView(envelope: GraphifyEvidenceEnvelope, options: GraphViewBuildOptions): GraphView`
  - reusable position helper export from `evidenceGraphModel.ts` if needed

- [ ] **Step 1: Write the failing test for focused GraphView generation**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { buildGraphView } from './graphViewAdapter'
import type { GraphifyEvidenceEnvelope } from './types'

const envelope: GraphifyEvidenceEnvelope = {
  status: 'executed',
  trace_id: 'trace-1',
  dataset_id: 'medical_catalog',
  question: '查询测试目录项',
  result: { columns: [], rows: [], truncated: false },
  semantic_context: {
    graph_version: 'v1',
    recommended_models: [],
    recommended_columns: [],
    domain_labels: { DRUG: '药品' },
    domain_headings: { DRUG: '药品目录项' },
    rules: [],
    provenance: {},
  },
  evidence: {
    source_record_ids: [],
    nodes: [
      { id: 'product:1', label: '阿莫西林胶囊', kind: 'product', domain: 'DRUG' },
      { id: 'org:1', label: '某制药公司', kind: 'organization' },
    ],
    edges: [
      { id: 'edge:1', source: 'product:1', target: 'org:1', label: '生产企业', kind: 'business' },
    ],
  },
}

test('buildGraphView returns business-facing nodes, edges, categories, and positions', () => {
  const graph = buildGraphView(envelope, { viewMode: 'focused' })

  assert.equal(graph.id, 'trace-1')
  assert.equal(graph.title, '查询测试目录项')
  assert.equal(graph.nodes.length, 2)
  assert.equal(graph.edges.length, 1)
  assert.ok(graph.categories.some((category) => category.key === 'product'))
  assert.ok(graph.categories.some((category) => category.key === 'organization'))
  assert.equal(graph.nodes[0]?.position?.x !== undefined, true)
  assert.equal(graph.nodes[0]?.position?.y !== undefined, true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/graphViewAdapter.test.ts`
Expected: FAIL with module/function missing error.

- [ ] **Step 3: Add reusable position helper export if needed**

If `fullViewPositions` remains private, export it (or a new `graphNodePositions`) from `evidenceGraphModel.ts` instead of duplicating layout logic.

- [ ] **Step 4: Write minimal GraphView adapter implementation**

Implementation requirements:
- Use `selectGraph(envelope, ...)` for filtered node/edge selection.
- Reuse current layout behavior:
  - `viewMode === 'full'` -> current compact full-view coordinates
  - otherwise -> dagre LR coordinates
- Map node `kind` to business-facing category keys.
- Build `categories[]` once from the selected nodes.
- Set `stats.nodeCount` / `stats.edgeCount` from selected graph sizes.
- Use `trace_id` as `GraphView.id`.
- Use `question` as `GraphView.title`.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/graphViewAdapter.test.ts`
Expected: PASS

- [ ] **Step 6: Add a second failing test for isolated/query-process node filtering**

```ts
test('buildGraphView excludes isolated and query-process-only nodes', () => {
  const graph = buildGraphView({
    ...envelope,
    evidence: {
      source_record_ids: [],
      nodes: [
        { id: 'product:1', label: '阿莫西林胶囊', kind: 'product', domain: 'DRUG' },
        { id: 'record:1', label: '来源记录', kind: 'record' },
        { id: 'source:1', label: '来源文件', kind: 'source' },
        { id: 'concept:1', label: '孤立概念', kind: 'concept' },
      ],
      edges: [],
    },
  }, { viewMode: 'focused' })

  assert.equal(graph.nodes.some((node) => node.id === 'record:1'), false)
  assert.equal(graph.nodes.some((node) => node.id === 'source:1'), false)
  assert.equal(graph.nodes.some((node) => node.id === 'concept:1'), false)
})
```

- [ ] **Step 7: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/graphViewAdapter.test.ts`
Expected: FAIL on filtering assertion.

- [ ] **Step 8: Implement minimal filtering fix and verify green**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/graphViewAdapter.test.ts`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add ui/src/features/graphify-data-query/evidenceGraphModel.ts \
  ui/src/features/graphify-data-query/graphViewAdapter.ts \
  ui/src/features/graphify-data-query/graphViewAdapter.test.ts
git commit -m "feat: adapt graphify evidence into graph view"
```

## Task 3: Map GraphView into ECharts graph data

**Files:**
- Create: `ui/src/features/graphify-data-query/echartsGraphAdapter.ts`
- Create: `ui/src/features/graphify-data-query/echartsGraphAdapter.test.ts`

**Interfaces:**
- Consumes: `GraphView` from `ui/src/features/graphify-data-query/graphView.ts`
- Produces:
  - `export type EChartsGraphData`
  - `export function toEchartsGraphData(graphView: GraphView): EChartsGraphData`

- [ ] **Step 1: Write the failing test for GraphView -> ECharts mapping**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { toEchartsGraphData } from './echartsGraphAdapter'
import type { GraphView } from './graphView'

const graphView: GraphView = {
  id: 'trace-1',
  title: '测试图谱',
  nodes: [
    {
      id: 'product:1',
      entityType: 'product',
      rawKind: 'product',
      label: '阿莫西林胶囊',
      style: { color: '#C98B37', borderColor: '#8A5410', categoryKey: 'product', symbolSize: 64 },
      position: { x: 60, y: 120 },
    },
    {
      id: 'org:1',
      entityType: 'organization',
      rawKind: 'organization',
      label: '某制药公司',
      style: { color: '#27AE60', borderColor: '#1D6E46', categoryKey: 'organization', symbolSize: 56 },
      position: { x: 260, y: 120 },
    },
  ],
  edges: [
    {
      id: 'edge:1',
      source: 'product:1',
      target: 'org:1',
      relationType: 'business',
      label: '生产企业',
      style: { color: '#4C9DB8', lineType: 'solid', width: 2 },
    },
  ],
  categories: [
    { key: 'product', name: '目录项', color: '#C98B37' },
    { key: 'organization', name: '企业', color: '#27AE60' },
  ],
  stats: { nodeCount: 2, edgeCount: 1, truncated: false },
}

test('toEchartsGraphData returns categories, positioned nodes, and labeled links', () => {
  const chart = toEchartsGraphData(graphView)

  assert.equal(chart.categories.length, 2)
  assert.equal(chart.data.length, 2)
  assert.equal(chart.links.length, 1)
  assert.equal(chart.data[0]?.x, 60)
  assert.equal(chart.data[0]?.y, 120)
  assert.equal(chart.links[0]?.value, '生产企业')
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/echartsGraphAdapter.test.ts`
Expected: FAIL with module/function missing error.

- [ ] **Step 3: Write minimal adapter implementation**

Implementation requirements:
- Build stable category index map from `graphView.categories`.
- Map `GraphViewNode.position` to ECharts `x/y`.
- Map `GraphViewNode.style.symbolSize` to ECharts `symbolSize`.
- Store node metadata inside `value` for tooltip/detail usage.
- Map `GraphViewEdge.style.lineType` to ECharts `lineStyle.type`.
- Emit `links[].value = edge.label`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/echartsGraphAdapter.test.ts`
Expected: PASS

- [ ] **Step 5: Add a failing test for business-facing category names and edge styling**

```ts
test('toEchartsGraphData preserves business-facing category names and edge styles', () => {
  const chart = toEchartsGraphData(graphView)

  assert.equal(chart.categories[0]?.name, '目录项')
  assert.equal(chart.links[0]?.lineStyle?.type, 'solid')
  assert.equal(chart.links[0]?.lineStyle?.color, '#4C9DB8')
})
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/echartsGraphAdapter.test.ts`
Expected: FAIL on style assertions.

- [ ] **Step 7: Implement minimal style mapping and verify green**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/echartsGraphAdapter.test.ts`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add ui/src/features/graphify-data-query/echartsGraphAdapter.ts \
  ui/src/features/graphify-data-query/echartsGraphAdapter.test.ts
git commit -m "feat: map graph view into echarts graph data"
```

## Task 4: Implement the ECharts graph renderer component

**Files:**
- Create: `ui/src/features/graphify-data-query/GraphifyEchartsGraph.vue`
- Test: structural assertions in `ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`

**Interfaces:**
- Consumes:
  - `GraphView`
  - `toEchartsGraphData(graphView)`
- Produces:
  - Vue component props:
    - `graphView: GraphView`
    - `fullscreen: boolean`
    - `showSummary?: boolean`
  - exposed methods:
    - `fit(): void`
    - `zoomIn(): void`
    - `zoomOut(): void`
    - `relayout(): void`

- [ ] **Step 1: Write the failing structural test**

Add assertions to `GraphifyDataQueryChatEmptyState.test.ts` for:
- import/use of `GraphifyEchartsGraph.vue`
- use of ECharts graph series data adapter
- absence of direct Cytoscape use inside the new renderer

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`
Expected: FAIL on missing component/assertions.

- [ ] **Step 3: Implement minimal renderer component**

Implementation requirements:
- Use `vue-echarts` already present in project dependencies.
- Build chart option with:
  - `series: [{ type: 'graph', layout: 'none', data, links, categories }]`
- Enable node labels and edge labels.
- Implement tooltip formatter using node `value` metadata.
- Expose basic methods with ECharts dispatchAction or setOption refresh.
- Render summary bar only when `showSummary` is true.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`
Expected: PASS for new renderer structure.

- [ ] **Step 5: Commit**

```bash
git add ui/src/features/graphify-data-query/GraphifyEchartsGraph.vue \
  ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts
git commit -m "feat: add echarts graph renderer for graphify"
```

## Task 5: Switch the graph modal to GraphView + ECharts renderer

**Files:**
- Modify: `ui/src/features/graphify-data-query/GraphifyGraphView.vue`
- Modify: `ui/src/features/graphify-data-query/GraphifyAssistantMessage.vue`
- Test: `ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`

**Interfaces:**
- Consumes:
  - `buildGraphView(...)`
  - `GraphifyEchartsGraph.vue`
- Produces:
  - unchanged answer-level `查看知识图谱` entry
  - modal rendering through `GraphView`

- [ ] **Step 1: Write the failing structural test for modal wiring**

Add assertions that:
- `GraphifyAssistantMessage.vue` computes a `GraphView` payload for the dialog
- `GraphifyGraphView.vue` renders `GraphifyEchartsGraph`
- the answer button remains gated by real `neo4jGraph` availability

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`
Expected: FAIL on missing GraphView / renderer wiring.

- [ ] **Step 3: Implement minimal modal migration**

Implementation requirements:
- Keep `hasNeo4jGraph` logic unchanged.
- Replace the old graph evidence payload passed into the dialog with a `GraphView` built from the answer evidence.
- Keep existing toolbar buttons in the modal where possible.
- Keep dialog open/close UX unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ui/src/features/graphify-data-query/GraphifyGraphView.vue \
  ui/src/features/graphify-data-query/GraphifyAssistantMessage.vue \
  ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts
git commit -m "feat: switch graphify modal to echarts graph view"
```

## Task 6: Full regression verification and local frontend refresh

**Files:**
- Modify: `ui/package.json` (only if test script needs extension)
- Verify: `ui/src/features/graphify-data-query/*`

**Interfaces:**
- Consumes: all previous tasks
- Produces: verified local deployment on port 23080

- [ ] **Step 1: Add new pure test files to the feature test script if missing**

Ensure `ui/package.json` `test:graphify-data-query` includes:
- `src/features/graphify-data-query/graphViewAdapter.test.ts`
- `src/features/graphify-data-query/echartsGraphAdapter.test.ts`

- [ ] **Step 2: Run focused feature tests**

Run:
```bash
cd ui && npm run test:graphify-data-query
```
Expected: PASS, 0 failures.

- [ ] **Step 3: Run type-check**

Run:
```bash
cd ui && npm run type-check
```
Expected: success with exit code 0.

- [ ] **Step 4: Update graphify output**

Run:
```bash
graphify update .
```
Expected: graphify-out updated without fatal errors.

- [ ] **Step 5: Refresh local frontend container**

Run:
```bash
cd docker && docker compose --project-name k-acp-local --env-file .env.kacp \
  -f docker-compose-simple.yml -f docker-compose-kacp-local.yml \
  up -d --build apboa-frontend
```
Expected: `k-acp-frontend` returns to `healthy`.

- [ ] **Step 6: Browser verification checklist**

Manual verify on:
`http://127.0.0.1:23080/web/#/chat/diy/graphify-data-query`

Check:
- answer still shows `查看知识图谱`
- dialog opens normally
- graph renders via ECharts
- nodes and relations are visible
- non-graphify chats remain unchanged

- [ ] **Step 7: Commit**

```bash
git add ui/package.json docs/superpowers/plans/2026-08-22-graphify-echarts-graph-migration.md
git commit -m "test: verify graphify echarts graph migration"
```

## Self-Review

### Spec coverage
- Display-only migration: covered by Tasks 2-5.
- ECharts-compatible structure: covered by Task 3.
- Keep current answer entry: covered by Task 5.
- No backend/API changes: enforced in Global Constraints.
- Scope to graphify page only: enforced in Global Constraints and file list.

### Placeholder scan
- No TBD/TODO placeholders left.
- Every code-changing task includes concrete function names, files, and commands.

### Type consistency
- `GraphView` is introduced once in Task 1 and reused consistently.
- `buildGraphView(...)` and `toEchartsGraphData(...)` are defined before later tasks consume them.
- `GraphifyEchartsGraph.vue` takes `graphView` instead of renderer-specific payloads.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-08-22-graphify-echarts-graph-migration.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
