import type { ChatMessageVO, UploadedFileItem } from '@/types'
import { splitChatAttachmentContent } from '../../utils/chat/messageContent.ts'
import type { ActiveLargeScreenTemplateContext, LargeScreenImageTemplateV2 } from './template'
import { parseLargeScreenImageTemplateV2 } from './templateParser.ts'

type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

type AnalyzeCandidate = {
  message: ChatMessageVO
  referenceFileId: string
  referenceFile: UploadedFileItem | null
}

type StoredDraft = {
  sessionId: string
  referenceFileId: string
  analyzeUserMessageId: string
  templateMessageId: string
  template: unknown
}

const ANALYZE_ENVELOPE = /^\[large-screen-image action=analyze ratio=(?:16:9|21:9|9:16) referenceFileId=(\d+)\]\r?\n[\s\S]*$/
const DRAFT_PREFIX = 'large-screen-image-template-v2'

function availableStorage(storage?: DraftStorage): DraftStorage | undefined {
  if (storage) return storage
  return typeof sessionStorage === 'undefined' ? undefined : sessionStorage
}

function clearSessionDrafts(sessionId: string, storage?: DraftStorage, keepKey?: string): void {
  const target = availableStorage(storage)
  const enumerable = target as (DraftStorage & { readonly length?: number; key?: (index: number) => string | null }) | undefined
  if (!enumerable || typeof enumerable.length !== 'number' || typeof enumerable.key !== 'function') return
  const prefix = `${encodeURIComponent(DRAFT_PREFIX)}:${encodeURIComponent(sessionId)}:`
  const keys = Array.from({ length: enumerable.length }, (_, index) => enumerable.key!(index))
  for (const key of keys) {
    if (key && key.startsWith(prefix) && key !== keepKey) enumerable.removeItem(key)
  }
}

function validTemplate(value: unknown): LargeScreenImageTemplateV2 | null {
  try {
    const json = JSON.stringify(value)
    if (!json) return null
    const parsed = parseLargeScreenImageTemplateV2(`\`\`\`large-screen-image-plan\n${json}\n\`\`\``)
    return parsed.kind === 'valid' ? parsed.template : null
  } catch {
    return null
  }
}

function analyzeCandidate(message: ChatMessageVO): AnalyzeCandidate | null {
  if (message.role !== 'user') return null
  const split = splitChatAttachmentContent(message.content ?? '')
  const match = split.text.match(ANALYZE_ENVELOPE)
  if (!match) return null
  const referenceFileId = match[1] ?? ''
  return {
    message,
    referenceFileId,
    referenceFile: split.files.find((file): file is UploadedFileItem =>
      typeof file === 'object' && file !== null && typeof file.id === 'string' && file.id === referenceFileId,
    ) ?? null,
  }
}

function exactDraft(value: unknown, context: ActiveLargeScreenTemplateContext): LargeScreenImageTemplateV2 | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const draft = value as Partial<StoredDraft>
  if (draft.sessionId !== context.sessionId || draft.referenceFileId !== context.referenceFileId
    || draft.analyzeUserMessageId !== context.analyzeUserMessageId || draft.templateMessageId !== context.templateMessageId) return null
  return validTemplate(draft.template)
}

export function largeScreenImageTemplateDraftKey(context: Pick<ActiveLargeScreenTemplateContext,
  'sessionId' | 'referenceFileId' | 'analyzeUserMessageId' | 'templateMessageId'>): string {
  return [
    DRAFT_PREFIX,
    context.sessionId,
    context.referenceFileId,
    context.analyzeUserMessageId,
    context.templateMessageId,
  ].map(encodeURIComponent).join(':')
}

export function saveLargeScreenImageTemplateDraft(
  context: ActiveLargeScreenTemplateContext,
  storage?: DraftStorage,
): void {
  const target = availableStorage(storage)
  const template = validTemplate(context.template)
  if (!target || !template) return
  target.setItem(largeScreenImageTemplateDraftKey(context), JSON.stringify({
    sessionId: context.sessionId,
    referenceFileId: context.referenceFileId,
    analyzeUserMessageId: context.analyzeUserMessageId,
    templateMessageId: context.templateMessageId,
    template,
  } satisfies StoredDraft))
}

export function clearLargeScreenImageTemplateDraft(
  context: Pick<ActiveLargeScreenTemplateContext, 'sessionId' | 'referenceFileId' | 'analyzeUserMessageId' | 'templateMessageId'>,
  storage?: DraftStorage,
): void {
  availableStorage(storage)?.removeItem(largeScreenImageTemplateDraftKey(context))
}

export function restoreLargeScreenImageTemplate(
  sessionId: string,
  messages: readonly ChatMessageVO[],
  storage?: DraftStorage,
): ActiveLargeScreenTemplateContext | null {
  let latest: AnalyzeCandidate | null = null
  let latestIndex = -1
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]!
    if (message.sessionId !== sessionId) continue
    const candidate = analyzeCandidate(message)
    if (candidate) {
      latest = candidate
      latestIndex = index
    }
  }
  if (!latest || !latest.referenceFile) {
    clearSessionDrafts(sessionId, storage)
    return null
  }

  let persistedTemplate: LargeScreenImageTemplateV2 | null = null
  let templateMessageId = ''
  for (let index = latestIndex + 1; index < messages.length; index += 1) {
    const message = messages[index]!
    if (message.sessionId !== sessionId) continue
    if (message.role !== 'assistant') continue
    const parsed = parseLargeScreenImageTemplateV2(message.content ?? '')
    if (parsed.kind === 'invalid') {
      clearSessionDrafts(sessionId, storage)
      return null
    }
    if (parsed.kind === 'valid') {
      persistedTemplate = parsed.template
      templateMessageId = String(message.id)
      break
    }
  }
  if (!persistedTemplate) {
    clearSessionDrafts(sessionId, storage)
    return null
  }

  const context: ActiveLargeScreenTemplateContext = {
    sessionId,
    referenceFileId: latest.referenceFileId,
    referenceFile: latest.referenceFile,
    analyzeUserMessageId: String(latest.message.id),
    templateMessageId,
    template: persistedTemplate,
  }
  const target = availableStorage(storage)
  if (!target) return context
  const key = largeScreenImageTemplateDraftKey(context)
  clearSessionDrafts(sessionId, target, key)
  const serialized = target.getItem(key)
  if (serialized === null) return context
  try {
    const draft = exactDraft(JSON.parse(serialized), context)
    if (!draft) {
      target.removeItem(key)
      return context
    }
    return { ...context, template: draft }
  } catch {
    target.removeItem(key)
    return context
  }
}
