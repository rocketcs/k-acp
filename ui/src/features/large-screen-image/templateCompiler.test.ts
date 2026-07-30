import assert from 'node:assert/strict'
import test from 'node:test'
import { compileLargeScreenImageGeneration } from './templateCompiler.ts'

const fixture = {
  version: '2', title: '城市运行态势感知大屏', confidence: 'HIGH',
  observedVisualFacts: ['深蓝低饱和背景'],
  canvas: { ratio: '16:9', coordinateSystem: 'normalized-1000', grid: '12-column' },
  visualTokens: { palette: ['#071B3A', '#00D9FF'], surface: '深蓝面板', border: '青蓝边框', typography: '无衬线数字字体' },
  regions: [
    { id: 'header', label: '顶部状态', bounds: { x: 0, y: 0, width: 1000, height: 100 }, layer: 1, component: 'title-status', purpose: '展示标题', locked: true, replaceable: ['title'] },
    { id: 'left-cluster', label: '左侧集群', bounds: { x: 0, y: 100, width: 300, height: 800 }, layer: 2, component: 'topology-cluster', purpose: '展示节点', locked: true, replaceable: ['businessLabels'] },
    { id: 'core', label: '核心拓扑', bounds: { x: 300, y: 100, width: 400, height: 800 }, layer: 2, component: 'core-topology', purpose: '展示核心关系', locked: true, replaceable: ['chartData'] },
  ],
  relations: [{ from: 'core', to: 'left-cluster', kind: 'topology-link', locked: true }],
  preservation: { mode: 'preserve-layout', mustKeep: ['region-bounds', 'information-hierarchy', 'locked-relations', 'palette-proportion'], mayReplace: ['business-labels', 'chart-data'] },
  prompt: '深蓝科技数据大屏，中心拓扑与左右分栏。', negativePrompt: '低清晰度、杂乱文字、水印', iterationHints: ['降低霓虹强度'],
} as const

const validInput = { template: fixture, referenceFileId: '2082729274554626051', businessPrompt: '改为服务器管理架构，展示负载和告警' }

test('编译模板生成请求为确定且可持久化的参考图请求', () => {
  const compiled = compileLargeScreenImageGeneration(validInput)!
  assert.deepEqual(compiled.fileIds, ['2082729274554626051'])
  assert.equal(compiled.persistedText, compiled.runtimeText)
  assert.match(compiled.runtimeText, /^\[large-screen-image action=generate ratio=16:9 quality=high referenceFileId=2082729274554626051 referenceImageUrl= templateVersion=2\]/)
  assert.match(compiled.runtimeText, /锁定布局骨架/)
  assert.match(compiled.runtimeText, /header.*left-cluster.*core/s)
  assert.match(compiled.runtimeText, /palette-proportion/)
  assert.match(compiled.runtimeText, /服务器管理架构/)
  assert.equal(compileLargeScreenImageGeneration({ ...validInput, referenceFileId: 'unsafe]\n' }), null)
})

test('不要重新布局时保留默认布局策略', () => {
  const compiled = compileLargeScreenImageGeneration({ ...validInput, businessPrompt: '不要重新布局，替换为能源监控数据' })!
  assert.match(compiled.runtimeText, /\[布局策略=preserve-layout\]/)
  assert.match(compiled.runtimeText, /仅允许替换 replaceable 和 mayReplace 内容/)
})

test('明确重新布局时只改变策略标记，仍保留比例和参考图', () => {
  const preserve = compileLargeScreenImageGeneration(validInput)!
  const relayout = compileLargeScreenImageGeneration({ ...validInput, businessPrompt: '请重新布局，改成服务器总览' })!
  assert.match(relayout.runtimeText, /\[布局策略=explicit-relayout\]/)
  assert.match(relayout.runtimeText, /ratio=16:9/)
  assert.match(relayout.runtimeText, /referenceFileId=2082729274554626051/)
  assert.equal(
    relayout.runtimeText.replace('[布局策略=explicit-relayout]', '[布局策略=preserve-layout]').replace('请重新布局，改成服务器总览', validInput.businessPrompt),
    preserve.runtimeText,
  )
})

test('无效模板不会退化为文生图请求', () => {
  assert.equal(compileLargeScreenImageGeneration({ ...validInput, template: { ...fixture, version: '1' } }), null)
})
