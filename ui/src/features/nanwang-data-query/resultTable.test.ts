import assert from 'node:assert/strict'
import test from 'node:test'
import { buildResultColumns } from './resultTable.ts'
import type { GraphifyEvidenceEnvelope } from './types.ts'

function makeEvidence(overrides: Partial<GraphifyEvidenceEnvelope> = {}): GraphifyEvidenceEnvelope {
  return {
    status: 'executed',
    trace_id: 'trace-1',
    dataset_id: 'medical_catalog',
    question: 'q',
    result: { columns: ['catalog_name', 'catalog_domain', 'empty_col'], rows: [], truncated: false },
    semantic_context: {
      graph_version: 'v1',
      recommended_models: [],
      recommended_columns: [],
      rules: [],
      provenance: {},
    },
    evidence: { source_record_ids: [], nodes: [], edges: [] },
    ...overrides,
  } as GraphifyEvidenceEnvelope
}

test('drops fully-empty columns', () => {
  const evidence = makeEvidence({
    result: {
      columns: ['catalog_name', 'catalog_domain', 'empty_col'],
      column_labels: { catalog_name: '目录名称', catalog_domain: '目录领域' },
      rows: [
        { catalog_name: '阿莫西林', catalog_domain: 'DRUG', empty_col: null },
        { catalog_name: '布洛芬', catalog_domain: 'CONSUMABLE', empty_col: '' },
      ],
      truncated: false,
    },
  })
  const cols = buildResultColumns(evidence)
  assert.deepEqual(cols.map((c) => c.key), ['catalog_name', 'catalog_domain'])
})

test('maps catalog domain via backend labels', () => {
  const evidence = makeEvidence({
    result: {
      columns: ['catalog_domain'],
      column_labels: { catalog_domain: '目录领域' },
      rows: [{ catalog_domain: 'DRUG' }, { catalog_domain: 'CONSUMABLE' }],
      truncated: false,
    },
  })
  const cols = buildResultColumns(evidence, { DRUG: '药品', CONSUMABLE: '耗材' })
  assert.equal(cols[0]!.formatValue('DRUG'), '药品')
  assert.equal(cols[0]!.formatValue('CONSUMABLE'), '耗材')
  assert.equal(cols[0]!.formatValue('UNKNOWN'), 'UNKNOWN')
})

test('returns empty array when no columns', () => {
  const evidence = makeEvidence({ result: { columns: [], rows: [], truncated: false } })
  assert.deepEqual(buildResultColumns(evidence), [])
})
