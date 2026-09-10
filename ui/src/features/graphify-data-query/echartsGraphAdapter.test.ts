import assert from 'node:assert/strict'
import test from 'node:test'
import { getEchartsGraphPresentation, toEchartsGraphData } from './echartsGraphAdapter.ts'
import type { GraphView } from './graphView.ts'

const graphView: GraphView = {
  id: 'trace-1',
  title: '测试图谱',
  nodes: [
    {
      id: 'product', entityType: 'product', rawKind: 'product', label: '覆膜气管支架',
      displayProperties: { fullLabel: '覆膜气管支架' },
    },
    {
      id: 'registration', entityType: 'registration', rawKind: 'registration', label: '注册备案\n国械注准',
    },
  ],
  edges: [{
    id: 'registration-edge', source: 'product', target: 'registration', relationType: 'business', label: '对应注册备案',
  }],
  categories: [
    { key: 'product', name: '业务对象', color: '#2563eb' },
    { key: 'registration', name: '编号/标识', color: '#ea580c' },
  ],
  stats: { nodeCount: 2, edgeCount: 1, totalNodeCount: 2 },
}

test('toEchartsGraphData follows the reference explorer node mapping', () => {
  const data = toEchartsGraphData(graphView)

  assert.equal(data.categories.length, 2)
  assert.equal(data.data.length, 2)
  assert.equal(data.links.length, 1)
  assert.deepEqual(data.categories.map((category) => category.name), ['业务对象', '编号/标识'])
  const product = data.data.find((node) => node.id === 'product')
  assert.equal(product?.name, 'product')
  assert.equal(product?.label, '覆膜气管支架')
  assert.equal(product?.x, undefined)
  assert.equal(product?.y, undefined)
  assert.equal(product?.category, 0)
  assert.equal(product?.symbolSize, 58)
  assert.equal(product?.value, '覆膜气管支架')
})

test('toEchartsGraphData keeps edge labels as the reference option consumes them', () => {
  const data = toEchartsGraphData(graphView)
  const edge = data.links[0]
  const registration = data.data.find((node) => node.id === 'registration')

  assert.equal(registration?.category, 1)
  assert.deepEqual(edge, {
    id: 'registration-edge',
    source: 'product',
    target: 'registration',
    label: '对应注册备案',
  })
})

test('all graph sizes use the local Graph Explorer force profile', () => {
  const presentation = getEchartsGraphPresentation(62, 61)

  assert.equal(presentation.showEdgeLabels, true)
  assert.equal(presentation.repulsion, 360)
  assert.deepEqual(presentation.edgeLength, [90, 180])
})
