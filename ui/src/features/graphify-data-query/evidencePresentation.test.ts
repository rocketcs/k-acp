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

test('does not create a sentence when a relation endpoint is not readable', () => {
  const hidden = { id: 'hidden', source: 'product', target: 'missing', label: '对应', kind: 'business' } as const
  assert.equal(graphRelationSentence(hidden, nodeById), null)
})
