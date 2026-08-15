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

test('focused selection keeps product, key business facts and 2-deep lineage', () => {
  const visible = focusSelection(nodes, edges, { lineageDepth: 2 })
  assert.deepEqual([...visible].sort(), ['catalog_record', 'import_batch', 'organization', 'product', 'registration', 'source_file'])
  assert.equal(visible.has('model'), false, 'model is hidden in focused view')
  assert.equal(visible.has('concept'), false, 'mapping concepts stay in the full view')
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

test('with two products, focuses the query-edge target product, not the array-first one', () => {
  const twoProductNodes: GraphifyEvidenceNode[] = [
    { id: 'product-first', label: '商品甲', kind: 'product' },
    { id: 'product-second', label: '商品乙', kind: 'product' },
    { id: 'registration-second', label: '注册乙', kind: 'registration' },
    { id: 'catalog_record-second', label: '目录记录乙', kind: 'catalog_record' },
    { id: 'source_file-second', label: '来源乙', kind: 'source_file' },
  ]
  const twoProductEdges: GraphifyEvidenceEdge[] = [
    { id: 'q1', source: 'model', target: 'product-second', label: '查询返回', kind: 'query' },
    { id: 'b1', source: 'product-second', target: 'registration-second', label: '对应', kind: 'business' },
    { id: 'p1', source: 'product-second', target: 'catalog_record-second', label: '证据支持', kind: 'provenance' },
    { id: 'p2', source: 'catalog_record-second', target: 'source_file-second', label: '包含', kind: 'provenance' },
  ]
  const visible = focusSelection(twoProductNodes, twoProductEdges, { lineageDepth: 2 })
  assert.equal(visible.has('product-first'), false, 'non-queried product must not be included')
  assert.equal(visible.has('product-second'), true)
  assert.equal(visible.has('registration-second'), true)
  assert.equal(visible.has('source_file-second'), true)
})
