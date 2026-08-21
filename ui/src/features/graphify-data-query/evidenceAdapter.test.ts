import assert from 'node:assert/strict'
import test from 'node:test'
import { displayGraphifyLabel, displayGraphifyNodeLabel, parseGraphifyEvidence, parseGraphifyToolOutcome } from './evidenceAdapter.ts'

const evidence = {
  status: 'executed', trace_id: 'trace-1', dataset_id: 'medical_catalog', question: '覆膜气管支架',
  result: { columns: ['catalog_code'], rows: [{ catalog_code: 'C0101010011303807555' }], truncated: false },
  semantic_context: { graph_version: 'v1', recommended_models: ['medical_catalog'], recommended_columns: [], rules: [], provenance: {} },
  evidence: { source_record_ids: ['consumable:main_catalog:C0101010011303807555:6:2'], nodes: [], edges: [] },
}

test('accepts an executed medical-catalog evidence envelope', () => {
  assert.equal(parseGraphifyEvidence('query', JSON.stringify(evidence))?.trace_id, 'trace-1')
})

test('accepts an executed envelope when trace_id/question are empty (direct query tool)', () => {
  const bare = { ...evidence, trace_id: '', question: '' }
  const parsed = parseGraphifyEvidence('query', JSON.stringify(bare))
  assert.equal(parsed?.trace_id, '')
  assert.equal(parsed?.question, '')
  assert.equal(parsed?.result.rows.length, 1)
})

test('rejects legacy bare results', () => {
  assert.equal(parseGraphifyEvidence('query', '{"catalog_code":"x"}'), null)
})

test('accepts a complete evidence envelope when the runtime reports an unexpected tool name', () => {
  assert.equal(parseGraphifyEvidence('runtime-tool-lookup-fallback', JSON.stringify(evidence))?.trace_id, 'trace-1')
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
  assert.equal(displayGraphifyLabel('payment_category'), '医保支付类别')
  assert.equal(displayGraphifyLabel('max_limit_text'), '最高限额')
  // 详情字段集（详情 query 完整投影）的中文映射
  assert.equal(displayGraphifyLabel('material'), '材质')
  assert.equal(displayGraphifyLabel('feature'), '特征')
  assert.equal(displayGraphifyLabel('policy_no'), '政策号')
  assert.equal(displayGraphifyLabel('spec_model_count'), '规格型号数')
  assert.equal(displayGraphifyLabel('registrant_name'), '注册备案人')
  assert.equal(displayGraphifyLabel('medical_generic_name'), '医保通用名')
  // 未映射键回显原值，绝不显示"业务字段"这类误导性占位表头
  assert.equal(displayGraphifyLabel('unknown_field'), 'unknown_field')
  assert.equal(displayGraphifyNodeLabel({ id: 'model:medical_catalog', label: 'medical_catalog', kind: 'model' }), '医保目录')
})
