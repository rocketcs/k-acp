import assert from 'node:assert/strict'
import test from 'node:test'
import { buildSessionEvidence } from './sessionEvidence.ts'
import type { ChatMessageVO } from '@/types'

function message(partial: Partial<ChatMessageVO> & { id: string | number; role: string; content: string }): ChatMessageVO {
  return {
    sessionId: 's1',
    parentId: null,
    path: '',
    depth: 0,
    createdAt: '',
    ...partial,
  }
}

function executedEnvelope(question: string) {
  return JSON.stringify({
    status: 'executed',
    dataset_id: 'medical_catalog',
    trace_id: `t-${question}`,
    question,
    result: { columns: ['catalog_name'], rows: [{ catalog_name: '阿莫西林' }], truncated: false },
    semantic_context: {
      graph_version: 'v1',
      recommended_models: [],
      recommended_columns: [],
      rules: [],
      provenance: {},
    },
    evidence: { source_record_ids: [], nodes: [], edges: [] },
  })
}

function blockedEnvelope(reason: string) {
  return JSON.stringify({ status: 'blocked', reason })
}

function toolMessage(id: string | number, resultJson: string) {
  return message({
    id,
    role: 'tool',
    content: JSON.stringify({ name: 'query', result: resultJson }),
  })
}

function namedToolMessage(id: string | number, name: string, resultJson: string) {
  return message({
    id,
    role: 'tool',
    content: JSON.stringify({ name, result: resultJson }),
  })
}

test('maps a turn tool result to the following assistant message', () => {
  const evidence = executedEnvelope('q1')
  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: '查询药品' }),
    toolMessage(2, evidence),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])
  assert.ok(map['3']?.evidence)
  assert.equal(map['3']?.evidence?.question, 'q1')
})

test('restores a directly persisted executed result for the following assistant message', () => {
  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: '查询药品' }),
    message({ id: 2, role: 'tool', content: executedEnvelope('persisted-query') }),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])
  assert.equal(map['3']?.evidence?.question, 'persisted-query')
  assert.equal(map['3']?.neo4jGraph, undefined)
})

test('each assistant message only receives its own preceding tool result', () => {
  const map = buildSessionEvidence([
    toolMessage(1, executedEnvelope('q1')),
    message({ id: 2, role: 'assistant', content: 'A' }),
    toolMessage(3, executedEnvelope('q2')),
    message({ id: 4, role: 'assistant', content: 'B' }),
  ])
  assert.equal(map['2']?.evidence?.question, 'q1')
  assert.equal(map['4']?.evidence?.question, 'q2')
})

test('maps a blocked outcome without evidence', () => {
  const map = buildSessionEvidence([
    toolMessage(1, blockedEnvelope('规则拦截')),
    message({ id: 2, role: 'assistant', content: '无法查询' }),
  ])
  assert.equal(map['2']?.outcome?.status, 'blocked')
  assert.equal(map['2']?.evidence, undefined)
})

test('replaces the query fallback graph with official Neo4j read-cypher nodes for the same turn', () => {
  const initial = JSON.parse(executedEnvelope('阿莫西林'))
  initial.evidence = {
    source_record_ids: ['source-1'],
    nodes: [{ id: 'product:阿莫西林', label: '阿莫西林', kind: 'product' }],
    edges: [],
  }
  const officialGraph = JSON.stringify([{
    source_id: '4:product:1', source_labels: ['DrugProduct'], source_properties: { generic_name: '阿莫西林' },
    relation_type: 'MANUFACTURED_BY',
    target_id: '4:org:1', target_labels: ['Organization'], target_properties: { name: '示例制药有限公司' },
  }])

  const map = buildSessionEvidence([
    toolMessage(1, JSON.stringify(initial)),
    namedToolMessage(2, 'read-cypher', officialGraph),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])

  assert.deepEqual(map['3']?.evidence?.evidence.nodes, [
    { id: 'neo4j:4:product:1', label: '阿莫西林', kind: 'product', domain: 'DRUG' },
    { id: 'neo4j:4:org:1', label: '示例制药有限公司', kind: 'organization' },
  ])
  assert.equal(map['3']?.evidence?.evidence.edges[0]?.label, '生产企业')
  assert.deepEqual(map['3']?.evidence?.evidence.source_record_ids, ['source-1'])
  assert.deepEqual(map['3']?.neo4jGraph, {
    nodes: [
      { id: 'neo4j:4:product:1', label: '阿莫西林', kind: 'product', domain: 'DRUG' },
      { id: 'neo4j:4:org:1', label: '示例制药有限公司', kind: 'organization' },
    ],
    edges: [{
      id: 'neo4j:4:product:1:MANUFACTURED_BY:neo4j:4:org:1',
      source: 'neo4j:4:product:1',
      target: 'neo4j:4:org:1',
      label: '生产企业',
      kind: 'business',
    }],
  })
})

test('uses only real Neo4j nodes from evidence_subgraph for the answer graph', () => {
  const query = JSON.parse(executedEnvelope('聚维酮碘含漱液'))
  query.evidence.nodes = [{ id: 'model:medical_catalog', label: '医保目录', kind: 'model' }]
  const graph = JSON.parse(executedEnvelope('聚维酮碘含漱液'))
  graph.evidence.nodes = [
    { id: 'model:medical_catalog', label: '医保目录', kind: 'model' },
    { id: 'product:聚维酮碘含漱液', label: '聚维酮碘含漱液', kind: 'product' },
    { id: 'catalog-record:1', label: '原始目录记录', kind: 'catalog_record' },
    { id: 'catalog-attribute:1', label: '规格：80ml', kind: 'attribute' },
  ]
  graph.evidence.edges = [
    { id: 'semantic', source: 'model:medical_catalog', target: 'product:聚维酮碘含漱液', label: '语义字段', kind: 'semantic' },
    { id: 'evidence', source: 'product:聚维酮碘含漱液', target: 'catalog-record:1', label: '原始目录记录', kind: 'provenance' },
    { id: 'attribute', source: 'catalog-record:1', target: 'catalog-attribute:1', label: '目录字段', kind: 'attribute' },
  ]

  const map = buildSessionEvidence([
    toolMessage(1, JSON.stringify(query)),
    namedToolMessage(2, 'evidence_subgraph', JSON.stringify(graph)),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])

  assert.deepEqual(map['3']?.neo4jGraph?.nodes.map((node) => node.id), [
    'product:聚维酮碘含漱液', 'catalog-record:1', 'catalog-attribute:1',
  ])
  assert.deepEqual(map['3']?.neo4jGraph?.edges.map((edge) => edge.id), ['evidence', 'attribute'])
})

test('keeps a compact graph reference alongside query evidence for async hydration', () => {
  const summary = JSON.stringify({
    status: 'executed', trace_id: 't-ref', dataset_id: 'medical_catalog', graph_ref: 't-ref',
    node_count: 12, edge_count: 14, source_record_count: 2,
  })
  const map = buildSessionEvidence([
    toolMessage(1, executedEnvelope('异步图谱')),
    namedToolMessage(2, 'evidence_subgraph', summary),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])
  assert.equal(map['3']?.evidence?.question, '异步图谱')
  assert.equal(map['3']?.graphRef?.graph_ref, 't-ref')
})

test('restores a legacy bare evidence_subgraph envelope as an answer graph', () => {
  const graph = JSON.parse(executedEnvelope('广东企业目录项'))
  graph.evidence.nodes = [
    { id: 'model:medical_catalog', label: '医保目录', kind: 'model' },
    { id: 'product:1', label: '盐酸氨溴索口服溶液', kind: 'product' },
    { id: 'catalog-record:1', label: '原始目录记录', kind: 'catalog_record' },
    { id: 'attribute:1', label: '规格：100ml', kind: 'attribute' },
  ]
  graph.evidence.edges = [
    { id: 'provenance', source: 'product:1', target: 'catalog-record:1', label: '原始记录', kind: 'provenance' },
    { id: 'attribute', source: 'catalog-record:1', target: 'attribute:1', label: '目录字段', kind: 'attribute' },
  ]

  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: '查询广东企业目录项' }),
    message({ id: 2, role: 'tool', content: JSON.stringify(graph) }),
    message({ id: 3, role: 'assistant', content: '以下是结果' }),
  ])

  assert.deepEqual(map['3']?.neo4jGraph, {
    nodes: graph.evidence.nodes.slice(1),
    edges: graph.evidence.edges,
  })
})

test('returns empty mapping when no tool results', () => {
  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: 'hi' }),
    message({ id: 2, role: 'assistant', content: 'hello' }),
  ])
  assert.deepEqual(map, {})
})
