import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'
import { dagrePositions } from './dagreLayout.ts'

const nodes: GraphifyEvidenceNode[] = [
  { id: 'product', label: '覆膜气管支架', kind: 'product' },
  { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
  { id: 'organization', label: '淮安市西格玛医用实业有限公司', kind: 'organization' },
  { id: 'catalog_record', label: '…:6:2', kind: 'catalog_record' },
  { id: 'source_file', label: '耗材映射库', kind: 'source_file' },
]
const edges: GraphifyEvidenceEdge[] = [
  { id: 'e1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
  { id: 'e2', source: 'product', target: 'organization', label: '生产', kind: 'business' },
  { id: 'e3', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
  { id: 'e4', source: 'catalog_record', target: 'source_file', label: '包含', kind: 'provenance' },
]

test('LR layout flows left-to-right: source rank left, sinks right', () => {
  const pos = dagrePositions(nodes, edges, { nodeWidth: 150, nodeHeight: 56, rankdir: 'LR' })
  assert.ok(pos.get('product')!.x < pos.get('registration')!.x)
  assert.ok(pos.get('product')!.x < pos.get('organization')!.x)
  assert.ok(pos.get('catalog_record')!.x < pos.get('source_file')!.x)
})

test('sibling nodes never overlap vertically', () => {
  const pos = dagrePositions(nodes, edges, { nodeWidth: 150, nodeHeight: 56, rankdir: 'LR' })
  const registration = pos.get('registration')!
  const organization = pos.get('organization')!
  assert.ok(Math.abs(registration.y - organization.y) >= 56, 'siblings must not overlap')
})

test('returns center coordinates that dagre reports', () => {
  const pos = dagrePositions([nodes[0]!], [], { nodeWidth: 150, nodeHeight: 56 })
  const point = pos.get('product')!
  assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y))
})
