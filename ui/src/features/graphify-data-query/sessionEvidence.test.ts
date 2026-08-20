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

test('returns empty mapping when no tool results', () => {
  const map = buildSessionEvidence([
    message({ id: 1, role: 'user', content: 'hi' }),
    message({ id: 2, role: 'assistant', content: 'hello' }),
  ])
  assert.deepEqual(map, {})
})
