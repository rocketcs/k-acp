import assert from 'node:assert/strict'
import test from 'node:test'
import { formatLargeScreenImageMessageContent } from './messageDisplay.ts'

test('hides a persisted analyze envelope while preserving its user requirement', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: '[large-screen-image action=analyze ratio=16:9 referenceFileId=file-1]\n请根据当前参考图生成一份可编辑的大屏创作方案。\n\n请突出城市交通态势。',
    }),
    '请突出城市交通态势。',
  )
})

test('uses a friendly label for a persisted analyze envelope without a user requirement', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: '[large-screen-image action=analyze ratio=16:9 referenceFileId=file-1]\n请根据当前参考图生成一份可编辑的大屏创作方案。',
    }),
    '已上传参考图，请分析其视觉风格并给出创作方案。',
  )
})

test('keeps attachment metadata while hiding an analyze envelope body', () => {
  const prefix = '{"files":[{"id":"2082729274554626051","name":"reference.jpg","extension":"jpg","size":"1 KB"}]}@==##::::##==@'
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: `${prefix}[large-screen-image action=analyze ratio=16:9 referenceFileId=2082729274554626051]\n请根据当前参考图生成一份可编辑的大屏结构化模板 v2。`,
    }),
    `${prefix}已上传参考图，请分析其视觉风格并给出创作方案。`,
  )
})

test('hides a persisted generate envelope and shows its prompt', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: '[large-screen-image action=generate ratio=16:9 quality=standard referenceFileId= referenceImageUrl=]\n正向提示词：\n生成一张城市运行态势大屏\n\n负向提示词：\n水印',
    }),
    '生成一张城市运行态势大屏',
  )
})

test('hides a persisted natural-language generation envelope and shows its brief', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: '[large-screen-image action=generate ratio=16:9 quality=high referenceFileId= referenceImageUrl=]\n用户创作需求：\n按上面的方案生成一版',
    }),
    '按上面的方案生成一版',
  )
})

test('leaves other messages and malformed envelopes unchanged', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({ role: 'assistant', content: '[large-screen-image action=generate]' }),
    '[large-screen-image action=generate]',
  )
  assert.equal(
    formatLargeScreenImageMessageContent({ role: 'user', content: '普通聊天内容' }),
    '普通聊天内容',
  )
})
