import assert from 'node:assert/strict'
import test from 'node:test'
import { buildGraphView, hasRenderableGraph } from './graphViewAdapter.ts'
import type { GraphifyEvidenceEnvelope } from './types.ts'

const envelope: GraphifyEvidenceEnvelope = {
  status: 'executed',
  trace_id: 'trace-1',
  dataset_id: 'medical_catalog',
  question: '覆膜气管支架的注册备案和来源是什么？',
  result: { columns: [], rows: [], truncated: false },
  semantic_context: {
    graph_version: 'v1',
    recommended_models: [],
    recommended_columns: [],
    domain_labels: { CONSUMABLE: '耗材' },
    domain_headings: { CONSUMABLE: '耗材目录项' },
    rules: [],
    provenance: {},
  },
  evidence: {
    source_record_ids: [],
    nodes: [
      { id: 'model', label: 'medical_catalog', kind: 'model' },
      { id: 'product', label: '覆膜气管支架', kind: 'product', domain: 'CONSUMABLE' },
      { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
      { id: 'catalog-record', label: '河南目录', kind: 'catalog_record' },
      { id: 'attribute', label: '材质：合金', kind: 'attribute' },
      { id: 'record', label: 'query:record', kind: 'record' },
      { id: 'orphan', label: '孤立节点', kind: 'organization' },
    ],
    edges: [
      { id: 'query-result', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
      { id: 'registration-edge', source: 'product', target: 'registration', label: '对应', kind: 'business' },
      { id: 'provenance-edge', source: 'product', target: 'catalog-record', label: '证据支持', kind: 'provenance' },
      { id: 'attribute-edge', source: 'catalog-record', target: 'attribute', label: '目录字段', kind: 'attribute' },
      { id: 'query-process', source: 'product', target: 'record', label: '查询过程', kind: 'query' },
    ],
  },
}

test('buildGraphView follows the reference graph explorer projection and folds record attributes onto their product', () => {
  const view = buildGraphView(envelope, { viewMode: 'full', showFields: false })

  assert.equal(view.id, 'trace-1')
  assert.equal(view.title, envelope.question)
  assert.equal(view.nodes.length, 3)
  assert.equal(view.edges.length, 2)
  assert.equal(view.stats.nodeCount, 3)
  assert.equal(view.stats.edgeCount, 2)
  assert.equal(view.nodes.some((node) => node.id === 'record'), false)
  assert.equal(view.nodes.some((node) => node.id === 'model'), false)
  assert.equal(view.nodes.some((node) => node.id === 'catalog-record'), false)
  assert.equal(view.nodes.some((node) => node.id === 'orphan'), false)
  assert.deepEqual(view.nodes.map((node) => node.id).sort(), [
    'attribute:材质:合金', 'product:覆膜气管支架', 'registration:国械注准20173134669',
  ])
  assert.ok(view.nodes.every((node) => node.position === undefined && node.style === undefined))
  assert.equal(view.nodes.find((node) => node.id === 'product:覆膜气管支架')?.entityType, 'product')
  assert.equal(view.nodes.find((node) => node.id === 'product:覆膜气管支架')?.label, '覆膜气管支架')
  assert.equal(view.nodes.find((node) => node.id === 'attribute:材质:合金')?.label, '合金')
  assert.deepEqual(view.edges.find((edge) => edge.target === 'attribute:材质:合金'), {
    id: 'attribute:product:覆膜气管支架:attribute:材质:合金:材质',
    source: 'product:覆膜气管支架', target: 'attribute:材质:合金', relationType: 'attribute', label: '材质',
  })
  assert.deepEqual(view.categories, [
    { key: 'product', name: '业务对象', color: '#2563eb' },
    { key: 'organization', name: '组织/企业', color: '#16a34a' },
    { key: 'registration', name: '编号/标识', color: '#ea580c' },
    { key: 'concept', name: '概念/分类', color: '#7c3aed' },
    { key: 'attribute', name: '业务字段', color: '#0f766e' },
    { key: 'source', name: '来源依据', color: '#64748b' },
  ])
})

test('buildGraphView has the same node projection for the graph dialog focus mode', () => {
  const view = buildGraphView(envelope, { viewMode: 'focused', showFields: false })

  assert.deepEqual(view.nodes.map((node) => node.id).sort(), [
    'attribute:材质:合金', 'product:覆膜气管支架', 'registration:国械注准20173134669',
  ])
  assert.equal(view.edges.length, 2)
})

test('buildGraphView merges equal business attribute values like the reference explorer', () => {
  const merged: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      nodes: [
        ...envelope.evidence.nodes,
        { id: 'product-2', label: '另一耗材', kind: 'product', domain: 'CONSUMABLE' },
        { id: 'record-2', label: '另一记录', kind: 'catalog_record' },
        { id: 'attribute-2', label: '材质：合金', kind: 'attribute' },
      ],
      edges: [
        ...envelope.evidence.edges,
        { id: 'product-2-record', source: 'product-2', target: 'record-2', label: '证据支持', kind: 'provenance' },
        { id: 'attribute-2-edge', source: 'record-2', target: 'attribute-2', label: '目录字段', kind: 'attribute' },
      ],
      source_record_ids: [],
    },
  }
  const view = buildGraphView(merged, { viewMode: 'full', showFields: false })

  assert.equal(view.nodes.filter((node) => node.entityType === 'attribute' && node.label === '合金').length, 1)
  assert.equal(view.edges.filter((edge) => edge.target === 'attribute:材质:合金').length, 2)
})

test('buildGraphView defaults to at most 100 visible ECharts nodes', () => {
  const broad: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      nodes: [
        { id: 'root', label: '氯雷他定', kind: 'product' },
        ...Array.from({ length: 120 }, (_, index) => ({
          id: `category-${index}`,
          label: `收费类别：${index}`,
          kind: 'attribute' as const,
        })),
      ],
      edges: Array.from({ length: 120 }, (_, index) => ({
        id: `edge-${index}`,
        source: 'root',
        target: `category-${index}`,
        label: '收费类别',
        kind: 'attribute' as const,
      })),
      source_record_ids: [],
    },
  }

  const view = buildGraphView(broad, { viewMode: 'full', showFields: false })
  assert.ok(view.nodes.length <= 100)
  assert.ok(view.edges.length > 0)
  assert.ok(view.nodes.some((node) => node.id === 'product:氯雷他定'))
  assert.ok(view.stats.totalNodeCount > view.nodes.length)
})

test('hasRenderableGraph rejects a projection made only of hidden intermediary nodes', () => {
  const emptyProjection: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      nodes: [
        { id: 'record-only', label: '原始目录记录', kind: 'catalog_record' },
        { id: 'source-only', label: '来源工作簿', kind: 'source_file' },
      ],
      edges: [{ id: 'lineage', source: 'record-only', target: 'source-only', label: '来源', kind: 'provenance' }],
      source_record_ids: [],
    },
  }

  assert.equal(hasRenderableGraph(emptyProjection), false)
})
