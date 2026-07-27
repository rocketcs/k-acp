import assert from 'node:assert/strict'
import test from 'node:test'
import { needsTenderFallback, normalizeUIPContent } from './uip.ts'

const validChoice = (id: string) => JSON.stringify({
  role: 'assistant',
  content: '',
  version: '2.0',
  interaction: {
    id,
    type: 'choice',
    question: '下一步怎么做？',
    options: [{ value: 'continue', label: '继续' }],
  },
})

test('移除未闭合 UIP 围栏以及其中的截断 JSON', () => {
  const result = normalizeUIPContent('已找到 3 个项目。\n```uip\n{"interaction":', 'default')

  assert.equal(result.content, '已找到 3 个项目。')
  assert.equal(result.validBlocks.length, 0)
  assert.deepEqual(result.invalidReasons, ['unclosed'])
})

test('保留普通文本和可渲染的 UIP 卡片', () => {
  const card = validChoice('next-step')
  const result = normalizeUIPContent(`分析完成。\n\n\`\`\`uip\n${card}\n\`\`\``, 'default')

  assert.equal(result.validBlocks.length, 1)
  assert.equal(result.invalidReasons.length, 0)
  assert.equal(result.content, `分析完成。\n\n\`\`\`uip\n${card}\n\`\`\``)
})

test('默认策略移除无法渲染的 UIP 但不注入问标卡', () => {
  const result = normalizeUIPContent('说明文字\n```uip\n{"interaction":{"id":"unknown","type":"unknown"}}\n```', 'default')

  assert.equal(result.content, '说明文字')
  assert.equal(result.validBlocks.length, 0)
  assert.deepEqual(result.invalidReasons, ['unknown_interaction_type'])
})

test('问标严格策略只保留第一张合法卡片', () => {
  const first = validChoice('first')
  const second = validChoice('second')
  const result = normalizeUIPContent(
    `正文\n\`\`\`uip\n${first}\n\`\`\`\n\`\`\`uip\n${second}\n\`\`\``,
    'tenderStrict',
  )

  assert.equal(result.validBlocks.length, 1)
  assert.equal(result.validBlocks[0]?.interaction.id, 'first')
  assert.equal(result.content.includes(second), false)
})

test('仅有损坏 UIP 的问标回复仍需要默认卡', () => {
  const normalized = normalizeUIPContent('```uip\n{"interaction":\n```', 'tenderStrict')

  assert.equal(needsTenderFallback(normalized), true)
})

test('完全没有业务输出的问标回复不需要默认卡', () => {
  assert.equal(needsTenderFallback(normalizeUIPContent('', 'tenderStrict')), false)
})
