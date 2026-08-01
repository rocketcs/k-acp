<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import * as attachApi from '@/api/attach'
import type { ChatAttachmentPolicy } from '@/composables/chat/useChatAttachments'
import type { Attach, ChatMessagePresentation, ChatMessagePresentationInput, ChatMessageVO, UploadedFileItem } from '@/types'
import { splitChatAttachmentContent } from '@/utils/chat/messageContent'
import { TagRegistry } from '@/utils/chat/tagSystem'
import {
  adaptLargeScreenImageSubmission,
  createLargeScreenAnalyzeSubmission,
  restoreLargeScreenImageTemplate,
  type ActiveLargeScreenTemplateContext,
  type LargeScreenImageSubmission,
  type LargeScreenImageSubmissionInput,
  type LargeScreenImageTemplate,
  classifyLargeScreenImagePresentation,
  extractGeneratedImageUrl,
  formatLargeScreenImageMessageContent,
} from './message'
import LargeScreenImageTemplateCard from './LargeScreenImageTemplateCard.vue'
import LargeScreenImageTemplateErrorCard from './LargeScreenImageTemplateErrorCard.vue'
import LargeScreenImageReferenceTag from './LargeScreenImageReferenceTag.vue'
import LargeScreenImageGeneratedCard from './LargeScreenImageGeneratedCard.vue'
import LargeScreenImageTimeline from './LargeScreenImageTimeline.vue'

const LARGE_SCREEN_IMAGE_AGENT_CODE = 'default-large-screen-image'
TagRegistry.register({ tagName: 'large-screen-image-reference', component: LargeScreenImageReferenceTag })

const MAX_REFERENCE_IMAGE_BYTES = 30 * 1024 * 1024
const VALID_REFERENCE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp'])
const INVALID_REFERENCE_MESSAGE = '参考图已失效，请重新上传。'
const ANALYZE_ENVELOPE = /^\[large-screen-image action=analyze(?: [^\]\r\n]*)? referenceFileId=(\d+)(?: [^\]\r\n]*)?\]\r?\n/

type ChatExpose = {
  submitExternalSubmission: (
    submission: LargeScreenImageSubmission,
    options?: { consumeComposerOnSuccess?: boolean },
  ) => Promise<boolean>
  requestAttachmentPicker: (options?: { replace?: boolean }) => void
}

const agentId = ref('')
const loading = ref(true)
const loadError = ref('')
const chatRef = ref<ChatExpose | null>(null)
const activeTemplate = ref<ActiveLargeScreenTemplateContext | null>(null)
type TemplateVersion = { id: string; label: string; context: ActiveLargeScreenTemplateContext }
const templateVersions = ref<TemplateVersion[]>([])
const activeTemplateVersionId = ref('')
const pendingReferenceFile = ref<UploadedFileItem | null>(null)
const referenceWorkflowActive = ref(false)
const analysisRun = ref<{
  sessionId: string | null
  referenceFileId: string
  analyzeUserMessageId: string | null
  responseMessageId: string | null
  streamEligible: boolean
} | null>(null)
const submittingGeneration = ref(false)
const templateValidationError = ref('')
const currentSessionId = ref<string | null>(null)
type GeneratedImageTimelineItem = { id: string; imageUrl: string }
const generatedImageTimeline = ref<GeneratedImageTimelineItem[]>([])

const attachmentPolicy: ChatAttachmentPolicy = {
  maxFileCount: 1,
  maxFileSizeBytes: MAX_REFERENCE_IMAGE_BYTES,
  allowedExtensions: ['png', 'jpg', 'jpeg', 'webp'],
  allowedMimeTypes: ['image/png', 'image/jpeg', 'image/webp'],
}

function templateProvenanceMatches(
  left: Pick<ActiveLargeScreenTemplateContext, 'sessionId' | 'referenceFileId' | 'analyzeUserMessageId' | 'templateMessageId'>,
  right: Pick<ActiveLargeScreenTemplateContext, 'sessionId' | 'referenceFileId' | 'analyzeUserMessageId' | 'templateMessageId'>,
) {
  return left.sessionId === right.sessionId
    && left.referenceFileId === right.referenceFileId
    && left.analyzeUserMessageId === right.analyzeUserMessageId
    && left.templateMessageId === right.templateMessageId
}

function clearTemplateContext() {
  activeTemplate.value = null
  activeTemplateVersionId.value = ''
  templateValidationError.value = ''
}

function templateVersionId(context: ActiveLargeScreenTemplateContext) {
  return `${context.analyzeUserMessageId}:${context.templateMessageId}`
}

function rememberTemplateVersion(context: ActiveLargeScreenTemplateContext) {
  const id = templateVersionId(context)
  const version: TemplateVersion = { id, label: context.template.title || `模板 ${templateVersions.value.length + 1}`, context }
  const index = templateVersions.value.findIndex((item) => item.id === id)
  if (index === -1) templateVersions.value = [...templateVersions.value, version]
  else templateVersions.value = templateVersions.value.map((item, itemIndex) => itemIndex === index ? version : item)
  return id
}

function activateTemplate(context: ActiveLargeScreenTemplateContext) {
  const id = rememberTemplateVersion(context)
  activeTemplate.value = context
  activeTemplateVersionId.value = id
  templateValidationError.value = ''
}

function selectTemplateVersion(id: string) {
  const version = templateVersions.value.find((item) => item.id === id)
  if (!version) return
  activeTemplate.value = version.context
  activeTemplateVersionId.value = id
  templateValidationError.value = ''
}

function analysisResponseMatches(input: ChatMessagePresentationInput) {
  const run = analysisRun.value
  return run !== null && run.sessionId === currentSessionId.value && run.analyzeUserMessageId !== null && run.responseMessageId === input.id
}

function analysisStreamMatches(input: ChatMessagePresentationInput) {
  const run = analysisRun.value
  return input.isStreaming && run !== null && run.sessionId === currentSessionId.value && run.analyzeUserMessageId !== null && run.responseMessageId === null && run.streamEligible
}

function largeScreenMessageDisplayAdapter(input: { role: string; content: string }) {
  const displayText = formatLargeScreenImageMessageContent(input)
  const reference = input.role === 'user' ? input.content.match(ANALYZE_ENVELOPE)?.[1] : undefined
  return reference ? `<large-screen-image-reference>${reference}</large-screen-image-reference>\n${displayText}` : displayText
}


function normalizeExtension(value: string | undefined) {
  return (value ?? '').trim().replace(/^\./, '').toLowerCase()
}

function uploadedReferenceFromAttach(attachment: Attach, fallback: UploadedFileItem): UploadedFileItem | null {
  const extension = normalizeExtension(attachment.extension || fallback.extension)
  if (!VALID_REFERENCE_EXTENSIONS.has(extension)) return null
  return {
    id: fallback.id,
    name: attachment.originalName || attachment.name || fallback.name,
    extension,
    size: attachment.attachSize ? String(attachment.attachSize) : fallback.size,
  }
}

async function validateReferenceFile(reference: UploadedFileItem): Promise<UploadedFileItem | null> {
  try {
    const response = await attachApi.selectOne(reference.id)
    const attachment = response.data?.data
    if (!attachment) throw new Error('missing attachment')
    const verified = uploadedReferenceFromAttach(attachment, reference)
    if (!verified) throw new Error('unsupported attachment')
    return verified
  } catch {
    clearTemplateContext()
    pendingReferenceFile.value = null
    analysisRun.value = null
    referenceWorkflowActive.value = true
    message.error(INVALID_REFERENCE_MESSAGE)
    return null
  }
}

async function validateActiveReference(): Promise<UploadedFileItem | null> {
  const context = activeTemplate.value
  if (!context) return null
  const verified = await validateReferenceFile(context.referenceFile)
  if (!verified) return null
  if (activeTemplate.value && templateProvenanceMatches(activeTemplate.value, context)
    && currentSessionId.value === context.sessionId) {
    activeTemplate.value = { ...context, referenceFile: verified }
  }
  pendingReferenceFile.value = verified
  return verified
}

async function compileActiveTemplateGeneration(businessPrompt: string): Promise<LargeScreenImageSubmission | null> {
  const context = activeTemplate.value
  if (!context || context.sessionId !== currentSessionId.value) {
    if (context) clearTemplateContext()
    message.error('参考图识别尚未完成，请先完成识图。')
    return null
  }
  const referenceFile = await validateActiveReference()
  if (!referenceFile) return null
  const submission = adaptLargeScreenImageSubmission({
    text: businessPrompt,
    fileIds: [],
    activeTemplate: activeTemplate.value,
    referenceWorkflowActive: true,
  })
  if (!submission) {
    templateValidationError.value = '模板编辑不符合结构要求，请修正后生成。'
    message.error(templateValidationError.value)
    return null
  }
  templateValidationError.value = ''
  return submission
}

async function submitCompiledTemplate(businessPrompt: string) {
  if (submittingGeneration.value) return false
  submittingGeneration.value = true
  try {
    const submission = await compileActiveTemplateGeneration(businessPrompt)
    if (!submission) return false
    const sent = await chatRef.value?.submitExternalSubmission(submission, { consumeComposerOnSuccess: true })
    if (!sent) message.error('生成请求发送失败，请稍后重试。')
    return sent === true
  } finally {
    submittingGeneration.value = false
  }
}

async function adaptSubmission(input: LargeScreenImageSubmissionInput): Promise<LargeScreenImageSubmission | null> {
  const context = activeTemplate.value
  if (context) {
    if (context.sessionId !== currentSessionId.value) {
      clearTemplateContext()
      referenceWorkflowActive.value = false
      message.error('当前会话没有可用的参考图模板。')
      return null
    }
    if (submittingGeneration.value) return null
    submittingGeneration.value = true
    try {
      return await compileActiveTemplateGeneration(input.text)
    } finally {
      submittingGeneration.value = false
    }
  }
  const submission = adaptLargeScreenImageSubmission({
    ...input,
    referenceWorkflowActive: referenceWorkflowActive.value,
  })
  if (!submission) {
    message.error(referenceWorkflowActive.value
      ? '参考图识别尚未完成，请先完成识图。'
      : input.fileIds.length > 0 ? '参考图数据无效，请移除后重新上传。' : '请输入生成需求或上传参考图。')
    return null
  }
  return submission
}

function handleAttachmentAutoSubmit({ uploadedFile }: LargeScreenImageSubmissionInput & { uploadedFile: UploadedFileItem }) {
  clearTemplateContext()
  referenceWorkflowActive.value = true
  pendingReferenceFile.value = uploadedFile
  analysisRun.value = {
    sessionId: currentSessionId.value,
    referenceFileId: uploadedFile.id,
    analyzeUserMessageId: null,
    responseMessageId: null,
    streamEligible: false,
  }
  const submission = createLargeScreenAnalyzeSubmission(uploadedFile)
  if (!submission) {
    analysisRun.value = null
    message.error('参考图数据无效，请移除后重新上传。')
  }
  return submission
}

function isAnalyzeMessage(message: ChatMessageVO) {
  if (message.role !== 'user') return false
  return ANALYZE_ENVELOPE.test(splitChatAttachmentContent(message.content ?? '').text)
}

function firstResponseForAnalyze(messages: readonly ChatMessageVO[], sessionId: string, analyzeIndex: number) {
  for (const message of messages.slice(analyzeIndex + 1)) {
    if (message.sessionId !== sessionId) continue
    if (message.role === 'user') return null
    if (message.role === 'assistant' || String(message.role) === 'error') return message
  }
  return null
}

function onSessionMessagesChanged({ sessionId, messages }: { sessionId: string | null; messages: readonly ChatMessageVO[] }) {
  if (currentSessionId.value !== sessionId) templateVersions.value = []
  currentSessionId.value = sessionId
  if (!sessionId) {
    generatedImageTimeline.value = []
    clearTemplateContext()
    referenceWorkflowActive.value = false
    pendingReferenceFile.value = null
    analysisRun.value = null
    return
  }
  generatedImageTimeline.value = messages
    .filter((item) => item.sessionId === sessionId && item.role === 'assistant')
    .map((item) => ({ id: String(item.id), imageUrl: extractGeneratedImageUrl(item.content) }))
    .filter((item): item is GeneratedImageTimelineItem => Boolean(item.imageUrl))
  const hasReferenceWorkflow = messages.some((item) => item.sessionId === sessionId && isAnalyzeMessage(item))
  if (!hasReferenceWorkflow && activeTemplate.value?.sessionId !== sessionId && analysisRun.value?.sessionId !== sessionId) {
    referenceWorkflowActive.value = false
    pendingReferenceFile.value = null
  } else if (hasReferenceWorkflow) {
    referenceWorkflowActive.value = true
  }

  const matchingAnalyze = analysisRun.value?.analyzeUserMessageId
    ? messages.find((item) => String(item.id) === analysisRun.value?.analyzeUserMessageId) ?? null
    : [...messages].reverse().find((item) => item.sessionId === sessionId
      && isAnalyzeMessage(item)
      && splitChatAttachmentContent(item.content ?? '').text.includes(`referenceFileId=${analysisRun.value?.referenceFileId ?? ''}`)) ?? null
  if (matchingAnalyze && analysisRun.value) {
    analysisRun.value = {
      ...analysisRun.value,
      sessionId,
      analyzeUserMessageId: String(matchingAnalyze.id),
    }
    const analyzeIndex = messages.findIndex((item) => String(item.id) === String(analysisRun.value?.analyzeUserMessageId))
    const following = analyzeIndex === -1 ? [] : messages.slice(analyzeIndex + 1).filter((item) => item.sessionId === sessionId)
    const response = analyzeIndex === -1 ? null : firstResponseForAnalyze(messages, sessionId, analyzeIndex)
    analysisRun.value = {
      ...analysisRun.value,
      responseMessageId: response ? String(response.id) : null,
      streamEligible: response === null && !following.some((item) => item.role === 'user'),
    }
  }

  const restored = restoreLargeScreenImageTemplate(sessionId, messages)
  const reconciled = restored
  if (!reconciled) {
    // Hydrating a session without a valid context must never retain another session's draft.
    clearTemplateContext()
    return
  }
  pendingReferenceFile.value = reconciled.referenceFile
  const reconciledVersionId = templateVersionId(reconciled)
  if (!activeTemplate.value || !templateVersions.value.some((item) => item.id === reconciledVersionId)) activateTemplate(reconciled)
  else rememberTemplateVersion(activeTemplate.value)
  if (analysisRun.value?.sessionId === sessionId
    && analysisRun.value.referenceFileId === reconciled.referenceFileId
    && analysisRun.value.analyzeUserMessageId === reconciled.analyzeUserMessageId) {
    analysisRun.value = null
  }
}

function scrollToGeneratedImage(messageId: string) {
  requestAnimationFrame(() => {
    const target = document.querySelector<HTMLElement>(
      `[data-large-screen-generated-message-id="${messageId}"]`,
    )
    if (!target) return
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    target.classList.remove('large-screen-generated-card-highlighted')
    requestAnimationFrame(() => target.classList.add('large-screen-generated-card-highlighted'))
    window.setTimeout(() => target.classList.remove('large-screen-generated-card-highlighted'), 1800)
  })
}

function handleAttachmentRemoved(file: UploadedFileItem) {
  if (file.id !== activeTemplate.value?.referenceFileId && file.id !== pendingReferenceFile.value?.id) return
  clearTemplateContext()
  pendingReferenceFile.value = null
  analysisRun.value = null
  referenceWorkflowActive.value = true
}

function updateTemplate(template: LargeScreenImageTemplate) {
  const context = activeTemplate.value
  if (!context) return
  const next = { ...context, template }
  activeTemplate.value = next
  rememberTemplateVersion(next)
  templateValidationError.value = ''
}

async function startAnalysis(reference: UploadedFileItem) {
  const verified = await validateReferenceFile(reference)
  if (!verified) return false
  clearTemplateContext()
  referenceWorkflowActive.value = true
  pendingReferenceFile.value = verified
  analysisRun.value = {
    sessionId: currentSessionId.value,
    referenceFileId: verified.id,
    analyzeUserMessageId: null,
    responseMessageId: null,
    streamEligible: false,
  }
  const submission = createLargeScreenAnalyzeSubmission(verified)
  if (!submission || !await chatRef.value?.submitExternalSubmission(submission, { consumeComposerOnSuccess: true })) {
    analysisRun.value = null
    message.error('识图请求发送失败，可重试。')
    return false
  }
  return true
}

async function retryAnalyze() {
  const reference = activeTemplate.value?.referenceFile ?? pendingReferenceFile.value
  if (!reference) {
    message.error(INVALID_REFERENCE_MESSAGE)
    return
  }
  await startAnalysis(reference)
}

async function analyzeGeneratedImage(imageUrl: string) {
  if (submittingGeneration.value || analysisRun.value) return
  let stage = '下载生成图'
  try {
    const response = await fetch(imageUrl)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const blob = await response.blob()
    if (!blob.size || !blob.type.startsWith('image/')) throw new Error(`无效图片响应：${blob.type || '未知类型'}`)
    const extension = blob.type === 'image/jpeg' ? 'jpg' : blob.type === 'image/webp' ? 'webp' : 'png'
    const file = new File([blob], `大屏生成图-${Date.now()}.${extension}`, { type: blob.type })
    stage = '上传生成图'
    const upload = await attachApi.upload(file)
    const id = upload.data?.data
    if (!id) throw new Error('upload failed')
    stage = '校验上传附件'
    const reference = await validateReferenceFile({ id: String(id), name: file.name, extension, size: String(file.size) })
    if (!reference) return
    stage = '提交识图请求'
    await startAnalysis(reference)
  } catch (error) {
    console.error('[large-screen-image] 识别生成图失败', { stage, imageUrl, error })
    message.error('当前生成图无法作为参考图，请稍后重试。')
  }
}

async function removeReference() {
  const reference = activeTemplate.value?.referenceFile ?? pendingReferenceFile.value
  clearTemplateContext()
  pendingReferenceFile.value = null
  analysisRun.value = null
  referenceWorkflowActive.value = true
  if (!reference) return
  try {
    await attachApi.remove([reference.id])
  } catch {
    message.warning('参考图已从当前创作中移除，附件删除失败可稍后在文件管理中处理。')
  }
}

function replaceReference() {
  // This clears only Chat's local composer attachments, then opens the normal one-slot picker.
  chatRef.value?.requestAttachmentPicker({ replace: true })
}

function messagePresentationAdapter(input: ChatMessagePresentationInput): ChatMessagePresentation {
  if (analysisStreamMatches(input)) return { kind: 'markdown', content: '正在识别布局与视觉系统' }
  if (String(input.role) === 'error' && analysisResponseMatches(input)) analysisRun.value = null
  const descriptor = classifyLargeScreenImagePresentation({ role: input.role, rawContent: input.rawContent })
  if (descriptor.kind === 'template') {
    const editable = activeTemplate.value?.sessionId === currentSessionId.value && activeTemplate.value.templateMessageId === input.id
    return {
      kind: 'custom',
      component: LargeScreenImageTemplateCard,
      props: {
        template: editable ? activeTemplate.value?.template ?? descriptor.template : descriptor.template,
        referenceFile: editable ? activeTemplate.value?.referenceFile ?? null : null,
        editable,
        busy: editable && submittingGeneration.value,
        validationError: editable ? templateValidationError.value : '',
        onUpdateTemplate: updateTemplate,
        onRetryAnalyze: retryAnalyze,
        onGenerate: () => { void submitCompiledTemplate('') },
        onRemoveReference: () => { void removeReference() },
        onReplaceReference: replaceReference,
        versions: templateVersions.value.map(({ id, label }) => ({ id, label })),
        activeVersionId: activeTemplateVersionId.value,
        onSelectVersion: selectTemplateVersion,
      },
    }
  }
  if (descriptor.kind === 'invalid-template') {
    if (analysisResponseMatches(input)) analysisRun.value = null
    return {
      kind: 'custom',
      component: LargeScreenImageTemplateErrorCard,
      props: {
        reason: descriptor.reason,
        canRetry: Boolean(pendingReferenceFile.value),
        onRetryAnalyze: retryAnalyze,
      },
    }
  }
  const generatedImageUrl = String(input.role) === 'assistant' ? extractGeneratedImageUrl(input.rawContent) : null
  if (generatedImageUrl) {
    return {
      kind: 'custom',
      component: LargeScreenImageGeneratedCard,
      props: {
        imageUrl: generatedImageUrl,
        messageId: input.id,
        busy: Boolean(analysisRun.value),
        onAnalyze: () => { void analyzeGeneratedImage(generatedImageUrl) },
      },
    }
  }
  return { kind: 'markdown', content: input.content }
}

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  agentId.value = ''
  try {
    const response = await agentApi.page({ agentCode: LARGE_SCREEN_IMAGE_AGENT_CODE, page: 1, size: 2 })
    const agents = (response.data?.data?.records ?? []).filter((item) => item.agentCode === LARGE_SCREEN_IMAGE_AGENT_CODE)
    if (agents.length > 1) throw new Error('Duplicate large-screen-image agents')
    const agent = agents[0]
    if (!agent) {
      loadError.value = '大屏生图智能体尚未配置或未启用'
      return
    }
    agentId.value = String(agent.id)
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的大屏生图智能体，请联系管理员处理'
      : '大屏生图智能体加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadAgent() })
</script>

<template>
  <section v-if="agentId" class="large-screen-image-chat-shell">
    <Chat
      class="large-screen-image-chat"
      ref="chatRef"
      :chat-agent-id="agentId"
      :show-account="true"
      :submission-adapter="adaptSubmission"
      :message-display-adapter="largeScreenMessageDisplayAdapter"
      :message-presentation-adapter="messagePresentationAdapter"
      :attachment-policy="attachmentPolicy"
      :attachment-drop-enabled="true"
      :attachment-auto-submit-adapter="handleAttachmentAutoSubmit"
      :on-attachment-removed="handleAttachmentRemoved"
      :on-session-messages-changed="onSessionMessagesChanged"
    />
    <LargeScreenImageTimeline :items="generatedImageTimeline" @select="scrollToGeneratedImage" />
  </section>
  <main v-else class="chat-route-state">
    <ASpin v-if="loading" tip="正在加载大屏生图…" />
    <section v-else aria-live="polite">
      <p>{{ loadError }}</p>
      <AButton type="primary" @click="loadAgent">重新加载</AButton>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-route-state { display: grid; min-height: 100dvh; place-items: center; padding: 24px; background: $chat-bg-main; color: var(--color-text-primary); text-align: center; }
.chat-route-state section { display: grid; gap: 16px; max-width: 32rem; }
.chat-route-state p { margin: 0; }
.large-screen-image-chat-shell { min-width: 0; }
</style>
