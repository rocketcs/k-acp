import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types.ts'
import { graphEdgeLabel, graphRelationSentence, graphRelationSummary } from './evidencePresentation.ts'

const nodes: GraphifyEvidenceNode[] = [
  { id: 'product', label: '覆膜气管支架', kind: 'product' },
  { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
  { id: 'organization', label: '淮安市西格玛医用实业有限公司', kind: 'organization' },
  { id: 'source-file', label: '耗材谈判记录', kind: 'source_file' },
  { id: 'record', label: 'record:C1', kind: 'catalog_record' },
]
const nodeById = new Map(nodes.map((node) => [node.id, node]))
const edges: GraphifyEvidenceEdge[] = [
  { id: 'registration-edge', source: 'product', target: 'registration', label: '对应', kind: 'business' },
  { id: 'organization-edge', source: 'product', target: 'organization', label: '生产', kind: 'business' },
  { id: 'source-edge', source: 'product', target: 'source-file', label: '来源', kind: 'provenance' },
  { id: 'hidden-edge', source: 'product', target: 'record', label: '来源', kind: 'provenance' },
]
const envelope: GraphifyEvidenceEnvelope = {
  status: 'executed', trace_id: 'trace-1', dataset_id: 'medical_catalog', question: '覆膜气管支架',
  result: { columns: [], rows: [], truncated: false },
  semantic_context: { graph_version: 'v1', recommended_models: [], recommended_columns: [], rules: [], provenance: {} },
  evidence: { source_record_ids: ['record:C1', 'import:batch', '0123456789abcdef0123456789abcdef'], nodes, edges },
}

test('describes a product to registration relation with a business label', () => {
  assert.equal(graphEdgeLabel(edges[0]!, nodeById), '对应注册备案')
  assert.equal(graphRelationSentence(edges[0]!, nodeById), '覆膜气管支架对应注册备案号：国械注准20173134669。')
})

test('describes a product to organization relation as production', () => {
  assert.equal(graphEdgeLabel(edges[1]!, nodeById), '生产企业')
  assert.equal(graphRelationSentence(edges[1]!, nodeById), '覆膜气管支架由淮安市西格玛医用实业有限公司生产。')
})

test('summarizes at most three readable relations and hides internal lineage ids', () => {
  const summary = graphRelationSummary(envelope)
  assert.match(summary ?? '', /覆膜气管支架对应注册备案号/)
  assert.match(summary ?? '', /耗材谈判记录/)
  assert.doesNotMatch(summary ?? '', /record:C1|import:batch|[a-f0-9]{32}/i)
})

test('returns a safe fallback for an unknown readable relation', () => {
  const unknown = { id: 'unknown', source: 'product', target: 'organization', label: 'CUSTOM_LINK', kind: 'business' } as const
  assert.equal(graphEdgeLabel(unknown, nodeById), '相关')
  assert.equal(graphRelationSentence(unknown, nodeById), '覆膜气管支架与淮安市西格玛医用实业有限公司相关。')
})

test('hides raw labels for unknown semantic relations', () => {
  const unknown = { id: 'semantic-unknown', source: 'product', target: 'registration', label: 'HAS_OBSERVED_LABEL', kind: 'semantic' } as const
  assert.doesNotMatch(graphEdgeLabel(unknown, nodeById), /HAS_OBSERVED_LABEL/)
})

test('hides whitespace-prefixed internal ids and physical names from sentences and summaries', () => {
  const internalLabels = [
    ' record:C1',
    ' raw.catalog_record',
    ' public.medical_excel_consumable_negotiation_records',
    ' 0123456789abcdef0123456789abcdef',
  ]

  for (const [index, label] of internalLabels.entries()) {
    const targetId = `internal-${index}`
    const target = { id: targetId, label, kind: 'catalog_record' } as const
    const edge = { id: `internal-edge-${index}`, source: 'product', target: targetId, label: '来源', kind: 'business' } as const
    const localNodes = new Map(nodeById).set(targetId, target)
    const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

    assert.equal(graphRelationSentence(edge, localNodes), '覆膜气管支架与原始目录记录相关。')
    const summary = graphRelationSummary(localEnvelope)
    assert.doesNotMatch(summary ?? '', /record:C1|raw\.catalog_record|public\.medical_excel_consumable_negotiation_records|0123456789abcdef0123456789abcdef/)
  }
})

test('rejects import batches, unqualified physical tables, and batch identifiers', () => {
  const rejectedLabels = [
    { label: '导入批次-20260816', kind: 'import_batch' as const },
    { label: 'medical_excel_consumable_negotiation_records', kind: 'catalog_record' as const },
    { label: 'catalog_records', kind: 'catalog_record' as const },
    { label: 'batch_20260816', kind: 'catalog_record' as const },
    { label: '550e8400-e29b-41d4-a716-446655440000', kind: 'catalog_record' as const },
    { label: '00000000-0000-0000-0000-000000000000', kind: 'catalog_record' as const },
  ]

  for (const [index, item] of rejectedLabels.entries()) {
    const targetId = `rejected-${index}`
    const target = { id: targetId, label: item.label, kind: item.kind } as const
    const edge = { id: `rejected-edge-${index}`, source: 'product', target: targetId, label: '来源', kind: 'provenance' } as const
    const localNodes = new Map(nodeById).set(targetId, target)
    const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

    assert.equal(graphRelationSentence(edge, localNodes), `该信息由${item.kind === 'import_batch' ? '导入批次' : '原始目录记录'}佐证。`)
    assert.doesNotMatch(graphRelationSummary(localEnvelope) ?? '', new RegExp(item.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
})

test('uses only safe source names in provenance sentences and summaries', () => {
  const unsafeSources = [
    { label: '内部导入批次-20260816', kind: 'import_batch' as const, heading: '导入批次' },
    { label: '查询记录 #42', kind: 'record' as const, heading: '查询记录' },
    { label: '内部来源', kind: 'source' as const, heading: '来源记录' },
    { label: 'medical_reconciliation_ledger', kind: 'catalog_record' as const, heading: '原始目录记录' },
    { label: 'sha256:abcdef0123456789abcdef0123456789', kind: 'catalog_record' as const, heading: '原始目录记录' },
    { label: 'SHA-256=abcdef0123456789abcdef0123456789', kind: 'source_file' as const, heading: '来源工作簿' },
    { label: 'md5:abcdef0123456789abcdef01234567', kind: 'source_file' as const, heading: '来源工作簿' },
  ]

  for (const [index, item] of unsafeSources.entries()) {
    const targetId = `unsafe-source-${index}`
    const target = { id: targetId, label: item.label, kind: item.kind } as const
    const edge = { id: `unsafe-source-edge-${index}`, source: 'product', target: targetId, label: '来源', kind: 'provenance' } as const
    const localNodes = new Map(nodeById).set(targetId, target)
    const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

    const sentence = graphRelationSentence(edge, localNodes)
    assert.equal(sentence, `该信息由${item.heading}佐证。`)
    assert.doesNotMatch(sentence ?? '', new RegExp(item.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'))
    const summary = graphRelationSummary(localEnvelope)
    assert.match(summary ?? '', new RegExp(item.heading))
    assert.doesNotMatch(summary ?? '', new RegExp(item.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'))
  }
})

test('keeps a human-readable source file name in provenance text', () => {
  const source = { id: 'human-source-file', label: '耗材谈判记录', kind: 'source_file' } as const
  const edge = { id: 'human-source-edge', source: 'product', target: source.id, label: '来源', kind: 'provenance' } as const
  const localNodes = new Map(nodeById).set(source.id, source)
  const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, source], edges: [edge] } }

  assert.equal(graphRelationSentence(edge, localNodes), '该信息由耗材谈判记录佐证。')
  assert.match(graphRelationSummary(localEnvelope) ?? '', /耗材谈判记录/)
})

test('falls back to generic headings when source labels contain embedded internal tokens', () => {
  const unsafeLabels = [
    '耗材谈判记录 record:C1',
    '耗材谈判记录 public.medical_reconciliation_ledger',
    '耗材谈判记录 catalog_records',
    '耗材谈判记录 BLAKE3:0123456789abcdef0123456789abcdef',
  ]

  for (const [index, label] of unsafeLabels.entries()) {
    const targetId = `embedded-unsafe-${index}`
    const target = { id: targetId, label, kind: 'catalog_record' } as const
    const edge = { id: `embedded-unsafe-edge-${index}`, source: 'product', target: targetId, label: '来源', kind: 'provenance' } as const
    const localNodes = new Map(nodeById).set(targetId, target)
    const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

    const sentence = graphRelationSentence(edge, localNodes)
    assert.equal(sentence, '该信息由原始目录记录佐证。')
    assert.doesNotMatch(sentence ?? '', /record:C1|public\.medical_reconciliation_ledger|catalog_records|BLAKE3:0123456789abcdef0123456789abcdef/)
    const summary = graphRelationSummary(localEnvelope)
    assert.equal(summary, '该信息由原始目录记录佐证。')
    assert.doesNotMatch(summary ?? '', /record:C1|public\.medical_reconciliation_ledger|catalog_records|BLAKE3:0123456789abcdef0123456789abcdef/)
  }
})

test('falls back when a source label contains an embedded UUID', () => {
  const targetId = 'embedded-uuid'
  const target = { id: targetId, label: '耗材谈判记录 550e8400-e29b-41d4-a716-446655440000', kind: 'catalog_record' } as const
  const edge = { id: 'embedded-uuid-edge', source: 'product', target: targetId, label: '来源', kind: 'provenance' } as const
  const localNodes = new Map(nodeById).set(targetId, target)
  const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

  assert.equal(graphRelationSentence(edge, localNodes), '该信息由原始目录记录佐证。')
  const summary = graphRelationSummary(localEnvelope)
  assert.equal(summary, '该信息由原始目录记录佐证。')
  assert.doesNotMatch(summary ?? '', /550e8400-e29b-41d4-a716-446655440000/)
})

test('hides uppercase physical raw-code source labels', () => {
  const targetId = 'uppercase-raw-code'
  const target = { id: targetId, label: 'HAS_OBSERVED_LABEL', kind: 'catalog_record' } as const
  const edge = { id: 'uppercase-raw-code-edge', source: 'product', target: targetId, label: '来源', kind: 'provenance' } as const
  const localNodes = new Map(nodeById).set(targetId, target)
  const localEnvelope = { ...envelope, evidence: { ...envelope.evidence, nodes: [...nodes, target], edges: [edge] } }

  assert.equal(graphRelationSentence(edge, localNodes), '该信息由原始目录记录佐证。')
  const summary = graphRelationSummary(localEnvelope)
  assert.equal(summary, '该信息由原始目录记录佐证。')
  assert.doesNotMatch(summary ?? '', /HAS_OBSERVED_LABEL/)
})

test('does not create a sentence when a relation endpoint is not readable', () => {
  const hidden = { id: 'hidden', source: 'product', target: 'missing', label: '对应', kind: 'business' } as const
  assert.equal(graphRelationSentence(hidden, nodeById), null)
})
