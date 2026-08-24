import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const component = readFileSync(new URL('./GraphifyEchartsGraph.vue', import.meta.url), 'utf8')

test('GraphifyEchartsGraph uses the reference graph explorer force option', () => {
  assert.match(component, /graphView: GraphView/)
  assert.match(component, /toEchartsGraphData\(props\.graphView\)/)
  assert.match(component, /type:\s*'graph'/)
  assert.match(component, /layout:\s*'force'/)
  assert.match(component, /draggable:\s*true/)
  assert.match(component, /getEchartsGraphPresentation/)
  assert.match(component, /repulsion: presentation\.value\.repulsion/)
  assert.match(component, /edgeLength: presentation\.value\.edgeLength/)
  assert.match(component, /lineStyle:\s*\{\s*color:\s*'#94a3b8',\s*curveness:\s*0\.08,\s*width:\s*1\.5\s*\}/)
  assert.match(component, /fontSize:\s*12,\s*color:\s*'#0f172a'/)
  assert.match(component, /show: presentation\.value\.showEdgeLabels/)
  assert.match(component, /<VChart/)
})

test('GraphifyEchartsGraph uses the same animated force profile as the reference explorer', () => {
  assert.doesNotMatch(component, /animation:\s*false/)
  assert.doesNotMatch(component, /layoutAnimation:\s*false/)
})

test('GraphifyEchartsGraph uses the reference explorer adjacency emphasis', () => {
  assert.match(component, /emphasis:\s*\{\s*focus:\s*'adjacency'\s*as const\s*\}/)
})

test('GraphifyEchartsGraph keeps the reference explorer interaction contract and emits node selection', () => {
  assert.match(component, /select: \[nodeId: string\]/)
  assert.match(component, /emit\('select', String\(data\.id\)\)/)
  assert.match(component, /backgroundColor:\s*'#fff'/)
  assert.match(component, /defineExpose\(\{ fit \}\)/)
  assert.match(component, /watch\(\(\) => props\.fullscreen/)
  assert.doesNotMatch(component, /graphify-echarts-graph-summary/)
  assert.doesNotMatch(component, /background-image/)
  assert.doesNotMatch(component, /ToolboxComponent/)
  assert.doesNotMatch(component, /toolbox:/)
  assert.doesNotMatch(component, /legend:/)
})

test('GraphifyEchartsGraph persists dragged node coordinates as fixed force nodes', () => {
  assert.match(component, /fixedPositions = ref\(new Map<string, \{ x: number; y: number \}>\(\)\)/)
  assert.match(component, /fixed: true/)
  assert.match(component, /function onChartMouseup\(params: ECElementEvent\)/)
  assert.match(component, /function onChartMousedown\(params: ECElementEvent\)/)
  assert.match(component, /function onChartMousemove\(params: ECElementEvent\)/)
  assert.match(component, /getItemLayout\?\.\(dataIndex\)/)
  assert.match(component, /@mousedown="onChartMousedown"/)
  assert.match(component, /@mousemove="onChartMousemove"/)
  assert.match(component, /@mouseup="onChartMouseup"/)
  assert.match(component, /watch\(\(\) => props\.graphView\.id/)
})
