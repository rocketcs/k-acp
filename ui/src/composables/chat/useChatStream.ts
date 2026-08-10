import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentClient } from '@/composables/useAgentClient'
import { usePlanTracking } from '@/composables/chat/usePlanTracking'
import { buildToolCallsContent } from '@/utils/chat/format'
import { composeTenderResponse, needsTenderFallback, normalizeUIPContent } from '@/utils/chat/uip'
import { toAguiRuntimeMessages, type RuntimeChatMessage } from '@/utils/chat/runtimeMessages'
import type {ChatMessageVO, RawEvent, RunActivity} from '@/types'
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
  // 一旦本轮回答已有可见正文，执行卡应立刻退出，避免占据正文下方的位置。
  const hasVisibleAnswer = ref(false)
  const runStartedAt = ref<number | null>(null)
  // 高召回工作流提供确定性事实正文；外层策展 Skill 提供唯一的后续卡片。
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

  function finalizeStreamingMessage(finalText = streamingContent.value) {
    const displayText = isTenderAgent() && pendingHighRecallAnswer.value
      ? composeTenderResponse(pendingHighRecallAnswer.value, finalText)
      : finalText
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
  // 与工具调用的持久化记录分开：此列表只服务于当前一轮的实时执行轨迹。
  // 即使工具已返回，也保留它直到本轮结束，避免用户在模型继续整理答案时看到空白。
  const runActivities = ref<RunActivity[]>([])

  // HITL：逐工具确认决策（toolUseId → 状态），所有项决策完即调 /agui/resume
  const pendingConfirms = ref<Record<string, 'pending' | 'approved' | 'rejected'>>({})

  /**
   * HITL：根据待确认列表重建确认 UI（标记/新建工具项 + 建立逐工具决策态）。两条来源共用：
   * - 实时 TOOL_CONFIRM_REQUIRED 事件：工具项已由 ToolCallStart 建立，只需标记 needConfirm；
   * - 刷新/重进会话（GET /agui/pending 恢复）：toolCallsInProgress 已被清空，须按 input 新建工具项，
   *   否则没有任何工具项承载「允许/禁止」按钮，暂停态卡死无法续点。
   * @param pending 待确认工具 [{toolUseId,name,input}]
   */
  const restorePending = (
    pending: Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
  ) => {
    if (!pending || pending.length === 0) return
    const ids = new Set(pending.map(p => p.toolUseId))
    // 已存在的标记 needConfirm
    const arr = toolCallsInProgress.value.map(t => (ids.has(t.id) ? { ...t, needConfirm: true } : t))
    // 缺失的新建（刷新场景）
    const existing = new Set(arr.map(t => t.id))
    pending.forEach(p => {
      if (!existing.has(p.toolUseId)) {
        const args = p.input && Object.keys(p.input).length ? JSON.stringify(p.input) : '{}'
        arr.push({ id: p.toolUseId, name: p.name, args, needConfirm: true, startTime: Date.now() })
      }
    })
    toolCallsInProgress.value = arr
    const next: Record<string, 'pending' | 'approved' | 'rejected'> = { ...pendingConfirms.value }
    pending.forEach(p => { next[p.toolUseId] = 'pending' })
    pendingConfirms.value = next
  }

  // 使用原有的 useAgentClient
  const { messages, isRunning, isReplaying, run, abort, disconnect, reconnect, resume, addUserMessage, client } = useAgentClient({
    handlers: {
      onRunStarted: () => {
        toolCallsInProgress.value = []
        runActivities.value = []
        runStartedAt.value = Date.now()
        streamingContent.value = ''
        hasVisibleAnswer.value = false
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
        if (currentText.trim()) hasVisibleAnswer.value = true
      },
      onTextMessageEnd: (_e, finalText) => {
        finalizeStreamingMessage(finalText)
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
        // Reasoning is not user-visible and is intentionally not persisted here.
      },
      onToolCallArgs: (_e, partialArgs) => {
        // 计划追踪：累积工具参数
        onPlanToolArgs(_e.toolCallId, partialArgs)

        toolCallsInProgress.value = toolCallsInProgress.value.map((toolCall) =>
          toolCall.id === _e.toolCallId ? { ...toolCall, args: partialArgs } : toolCall,
        )
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

          // 判断是否开启了显示工具调用。实时执行轨迹不受此设置影响：
          // 它只展示面向业务用户的进度，不展示工具的原始参数与结果。
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
        // 不再全标记 needConfirm（旧 Bug1/MCP 假象根源）；
        // 确认改由 onCustom 的 TOOL_CONFIRM_REQUIRED 事件精确驱动
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
      onCustom: (event) => {
        // HITL：收到 TOOL_CONFIRM_REQUIRED 时，精确标记需确认的工具（不再全标记）
        if (event.name === 'TOOL_CONFIRM_REQUIRED') {
          const pending = (((event.value as any)?.pending) ?? []) as Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
          restorePending(pending)
          return
        }
        if (event.name === 'WORKFLOW_NODE_PROGRESS') {
          const progress = (event.value ?? {}) as {
            nodeId?: string
            nodeName?: string
            status?: RunActivity['status']
            startTime?: number
            endTime?: number
          }
          if (!progress.nodeId || !progress.nodeName || !progress.status) return
          const startTime = progress.startTime ?? Date.now()
          runActivities.value = [
            ...runActivities.value.filter((activity) => activity.id !== progress.nodeId),
            {
              id: progress.nodeId,
              name: progress.nodeName,
              status: progress.status,
              startTime,
              elapsed: progress.endTime ? Math.max(0, progress.endTime - startTime) : undefined,
            },
          ]
        }
      },
      onRunError: () => {
        agentHasResult.value = true
        finalizeStreamingMessage()
        appendTenderFallbackIfNeeded()
        runActivities.value = runActivities.value.map((activity) =>
          activity.status === 'running'
            ? { ...activity, status: 'failed', elapsed: Date.now() - activity.startTime }
            : activity,
        )
        toolCallsInProgress.value = []
      }
    }
  })

  /**
   * HITL：记录单个工具的确认决策（替代旧的「前端代执行/塞文本 + run 重开一轮」）。
   * 所有待确认工具都决策后，调用 /agui/resume 由后端从暂停点续跑。
   * @param toolUseId 工具调用 id（= TOOL_CONFIRM_REQUIRED 的 toolUseId）
   * @param approved true=允许，false=拒绝
   */
  const decideConfirm = (toolUseId: string, approved: boolean) => {
    if (pendingConfirms.value[toolUseId] === undefined) return
    pendingConfirms.value = {
      ...pendingConfirms.value,
      [toolUseId]: approved ? 'approved' : 'rejected'
    }
    // 该工具按钮收起（已决策）
    toolCallsInProgress.value = toolCallsInProgress.value.map(t =>
      t.id === toolUseId ? { ...t, needConfirm: false } : t
    )
    // 所有待确认工具都已决策 → 提交 resume
    const states = Object.values(pendingConfirms.value)
    if (states.length > 0 && states.every(s => s !== 'pending')) {
      void submitResume()
    }
  }

  /** 汇总逐工具决策并调用后端 resume，续接 SSE 流。 */
  const submitResume = async () => {
    const sid = currentSessionId.value
    if (!sid) return
    const decisions = Object.entries(pendingConfirms.value).map(([toolUseId, s]) => {
      const t = toolCallsInProgress.value.find(x => x.id === toolUseId)
      return { toolUseId, name: t?.name ?? '', approved: s === 'approved' }
    })
    pendingConfirms.value = {}
    agentHasResult.value = false
    await resume(sid, decisions, memoryActive?.value ?? false)
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
    messagesList: RuntimeChatMessage[],
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
    client.messages = toAguiRuntimeMessages(messagesList)

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
    runStartedAt.value = null
    agentHasResult.value = true
    currentPlan.value = null
  }

  return {
    agentHasResult,
    streamingContent,
    hasVisibleAnswer,
    runStartedAt,
    streamingMessageId,
    streamingRole,
    toolCallsInProgress,
    runActivities,
    isRunning,
    isReplaying,
    currentPlan,
    hasPlan,
    abortRun,
    sendMessage,
    decideConfirm,
    pendingConfirms,
    restorePending,
    reconnect,
    disconnect,
    resetStreamingState,
    client, // 如果需要暴露
  }
}
