import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentClient } from '@/composables/useAgentClient'
import { usePlanTracking } from '@/composables/chat/usePlanTracking'
import { buildToolCallsContent } from '@/utils/chat/format'
import { needsTenderFallback, normalizeUIPContent } from '@/utils/chat/uip'
import type {ChatMessageVO, RawEvent} from '@/types'
import { useAccountStore } from '@/stores'
import { stopRun } from '@/api/agui'

let lastIdBig = BigInt(Date.now()) << 12n;
function nextIdBig() {
  return String(lastIdBig++);
}

// 仅在模型未按商业标书追问 Skill 生成卡片时兜底。正常路径的卡片由当前
// 项目、采购方和商机阶段动态生成；兜底不能再退回纯分页式的固定菜单。
const TENDER_FOLLOW_UP_CARD = `下一步想推进哪件事？

\`\`\`uip
{"role":"assistant","content":"","version":"2.0","interaction":{"id":"tender-followups","type":"choice","question":"下一步想推进哪件事？","multiple":false,"allowCustom":false,"autoSubmit":true,"options":[{"value":"prioritize_current","label":"筛选当前最值得跟进的项目","description":"结合时间、预算和匹配度排出优先顺序"},{"value":"capability_match","label":"根据我的业务能力精准筛一筛当前项目","description":"找出更适合我方参与的机会"},{"value":"compare_similar","label":"再看看同类在招项目，横向对比选择","description":"扩展机会并比较采购需求与时间窗口"},{"value":"continue_next_batch","label":"继续查看下一批","description":"保留当前条件，继续展示尚未查看的结果"}]}}
\`\`\``

export function useChatStream(
  agentId: import('vue').Ref<string>,
  agentDetail: import('vue').Ref<any>,
  currentSessionId: import('vue').Ref<string | null>,
  fileIds?: import('vue').Ref<string[]>,
  memoryActive?: import('vue').Ref<boolean>,
  planActive?: import('vue').Ref<boolean>,
  toolProcessActive?: import('vue').Ref<boolean>,
  onMessageSaved?: (chatMsg: ChatMessageVO) => void) {

  const { userInfo } = useAccountStore()

  // 计划追踪
  const {
    currentPlan,
    hasPlan,
    onToolStart: onPlanToolStart,
    onToolArgs: onPlanToolArgs,
    onToolResult: onPlanToolResult,
    resetPlan
  } = usePlanTracking()

  const getForwardedProps = () => ({
    agentId: agentId.value,
    agentCode: agentDetail.value?.agentCode,
    fileIds: fileIds?.value ?? [],
    memoryActive: memoryActive?.value ?? false,
    planActive: planActive?.value ?? false,
    toolProcessActive: toolProcessActive?.value ?? false,
    userInfo: userInfo
  })

  // 流式内容
  const agentHasResult = ref(true)
  const streamingMessageId = ref<string | null>(null)
  const streamingRole = ref<'user' | 'assistant' | 'system' | 'tool' | 'thinking'>('system')
  const streamingContent = ref('')
  // 高召回工作流已经生成可直接渲染的业务答案和 UIP 卡片。外层 Agent 的
  // 文本重写可能丢失卡片，因此在本次运行结束时优先展示该工具的原始答案。
  const pendingHighRecallAnswer = ref<string | null>(null)
  // 任何商业标书回答都应保留后续操作；不只限于高召回检索，也包括筛选和分析。
  const runHasAssistantAnswer = ref(false)
  const runHasTenderFollowups = ref(false)
  const fallbackFollowupSaved = ref(false)

  const isTenderAgent = () => agentDetail.value?.agentCode === 'default-tender'

  function saveNormalizedAssistantContent(
    rawContent: string,
    messageId: string | null,
    role: ChatMessageVO['role'],
  ) {
    const normalized = normalizeUIPContent(rawContent, isTenderAgent() ? 'tenderStrict' : 'default')
    const displayText = normalized.content

    if (displayText || (isTenderAgent() && needsTenderFallback(normalized))) {
      runHasAssistantAnswer.value = true
      if (isTenderAgent() && normalized.validBlocks.length > 0) {
        runHasTenderFollowups.value = true
      }
    }

    if (displayText) {
      const sid = currentSessionId.value
      if (sid) {
        onMessageSaved?.({
          id: messageId,
          sessionId: sid,
          role,
          content: displayText,
          parentId: '',
          path: '',
          depth: 0,
          createdAt: ''
        } as ChatMessageVO)
      }
    }
  }

  function clearStreamingMessage() {
    streamingMessageId.value = null
    streamingContent.value = ''
    streamingRole.value = 'system'
    pendingHighRecallAnswer.value = null
  }

  function finalizeStreamingMessage() {
    const displayText = pendingHighRecallAnswer.value || streamingContent.value
    if (displayText) {
      saveNormalizedAssistantContent(displayText, streamingMessageId.value, streamingRole.value)
    }
    clearStreamingMessage()
  }

  function appendTenderFallbackIfNeeded() {
    if (!isTenderAgent()) return
    const sid = currentSessionId.value
    if (!sid || !runHasAssistantAnswer.value || runHasTenderFollowups.value || fallbackFollowupSaved.value) {
      return
    }

    fallbackFollowupSaved.value = true
    onMessageSaved?.({
      id: nextIdBig(),
      sessionId: sid,
      role: 'assistant',
      content: TENDER_FOLLOW_UP_CARD,
      parentId: '',
      path: '',
      depth: 0,
      createdAt: ''
    } as ChatMessageVO)
  }

  // 工具调用进度
  const toolCallsInProgress = ref<
    Array<{ id: string; name: string; args: string; result?: string; startTime: number; elapsed?: number, needConfirm?: boolean }>
  >([])

  // 使用原有的 useAgentClient
  const { messages, isRunning, isReplaying, run, abort, disconnect, reconnect, addUserMessage, client } = useAgentClient({
    handlers: {
      onRunStarted: () => {
        toolCallsInProgress.value = []
        streamingContent.value = ''
        streamingMessageId.value = null
        pendingHighRecallAnswer.value = null
        runHasAssistantAnswer.value = false
        runHasTenderFollowups.value = false
        fallbackFollowupSaved.value = false
      },
      onTextMessageStart: (e) => {
        streamingRole.value = 'assistant'
        streamingContent.value = ''
        streamingMessageId.value = e.messageId
      },
      onTextMessageContent: (_e, currentText) => {
        agentHasResult.value = true
        streamingContent.value = currentText
      },
      onTextMessageEnd: (_e, finalText) => {
        const displayText = pendingHighRecallAnswer.value || finalText
        saveNormalizedAssistantContent(displayText, streamingMessageId.value, streamingRole.value)
        clearStreamingMessage()
      },
      onReasoningMessageStart: (_e) => {
        // Internal reasoning is not business-facing content. Ignore it instead of
        // creating a visible or persisted “思考过程” message.
      },
      onReasoningMessageContent: (_e, _currentText) => {
        // Intentionally ignored; only the final business answer is rendered.
      },
      onReasoningMessageEnd: () => {
        // Intentionally ignored; reasoning is never added to the chat transcript.
      },
      onToolCallStart: (e) => {
        // 计划追踪：记录工具调用名称
        onPlanToolStart(e.toolCallId, e.toolCallName)

        agentHasResult.value = true
        toolCallsInProgress.value = [
          ...toolCallsInProgress.value,
          { id: e.toolCallId, name: e.toolCallName, args: '', startTime: Date.now() }
        ]

        const sid = currentSessionId.value
        // Reasoning is not user-visible and is intentionally not persisted here.
      },
      onToolCallArgs: (_e, partialArgs) => {
        // 计划追踪：累积工具参数
        onPlanToolArgs(_e.toolCallId, partialArgs)

        const arr = [...toolCallsInProgress.value]
        const last = arr[arr.length - 1]
        if (last) last.args = partialArgs
        toolCallsInProgress.value = arr
      },
      onToolCallResult: (e) => {
        // 计划追踪：处理工具结果
        onPlanToolResult(e.toolCallId)

        try {
          if (agentDetail.value?.agentCode === 'default-tender') {
            try {
              const result = JSON.parse(e.content) as {
                answer?: unknown
                queryPlan?: unknown
                resultStatus?: unknown
                continuationState?: unknown
              }
              // 不依赖流事件中的工具名：部分模型会让结果事件先于工具名映射
              // 到达。以下四个字段是高召回工作流的固定返回契约。
              if (
                typeof result.answer === 'string' &&
                result.answer.trim() &&
                result.queryPlan &&
                result.resultStatus &&
                result.continuationState
              ) {
                pendingHighRecallAnswer.value = result.answer
              }
            } catch {
              // 工具异常时继续使用外层 Agent 的正常回复，不影响其他对话。
            }
          }

          // 判断是否开启了显示工具调用
          if (!(toolProcessActive?.value ?? true)) {
            return
          }
          // 更新工具调用结果和耗时
          toolCallsInProgress.value = toolCallsInProgress.value.map((t) =>
            t.id === e.toolCallId ? { ...t, result: e.content, elapsed: Date.now() - t.startTime } : t
          )

          // // 保存工具调用消息，通过队列保证写入顺序
          const sid = currentSessionId.value
          if (sid) {
            const contentToSave = buildToolCallsContent(toolCallsInProgress.value)
            if (contentToSave) {
              onMessageSaved?.({
                id: nextIdBig(),
                sessionId: sid,
                role: 'tool',
                content: contentToSave,
                parentId: '',
                path: '',
                depth: 0,
                createdAt: ''
              } as ChatMessageVO)
            }
          }
        } finally {
          // 清空进行中的工具调用（可根据需要保留，此处清空）
          toolCallsInProgress.value = []
        }
      },
      onRunFinished: (_e) => {
        agentHasResult.value = true
        finalizeStreamingMessage()
        // 商业标书智能体的高召回入口已明确配置为无需人工确认。
        // UIP 卡片点击后应立即继续查询；不要把未完成的工具状态误渲染成“允许/禁止”。
        if (isTenderAgent()) {
          appendTenderFallbackIfNeeded()
          toolCallsInProgress.value = []
          return
        }
        if (toolCallsInProgress.value.length > 0) {
          toolCallsInProgress.value.forEach(item => item.needConfirm = true)
        }
      },
      onRaw: (event) => {
        const e = event as RawEvent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const rawEvent: any = e.rawEvent
        if(rawEvent.error) {
          streamingMessageId.value = new Date().getTime() + '' + Math.floor(Math.random() * 90000) + 10000
          streamingContent.value = rawEvent.error
          const sid = currentSessionId.value
          if (sid) {
            onMessageSaved?.({
              id: streamingMessageId.value,
              sessionId: sid,
              role: 'error',
              content: rawEvent.error,
              parentId: '',
              path: '',
              depth: 0,
              createdAt: ''
            } as ChatMessageVO)
            // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
            streamingMessageId.value = null
            streamingContent.value = ''
            streamingRole.value = 'system'
          }
       }
      },
      onRunError: () => {
        agentHasResult.value = true
        finalizeStreamingMessage()
        appendTenderFallbackIfNeeded()
        toolCallsInProgress.value = []
      }
    }
  })

  // 发送消息
  const sendToolContent = async (value: any) => {
    const {id, name, args, result, content } = value
    client.messages = [{
      id,
      role: 'tool',
      content: JSON.stringify(content),
      toolCallId: content[0].id,
    }]

    // 判断是否开启了显示工具调用
    if ((toolProcessActive?.value ?? true)) {
      const sid = currentSessionId.value as string
      // 保存历史，通过队列保证写入顺序
      const contentToSave = buildToolCallsContent([{ id, name, args, result, elapsed: 0 }])
      if (contentToSave) {
        onMessageSaved?.({
          id: nextIdBig(),
          sessionId: sid,
          role: 'tool',
          content: contentToSave,
          parentId: '',
          path: '',
          depth: 0,
          createdAt: ''
        } as ChatMessageVO)
      }
    }

    await run({
      threadId: currentSessionId.value || undefined,
      runId: `run_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`,
      forwardedProps: getForwardedProps()
    })
  }

  // 中止运行
  const abortRun = async  () => {
    // 先调用后端 stop API 强制中断
    const sid = currentSessionId.value
    if (sid) {
      try { await stopRun(sid) } catch { /* 忽略 stop API 错误 */ }
    }
    await abort()
    agentHasResult.value = true

    // 重置计划状态
    resetPlan()

    finalizeStreamingMessage()

    if (sid) {
      // 保存工具调用消息，通过队列保证写入顺序
      if (toolCallsInProgress.value.length > 0) {
        const contentToSave = buildToolCallsContent(toolCallsInProgress.value)
        if (contentToSave) {
          onMessageSaved?.({
            id: nextIdBig(),
            sessionId: sid,
            role: 'tool',
            content: contentToSave,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
        }
      }
      toolCallsInProgress.value = []
      isRunning.value = false
    }

    appendTenderFallbackIfNeeded()

  }

  // 发送消息（可选传入 fileIds 覆盖，用于发送时已清空输入框的场景）
  const sendMessage = async (
    inputText: string,
    messagesList: ChatMessageVO[],
    overrideFileIds?: string[]
  ) => {
    const effectiveFileIds = overrideFileIds ?? fileIds?.value ?? []
    if (!agentId.value) return
    if (!inputText.trim() && !effectiveFileIds.length) return
    if (isRunning.value) return
    if (!agentDetail.value?.agentCode) {
      message.error('智能体信息未加载完成，请稍后再试')
      return
    }

    // 构建 client 需要的消息格式
    client.messages = messagesList
      .filter((m) => !['system', 'tool'].includes(m.role))
      .map((m) => ({
        id: String(m.id),
        role: m.role as any,
        content: (m.content || '') as string
      }))

    const forwardedProps = getForwardedProps()
    if (overrideFileIds !== undefined) {
      forwardedProps.fileIds = overrideFileIds
    }

    agentHasResult.value = false
    await run({
      threadId: currentSessionId.value || undefined,
      runId: `run_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`,
      forwardedProps
    })
  }

  /**
   * 重置所有流式状态，用于会话切换时清理旧 session 残留。
   */
  function resetStreamingState() {
    streamingMessageId.value = null
    streamingContent.value = ''
    streamingRole.value = 'system'
    toolCallsInProgress.value = []
    agentHasResult.value = true
    currentPlan.value = null
  }

  return {
    agentHasResult,
    streamingContent,
    streamingMessageId,
    streamingRole,
    toolCallsInProgress,
    isRunning,
    isReplaying,
    currentPlan,
    hasPlan,
    abortRun,
    sendMessage,
    sendToolContent,
    reconnect,
    disconnect,
    resetStreamingState,
    client, // 如果需要暴露
  }
}
