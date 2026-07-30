import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { classifyLargeScreenImagePresentation } from './messagePresentation.ts'

const template = {
  version: '2',
  title: '城市运行态势感知大屏',
  confidence: 'HIGH',
  observedVisualFacts: ['深蓝低饱和背景'],
  canvas: { ratio: '16:9', coordinateSystem: 'normalized-1000', grid: '12-column' },
  visualTokens: { palette: ['#071B3A'], surface: '深蓝面板', border: '青蓝边框', typography: '无衬线字体' },
  regions: [{
    id: 'header', label: '顶部标题', bounds: { x: 0, y: 0, width: 1000, height: 100 },
    layer: 1, component: 'title-status', purpose: '展示标题', locked: true, replaceable: ['title'],
  }],
  relations: [],
  preservation: { mode: 'preserve-layout', mustKeep: ['region-bounds'], mayReplace: ['business-labels'] },
  prompt: '城市运行态势感知数据大屏。',
  negativePrompt: '低清晰度、水印',
  iterationHints: [],
}

function plan(value: unknown) {
  return `\`\`\`large-screen-image-plan\n${JSON.stringify(value)}\n\`\`\``
}

test('有效的助手 v2 方案分类为模板', () => {
  const result = classifyLargeScreenImagePresentation({ role: 'assistant', rawContent: plan(template) })
  assert.equal(result.kind, 'template')
  if (result.kind === 'template') assert.deepEqual(result.template, template)
})

test('用户消息和普通助手回复保持 Markdown', () => {
  assert.deepEqual(
    classifyLargeScreenImagePresentation({ role: 'user', rawContent: plan(template) }),
    { kind: 'markdown' },
  )
  assert.deepEqual(
    classifyLargeScreenImagePresentation({ role: 'assistant', rawContent: '正在为你生成图片。' }),
    { kind: 'markdown' },
  )
})

test('预期但非法的助手方案分类为安全错误', () => {
  const result = classifyLargeScreenImagePresentation({
    role: 'assistant', rawContent: '```large-screen-image-plan\n{not-json}\n```',
  })
  assert.deepEqual(result, { kind: 'invalid-template', reason: '模板 JSON 无法解析' })
})

test('历史模板的重新识图按钮保持只读', () => {
  const card = readFileSync(new URL('./LargeScreenImageTemplateCard.vue', import.meta.url), 'utf8')
  assert.match(
    card,
    /@click="onRetryAnalyze"[^>]*:disabled="!editable \|\| busy"|:disabled="!editable \|\| busy"[^>]*@click="onRetryAnalyze"/,
  )
})
