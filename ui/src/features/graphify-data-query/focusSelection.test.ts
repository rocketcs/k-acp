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
