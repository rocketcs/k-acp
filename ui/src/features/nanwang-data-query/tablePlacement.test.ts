import assert from 'node:assert/strict'
import test from 'node:test'
import { DATA_TABLE_PLACEHOLDER, splitAssistantContent } from './tablePlacement.ts'

test('splits content at the table placeholder', () => {
  const content = `结论\n\n关键信息：20mg\n\n${DATA_TABLE_PLACEHOLDER}\n\n说明\n后续可选项`
  const r = splitAssistantContent(content)
  assert.equal(r.hasPlaceholder, true)
  assert.ok(r.before.includes('关键信息：20mg'))
  assert.ok(!r.before.includes(DATA_TABLE_PLACEHOLDER))
  assert.ok(r.after.includes('说明'))
  assert.ok(r.after.includes('后续可选项'))
  assert.ok(!r.after.includes(DATA_TABLE_PLACEHOLDER))
})

test('content without placeholder stays whole in before', () => {
  const content = '没有任何占位符的正文。\n\n表格将出现在末尾。'
  const r = splitAssistantContent(content)
  assert.equal(r.hasPlaceholder, false)
  assert.equal(r.before, content)
  assert.equal(r.after, '')
})

test('placeholder removal trims surrounding whitespace', () => {
  const r = splitAssistantContent(`摘要 \t\n${DATA_TABLE_PLACEHOLDER}\n\t说明`)
  assert.equal(r.before, '摘要')
  assert.equal(r.after, '说明')
})
