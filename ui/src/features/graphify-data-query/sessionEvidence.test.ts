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

test('returns empty mapping when no tool results', () => {
  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: 'hi' }),
    message({ id: 2, role: 'assistant', content: 'hello' }),
  ])
  assert.deepEqual(map, {})
})
