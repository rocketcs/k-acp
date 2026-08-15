import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'
import { expandSelection } from './expandSelection.ts'

const nodes: GraphifyEvidenceNode[] = [
  { id: 'product', label: '覆膜气管支架', kind: 'product' },
  { id: 'model', label: 'medical_catalog', kind: 'model' },
  { id: 'registration', label: '注册证', kind: 'registration' },
  { id: 'catalog_record', label: '记录', kind: 'catalog_record' },
  { id: 'source_file', label: '工作簿', kind: 'source_file' },
  { id: 'record', label: 'record:C1', kind: 'record' },
]
const edges: GraphifyEvidenceEdge[] = [
  { id: 'q1', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
  { id: 'b1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
  { id: 'p1', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
  { id: 'p2', source: 'catalog_record', target: 'source_file', label: '来源', kind: 'provenance' },
  { id: 'p3', source: 'catalog_record', target: 'record', label: '来源记录', kind: 'provenance' },
]

test('empty expansion shows only root product + its level-1 neighbors', () => {
  const visible = expandSelection(nodes, edges, new Set())
  // root(s): product & model. level-1 of product: model(原query但query被排除), registration, catalog_record
  assert.ok(visible.has('product'))
  assert.ok(visible.has('registration'))
  assert.ok(visible.has('catalog_record'))
  // 深层（source_file 需经 catalog_record）与查询过程（record）不出现
  assert.equal(visible.has('source_file'), false)
  assert.equal(visible.has('record'), false)
})

test('expanding a node reveals its next-level neighbors', () => {
  const expanded = expandSelection(nodes, edges, new Set(['catalog_record']))
  assert.ok(expanded.has('source_file'))
  // 查询过程节点 record 仍不出现
  assert.equal(expanded.has('record'), false)
})

test('query edges are excluded by default; model root kept', () => {
  const visible = expandSelection(nodes, edges, new Set())
  assert.ok(visible.has('model'))
})
