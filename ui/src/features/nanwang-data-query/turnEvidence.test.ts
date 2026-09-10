import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeTurnEvidence } from './turnEvidence.ts'

const evidence = { status: 'executed', trace_id: 'trace-success', dataset_id: 'medical_catalog', question: '阿莫西林', result: { columns: [], rows: [], truncated: false }, semantic_context: { graph_version: 'v1', recommended_models: [], recommended_columns: [], rules: [], provenance: {} }, evidence: { source_record_ids: [], nodes: [], edges: [] } } as const
const blocked = { status: 'blocked', trace_id: 'trace-blocked', reason: '字段未发布' } as const

test('preserves a completed evidence graph after a later blocked preflight', () => {
  const merged = mergeTurnEvidence({ evidence }, { outcome: blocked })
  assert.equal(merged.evidence?.trace_id, 'trace-success')
  assert.equal(merged.outcome, undefined)
})

test('keeps a blocked outcome when no fact query completed', () => {
  assert.deepEqual(mergeTurnEvidence(undefined, { outcome: blocked }), { outcome: blocked })
})

test('removes a stale blocked outcome when a later fact query completes', () => {
  const merged = mergeTurnEvidence({ outcome: blocked }, { evidence })
  assert.equal(merged.evidence?.trace_id, 'trace-success')
  assert.equal(merged.outcome, undefined)
})
