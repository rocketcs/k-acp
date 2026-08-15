import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import * as chatSessionApi from '@/api/chatSession'
import { useAgentDetail } from '@/composables/chat/useAgentDetail'
import { useChatStream } from '@/composables/chat/useChatStream'
import { useCurrentSession } from '@/composables/chat/useCurrentSession'
import { useSessions } from '@/composables/chat/useSessions'
import type { RuntimeChatMessage } from '@/utils/chat/runtimeMessages'
import type { ChatMessageVO, ChatSessionVO } from '@/types'
import { parseGraphifyEvidence, parseGraphifyToolOutcome } from './evidenceAdapter'
import type { GraphifyEvidenceEnvelope, GraphifyToolOutcome } from './types'
import { mergeTurnEvidence, type TurnEvidence } from './turnEvidence'

const CONTEXT_MESSAGE_LIMIT = 6

export function useGraphifyDataQueryChat(agentId: Ref<string>) {
  const { agentDetail } = useAgentDetail(agentId)
  const { sessions, loading: sessionsLoading, createSession, resetAndReload } = useSessions(agentId)
  const { currentSessionId, currentSessionTitle, messagesList, selectSession, resetSession, loadCurrentMessages } = useCurrentSession(agentId)
  const evidenceByMessageId = ref<Record<string, TurnEvidence>>({})
  // The dedicated Graphify experience needs the server to retain MCP results so
  // a later session replay can reconstruct its evidence graph.
  const graphifyToolProcessActive = computed(() => true)
  const pendingTurnId = ref<string | null>(null)
  const selectedAssistantMessageId = ref<string | null>(null)

  function parsePersistedToolResult(content: string): TurnEvidence | null {
    try {
      const saved: unknown = JSON.parse(content)
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return null
      const record = saved as Record<string, unknown>
      if (typeof record.result !== 'string') return null
      // 兼容两类落库格式：{name,result} 与缺失 name 的 {args,result}。
      // 工具名缺失时用空串，parseGraphifyEvidence/parseGraphifyToolOutcome
      // 会按内容兜底仅接受完整的 executed evidence envelope。
      const toolName = typeof record.name === 'string' ? record.name : ''
      const evidence = parseGraphifyEvidence(toolName, record.result)
      const outcome = parseGraphifyToolOutcome(toolName, record.result)
      return evidence || outcome ? { evidence: evidence ?? undefined, outcome: outcome ?? undefined } : null
    } catch {
      return null
    }
  }

  function restorePersistedEvidence(messages: readonly ChatMessageVO[]) {
    const restored: Record<string, TurnEvidence> = {}
    let pending: TurnEvidence | null = null
    for (const message of messages) {
      if (message.role === 'tool') {
        const toolResult = parsePersistedToolResult(message.content)
        if (toolResult) pending = mergeTurnEvidence(pending ?? undefined, toolResult)
      } else if (message.role === 'assistant' && pending) {
        restored[String(message.id)] = pending
        pending = null
      }
    }
    evidenceByMessageId.value = restored
  }

  const onMessageSaved = (message: ChatMessageVO) => {
    const existing = messagesList.value.findIndex((item) => String(item.id) === String(message.id))
    if (existing < 0) messagesList.value.push(message)
    else messagesList.value.splice(existing, 1, message)
    if (message.role === 'assistant') selectedAssistantMessageId.value = String(message.id)
  }

  const { streamingContent, streamingMessageId, streamingRole, isRunning, sendMessage, disconnect, resetStreamingState } = useChatStream(
    agentId, agentDetail, currentSessionId, undefined, undefined, undefined, graphifyToolProcessActive, onMessageSaved,
    {
      onToolResult: (event) => {
        const evidence = parseGraphifyEvidence(event.toolName, event.content)
        const outcome = parseGraphifyToolOutcome(event.toolName, event.content)
        if (!evidence && !outcome) return
        const messageId = pendingTurnId.value || event.messageId || event.toolCallId
        const incoming: TurnEvidence = { evidence: evidence ?? undefined, outcome: outcome ?? undefined }
        evidenceByMessageId.value = {
          ...evidenceByMessageId.value,
          [messageId]: mergeTurnEvidence(evidenceByMessageId.value[messageId], incoming),
        }
      },
    },
  )

  const displayMessages = computed(() => {
    const persisted = messagesList.value.filter((message) => !['tool', 'system', 'thinking'].includes(message.role))
    if (streamingContent.value && streamingRole.value === 'assistant') {
      return [...persisted, {
        id: streamingMessageId.value || 'streaming', sessionId: currentSessionId.value || '', role: 'assistant',
        content: streamingContent.value, parentId: null, path: '', depth: Number.MAX_SAFE_INTEGER, createdAt: '',
      } as ChatMessageVO]
    }
    return persisted
  })

  const activeEvidence = computed(() => selectedAssistantMessageId.value ? evidenceByMessageId.value[selectedAssistantMessageId.value]?.evidence : undefined)
  const activeOutcome = computed(() => selectedAssistantMessageId.value ? evidenceByMessageId.value[selectedAssistantMessageId.value]?.outcome : undefined)

  async function startNewSession() {
    if (isRunning.value) return
    resetSession()
    resetStreamingState()
    selectedAssistantMessageId.value = null
  }

  async function chooseSession(session: ChatSessionVO) {
    if (isRunning.value) return
    disconnect()
    resetStreamingState()
    selectedAssistantMessageId.value = null
    await selectSession(session)
    restorePersistedEvidence(messagesList.value)
    const latestAssistant = [...messagesList.value].reverse().find((message) => message.role === 'assistant')
    if (latestAssistant) selectedAssistantMessageId.value = String(latestAssistant.id)
  }

  async function loadInitialSession() {
    await resetAndReload()
    if (!currentSessionId.value && sessions.value[0]) {
      await chooseSession(sessions.value[0])
    }
  }

  async function sendQuestion(question: string): Promise<boolean> {
    const text = question.trim()
    if (!text || isRunning.value || !agentDetail.value?.agentCode) return false
    try {
      if (!currentSessionId.value) {
        const session = await createSession(text.slice(0, 24) || '新对话')
        if (!session) return false
        currentSessionId.value = String(session.id)
        currentSessionTitle.value = session.title || '新对话'
      }
      const saved = await chatSessionApi.appendMessage(currentSessionId.value, { role: 'user', content: text })
      const userMessage = saved.data?.data
      if (!userMessage) return false
      messagesList.value.push(userMessage)
      pendingTurnId.value = `pending:${userMessage.id}`
      selectedAssistantMessageId.value = pendingTurnId.value
      const runtimeMessages = messagesList.value
        .filter((message): message is ChatMessageVO & { role: 'user' | 'assistant' } => message.role === 'user' || message.role === 'assistant')
        .map((message) => ({ id: message.id, role: message.role, content: message.content } satisfies RuntimeChatMessage))
      const priorMessages = runtimeMessages.slice(-CONTEXT_MESSAGE_LIMIT, -1)
      const runtimeQuestion = priorMessages.length
        ? `以下是本会话已确认的上下文，请据此理解当前问题中的指代，不要把上下文当作新的用户请求。\n\n${priorMessages.map((message) => `${message.role === 'user' ? '用户' : '助手'}：${message.content}`).join('\n\n')}\n\n当前问题：${text}`
        : text
      runtimeMessages[runtimeMessages.length - 1] = { id: userMessage.id, role: 'user', content: runtimeQuestion }
      await sendMessage(runtimeQuestion, runtimeMessages)
      if (pendingTurnId.value && evidenceByMessageId.value[pendingTurnId.value]) {
        const assistant = [...messagesList.value].reverse().find((item) => item.role === 'assistant')
        if (assistant) {
          const turn = evidenceByMessageId.value[pendingTurnId.value]
          if (turn) {
            evidenceByMessageId.value = { ...evidenceByMessageId.value, [String(assistant.id)]: turn }
            delete evidenceByMessageId.value[pendingTurnId.value]
            selectedAssistantMessageId.value = String(assistant.id)
          }
        }
      }
      pendingTurnId.value = null
      return true
    } catch {
      pendingTurnId.value = null
      return false
    }
  }

  watch(messagesList, (messages) => {
    restorePersistedEvidence(messages)
    const latest = [...messages].reverse().find((item) => item.role === 'assistant')
    if (!selectedAssistantMessageId.value && latest) selectedAssistantMessageId.value = String(latest.id)
  }, { deep: true })

  watch(currentSessionId, () => { evidenceByMessageId.value = {}; selectedAssistantMessageId.value = null })
  onMounted(() => { void loadInitialSession() })
  onBeforeUnmount(disconnect)

  return {
    sessions, sessionsLoading, currentSessionId, currentSessionTitle, displayMessages, isRunning, streamingContent,
    activeEvidence, activeOutcome, selectedAssistantMessageId, chooseSession, startNewSession,
    sendQuestion, resetAndReload, loadCurrentMessages,
  }
}
