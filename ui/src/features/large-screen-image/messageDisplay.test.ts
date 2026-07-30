import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
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

test('hides a compiled v2 template envelope and shows only its business request', () => {
  assert.equal(
    formatLargeScreenImageMessageContent({
      role: 'user',
      content: '[large-screen-image action=generate ratio=16:9 quality=high referenceFileId=2082729274554626051 referenceImageUrl= templateVersion=2]\n布局模板约束（系统约束，必须保留）：\n锁定布局骨架\n\n用户创作需求：\n改为服务器管理架构',
    }),
    '改为服务器管理架构',
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

test('replacement clears the one-slot composer attachment before reopening the picker', () => {
  const wrapper = readFileSync(new URL('./LargeScreenImageChat.vue', import.meta.url), 'utf8')
  const chat = readFileSync(new URL('../../views/Chat/index.vue', import.meta.url), 'utf8')
  assert.match(wrapper, /requestAttachmentPicker\(\{ replace: true \}\)/)
  assert.match(chat, /if \(options\?\.replace\) \{\s*inputText\.value = ''\s*uploadedFiles\.value = \[\]/)
})

test('async template compilation is guarded and successful submission consumes the composer once', () => {
  const chat = readFileSync(new URL('../../views/Chat/index.vue', import.meta.url), 'utf8')
  assert.match(chat, /let submissionAdapterInFlight = false/)
  assert.match(chat, /if \(props\.submissionAdapter && submissionAdapterInFlight\) return/)
  assert.match(chat, /submission = await props\.submissionAdapter\(\{ text, fileIds: fileIdsToSend \}\)/)
  assert.match(chat, /if \(!submission\) return\s*\n\s*inputText\.value = ''\s*\n\s*uploadedFiles\.value = \[\]/)
})
