<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import * as attachApi from '@/api/attach'
import type { ChatAttachmentPolicy } from '@/composables/chat/useChatAttachments'
import type { Attach, ChatMessagePresentation, ChatMessagePresentationInput, ChatMessageVO, UploadedFileItem } from '@/types'
import { splitChatAttachmentContent } from '@/utils/chat/messageContent'
import {
  LARGE_SCREEN_IMAGE_AGENT_CODE,
  LARGE_SCREEN_IMAGE_GENERATION_TOOL_ID,
  resolveLargeScreenImageAgent,
} from './agent'
import {
  adaptLargeScreenImageSubmission,
  canStartLargeScreenGeneration,
  createLargeScreenAnalyzeSubmission,
  largeScreenAnalysisResponseMatches,
  largeScreenAnalysisStreamMatches,
  reconcileLargeScreenTemplateContext,
  resolveLargeScreenTemplateCard,
  type LargeScreenImageSubmission,
  type LargeScreenImageSubmissionInput,
} from './submission'
import { formatLargeScreenImageMessageContent } from './messageDisplay'
import { classifyLargeScreenImagePresentation } from './messagePresentation'
import LargeScreenImageTemplateCard from './LargeScreenImageTemplateCard.vue'
import LargeScreenImageTemplateErrorCard from './LargeScreenImageTemplateErrorCard.vue'
import type { ActiveLargeScreenTemplateContext, LargeScreenImageTemplateV2 } from './template'
import {
  clearLargeScreenImageTemplateDraft,
  restoreLargeScreenImageTemplate,
  saveLargeScreenImageTemplateDraft,
} from './templateSession'
import { compileLargeScreenImageGeneration } from './templateCompiler.ts'

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
const generationToolConfigured = ref(false)
const chatRef = ref<ChatExpose | null>(null)
const activeTemplate = ref<ActiveLargeScreenTemplateContext | null>(null)
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
  if (activeTemplate.value) clearLargeScreenImageTemplateDraft(activeTemplate.value)
  activeTemplate.value = null
  templateValidationError.value = ''
}

function analysisResponseMatches(input: ChatMessagePresentationInput) {
  return largeScreenAnalysisResponseMatches({
    run: analysisRun.value,
    currentSessionId: currentSessionId.value,
    messageId: input.id,
  })
}

function analysisStreamMatches(input: ChatMessagePresentationInput) {
  return largeScreenAnalysisStreamMatches({
    run: analysisRun.value,
    currentSessionId: currentSessionId.value,
    isStreaming: input.isStreaming,
  })
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
  if (!generationToolConfigured.value) {
    message.info('生图能力尚未配置；请联系管理员完成 image_generate Tool 配置。')
    return null
  }
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
    if (!canStartLargeScreenGeneration({ hasValidatedReference: true, submitting: submittingGeneration.value })) return null
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
  if (!generationToolConfigured.value) {
    message.info('生图能力尚未配置；请联系管理员完成 image_generate Tool 配置。')
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
  currentSessionId.value = sessionId
  if (!sessionId) {
    clearTemplateContext()
    referenceWorkflowActive.value = false
    pendingReferenceFile.value = null
    analysisRun.value = null
    return
  }
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
  const reconciled = reconcileLargeScreenTemplateContext({
    currentSessionId: sessionId,
    activeTemplate: activeTemplate.value,
    restoredTemplate: restored,
  })
  if (!reconciled) {
    // Hydrating a session without a valid context must never retain another session's draft.
    clearTemplateContext()
    return
  }
  pendingReferenceFile.value = reconciled.referenceFile
  if (activeTemplate.value !== reconciled) {
    activeTemplate.value = reconciled
    templateValidationError.value = ''
  }
  if (analysisRun.value?.sessionId === sessionId
    && analysisRun.value.referenceFileId === reconciled.referenceFileId
    && analysisRun.value.analyzeUserMessageId === reconciled.analyzeUserMessageId) {
    analysisRun.value = null
  }
}

function handleAttachmentRemoved(file: UploadedFileItem) {
  if (file.id !== activeTemplate.value?.referenceFileId && file.id !== pendingReferenceFile.value?.id) return
  clearTemplateContext()
  pendingReferenceFile.value = null
  analysisRun.value = null
  referenceWorkflowActive.value = true
}

function updateTemplate(template: LargeScreenImageTemplateV2) {
  const context = activeTemplate.value
  if (!context) return
  const next = { ...context, template }
  activeTemplate.value = next
  templateValidationError.value = ''
  saveLargeScreenImageTemplateDraft(next)
}

async function retryAnalyze() {
  const reference = activeTemplate.value?.referenceFile ?? pendingReferenceFile.value
  if (!reference) {
    message.error(INVALID_REFERENCE_MESSAGE)
    return
  }
  const verified = await validateReferenceFile(reference)
  if (!verified) return
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
    const card = resolveLargeScreenTemplateCard({
      currentSessionId: currentSessionId.value,
      activeTemplate: activeTemplate.value,
      templateMessageId: input.id,
      persistedTemplate: descriptor.template,
    })
    return {
      kind: 'custom',
      component: LargeScreenImageTemplateCard,
      props: {
        template: card.template,
        referenceFile: card.editable ? activeTemplate.value?.referenceFile ?? null : null,
        editable: card.editable,
        busy: card.editable && submittingGeneration.value,
        validationError: card.editable ? templateValidationError.value : '',
        onUpdateTemplate: updateTemplate,
        onRetryAnalyze: retryAnalyze,
        onGenerate: () => { void submitCompiledTemplate('') },
        onRemoveReference: () => { void removeReference() },
        onReplaceReference: replaceReference,
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
  return { kind: 'markdown', content: input.content }
}

async function loadGenerationToolState(resolvedAgentId: string) {
  try {
    const response = await agentApi.enabledToolsOfAgent(resolvedAgentId)
    generationToolConfigured.value = (response.data?.data ?? []).some(
      (tool) => tool.toolId === LARGE_SCREEN_IMAGE_GENERATION_TOOL_ID,
    )
  } catch {
    generationToolConfigured.value = false
  }
}

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  agentId.value = ''
  try {
    const response = await agentApi.page({ agentCode: LARGE_SCREEN_IMAGE_AGENT_CODE, page: 1, size: 2 })
    const agent = resolveLargeScreenImageAgent(response.data?.data?.records ?? [])
    if (!agent) {
      loadError.value = '大屏生图智能体尚未配置或未启用'
      return
    }
    agentId.value = String(agent.id)
    await loadGenerationToolState(agentId.value)
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
  <Chat
    v-if="agentId"
    ref="chatRef"
    :chat-agent-id="agentId"
    :show-account="true"
    :submission-adapter="adaptSubmission"
    :message-display-adapter="formatLargeScreenImageMessageContent"
    :message-presentation-adapter="messagePresentationAdapter"
    :attachment-policy="attachmentPolicy"
    :attachment-drop-enabled="true"
    :attachment-auto-submit-adapter="handleAttachmentAutoSubmit"
    :on-attachment-removed="handleAttachmentRemoved"
    :on-session-messages-changed="onSessionMessagesChanged"
  />
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
</style>
