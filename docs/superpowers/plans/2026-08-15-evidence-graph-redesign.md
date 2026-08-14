# Evidence Graph Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the evidence graph (`GraphifyEvidenceGraph.vue`) on dagre layered auto-layout, showing only business-logic relations (对应/生产/归类/证据支持/包含), with hover neighborhood highlighting, click-to-focus, tooltips, true relation filtering, and a container-controlled focused/full view mode.

**Architecture:** Extract pure functions (`dagreLayout`, `focusSelection`, `evidenceStyles`, `evidenceGraphModel`) from the monolithic cytoscape component; the component becomes a controlled renderer driven by `viewMode`/`showFields`/`relationFilter` props. Query-process nodes (`record`, `source`, `query` edges) are filtered out of the graph; `model` is hidden in focused view and acts as the semantic-field mount in full view.

**Tech Stack:** Vue 3, TypeScript, cytoscape, `@dagrejs/dagre` (already a dependency), Node built-in test runner (`--experimental-strip-types`).

## Global Constraints

- Bind only to `ui/src/features/graphify-data-query/`; the generic chat experience (`useChatStream.ts`) must not change.
- The MCP evidence contract (`types.ts`, `wren_mcp`) is unchanged — all reshaping happens client-side.
- Cytoscape node style: `shape: 'round-rectangle' | 'diamond' | 'ellipse'`; node size 150×56; font ≥ 11px.
- Relation filter options become `'all' | 'business' | 'provenance' | 'semantic'` (drop `'query'`).
- Verify with `npm run test:graphify-data-query`, `npm run type-check`, `npm run build:main` from `ui/`.

---

## Target File Structure

- Create: `ui/src/features/graphify-data-query/evidenceStyles.ts` — kind → visual constant map.
- Create: `ui/src/features/graphify-data-query/dagreLayout.ts` — dagre coordinate computation.
- Create: `ui/src/features/graphify-data-query/focusSelection.ts` — focused-view visible node set.
- Create: `ui/src/features/graphify-data-query/evidenceGraphModel.ts` — envelope → cytoscape elements.
- Rewrite: `ui/src/features/graphify-data-query/GraphifyEvidenceGraph.vue` — controlled cytoscape renderer + interactions.
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue` — viewMode/showFields state, toolbar, legend, filter options.
- Modify: `ui/src/features/graphify-data-query/evidenceAdapter.ts:126-155` — remove/replace the now-misleading `toVueFlowEvidence` (kept only if still referenced; otherwise delete and update tests).
- Modify: `ui/package.json` — extend `test:graphify-data-query` with new test files.

---

### Task 1: `evidenceStyles.ts` — Kind Visual Map

**Files:**
- Create: `ui/src/features/graphify-data-query/evidenceStyles.ts`
- Test: `ui/src/features/graphify-data-query/evidenceStyles.test.ts`
- Modify: `ui/package.json` (add test file to `test:graphify-data-query`)

**Interfaces:**
- Produces:
  - `export type NodeVisual = { shape: 'round-rectangle' | 'diamond' | 'ellipse'; fill: string; border: string; color: string; heading: string }`
  - `export function nodeVisual(kind: string): NodeVisual`
  - `export function isBusinessEntity(kind: string): boolean`
  - `export function isSourceKind(kind: string): boolean`

- [ ] **Step 1: Write the failing test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { isBusinessEntity, isSourceKind, nodeVisual } from './evidenceStyles.ts'

test('maps every business/source kind to a visual', () => {
  for (const kind of ['product', 'organization', 'registration', 'base', 'concept']) {
    assert.equal(nodeVisual(kind).heading.length > 0, true, kind)
  }
  for (const kind of ['catalog_record', 'source_file', 'import_batch']) {
    assert.equal(nodeVisual(kind).shape, 'diamond', kind)
  }
})

test('unknown kinds fall back to the default entity visual', () => {
  assert.equal(nodeVisual('whatever').heading, '业务实体')
})

test('classifies business entities and source kinds', () => {
  assert.equal(isBusinessEntity('product'), true)
  assert.equal(isBusinessEntity('organization'), true)
  assert.equal(isBusinessEntity('catalog_record'), false)
  assert.equal(isSourceKind('source_file'), true)
  assert.equal(isSourceKind('import_batch'), true)
  assert.equal(isSourceKind('product'), false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/evidenceStyles.test.ts` from `ui/`.
Expected: FAIL with `Cannot find module './evidenceStyles.ts'`.

- [ ] **Step 3: Implement the map**

```ts
import type { GraphifyEvidenceNode } from './types'

export type NodeVisual = {
  shape: 'round-rectangle' | 'diamond' | 'ellipse'
  fill: string
  border: string
  color: string
  heading: string
}

const BUSINESS_KINDS = new Set(['product', 'organization', 'registration', 'base', 'concept'])
const SOURCE_KINDS = new Set(['catalog_record', 'source_file', 'import_batch', 'record', 'source'])

const KIND_VISUALS: Record<string, NodeVisual> = {
  model: { shape: 'ellipse', fill: '#e8f1fb', border: '#2f6fb0', color: '#1e4f7d', heading: '业务模型' },
  product: { shape: 'round-rectangle', fill: '#fff4e4', border: '#c98b37', color: '#8a5410', heading: '查询目录项' },
  organization: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '耗材企业' },
  registration: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '注册备案' },
  base: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '基础耗材' },
  concept: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '映射概念' },
  catalog_record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '原始目录记录' },
  source_file: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源工作簿' },
  import_batch: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '导入批次' },
  entity: { shape: 'round-rectangle', fill: '#f7fafc', border: '#b9cbd6', color: '#5a7184', heading: '语义字段' },
  record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '查询记录' },
  source: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源记录' },
}

const DEFAULT_VISUAL: NodeVisual = { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '业务实体' }

export function nodeVisual(kind: string): NodeVisual {
  return KIND_VISUALS[kind] ?? DEFAULT_VISUAL
}

export function isBusinessEntity(kind: string): boolean {
  return BUSINESS_KINDS.has(kind)
}

export function isSourceKind(kind: string): boolean {
  return SOURCE_KINDS.has(kind)
}

// Re-exported so callers can narrow without importing GraphifyEvidenceNode type names.
export function nodeHeading(node: Pick<GraphifyEvidenceNode, 'kind'>): string {
  return nodeVisual(node.kind).heading
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/evidenceStyles.test.ts` from `ui/`.
Expected: PASS.

- [ ] **Step 5: Add the test file to the package script**

In `ui/package.json`, change:
```json
"test:graphify-data-query": "node --experimental-strip-types --test src/features/graphify-data-query/evidenceAdapter.test.ts src/features/graphify-data-query/turnEvidence.test.ts"
```
to:
```json
"test:graphify-data-query": "node --experimental-strip-types --test src/features/graphify-data-query/evidenceAdapter.test.ts src/features/graphify-data-query/turnEvidence.test.ts src/features/graphify-data-query/evidenceStyles.test.ts"
```

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/evidenceStyles.ts ui/src/features/graphify-data-query/evidenceStyles.test.ts ui/package.json
git commit -m "feat: evidence graph kind visual map"
```

---

### Task 2: `dagreLayout.ts` — Layered Coordinates

**Files:**
- Create: `ui/src/features/graphify-data-query/dagreLayout.ts`
- Test: `ui/src/features/graphify-data-query/dagreLayout.test.ts`
- Modify: `ui/package.json` (add test file)

**Interfaces:**
- Consumes: `GraphifyEvidenceNode`, `GraphifyEvidenceEdge` from `./types`
- Produces:
  - `export type DagreOptions = { nodeWidth?: number; nodeHeight?: number; nodesep?: number; ranksep?: number; rankdir?: 'LR' | 'TB' }`
  - `export function dagrePositions(nodes: GraphifyEvidenceNode[], edges: GraphifyEvidenceEdge[], opts?: DagreOptions): Map<string, { x: number; y: number }>`
  - Positions are cytoscape **center** coordinates (dagre returns centers; do NOT halve like the old Vue Flow adapter did).

- [ ] **Step 1: Write the failing test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'
import { dagrePositions } from './dagreLayout.ts'

const nodes: GraphifyEvidenceNode[] = [
  { id: 'product', label: '覆膜气管支架', kind: 'product' },
  { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
  { id: 'organization', label: '淮安市西格玛医用实业有限公司', kind: 'organization' },
  { id: 'catalog_record', label: '…:6:2', kind: 'catalog_record' },
  { id: 'source_file', label: '耗材映射库', kind: 'source_file' },
]
const edges: GraphifyEvidenceEdge[] = [
  { id: 'e1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
  { id: 'e2', source: 'product', target: 'organization', label: '生产', kind: 'business' },
  { id: 'e3', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
  { id: 'e4', source: 'catalog_record', target: 'source_file', label: '包含', kind: 'provenance' },
]

test('LR layout flows left-to-right: source rank left, sinks right', () => {
  const pos = dagrePositions(nodes, edges, { nodeWidth: 150, nodeHeight: 56, rankdir: 'LR' })
  assert.ok(pos.get('product')!.x < pos.get('registration')!.x)
  assert.ok(pos.get('product')!.x < pos.get('organization')!.x)
  assert.ok(pos.get('catalog_record')!.x < pos.get('source_file')!.x)
})

test('sibling nodes never overlap vertically', () => {
  const pos = dagrePositions(nodes, edges, { nodeWidth: 150, nodeHeight: 56, rankdir: 'LR' })
  const registration = pos.get('registration')!
  const organization = pos.get('organization')!
  assert.ok(Math.abs(registration.y - organization.y) >= 56, 'siblings must not overlap')
})

test('returns center coordinates that dagre reports', () => {
  const pos = dagrePositions([nodes[0]!], [], { nodeWidth: 150, nodeHeight: 56 })
  const point = pos.get('product')!
  assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y))
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/dagreLayout.test.ts` from `ui/`.
Expected: FAIL with `Cannot find module './dagreLayout.ts'`.

- [ ] **Step 3: Implement dagre wrapper**

```ts
import { Graph, layout } from '@dagrejs/dagre'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type DagreOptions = {
  nodeWidth?: number
  nodeHeight?: number
  nodesep?: number
  ranksep?: number
  rankdir?: 'LR' | 'TB'
}

export function dagrePositions(
  nodes: GraphifyEvidenceNode[],
  edges: GraphifyEvidenceEdge[],
  opts: DagreOptions = {},
): Map<string, { x: number; y: number }> {
  const { nodeWidth = 150, nodeHeight = 56, nodesep = 48, ranksep = 90, rankdir = 'LR' } = opts
  const graph = new Graph({ multigraph: true })
  graph.setGraph({ rankdir, nodesep, ranksep, edgesep: 20, marginx: 24, marginy: 24 })
  graph.setDefaultEdgeLabel(() => ({}))
  nodes.forEach((node) => graph.setNode(node.id, { width: nodeWidth, height: nodeHeight }))
  edges.forEach((edge) => {
    if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) {
      graph.setEdge(edge.source, edge.target, { weight: edge.kind === 'business' ? 2 : 1 }, edge.id)
    }
  })
  layout(graph)
  const positions = new Map<string, { x: number; y: number }>()
  nodes.forEach((node) => {
    const point = graph.node(node.id) as { x: number; y: number } | undefined
    if (!point) return
    // dagre reports center coordinates; cytoscape consumes centers directly.
    positions.set(node.id, { x: Math.round(point.x), y: Math.round(point.y) })
  })
  return positions
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/dagreLayout.test.ts` from `ui/`.
Expected: PASS.

- [ ] **Step 5: Add test file to package script** (same pattern as Task 1 Step 5; append `src/features/graphify-data-query/dagreLayout.test.ts`)

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/dagreLayout.ts ui/src/features/graphify-data-query/dagreLayout.test.ts ui/package.json
git commit -m "feat: dagre layered layout for evidence graph"
```

---

### Task 3: `focusSelection.ts` — Focused-View Node Set

**Files:**
- Create: `ui/src/features/graphify-data-query/focusSelection.ts`
- Test: `ui/src/features/graphify-data-query/focusSelection.test.ts`
- Modify: `ui/package.json` (add test file)

**Interfaces:**
- Consumes: `GraphifyEvidenceNode`, `GraphifyEvidenceEdge` from `./types`
- Produces:
  - `export type FocusSelectionOptions = { lineageDepth?: number }`
  - `export function focusSelection(nodes: GraphifyEvidenceNode[], edges: GraphifyEvidenceEdge[], opts?: FocusSelectionOptions): Set<string>`

- [ ] **Step 1: Write the failing test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'
import { focusSelection } from './focusSelection.ts'

const nodes: GraphifyEvidenceNode[] = [
  { id: 'model', label: 'medical_catalog', kind: 'model' },
  { id: 'product', label: '覆膜气管支架', kind: 'product' },
  { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
  { id: 'organization', label: '淮安市西格玛医用实业有限公司', kind: 'organization' },
  { id: 'concept', label: '0133b', kind: 'concept' },
  { id: 'catalog_record', label: '…:6:2', kind: 'catalog_record' },
  { id: 'source_file', label: '耗材映射库', kind: 'source_file' },
  { id: 'import_batch', label: '导入批次', kind: 'import_batch' },
]
const edges: GraphifyEvidenceEdge[] = [
  { id: 'q1', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
  { id: 'b1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
  { id: 'b2', source: 'product', target: 'organization', label: '生产', kind: 'business' },
  { id: 'b3', source: 'product', target: 'concept', label: '归类', kind: 'business' },
  { id: 'p1', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
  { id: 'p2', source: 'catalog_record', target: 'source_file', label: '包含', kind: 'provenance' },
  { id: 'p3', source: 'source_file', target: 'import_batch', label: '包含', kind: 'provenance' },
]

test('focused selection keeps product, direct business links and 2-deep lineage', () => {
  const visible = focusSelection(nodes, edges, { lineageDepth: 2 })
  assert.deepEqual([...visible].sort(), ['catalog_record', 'concept', 'import_batch', 'organization', 'product', 'registration', 'source_file'])
  assert.equal(visible.has('model'), false, 'model is hidden in focused view')
})

test('without a product, focused selection returns all business/source nodes', () => {
  const noProduct = nodes.filter((node) => node.kind !== 'product')
  const visible = focusSelection(noProduct, edges, { lineageDepth: 2 })
  assert.ok(visible.has('registration') && visible.has('catalog_record'))
})

test('lineageDepth 0 stops the provenance chain at the catalog record', () => {
  const visible = focusSelection(nodes, edges, { lineageDepth: 0 })
  assert.equal(visible.has('source_file'), false)
  assert.equal(visible.has('catalog_record'), true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/focusSelection.test.ts` from `ui/`.
Expected: FAIL with `Cannot find module './focusSelection.ts'`.

- [ ] **Step 3: Implement focus selection**

```ts
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type FocusSelectionOptions = {
  lineageDepth?: number
}

export function focusSelection(
  nodes: GraphifyEvidenceNode[],
  edges: GraphifyEvidenceEdge[],
  opts: FocusSelectionOptions = {},
): Set<string> {
  const { lineageDepth = 2 } = opts
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const product = nodes.find((node) => node.kind === 'product')
  const visible = new Set<string>()
  if (!product) {
    nodes.forEach((node) => {
      if (['product', 'organization', 'registration', 'base', 'concept', 'catalog_record', 'source_file', 'import_batch'].includes(node.kind)) {
        visible.add(node.id)
      }
    })
    return visible
  }
  visible.add(product.id)
  const outgoing = (source: string, kind?: 'business' | 'provenance') => edges
    .filter((edge) => edge.source === source && (!kind || edge.kind === kind))
    .map((edge) => edge.target)
  outgoing(product.id, 'business').forEach((id) => visible.add(id))
  const catalogRecord = outgoing(product.id, 'provenance').find((id) => nodeById.get(id)?.kind === 'catalog_record')
  if (catalogRecord) {
    visible.add(catalogRecord)
    let current = catalogRecord
    for (let depth = 0; depth < lineageDepth; depth += 1) {
      const next = outgoing(current, 'provenance')[0]
      if (!next || visible.has(next)) break
      visible.add(next)
      current = next
    }
  }
  return visible
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/focusSelection.test.ts` from `ui/`.
Expected: PASS.

- [ ] **Step 5: Add test file to package script**

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/focusSelection.ts ui/src/features/graphify-data-query/focusSelection.test.ts ui/package.json
git commit -m "feat: focused evidence chain selection"
```

---

### Task 4: `evidenceGraphModel.ts` — Envelope → Cytoscape Elements

**Files:**
- Create: `ui/src/features/graphify-data-query/evidenceGraphModel.ts`
- Test: `ui/src/features/graphify-data-query/evidenceGraphModel.test.ts`
- Modify: `ui/package.json` (add test file)

**Interfaces:**
- Consumes: `dagrePositions` from `./dagreLayout`, `nodeVisual` from `./evidenceStyles`, `GraphifyEvidenceEnvelope` from `./types`
- Produces:
  - `export type GraphModelOptions = { viewMode: 'focused' | 'full'; showFields: boolean; visibleIds?: ReadonlySet<string> }`
  - `export function evidenceGraphModel(envelope: GraphifyEvidenceEnvelope, opts: GraphModelOptions): ElementDefinition[]`
  - Edge elements carry `data.kind` so the component can true-hide by filter. Query-process nodes (`record`, `source`) and `query` edges are never emitted. `model` is emitted only in `full` view; `entity` (semantic field) only in `full` view with `showFields`.

- [ ] **Step 1: Write the failing test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import type { ElementDefinition } from 'cytoscape'
import type { GraphifyEvidenceEnvelope } from './types'
import { evidenceGraphModel } from './evidenceGraphModel.ts'

const envelope: GraphifyEvidenceEnvelope = {
  status: 'executed', trace_id: 't1', dataset_id: 'medical_catalog', question: 'q',
  result: { columns: ['catalog_name'], rows: [{ catalog_name: '覆膜气管支架' }], truncated: false },
  semantic_context: { graph_version: 'v1', recommended_models: ['medical_catalog'], recommended_columns: ['catalog_name'], rules: [], provenance: {} },
  evidence: {
    source_record_ids: ['consumable:main:C1'],
    nodes: [
      { id: 'model', label: 'medical_catalog', kind: 'model' },
      { id: 'product', label: '覆膜气管支架', kind: 'product' },
      { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
      { id: 'catalog_record', label: '…:6:2', kind: 'catalog_record' },
      { id: 'record', label: 'record:C1', kind: 'record' },
      { id: 'field', label: 'catalog_name', kind: 'entity' },
    ],
    edges: [
      { id: 'q1', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
      { id: 'b1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
      { id: 'p1', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
      { id: 'p2', source: 'catalog_record', target: 'record', label: '来源记录', kind: 'provenance' },
      { id: 's1', source: 'model', target: 'field', label: '字段来源', kind: 'semantic' },
    ],
  },
}

const ids = (elements: ElementDefinition[]) => elements.filter((e) => e.data?.kind && !e.data?.source).map((e) => String(e.data!.id)).sort()

test('focused view: business + provenance only, no model/record/query', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  assert.deepEqual(ids(elements), ['catalog_record', 'product', 'registration'])
  const kinds = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.kind))
  assert.equal(kinds.includes('model'), false)
  assert.equal(kinds.includes('record'), false)
  const edgeKinds = elements.filter((e) => e.data?.source).map((e) => String(e.data!.kind))
  assert.equal(edgeKinds.includes('query'), false)
})

test('full view with fields includes model, semantic field and their edges', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'full', showFields: true })
  const all = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.id))
  assert.ok(all.includes('model') && all.includes('field') && all.includes('product'))
  const edgeKinds = elements.filter((e) => e.data?.source).map((e) => String(e.data!.kind))
  assert.ok(edgeKinds.includes('semantic'))
})

test('full view without fields omits semantic entities', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'full', showFields: false })
  const all = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.id))
  assert.equal(all.includes('field'), false)
})

test('nodes carry positions from dagre', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  const product = elements.find((e) => e.data?.id === 'product')
  assert.ok(product?.position, 'product node must have dagre position')
  assert.ok(Number.isFinite(product!.position!.x))
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/evidenceGraphModel.test.ts` from `ui/`.
Expected: FAIL with `Cannot find module './evidenceGraphModel.ts'`.

- [ ] **Step 3: Implement the model**

```ts
import type { ElementDefinition } from 'cytoscape'
import { dagrePositions } from './dagreLayout'
import { nodeVisual } from './evidenceStyles'
import type { GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types'

export type GraphModelOptions = {
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
}

const QUERY_PROCESS_KINDS = new Set(['record', 'source'])

function nodeVisible(node: GraphifyEvidenceNode, opts: GraphModelOptions): boolean {
  if (QUERY_PROCESS_KINDS.has(node.kind)) return false
  if (node.kind === 'model') return opts.viewMode === 'full'
  if (node.kind === 'entity') return opts.viewMode === 'full' && opts.showFields
  return true
}

export function evidenceGraphModel(envelope: GraphifyEvidenceEnvelope, opts: GraphModelOptions): ElementDefinition[] {
  const allNodes = envelope.evidence.nodes.filter((node) => nodeVisible(node, opts))
  const nodes = opts.visibleIds
    ? allNodes.filter((node) => opts.visibleIds!.has(node.id))
    : allNodes
  const nodeIds = new Set(nodes.map((node) => node.id))
  const edges = envelope.evidence.edges.filter((edge) =>
    edge.kind !== 'query' && nodeIds.has(edge.source) && nodeIds.has(edge.target))
  const position = dagrePositions(nodes, edges)

  const nodeElements: ElementDefinition[] = nodes.map((node) => {
    const visual = nodeVisual(node.kind)
    return {
      data: {
        id: node.id,
        label: `${visual.heading}\n${node.label}`,
        fullLabel: node.label,
        kind: node.kind,
        category: node.kind,
        heading: visual.heading,
      },
      position: position.get(node.id) ?? { x: 0, y: 0 },
    }
  })
  const edgeElements: ElementDefinition[] = edges.map((edge) => ({
    data: {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
      kind: edge.kind,
    },
  }))
  return [...nodeElements, ...edgeElements]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --experimental-strip-types --test src/features/graphify-data-query/evidenceGraphModel.test.ts` from `ui/`.
Expected: PASS.

- [ ] **Step 5: Add test file to package script**

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/evidenceGraphModel.ts ui/src/features/graphify-data-query/evidenceGraphModel.test.ts ui/package.json
git commit -m "feat: evidence graph model with logic-only relations"
```

---

### Task 5: Rewrite `GraphifyEvidenceGraph.vue` — Controlled Renderer + Interactions

**Files:**
- Rewrite: `ui/src/features/graphify-data-query/GraphifyEvidenceGraph.vue`
- Test: existing `evidenceAdapter.test.ts` + `turnEvidence.test.ts` must keep passing (regression gate)

**Interfaces:**
- Consumes: `evidenceGraphModel` (Task 4), `nodeVisual` (Task 1), `focusSelection` (Task 3)
- Produces (component contract):
  ```ts
  props: {
    evidence: GraphifyEvidenceEnvelope
    relationFilter: 'all' | 'business' | 'provenance' | 'semantic'
    fullscreen: boolean
    viewMode: 'focused' | 'full'
    showFields?: boolean          // default false
  }
  emits: {
    select: [nodeId: string]
    'update:viewMode': [mode: 'focused' | 'full']
  }
  expose: { zoomIn, zoomOut, fit, relayout }
  ```

- [ ] **Step 1: Delete the old hand-written coordinate logic** — replace the entire file content with the controlled implementation below.

```vue
<script setup lang="ts">
import cytoscape, { type Core } from 'cytoscape'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { evidenceGraphModel } from './evidenceGraphModel'
import { nodeVisual } from './evidenceStyles'
import { focusSelection } from './focusSelection'
import type { GraphifyEvidenceEnvelope } from './types'

type RelationFilter = 'all' | 'business' | 'provenance' | 'semantic'

const props = withDefaults(defineProps<{
  evidence: GraphifyEvidenceEnvelope
  relationFilter: RelationFilter
  fullscreen: boolean
  viewMode: 'focused' | 'full'
  showFields?: boolean
}>(), { showFields: false })
const emit = defineEmits<{
  select: [nodeId: string]
  'update:viewMode': [mode: 'focused' | 'full']
}>()

const canvas = ref<HTMLElement>()
const tooltipEl = ref<HTMLElement>()
let cy: Core | undefined
const selectedId = ref<string | null>(null)

const visibleNodeCount = computed(() => {
  const ids = props.viewMode === 'focused'
    ? focusSelection(props.evidence.evidence.nodes, props.evidence.evidence.edges, { lineageDepth: 2 })
    : new Set(props.evidence.evidence.nodes
        .filter((node) => {
          if (node.kind === 'record' || node.kind === 'source') return false
          if (node.kind === 'entity') return props.showFields
          return true
        })
        .map((node) => node.id))
  return ids.size
})
const totalNodeCount = computed(() => props.evidence.evidence.nodes
  .filter((node) => node.kind !== 'record' && node.kind !== 'source')
  .length)

function buildElements() {
  const visibleIds = props.viewMode === 'focused'
    ? focusSelection(props.evidence.evidence.nodes, props.evidence.evidence.edges, { lineageDepth: 2 })
    : undefined
  return evidenceGraphModel(props.evidence, {
    viewMode: props.viewMode,
    showFields: props.showFields,
    visibleIds,
  })
}

function applyFilter() {
  if (!cy) return
  cy.edges().forEach((edge) => {
    const hide = props.relationFilter !== 'all' && edge.data('kind') !== props.relationFilter
    edge.toggleClass('filtered-out', hide)
  })
}

function render(animate = false) {
  if (!cy) return
  cy.elements().remove()
  cy.add(buildElements())
  applyFilter()
  cy.layout({ name: 'preset', fit: true, padding: props.fullscreen ? 32 : 16, animate, animationDuration: 300 }).run()
  const selected = selectedId.value ? cy.getElementById(selectedId.value) : undefined
  if (selected && selected.length) selected.select()
  else {
    const first = cy.nodes().first()
    if (first.length) select(first.id())
  }
}

function select(id: string) {
  selectedId.value = id
  cy?.nodes().unselect()
  cy?.getElementById(id).select()
  emit('select', id)
}

function showTooltip(event: cytoscape.EventObject) {
  const node = event.target as cytoscape.NodeSingular
  if (!tooltipEl.value) return
  tooltipEl.value.textContent = `${node.data('heading')}：${node.data('fullLabel')}`
  tooltipEl.value.style.display = 'block'
  const box = (event.cy.container() as HTMLElement).getBoundingClientRect()
  tooltipEl.value.style.left = `${Math.min(Math.max(event.renderedPosition.x - 80, 4), box.width - 170)}px`
  tooltipEl.value.style.top = `${Math.max(event.renderedPosition.y - 42, 4)}px`
}
function hideTooltip() {
  if (!tooltipEl.value) return
  tooltipEl.value.style.display = 'none'
}

function zoomIn() { cy?.zoom({ level: Math.min(2.4, cy.zoom() + 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function zoomOut() { cy?.zoom({ level: Math.max(0.45, cy.zoom() - 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function fit() { cy?.fit(cy.elements(), props.fullscreen ? 32 : 16) }
function relayout() { render(true) }
function toggleViewMode() {
  emit('update:viewMode', props.viewMode === 'focused' ? 'full' : 'focused')
}

function initialize() {
  if (!canvas.value) return
  cy = cytoscape({
    container: canvas.value,
    elements: buildElements(),
    minZoom: 0.45,
    maxZoom: 2.4,
    wheelSensitivity: 0.16,
    style: [
      {
        selector: 'node',
        style: {
          shape: 'round-rectangle',
          width: 150,
          height: 56,
          'background-color': '#eef7fb',
          'border-width': 2,
          'border-color': '#2f8fb0',
          label: 'data(label)',
          color: '#146a85',
          'font-family': 'PingFang SC, Microsoft YaHei, sans-serif',
          'font-size': 11,
          'font-weight': 650,
          'text-wrap': 'wrap',
          'text-max-width': 136,
          'text-valign': 'center',
          'text-halign': 'center',
          'overlay-padding': 7,
        },
      },
      {
        selector: 'node[?coreNode]',
        style: { 'border-width': 3, 'border-color': '#c98b37', 'background-color': '#fff4e4', color: '#8a5410' },
      },
      {
        selector: 'node[category = "entity"]',
        style: { 'border-style': 'dashed', 'border-color': '#b9cbd6', 'background-color': '#f7fafc', color: '#5a7184', 'font-size': 10.5 },
      },
      {
        selector: 'node[category = "model"]',
        style: { shape: 'ellipse', 'border-color': '#2f6fb0', 'background-color': '#e8f1fb', color: '#1e4f7d' },
      },
      {
        selector: 'node[?diamond]',
        style: { shape: 'diamond', 'border-color': '#5d8fb5', 'background-color': '#f0f6fb', color: '#2b5d80' },
      },
      { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#2f80c5', 'background-color': '#e6f2fb', underlay: { color: '#5a9fd0', opacity: 0.24, padding: 6 } } },
      { selector: 'node.hover', style: { 'border-width': 3, 'border-color': '#e8a23a' } },
      { selector: 'node.dimmed', style: { opacity: 0.15 } },
      { selector: 'edge', style: { width: 1.5, 'curve-style': 'bezier', 'line-color': '#8faec6', 'target-arrow-color': '#8faec6', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.7, label: 'data(label)', 'font-size': 9, 'font-family': 'PingFang SC, Microsoft YaHei, sans-serif', color: '#4d6675', 'text-background-color': '#f3f8fc', 'text-background-opacity': 0.92, 'text-background-padding': 2, 'text-rotation': 'autorotate' } },
      { selector: 'edge[category = "business"]', style: { 'line-color': '#4c9db8', 'target-arrow-color': '#4c9db8' } },
      { selector: 'edge[category = "provenance"]', style: { 'line-color': '#7d9cbb', 'target-arrow-color': '#7d9cbb', 'line-style': 'dashed' } },
      { selector: 'edge[category = "semantic"]', style: { 'line-color': '#b7c8d6', 'target-arrow-color': '#b7c8d6', 'line-style': 'dotted' } },
      { selector: '.filtered-out', style: { display: 'none' } },
    ],
    layout: { name: 'preset' },
  })

  cy.on('mouseover', 'node', (event) => {
    const node = event.target
    const keep = node.closedNeighborhood().union(node)
    cy?.nodes().forEach((n) => { if (!keep.has(n)) n.addClass('dimmed') })
    node.addClass('hover')
    showTooltip(event)
  })
  cy.on('mouseout', 'node', () => {
    cy?.nodes().removeClass('dimmed hover')
    hideTooltip()
  })
  cy.on('tap', 'node', (event) => select(event.target.id()))
  cy.on('dbltap', 'node', toggleViewMode)
  cy.on('tap', (event) => {
    if (event.target === cy) {
      cy?.nodes().unselect()
      selectedId.value = null
      emit('select', '')
    }
  })
  render(false)
  const first = cy.nodes().first()
  if (first.length) select(first.id())
}

watch(() => props.evidence.trace_id, () => { selectedId.value = null; nextTick(() => render(false)) })
watch(() => props.viewMode, () => nextTick(() => render(true)))
watch(() => props.showFields, () => nextTick(() => render(false)))
watch(() => props.relationFilter, applyFilter)
watch(() => props.fullscreen, async () => { await nextTick(); cy?.resize(); render(false) })

onMounted(() => initialize())
onBeforeUnmount(() => { cy?.destroy(); cy = undefined })
defineExpose({ zoomIn, zoomOut, fit, relayout, toggleViewMode })
</script>

<template>
  <div class="evidence-graph" :class="{ 'is-fullscreen': fullscreen }" aria-label="业务逻辑关系图谱">
    <div ref="canvas" class="cy-canvas" />
    <div ref="tooltipEl" class="cy-tooltip" role="tooltip" />
    <div class="graph-summary">
      {{ visibleNodeCount }} 个节点 · {{ props.evidence.evidence.edges.length }} 条关系
      <template v-if="viewMode === 'focused' && totalNodeCount > visibleNodeCount">
        · 另有 {{ totalNodeCount - visibleNodeCount }} 个节点未展示
      </template>
    </div>
  </div>
</template>

<style scoped>
.evidence-graph { position: relative; height: 392px; overflow: hidden; border: 1px solid #c5dbea; border-radius: 4px; background-color: #f2f8fc; background-image: linear-gradient(to right, rgb(75 137 181 / 10%) 1px, transparent 1px), linear-gradient(to bottom, rgb(75 137 181 / 10%) 1px, transparent 1px); background-size: 18px 18px; }
.evidence-graph.is-fullscreen { height: 100%; min-height: 0; }
.cy-canvas { position: absolute; inset: 0; z-index: 1; }
.cy-tooltip { position: absolute; z-index: 8; display: none; max-width: 220px; padding: 5px 9px; border: 1px solid #b8d0dd; border-radius: 4px; background: rgb(255 255 255 / 96%); box-shadow: 0 4px 14px rgb(31 58 58 / 14%); color: #21445f; font-size: 11px; line-height: 1.45; pointer-events: none; }
.graph-summary { position: absolute; z-index: 3; right: 10px; bottom: 10px; padding: 4px 7px; border: 1px solid #cbdfea; border-radius: 3px; background: rgb(255 255 255 / 94%); color: #286fa8; font-size: 11px; font-weight: 650; pointer-events: none; }
</style>
```

> Implementation note: `data.coreNode = true` is set on `product` nodes and `data.diamond = true` on source kinds inside `evidenceGraphModel` so the stylesheet selectors above work. Add these flags in Task 4's model:
> - `coreNode: node.kind === 'product'`
> - `diamond: ['catalog_record', 'source_file', 'import_batch'].includes(node.kind)`

- [ ] **Step 2: Add the two data flags to `evidenceGraphModel.ts`** — in the node element `data` object, append `coreNode: node.kind === 'product'` and `diamond: ['catalog_record', 'source_file', 'import_batch'].includes(node.kind)`; then re-run its test (still passes, assertions unaffected).

- [ ] **Step 3: Run type-check and full test suite**

Run: `npm run type-check && npm run test:graphify-data-query` from `ui/`.
Expected: PASS — 11 existing + 4 new test files all green.

- [ ] **Step 4: Commit**

```bash
git add ui/src/features/graphify-data-query/GraphifyEvidenceGraph.vue ui/src/features/graphify-data-query/evidenceGraphModel.ts
git commit -m "feat: controlled evidence graph with dagre layout and interactions"
```

---

### Task 6: Update `GraphifyDataQueryPage.vue` — View Mode State and Toolbar

**Files:**
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue:25` (filter type), `:27-28` (state), `:80-84` (onGraphExpansion → viewMode), `:154` (toolbar + props + filter options), `:153` (legend)

**Interfaces:**
- Consumes: `GraphifyEvidenceGraph` new props/emits (Task 5)
- Produces: container holds `viewMode: 'focused' | 'full'` and `showFields` refs, passes them down, renders「查看全部 N 个节点 / 收起为聚焦视图」and「显示语义字段」controls.

- [ ] **Step 1: Update script state and handlers**

Replace the filter type and remove `onGraphExpansion` (which wrongly toggled `fullscreen`); add viewMode/showFields refs:

```ts
const relationFilter = ref<'all' | 'business' | 'provenance' | 'semantic'>('all')
const selectedId = ref<string | null>(null)
const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)
const viewMode = ref<'focused' | 'full'>('focused')
const showFields = ref(false)
```

Replace `onGraphExpansion` (lines 80-84) with:

```ts
function onViewModeChange(mode: 'focused' | 'full') {
  viewMode.value = mode
  nextTick(() => graphRef.value?.relayout())
}
```

- [ ] **Step 2: Update the graph component usage and toolbar (template line 154)**

Replace the `<GraphifyEvidenceGraph …>` usage and toolbar with:

```html
<div class="graph-workspace">
  <GraphifyEvidenceGraph
    ref="graphRef"
    :evidence="activeEvidence"
    :relation-filter="relationFilter"
    :fullscreen="fullscreen"
    :view-mode="viewMode"
    :show-fields="showFields"
    @select="selectedId = $event"
    @update:view-mode="onViewModeChange"
  />
  <div class="graph-tools" aria-label="图谱工具栏">
    <button class="icon-btn" title="放大" @click="graphRef?.zoomIn()"><ZoomInOutlined /></button>
    <button class="icon-btn" title="缩小" @click="graphRef?.zoomOut()"><ZoomOutOutlined /></button>
    <button class="icon-btn" title="适应画布" @click="graphRef?.fit()"><AimOutlined /></button>
    <button class="icon-btn" title="重新布局" @click="graphRef?.relayout()"><ReloadOutlined /></button>
    <button class="icon-btn" :class="{ active: viewMode === 'full' }" :title="viewMode === 'full' ? '收起为聚焦视图' : `查看全部 ${activeEvidence.evidence.nodes.filter((n) => n.kind !== 'record' && n.kind !== 'source').length} 个节点`" @click="viewMode = viewMode === 'full' ? 'focused' : 'full'"><BranchesOutlined /></button>
    <button class="icon-btn" :class="{ active: showFields }" title="显示/隐藏语义字段" @click="showFields = !showFields"><FieldStringOutlined /></button>
    <button class="icon-btn" :title="fullscreen ? '退出图谱大屏' : '图谱大屏查看'" @click="fullscreen = !fullscreen"><FullscreenExitOutlined v-if="fullscreen" /><FullscreenOutlined v-else /></button>
  </div>
  <div class="graph-actions">
    <label><FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系">
      <option value="all">全部关系</option>
      <option value="business">业务关系</option>
      <option value="provenance">来源追溯</option>
      <option value="semantic">语义关系</option>
    </select></label>
  </div>
</div>
```

- [ ] **Step 3: Update the legend (template line 153)** — remove the "查询记录" entry:

```html
<div class="legend"><span><DatabaseOutlined class="blue" /> Wren MDL</span><span><MedicineBoxOutlined class="teal" /> 业务实体</span><span><ShareAltOutlined class="green" /> 来源追溯</span><span><DeploymentUnitOutlined class="plum" /> 业务关系</span></div>
```

- [ ] **Step 4: Import the new icon**

In the `<script setup>` imports add `FieldStringOutlined` to the `@ant-design/icons-vue` import list.

- [ ] **Step 5: Run type-check and tests**

Run: `npm run type-check && npm run test:graphify-data-query` from `ui/`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue
git commit -m "feat: view mode and field toggle for evidence graph"
```

---

### Task 7: Clean Up Legacy Adapter and Final Verification

**Files:**
- Modify: `ui/src/features/graphify-data-query/evidenceAdapter.ts:126-155` — remove `toVueFlowEvidence` + `edgeHandles` and the now-unused `Position`/`Node`/`Edge` imports and `@dagrejs/dagre` import (dagre now lives in `dagreLayout.ts`)
- Modify: `ui/src/features/graphify-data-query/evidenceAdapter.test.ts` — drop tests that call `toVueFlowEvidence`; keep label/parse/outcome tests

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: leaner `evidenceAdapter.ts` exposing only `displayGraphifyLabel`, `displayGraphifyNodeLabel`, `parseGraphifyEvidence`, `parseGraphifyToolOutcome`, `baseEvidenceNodeIds` (if still used by page; verify — the page uses `selectedNodeSummary` locals, so check references before deleting).

- [ ] **Step 1: Verify references before deleting**

Run: `grep -rn "toVueFlowEvidence\|baseEvidenceNodeIds\|edgeHandles" ui/src --include="*.ts" --include="*.vue"`.
If `baseEvidenceNodeIds` is referenced outside tests, keep it; otherwise delete it too. If nothing references `toVueFlowEvidence`, delete the function and `edgeHandles`.

- [ ] **Step 2: Remove dead code from `evidenceAdapter.ts`**

Delete lines 126-168 (`toVueFlowEvidence`, `edgeHandles`) and the imports `Graph, layout` from `@dagrejs/dagre`, and `Position, type Edge, type Node` from `@vue-flow/core`. Keep `displayGraphifyNodeLabel` (used by the page's `selectedNodeSummary`).

- [ ] **Step 3: Update tests**

In `evidenceAdapter.test.ts`, remove the three tests that call `toVueFlowEvidence` (`uses Chinese labels…` keep the label assertions but drop the flow part; delete `keeps real business graph nodes…`, `lays out sibling evidence nodes…`, `keeps the full evidence backbone…`). Keep parser/outcome/label tests.

- [ ] **Step 4: Run the full suite**

Run: `npm run type-check && npm run test:graphify-data-query` from `ui/`.
Expected: PASS.

- [ ] **Step 5: Build and E2E**

Run: `npm run build:main` from `ui/`; then rebuild the Docker frontend:
```bash
cd docker
docker compose -p k-acp-local --env-file .env.kacp -f docker-compose-simple.yml -f docker-compose-kacp-local.yml up -d --build --no-deps apboa-frontend
```
Then in a logged-in browser at `http://127.0.0.1:23080/web/#/chat/diy/graphify-data-query`, ask `国械注准20173134669对应什么耗材？` and verify: focused view shows only business/source nodes (no 查询目录项 process nodes, no model node), hover highlights neighborhood, click shows the node summary, the「查看全部」button expands without overlap, the filter dropdown hides relations entirely, and the second-turn/blocked flows still work.

- [ ] **Step 6: Commit**

```bash
git add ui/src/features/graphify-data-query/evidenceAdapter.ts ui/src/features/graphify-data-query/evidenceAdapter.test.ts
git commit -m "refactor: remove legacy vue-flow evidence adapter"
```

---

## Acceptance Matrix

| Requirement | Pass condition |
|---|---|
| Layout robust | For any evidence input, dagre positions never overlap (Task 2 test) |
| Logic-only semantics | No `record`/`source` nodes or `query` edges in emitted elements (Task 4 test) |
| View switching | focused ↔ full keeps selection; toolbar button + dbltap both work (Task 5/6) |
| Interactions | hover neighborhood highlight, click focus + summary, tooltip, filter true-hides (Task 5) |
| Visual hierarchy | kind shapes/colors per `evidenceStyles`, label ≥ 11px, legend updated (Task 1/6) |
| No regression | type-check, test:graphify-data-query, build:main green; generic chat untouched (Task 7) |

## Plan Review

- Coverage: Tasks 1-4 build the pure-function layer with tests; Task 5 rewires the component; Task 6 wires the container; Task 7 removes legacy code and verifies end to end.
- No placeholders: every function, test, template snippet, and command is specified.
- Dependency gate: Task 4 must land before Task 5 (model API); Task 5 before Task 6 (component contract). Task 7 is last because it removes code that earlier tasks still compile against.
