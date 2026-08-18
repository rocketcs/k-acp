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
import { shouldResetDeletedSession } from './composerControls'
import { mergeTurnEvidence, type TurnEvidence } from './turnEvidence'

const CONTEXT_MESSAGE_LIMIT = 6

/**
 * 会话选中态持久化：刷新后应回到用户上次所在的会话；若用户处于“新建对话”空状态，
 * 则刷新后仍保持空状态，而不是自动跳回最新会话。
 * 存储值：选中会话 id；空串 '' 表示“新建对话”空状态；未写过则为 null。
 */
const lastSessionStorageKey = (agentId: string) => `graphify:last-session:${agentId}`

function persistLastSession(agentId: Ref<string>, sessionId: string | null) {
  try {
    if (!agentId.value) return
    localStorage.setItem(lastSessionStorageKey(agentId.value), sessionId ?? '')
  } catch { /* 忽略 localStorage 不可用 */ }
}

function readLastSession(agentId: string): string | null {
  try {
    return localStorage.getItem(lastSessionStorageKey(agentId))
  } catch { return null }
}

/**
 * 本地执行的证据信封兜底：受治理数据服务的 executed 结果可能因后端/智能体
 * 落库链路未把 `query` 的 tool 消息写回 chat_message，刷新重放时只能还原出
 * blocked/warning 预检诊断，导致 `activeEvidence` 为空、表格消失。这里在查询
 * 成功取得 executed 信封时额外缓存到 localStorage（以助手消息 id 为键），刷新
 * 时优先合并进来，保证表格在本浏览器会话内稳定还原。
 */
const evidenceStoragePrefix = 'graphify:evidence:'
const evidenceStorageKey = (messageId: string) => `${evidenceStoragePrefix}${messageId}`

function persistTurnEvidence(messageId: string, turn: TurnEvidence) {
  try {
    if (!messageId || !turn?.evidence) return
    localStorage.setItem(evidenceStorageKey(messageId), JSON.stringify(turn))
  } catch { /* 忽略 localStorage 不可用 */ }
}

function readTurnEvidence(messageId: string): TurnEvidence | null {
  try {
    const raw = localStorage.getItem(evidenceStorageKey(messageId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as TurnEvidence
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || !parsed.evidence) return null
    return parsed
  } catch { return null }
}

function clearTurnEvidence(messageId: string) {
  try { localStorage.removeItem(evidenceStorageKey(messageId)) } catch { /* 忽略 localStorage 不可用 */ }
}

export function useGraphifyDataQueryChat(agentId: Ref<string>) {
  const { agentDetail } = useAgentDetail(agentId)
  const { sessions, loading: sessionsLoading, createSession, deleteSession, resetAndReload } = useSessions(agentId)
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
      } else if (message.role === 'assistant') {
        const messageId = String(message.id)
        // 本地已缓存的 executed 证据信封优先（覆盖后端未落库、仅剩 blocked/warning 预检诊断的情况）。
        const local = readTurnEvidence(messageId)
        if (local) {
          restored[messageId] = pending ? mergeTurnEvidence(local, pending) : local
        } else if (pending) {
          restored[messageId] = pending
        }
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

  async function deleteGraphifySession(session: ChatSessionVO): Promise<boolean> {
    if (isRunning.value) return false
    try {
      const isCurrentSession = shouldResetDeletedSession(currentSessionId.value, session.id)
      // 同步清理该会话助手消息对应的本地证据缓存。
      for (const message of messagesList.value) {
        if (message.role === 'assistant') clearTurnEvidence(String(message.id))
      }
      await deleteSession(session.id)
      if (isCurrentSession) await startNewSession()
      return true
    } catch {
      return false
    }
  }

  async function loadInitialSession() {
    await resetAndReload()
    if (currentSessionId.value) return

    // 刷新恢复：优先回到用户上次选中的会话。
    const last = readLastSession(agentId.value)
    if (last) {
      const target = sessions.value.find((s) => String(s.id) === last)
      if (target) {
        await chooseSession(target)
        return
      }
    }
    // 用户上次处于“新建对话”空状态（持久化为空串）→ 保持空状态，不自动选中最新会话。
    if (last === '') return
    // 从未持久化或上次会话已失效 → 退回最新会话（兼容首次进入）。
    if (sessions.value[0]) await chooseSession(sessions.value[0])
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
      if (pendingTurnId.value) {
        const assistant = [...messagesList.value].reverse().find((item) => item.role === 'assistant')
        if (assistant) {
          // 流的 watch(messagesList)→restorePersistedEvidence 会把 evidenceByMessageId 整体重建，
          // 可能把实时累积的 pending: 键证据移到 assistantId 下；因此这里两个键都查，保证持久化一定触发。
          const turn = evidenceByMessageId.value[pendingTurnId.value]
            ?? evidenceByMessageId.value[String(assistant.id)]
          if (turn) {
            evidenceByMessageId.value = { ...evidenceByMessageId.value, [String(assistant.id)]: turn }
            delete evidenceByMessageId.value[pendingTurnId.value]
            selectedAssistantMessageId.value = String(assistant.id)
            // 查询成功取得 executed 信封后缓存到本地，刷新时据此还原表格（见 readTurnEvidence）。
            persistTurnEvidence(String(assistant.id), turn)
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

  watch(currentSessionId, (id) => {
    evidenceByMessageId.value = {}
    selectedAssistantMessageId.value = null
    persistLastSession(agentId, id)
  })
  onMounted(() => { void loadInitialSession() })
  onBeforeUnmount(disconnect)

  return {
    sessions, sessionsLoading, currentSessionId, currentSessionTitle, displayMessages, isRunning, streamingContent,
    activeEvidence, activeOutcome, selectedAssistantMessageId, chooseSession, startNewSession,
    deleteGraphifySession, sendQuestion, resetAndReload, loadCurrentMessages,
  }
}
