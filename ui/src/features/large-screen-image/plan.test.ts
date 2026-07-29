import assert from 'node:assert/strict'
import test from 'node:test'
import { parseLargeScreenImagePlan } from './plan.ts'

const validPlan = {
  version: '1',
  title: '城市运行态势感知大屏',
  confidence: 'HIGH',
  observedVisualFacts: ['深蓝低饱和背景', '中心主视觉与左右分栏'],
  designSuggestions: ['中心区组织核心 KPI', '右侧形成趋势和告警信息流'],
  creativeBrief: {
    ratio: '16:9',
    styleTags: ['科技感', '深蓝', '数据驾驶舱'],
    palette: ['#071B3A', '#00D9FF', '#2B75FF'],
    layout: ['顶部：标题和全局状态', '中心：核心 KPI 与主图', '右侧：趋势与告警'],
    chartSuggestions: ['区域热力地图', '趋势折线图', '排行条形图'],
    prompt: '16:9 城市运行态势感知数据大屏，深蓝背景，青蓝发光边框，中心 KPI 与区域地图。',
    negativePrompt: '低清晰度、杂乱文字、错误图表、密集水印',
    iterationHints: ['更商务时降低霓虹强度', '更高密度时增加右侧列表区'],
  },
} as const

function fenced(value: unknown) {
  return `\`\`\`large-screen-image-plan\n${JSON.stringify(value)}\n\`\`\``
}

function clonedPlan() {
  return JSON.parse(JSON.stringify(validPlan)) as Record<string, any>
}

test('普通助手文本不被当作创作方案', () => {
  assert.deepEqual(parseLargeScreenImagePlan('正在为你生成图片。'), { kind: 'absent' })
})

test('带有方案围栏但 JSON 非法时明确失败', () => {
  const result = parseLargeScreenImagePlan('```large-screen-image-plan\n{not-json}\n```')
  assert.equal(result.kind, 'invalid')
})

test('多个代码块或代码块外文本不能隐藏为方案卡片', () => {
  assert.equal(parseLargeScreenImagePlan(`说明\n${fenced(validPlan)}`).kind, 'invalid')
  const extraBlock = '```json\n{}\n```'
  assert.equal(parseLargeScreenImagePlan(`${fenced(validPlan)}\n${extraBlock}`).kind, 'invalid')
})

test('缺少必填字段时拒绝隐藏原始回复', () => {
  const { creativeBrief, ...withoutCreativeBrief } = validPlan
  const result = parseLargeScreenImagePlan(fenced(withoutCreativeBrief))
  assert.equal(result.kind, 'invalid')
})

test('非法比例、置信度、版本、颜色和过长负面提示词均被拒绝', () => {
  for (const invalid of [
    { ...clonedPlan(), creativeBrief: { ...clonedPlan().creativeBrief, ratio: '4:3' } },
    { ...clonedPlan(), confidence: 'CERTAIN' },
    { ...clonedPlan(), version: '2' },
    { ...clonedPlan(), creativeBrief: { ...clonedPlan().creativeBrief, palette: ['red', '#00D9FF', '#2B75FF'] } },
    { ...clonedPlan(), creativeBrief: { ...clonedPlan().creativeBrief, negativePrompt: '负'.repeat(161) } },
  ]) {
    assert.equal(parseLargeScreenImagePlan(fenced(invalid)).kind, 'invalid')
  }
})

test('超过协议数组上限时拒绝不受控数据', () => {
  const invalid = clonedPlan()
  invalid.observedVisualFacts = Array.from({ length: 7 }, (_, index) => `事实 ${index}`)
  assert.equal(parseLargeScreenImagePlan(fenced(invalid)).kind, 'invalid')
})

test('完整方案返回类型化内容', () => {
  assert.deepEqual(parseLargeScreenImagePlan(fenced(validPlan)), { kind: 'valid', plan: validPlan })
})

test('历史会话中已持久化的方案仍可在刷新后解析', () => {
  const persistedChatMessage = { id: 'history-plan', role: 'assistant', content: fenced(validPlan) }
  assert.deepEqual(parseLargeScreenImagePlan(persistedChatMessage.content), { kind: 'valid', plan: validPlan })
})
