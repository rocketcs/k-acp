<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import { MedicineBoxOutlined } from '@ant-design/icons-vue'
import GraphifyAssistantMessage from './GraphifyAssistantMessage.vue'
import { parseGraphifyEvidence, parseNeo4jReadCypherGraph } from './evidenceAdapter'
import { buildSessionEvidence } from './sessionEvidence'
import type { ChatMessageVO, ChatMessagePresentation, ChatMessagePresentationInput, RunActivity } from '@/types'
import type { TurnEvidence } from './turnEvidence'

const GRAPHIFY_DATA_QUERY_AGENT_CODE = 'default-graphify-data-query'

type GraphifyChatSubmission = { displayText: string; runtimeText: string; titleText: string; fileIds: string[] }

const agentId = ref('')
const loading = ref(true)
const loadError = ref('')

/** 助手消息 id → 该轮语义依据（重构自会话消息 + 本地缓存，普通 chat 内联展示用）。 */
const evidenceByMessageId = ref<Record<string, TurnEvidence>>({})
/** 聊天是否已有内容（用于决定是否展示空状态快捷问题）。 */
const hasMessages = ref(false)
const chatRef = ref<{
  submitExternalSubmission: (submission: GraphifyChatSubmission) => Promise<boolean>
} | null>(null)
const quickSending = ref(false)

/** 持久化查询结果及官方 Neo4j 投影，刷新后仍可按回答恢复真实图谱。 */
function persistableGraphifyToolResult({ toolName, content }: { toolName: string; content: string }): string | null {
  if (parseGraphifyEvidence('', content)) return content
  if (toolName === 'read-cypher' && parseNeo4jReadCypherGraph(content)) {
    return JSON.stringify({ name: toolName, result: content })
  }
  return null
}

/** 查询流程活动（业务标签，替代原始工具调用条），供 AgentRunActivity 以参考样式呈现。 */
const queryFlowActivities = ref<RunActivity[]>([])
/** 最终正文已开始时，以回答阶段为准，避免迟到的工具开始事件回退进度。 */
const answerCompositionStarted = ref(false)

/** 每次问数预置完整业务流程；只有工具真实完成后才改变步骤标记。 */
const QUERY_FLOW_STEPS = [
  { id: 'semantic', name: '语义解析' },
  { id: 'preflight', name: '查询预检' },
  { id: 'query', name: '执行查询' },
  { id: 'evidence', name: '查询知识图谱' },
  { id: 'answer', name: '整理回答' },
] as const

function createQueryFlowActivities(): RunActivity[] {
  const startTime = Date.now()
  return QUERY_FLOW_STEPS.map((step) => ({ ...step, status: 'pending', startTime }))
}

/** MCP 工具名 → 业务阶段标签；不在表内的工具按「其他处理」折叠，不占步骤。 */
const QUERY_FLOW_LABEL: Record<string, string> = {
  semantic_context: '语义解析',
  query_preflight: '查询预检',
  wren_query_preflight: '查询预检',
  query: '执行查询',
  run_template_query: '执行查询',
  wren_query: '执行查询',
  'read-cypher': '查询知识图谱',
}

function handleToolCallActivity(t: { toolName: string; status: 'running' | 'completed' | 'failed'; content?: string }) {
  const label = QUERY_FLOW_LABEL[t.toolName]
  if (!label) return
  if (t.toolName === 'read-cypher' && t.status === 'completed' && !parseNeo4jReadCypherGraph(t.content ?? '')) return
  // 流式事件可能乱序抵达。最终正文已出现后，不允许旧工具的“开始”事件把进度重新标回处理中。
  if (answerCompositionStarted.value && t.status === 'running') return
  if (t.status === 'failed') {
    const failed = queryFlowActivities.value.find((a) => a.name === label)
    if (failed) { failed.status = 'failed'; failed.elapsed = Date.now() - failed.startTime }
    return
  }
  let step = queryFlowActivities.value.find((a) => a.name === label)
  if (!step) {
    step = { id: label, name: label, status: t.status === 'running' ? 'running' : 'completed', startTime: Date.now() }
    queryFlowActivities.value.push(step)
  } else {
    step.status = t.status === 'running' ? 'running' : 'completed'
    if (t.status === 'completed') step.elapsed = Date.now() - step.startTime
  }
}

/** 最终业务正文开始输出时，结束滞后的工具活动并进入回答整理阶段。 */
function beginAnswerComposition(content: string) {
  if (!content.trim()) return
  answerCompositionStarted.value = true
  const now = Date.now()
  queryFlowActivities.value.forEach((activity) => {
    if (activity.status === 'running') {
      activity.status = 'completed'
      activity.elapsed = now - activity.startTime
    }
  })
  const answer = queryFlowActivities.value.find((activity) => activity.id === 'answer')
  if (answer?.status === 'pending') answer.status = 'running'
}

/** 整轮结束时收口“整理回答”，避免最终结果已出现却仍显示加载中。 */
function completeAnswerComposition() {
  const answer = queryFlowActivities.value.find((activity) => activity.id === 'answer')
  if (answer?.status === 'running') {
    answer.status = 'completed'
    answer.elapsed = Date.now() - answer.startTime
  }
}

/** 每轮运行开始时预置业务步骤，结束时收口回答整理状态。 */
function handleRunStateChanged(isRunning: boolean) {
  if (isRunning) {
    queryFlowActivities.value = createQueryFlowActivities()
    answerCompositionStarted.value = false
  } else {
    completeAnswerComposition()
  }
}

/** 仅将医保问数的工具过程映射为业务步骤，状态卡仍由 Chat 消息流就近渲染。 */
function adaptQueryFlowActivities(_activities: readonly RunActivity[]): RunActivity[] {
  return queryFlowActivities.value
}

function onSessionMessagesChanged({ messages }: { sessionId: string | null; messages: readonly ChatMessageVO[] }) {
  evidenceByMessageId.value = buildSessionEvidence(messages)
  hasMessages.value = messages.some((m) => m.role === 'user' || m.role === 'assistant')
}

// 空状态快捷问题池：覆盖真实药品、耗材和医保服务目录的随机问题池。
const QUICK_QUESTIONS: readonly string[] = [
  '查询“复方氯己定含漱液”的生产企业和规格',
  '查询“聚维酮碘含漱液”的生产企业和规格',
  '查询“西吡氯铵含漱液”的支付类别、最高价格和价格口径',
  '查询“氯己定苯佐卡因含片”的规格和生产企业',
  '查询“西地碘含片”的支付类别、最高价格和价格口径',
  '查询“复方氢氧化铝片”的生产企业和规格',
  '查询生产企业为“杭州民生药业股份有限公司”的药品名称和规格',
  '查询甲类药品的名称、生产企业、最高价格和价格口径',
  '查询乙类药品的名称、规格和生产企业',
  '查询药品目录的最高价格和价格口径',
  '查询“覆膜气管支架”的分类、材质和生产企业',
  '查询“一次性使用支气管定位支架”的材质、生产企业和支付类别',
  '查询“镍钛记忆合金自扩张式医用内支架(气道支架)”的分类和生产企业',
  '查询“气管支架”的材质、生产企业和支付类别',
  '查询呼吸介入材料的目录名称、材质和生产企业',
  '查询非血管介入治疗类材料的二级分类和支付类别',
  '查询材质为不锈钢的耗材名称、分类和生产企业',
  '查询材质为合金的耗材名称、分类和生产企业',
  '查询乙类耗材的名称、二级分类和生产企业',
  '查询耗材目录的一级分类、二级分类和支付类别',
  '查询“互联网首诊（普通医师）”的支付类别和省级一档最高限额',
  '查询“互联网首诊(副主任医师)”的支付类别和省级一档最高限额',
  '查询“互联网首诊(主任医师)”的支付类别和省级一档最高限额',
  '查询不同医师级别的互联网首诊项目和最高限额',
  '查询“门诊诊查费（普通门诊）”的支付类别和自付比例',
  '查询政策号为“豫医保办〔2025〕51号”的医保服务项目',
  '查询甲类医保服务项目的名称、自付比例和最高限额',
  '查询丙类医保服务项目的名称、自付比例和最高限额',
  '查询名称含“诊查”的医保服务项目和支付类别',
  '查询医保服务项目的自付比例和省级一档最高限额',
]
/** 每次进入空状态随机取 8 条、乱序。 */
const quickQuestions = ref<string[]>([])

function shuffle<T>(list: readonly T[]): T[] {
  const arr = [...list]
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[arr[i], arr[j]] = [arr[j]!, arr[i]!]
  }
  return arr
}

function initializeQuickQuestions() {
  quickQuestions.value = shuffle(QUICK_QUESTIONS).slice(0, 8)
}

async function sendQuickQuestion(question: string) {
  if (quickSending.value || !chatRef.value) return
  quickSending.value = true
  try {
    await chatRef.value.submitExternalSubmission({
      displayText: question,
      runtimeText: question,
      titleText: question,
      fileIds: [],
    })
  } finally {
    quickSending.value = false
  }
}

const showQuickQuestions = computed(() => Boolean(agentId.value) && !hasMessages.value)

/** 助手消息完整渲染（数据即正文 Markdown 表格/详情，不剔除任何内容）。 */
function messageDisplayAdapter(input: { role: string; content: string }): string {
  if (input.role === 'assistant') return assistantBody(input.content)
  return input.content
}

/** 旧会话或模型偶发把过程叙述混进助手正文时，只保留以结果标题起始的最终答复。 */
const FINAL_ANSWER_ANCHOR = /^#{1,6}\s+.*(?:查询结果|结果)/m
const PROCESS_NARRATION = /trace_id|语义上下文|构造\s*SQL|现在(?:开始)?(?:执行|构造|查询)|(?:执行|进行)\s*preflight|\bSELECT\b|\bFROM\s+medical_catalog\b/i

function assistantBody(content: string): string {
  const resultTitle = content.match(FINAL_ANSWER_ANCHOR)
  if (resultTitle?.index !== undefined) return content.slice(resultTitle.index)
  if (PROCESS_NARRATION.test(content)) return ''
  return content
}

/**
 * 每条助手消息绑定自己的证据；只有同轮官方 Neo4j `read-cypher` 返回 nodes + edges 才提供查看图谱。
 */
function messagePresentationAdapter(input: ChatMessagePresentationInput): ChatMessagePresentation {
  if (input.role !== 'assistant') return { kind: 'markdown', content: input.content }
  const turn = evidenceByMessageId.value[input.id]
  return {
    kind: 'custom',
    component: GraphifyAssistantMessage,
    props: {
      content: assistantBody(input.content),
      isStreaming: input.isStreaming,
      evidence: turn?.evidence,
      outcome: turn?.outcome,
      neo4jGraph: turn?.neo4jGraph,
    },
  }
}

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  agentId.value = ''
  try {
    const response = await agentApi.page({ agentCode: GRAPHIFY_DATA_QUERY_AGENT_CODE, page: 1, size: 2 })
    const agents = (response.data?.data?.records ?? []).filter((item) => item.agentCode === GRAPHIFY_DATA_QUERY_AGENT_CODE)
    if (agents.length > 1) throw new Error('Duplicate default-graphify-data-query agents')
    if (!agents[0]) {
      loadError.value = '医保问数助手尚未配置或未启用'
      return
    }
    agentId.value = String(agents[0].id)
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的医保问数助手，请联系管理员处理'
      : '医保问数助手加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadAgent() })

// 进入空状态时刷新随机快捷问题（每次不同）。
watch(showQuickQuestions, (show) => {
  if (show) initializeQuickQuestions()
})
</script>

<template>
  <section v-if="agentId" class="graphify-data-query-chat-shell">
    <Chat
      ref="chatRef"
      class="graphify-data-query-chat"
      :chat-agent-id="agentId"
      :show-account="true"
      :message-display-adapter="messageDisplayAdapter"
      :message-presentation-adapter="messagePresentationAdapter"
      :on-session-messages-changed="onSessionMessagesChanged"
      :on-tool-call-activity="handleToolCallActivity"
      :on-assistant-text-activity="beginAnswerComposition"
      :on-run-state-changed="handleRunStateChanged"
      :run-activity-adapter="adaptQueryFlowActivities"
      run-activity-placement="after-latest-user"
      :force-run-activity="true"
      :hide-tool-messages="true"
      :tool-result-persistence-adapter="persistableGraphifyToolResult"
    />

    <!-- 空状态快捷问题：业务引导与自适应换行的胶囊问题。 -->
    <div v-if="showQuickQuestions" class="graphify-quick-pills" aria-label="快捷问题">
      <div class="graphify-quick-intro">
        <MedicineBoxOutlined class="graphify-quick-intro-icon" aria-hidden="true" />
        <h2>从业务问题开始</h2>
        <p>选择一个快捷问题，快速查看结构化表格数据。</p>
      </div>
      <div class="graphify-quick-pills-list">
        <button v-for="(question, i) in quickQuestions" :key="`${i}-${question}`" type="button"
          class="graphify-quick-pill" :disabled="quickSending" @click="sendQuickQuestion(question)">
          {{ question }}
        </button>
      </div>
    </div>

  </section>
  <main v-else class="graphify-route-state" aria-live="polite">
    <ASpin v-if="loading" tip="正在加载医保问数助手…" />
    <section v-else>
      <p>{{ loadError }}</p>
      <AButton type="primary" @click="loadAgent">重新加载</AButton>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.graphify-route-state {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: 24px;
  background: $chat-bg-main;
  color: var(--color-text-primary);
  text-align: center;
}

.graphify-route-state section {
  display: grid;
  gap: 16px;
  max-width: 32rem;
}

.graphify-route-state p {
  margin: 0;
}

.graphify-data-query-chat-shell {
  position: relative;
  min-width: 0;
  height: 100%;
}

/* 此路由已有业务引导，隐藏 Chat 通用欢迎文案，并将输入区停靠在底部。 */
:deep(.graphify-data-query-chat .chat-welcome) {
  padding: 0 32px;
  justify-content: flex-end;
}

:deep(.graphify-data-query-chat .chat-welcome-title),
:deep(.graphify-data-query-chat .chat-welcome-desc) {
  display: none;
}

:deep(.graphify-data-query-chat .chat-welcome-input) {
  margin-top: 0;
  margin-bottom: clamp(32px, 5vh, 72px);
}

/* 空状态快捷问题：居中业务引导与自适应换行的圆角胶囊。 */
.graphify-quick-pills {
  position: absolute;
  z-index: 20;
  top: clamp(96px, 13vh, 156px);
  left: calc(50% + 130px);
  transform: translateX(-50%);
  width: min(1000px, calc(100% - 64px));
  pointer-events: none;
}

.graphify-quick-intro {
  display: grid;
  justify-items: center;
  gap: 12px;
  margin-bottom: 40px;
  text-align: center;
}

.graphify-quick-intro-icon {
  color: #5d7991;
  font-size: 24px;
}

.graphify-quick-intro h2,
.graphify-quick-intro p {
  margin: 0;
}

.graphify-quick-intro h2 {
  color: #30343c;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.3;
}

.graphify-quick-intro p {
  color: #71879b;
  font-size: 17px;
  line-height: 1.6;
}

.graphify-quick-pills-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 18px 14px;
}

.graphify-quick-pill {
  flex: 0 0 auto;
  max-width: 100%;
  padding: 9px 18px;
  border: 1px solid #bdd9ee;
  border-radius: 999px;
  background: rgb(247 251 255 / 94%);
  color: #2f6fa8;
  font-size: 15px;
  line-height: 1.5;
  text-align: center;
  cursor: pointer;
  box-shadow: 0 1px 4px rgb(31 58 58 / 6%);
  transition: border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
  overflow-wrap: anywhere;
  pointer-events: auto;
}

.graphify-quick-pill:hover:not(:disabled) {
  border-color: #5f9ece;
  background: #eaf4fb;
  color: #1f5b8f;
  box-shadow: 0 2px 10px rgb(31 58 58 / 12%);
}

.graphify-quick-pill:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

@media (max-width: 900px) {
  :deep(.graphify-data-query-chat .chat-welcome) {
    padding: 0 16px;
  }

  .graphify-quick-pills {
    left: 50%;
    width: min(760px, calc(100% - 32px));
  }

  .graphify-quick-intro {
    margin-bottom: 28px;
  }

  .graphify-quick-intro h2 {
    font-size: 24px;
  }

  .graphify-quick-intro p {
    font-size: 15px;
  }

}

</style>
