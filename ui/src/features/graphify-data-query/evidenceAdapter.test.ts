import assert from 'node:assert/strict'
import test from 'node:test'
import { baseEvidenceNodeIds, displayGraphifyLabel, displayGraphifyNodeLabel, parseGraphifyEvidence, parseGraphifyToolOutcome, toVueFlowEvidence } from './evidenceAdapter.ts'

const evidence = {
  status: 'executed', trace_id: 'trace-1', dataset_id: 'medical_catalog', question: '覆膜气管支架',
  result: { columns: ['catalog_code'], rows: [{ catalog_code: 'C0101010011303807555' }], truncated: false },
  semantic_context: { graph_version: 'v1', recommended_models: ['medical_catalog'], recommended_columns: [], rules: [], provenance: {} },
  evidence: { source_record_ids: ['consumable:main_catalog:C0101010011303807555:6:2'], nodes: [], edges: [] },
}

test('accepts an executed medical-catalog evidence envelope', () => {
  assert.equal(parseGraphifyEvidence('run_template_query', JSON.stringify(evidence))?.trace_id, 'trace-1')
})

test('rejects legacy bare results and non-final tools', () => {
  assert.equal(parseGraphifyEvidence('run_template_query', '{"catalog_code":"x"}'), null)
  assert.equal(parseGraphifyEvidence('semantic_context', JSON.stringify(evidence)), null)
})

test('keeps evidence from separate tool results independently parseable', () => {
  const second = { ...evidence, trace_id: 'trace-2', question: '医保支付类别' }
  assert.equal(parseGraphifyEvidence('query', JSON.stringify(evidence))?.question, '覆膜气管支架')
  assert.equal(parseGraphifyEvidence('query', JSON.stringify(second))?.question, '医保支付类别')
})

test('accepts a blocked preflight outcome without treating it as evidence', () => {
  const result = parseGraphifyToolOutcome('query_preflight', JSON.stringify({
    status: 'blocked', trace_id: 'trace-blocked', findings: [{ message: 'Only SELECT statements are allowed.' }],
  }))
  assert.deepEqual(result, { status: 'blocked', trace_id: 'trace-blocked', reason: 'Only SELECT statements are allowed.' })
})

test('uses Chinese labels for user-visible fields and graph evidence', () => {
  assert.equal(displayGraphifyLabel('catalog_code'), '目录编码')
  assert.equal(displayGraphifyLabel('model-node'), '业务模型')
  assert.equal(displayGraphifyLabel('unknown_field'), '业务字段')
  assert.equal(displayGraphifyNodeLabel({ id: 'model:medical_catalog', label: 'medical_catalog', kind: 'model' }), '医疗目录')
  const flow = toVueFlowEvidence({ ...evidence, evidence: {
    source_record_ids: [],
    nodes: [{ id: 'model:medical_catalog', label: 'medical_catalog', kind: 'model' }],
    edges: [{ id: 'edge-1', source: 'model:medical_catalog', target: 'model:medical_catalog', label: 'query', kind: 'query' }],
  } })
  assert.equal(flow.nodes[0]?.label, '医疗目录')
  assert.equal(flow.edges[0]?.label, '查询返回')
})

test('keeps real business graph nodes and relationships returned by MCP', () => {
  const flow = toVueFlowEvidence({ ...evidence, evidence: {
    source_record_ids: ['consumable:main:C1'],
    nodes: [
      { id: 'product:C1', label: '覆膜气管支架', kind: 'product' },
      { id: 'organization:company', label: '淮安市西格玛医用实业有限公司', kind: 'organization' },
      { id: 'source:file', label: '耗材映射库', kind: 'source_file' },
      { id: 'batch:import', label: '导入批次', kind: 'import_batch' },
    ],
    edges: [
      { id: 'manufacturer:C1', source: 'product:C1', target: 'organization:company', label: '生产企业', kind: 'business' },
      { id: 'lineage:C1', source: 'product:C1', target: 'source:file', label: '来源工作簿', kind: 'provenance' },
      { id: 'batch:C1', source: 'source:file', target: 'batch:import', label: '导入批次', kind: 'provenance' },
    ],
  } })
  assert.equal(flow.nodes.length, 4)
  assert.deepEqual(flow.edges.map((edge) => edge.label), ['生产企业', '来源工作簿', '导入批次'])
})

test('lays out sibling evidence nodes without overlap and assigns directional handles', () => {
  const flow = toVueFlowEvidence({ ...evidence, evidence: {
    source_record_ids: [],
    nodes: [
      { id: 'model', label: 'medical_catalog', kind: 'model' },
      { id: 'product', label: '覆膜气管支架', kind: 'product' },
      { id: 'concept-1', label: '目录映射一', kind: 'concept' },
      { id: 'concept-2', label: '目录映射二', kind: 'concept' },
      { id: 'concept-3', label: '目录映射三', kind: 'concept' },
      { id: 'source', label: '来源工作簿', kind: 'source_file' },
    ],
    edges: [
      { id: 'query', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
      { id: 'map-1', source: 'product', target: 'concept-1', label: '目录映射', kind: 'business' },
      { id: 'map-2', source: 'product', target: 'concept-2', label: '目录映射', kind: 'business' },
      { id: 'map-3', source: 'product', target: 'concept-3', label: '目录映射', kind: 'business' },
      { id: 'source-link', source: 'product', target: 'source', label: '来源工作簿', kind: 'provenance' },
    ],
  } })
  const concepts = flow.nodes.filter((node) => node.id.startsWith('concept-'))
  assert.equal(new Set(concepts.map((node) => `${node.position.x}:${node.position.y}`)).size, 3)
  assert.ok(flow.edges.every((edge) => edge.sourcePosition && edge.targetPosition))
  assert.ok(flow.edges.every((edge) => edge.sourceHandle && edge.targetHandle))
  assert.ok(flow.edges.every((edge) => edge.type === 'bezier'))
  assert.equal(flow.edges.find((edge) => edge.id === 'query')?.sourcePosition, 'right')
  assert.equal(flow.edges.find((edge) => edge.id === 'query')?.targetPosition, 'left')
  assert.equal(flow.edges.find((edge) => edge.id === 'query')?.sourceHandle, 'right-source')
  assert.equal(flow.edges.find((edge) => edge.id === 'query')?.targetHandle, 'left-target')
})

test('keeps the full evidence backbone and one query result until global query expansion', () => {
  const graphEvidence = { ...evidence, evidence: {
    source_record_ids: [],
    nodes: [
      { id: 'model', label: 'medical_catalog', kind: 'model' },
      { id: 'product', label: '覆膜气管支架', kind: 'product' },
      { id: 'registration', label: '注册备案', kind: 'registration' },
      { id: 'enterprise', label: '耗材企业', kind: 'organization' },
      { id: 'source', label: '来源工作簿', kind: 'source_file' },
    ],
    edges: [
      { id: 'query', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
      { id: 'query-2', source: 'model', target: 'enterprise', label: '查询返回', kind: 'query' },
      { id: 'registration', source: 'product', target: 'registration', label: '注册备案', kind: 'business' },
      { id: 'enterprise', source: 'registration', target: 'enterprise', label: '耗材企业', kind: 'business' },
      { id: 'source', source: 'enterprise', target: 'source', label: '来源工作簿', kind: 'provenance' },
    ],
  } }
  const initial = baseEvidenceNodeIds(graphEvidence)
  assert.deepEqual([...initial].sort(), ['model', 'product', 'registration', 'source'])
  assert.deepEqual(toVueFlowEvidence(graphEvidence, initial).nodes.map((node) => node.id).sort(), ['model', 'product', 'registration', 'source'])
})
