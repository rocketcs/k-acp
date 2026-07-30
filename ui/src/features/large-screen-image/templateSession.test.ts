import assert from 'node:assert/strict'
import test from 'node:test'
import type { ChatMessageVO, UploadedFileItem } from '@/types'
import { prependChatAttachmentContent } from '../../utils/chat/messageContent.ts'
import type { LargeScreenImageSubmission } from './template.ts'
import {
  largeScreenImageTemplateDraftKey,
  restoreLargeScreenImageTemplate,
  saveLargeScreenImageTemplateDraft,
} from './templateSession.ts'

const referenceFile: UploadedFileItem = { id: '2082729274554626051', name: 'reference.png', extension: 'png', size: '1024' }
const otherFile: UploadedFileItem = { id: '2082729274554626052', name: 'next.png', extension: 'png', size: '1024' }
const template = {
  version: '2', title: '城市运行态势大屏', confidence: 'HIGH', observedVisualFacts: ['深蓝背景'],
  canvas: { ratio: '16:9', coordinateSystem: 'normalized-1000', grid: '12-column' },
  visualTokens: { palette: ['#071B3A'], surface: '深蓝面板', border: '青蓝边框', typography: '无衬线字体' },
  regions: [{ id: 'core', label: '核心', bounds: { x: 0, y: 0, width: 1000, height: 1000 }, layer: 1, component: 'core-topology', purpose: '展示核心关系', locked: true, replaceable: ['chartData'] }],
  relations: [], preservation: { mode: 'preserve-layout', mustKeep: ['region-bounds'], mayReplace: ['chart-data'] },
  prompt: '深蓝科技大屏，核心拓扑。', negativePrompt: '低清晰度、水印', iterationHints: [],
} as const

const analyzeEnvelope = '[large-screen-image action=analyze ratio=16:9 referenceFileId=2082729274554626051]\n请根据当前参考图生成一份可编辑的大屏结构化模板 v2。'
const plan = `\`\`\`large-screen-image-plan\n${JSON.stringify(template)}\n\`\`\``

function message(id: string, role: string, content: string): ChatMessageVO {
  return { id, sessionId: 'session-1', role, content, parentId: null, path: id, depth: 0, createdAt: '2026-07-30T00:00:00Z' }
}

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    get length() { return values.size },
    key: (index: number) => [...values.keys()][index] ?? null,
  }
}

test('恢复最新的已持久化识图/模板对及其附件来源', () => {
  const messages = [
    message('analyze-old', 'user', prependChatAttachmentContent([otherFile], analyzeEnvelope.replace(referenceFile.id, otherFile.id))),
    message('plan-old', 'assistant', plan),
    message('analyze-new', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)),
    message('plan-new', 'assistant', plan),
  ]
  const restored = restoreLargeScreenImageTemplate('session-1', messages)
  assert.deepEqual(restored?.referenceFileId, referenceFile.id)
  assert.deepEqual(restored?.referenceFile, referenceFile)
  assert.equal(restored?.analyzeUserMessageId, 'analyze-new')
  assert.equal(restored?.templateMessageId, 'plan-new')
})

test('较新的未匹配识图请求使旧模板失效，后续生图不会抹除有效对', () => {
  const established = [
    message('analyze-old', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)),
    message('plan-old', 'assistant', plan),
    message('generate', 'user', '[large-screen-image action=generate ratio=16:9 quality=high referenceFileId=2082729274554626051 referenceImageUrl= templateVersion=2]\n用户创作需求：\n生成一版'),
  ]
  assert.equal(restoreLargeScreenImageTemplate('session-1', established)?.templateMessageId, 'plan-old')

  const unmatched = [...established, message('analyze-new', 'user', prependChatAttachmentContent([otherFile], analyzeEnvelope.replace(referenceFile.id, otherFile.id)))]
  assert.equal(restoreLargeScreenImageTemplate('session-1', unmatched), null)
})

test('不会消费另一个会话的模板消息', () => {
  const foreignPlan = { ...message('plan-foreign', 'assistant', plan), sessionId: 'session-2' }
  const messages = [
    message('analyze', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)),
    foreignPlan,
  ]
  assert.equal(restoreLargeScreenImageTemplate('session-1', messages), null)
})

test('仅完整匹配四项来源的草稿覆盖持久化模板，其他草稿会被清除', () => {
  const storage = memoryStorage()
  const messages = [
    message('analyze', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)),
    message('plan', 'assistant', plan),
  ]
  const persisted = restoreLargeScreenImageTemplate('session-1', messages, storage)!
  const draft = { ...template, title: '本地编辑标题' }
  saveLargeScreenImageTemplateDraft({ ...persisted, template: draft }, storage)
  assert.equal(restoreLargeScreenImageTemplate('session-1', messages, storage)?.template.title, '本地编辑标题')

  const key = largeScreenImageTemplateDraftKey(persisted)
  storage.setItem(key, JSON.stringify({ ...persisted, template, templateMessageId: 'wrong-plan' }))
  assert.equal(restoreLargeScreenImageTemplate('session-1', messages, storage)?.template.title, template.title)
  assert.equal(storage.getItem(key), null)
})

test('新图片、缺失附件、无消息和无效模板都会清理该会话的陈旧草稿', () => {
  const established = [
    message('analyze-old', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)),
    message('plan-old', 'assistant', plan),
  ]
  for (const failedMessages of [
    [...established, message('analyze-new', 'user', prependChatAttachmentContent([otherFile], analyzeEnvelope.replace(referenceFile.id, otherFile.id)))],
    [message('analyze-missing', 'user', analyzeEnvelope)],
    [],
    [...established, message('analyze-invalid', 'user', prependChatAttachmentContent([referenceFile], analyzeEnvelope)), message('plan-invalid', 'assistant', '```large-screen-image-plan\n{not-json}\n```')],
  ]) {
    const storage = memoryStorage()
    const context = restoreLargeScreenImageTemplate('session-1', established, storage)!
    const key = largeScreenImageTemplateDraftKey(context)
    saveLargeScreenImageTemplateDraft(context, storage)
    assert.equal(restoreLargeScreenImageTemplate('session-1', failedMessages, storage), null)
    assert.equal(storage.getItem(key), null)
  }
})

test('外部重新识图携带 attachedFiles，空输入附件也能保留持久化前缀', () => {
  const externalSubmission: LargeScreenImageSubmission = {
    displayText: '已上传参考图，请分析其视觉风格并给出创作方案。', persistedText: analyzeEnvelope,
    runtimeText: analyzeEnvelope, titleText: '参考图识别', fileIds: [referenceFile.id], attachedFiles: [referenceFile],
  }
  const inputAttachments: UploadedFileItem[] = []
  const persisted = prependChatAttachmentContent(externalSubmission.attachedFiles ?? inputAttachments, externalSubmission.persistedText)
  assert.equal(persisted.startsWith(JSON.stringify({ files: [referenceFile] })), true)
})
