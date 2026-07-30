import assert from 'node:assert/strict'
import test from 'node:test'
import { parseLargeScreenImageTemplateV2 } from './templateParser.ts'

const fixture = {
  version: '2',
  title: '城市运行态势感知大屏',
  confidence: 'HIGH',
  observedVisualFacts: ['深蓝低饱和背景', '中心核心拓扑与左右信息分栏'],
  canvas: { ratio: '16:9', coordinateSystem: 'normalized-1000', grid: '12-column' },
  visualTokens: {
    palette: ['#071B3A', '#00D9FF', '#2B75FF'],
    surface: '深蓝磨砂面板',
    border: '青蓝发光细边框',
    typography: '无衬线数字字体',
  },
  regions: [
    {
      id: 'header', label: '顶部标题状态', bounds: { x: 0, y: 0, width: 1000, height: 100 },
      layer: 1, component: 'title-status', purpose: '展示标题和全局状态', locked: true,
      replaceable: ['title', 'statusText'],
    },
    {
      id: 'left-cluster', label: '左侧节点集群', bounds: { x: 0, y: 100, width: 300, height: 800 },
      layer: 2, component: 'topology-cluster', purpose: '展示左侧业务节点', locked: true,
      replaceable: ['businessLabels', 'icons'],
    },
    {
      id: 'core', label: '核心拓扑', bounds: { x: 300, y: 100, width: 400, height: 800 },
      layer: 2, component: 'core-topology', purpose: '展示核心关系和关键指标', locked: true,
      replaceable: ['metricMeanings', 'chartData'],
    },
  ],
  relations: [{ from: 'core', to: 'left-cluster', kind: 'topology-link', locked: true }],
  preservation: {
    mode: 'preserve-layout',
    mustKeep: ['region-bounds', 'information-hierarchy', 'locked-relations', 'palette-proportion'],
    mayReplace: ['business-labels', 'metric-meanings', 'chart-data', 'icons'],
  },
  prompt: '16:9 城市运行态势感知数据大屏，保持中心拓扑与左右分栏，深蓝背景和青蓝发光边框。',
  negativePrompt: '低清晰度、杂乱文字、错误图表、密集水印',
  iterationHints: ['更商务时降低霓虹强度', '更高密度时增加右侧列表区'],
} as const

function fenced(value: unknown) {
  return `\`\`\`large-screen-image-plan\n${JSON.stringify(value)}\n\`\`\``
}

function clonedFixture() {
  return JSON.parse(JSON.stringify(fixture)) as Record<string, any>
}

function withBounds(regionId: string, bounds: Record<string, unknown>) {
  const value = clonedFixture()
  value.regions = value.regions.map((region: Record<string, unknown>) =>
    region.id === regionId ? { ...region, bounds } : region,
  )
  return value
}

function withDanglingRelation() {
  const value = clonedFixture()
  value.relations = [{ ...value.relations[0], to: 'missing' }]
  return value
}

test('普通助手文本不被当作大屏模板', () => {
  assert.deepEqual(parseLargeScreenImageTemplateV2('正在为你生成图片。'), { kind: 'absent' })
})

test('格式、版本和边界非法时明确失败', () => {
  assert.equal(parseLargeScreenImageTemplateV2('```large-screen-image-plan\n{not-json}\n```').kind, 'invalid')
  assert.equal(parseLargeScreenImageTemplateV2(`${fenced(fixture)}\n\`\`\`json\n{}\n\`\`\``).kind, 'invalid')
  assert.equal(parseLargeScreenImageTemplateV2(fenced({ ...fixture, version: '1' })).kind, 'invalid')
  assert.equal(parseLargeScreenImageTemplateV2(fenced(withBounds('core', { x: 900, y: 0, width: 101, height: 1 }))).kind, 'invalid')
  assert.equal(parseLargeScreenImageTemplateV2(fenced(withBounds('core', { x: 0.5, y: 0, width: 1, height: 1 }))).kind, 'invalid')
  assert.equal(parseLargeScreenImageTemplateV2(fenced(withBounds('core', { x: 0, y: 0, width: 0, height: 1 }))).kind, 'invalid')
})

test('严格拒绝悬空关系、未知字段和非法枚举', () => {
  const duplicateRegion = clonedFixture()
  duplicateRegion.regions[1].id = 'header'
  const invalidColor = clonedFixture()
  invalidColor.visualTokens.palette[0] = 'blue'
  const invalidComponent = clonedFixture()
  invalidComponent.regions[0].component = 'hero'
  const invalidRelation = clonedFixture()
  invalidRelation.relations[0].kind = 'link'
  const invalidReplaceable = clonedFixture()
  invalidReplaceable.regions[0].replaceable = ['layout']
  const unknownKey = { ...clonedFixture(), unexpected: true }

  for (const value of [
    withDanglingRelation(), duplicateRegion, invalidColor, invalidComponent,
    invalidRelation, invalidReplaceable, unknownKey,
  ]) {
    assert.equal(parseLargeScreenImageTemplateV2(fenced(value)).kind, 'invalid')
  }
})

test('严格限制集合大小和文本长度', () => {
  const tooManyRegions = clonedFixture()
  tooManyRegions.regions = Array.from({ length: 19 }, (_, index) => ({
    ...tooManyRegions.regions[0], id: `region-${index}`,
  }))
  const tooManyRelations = clonedFixture()
  tooManyRelations.relations = Array.from({ length: 25 }, () => ({ ...tooManyRelations.relations[0] }))
  const overlongText = { ...clonedFixture(), prompt: '长'.repeat(4001) }

  for (const value of [tooManyRegions, tooManyRelations, overlongText]) {
    assert.equal(parseLargeScreenImageTemplateV2(fenced(value)).kind, 'invalid')
  }
})

test('整个 large-screen-image-plan 围栏返回新验证对象', () => {
  const result = parseLargeScreenImageTemplateV2(fenced(fixture))
  assert.equal(result.kind, 'valid')
  if (result.kind !== 'valid') return
  assert.deepEqual(result.template, fixture)
  assert.notEqual(result.template, fixture)
  assert.notEqual(result.template.regions, fixture.regions)
})

test('可观察事实允许为空数组', () => {
  assert.equal(parseLargeScreenImageTemplateV2(fenced({ ...fixture, observedVisualFacts: [] })).kind, 'valid')
})
