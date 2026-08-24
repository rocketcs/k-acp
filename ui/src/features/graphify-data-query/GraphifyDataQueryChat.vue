<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import GraphifyAssistantMessage from './GraphifyAssistantMessage.vue'
import GraphExplorerModal from './GraphExplorerModal.vue'
import { parseGraphifyEvidence, parseGraphifyGraphReference, parseNeo4jReadCypherGraph } from './evidenceAdapter'
import { buildSessionEvidence, hydrateGraphReferences } from './sessionEvidence'
import type { ChatMessageVO, ChatMessagePresentation, ChatMessagePresentationInput, RunActivity } from '@/types'
import type { TurnEvidence } from './turnEvidence'

const GRAPHIFY_DATA_QUERY_AGENT_CODE = 'default-graphify-data-query'

const agentId = ref('')
const loading = ref(true)
const loadError = ref('')
const graphExplorerOpen = ref(false)

/** 助手消息 id → 该轮语义依据（重构自会话消息 + 本地缓存，普通 chat 内联展示用）。 */
const evidenceByMessageId = ref<Record<string, TurnEvidence>>({})

/** 持久化查询结果及真实 Neo4j 证据投影，刷新后仍可按回答恢复图谱。 */
function persistableGraphifyToolResult({ toolName, content }: { toolName: string; content: string }): string | null {
  if (parseGraphifyEvidence('', content)) return JSON.stringify({ name: toolName, result: content })
  if (toolName === 'evidence_subgraph' && parseGraphifyGraphReference(content)) return JSON.stringify({ name: toolName, result: content })
  if (toolName === 'read-cypher' && parseNeo4jReadCypherGraph(content)) {
    return JSON.stringify({ name: toolName, result: content })
  }
  return null
}

/** 查询流程活动（业务标签，替代原始工具调用条），供 AgentRunActivity 以参考样式呈现。 */
const queryFlowActivities = ref<RunActivity[]>([])
const completedRunActivities = ref<RunActivity[]>([])
/** 最终正文已开始时，以回答阶段为准，避免迟到的工具开始事件回退进度。 */
const answerCompositionStarted = ref(false)

/** 每次问数预置完整业务流程；只有工具真实完成后才改变步骤标记。 */
const QUERY_FLOW_STEPS = [
  { id: 'semantic', name: '语义解析' },
  { id: 'preflight', name: '查询预检' },
  { id: 'query', name: '执行查询' },
  { id: 'evidence', name: '查询数据管理' },
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
  evidence_subgraph: '查询数据管理',
  // 兼容历史会话；新请求统一走 evidence_subgraph，避免智能体直接拼 Cypher。
  'read-cypher': '查询数据管理',
}

function handleToolCallActivity(t: { toolName: string; status: 'running' | 'completed' | 'failed'; content?: string }) {
  const label = QUERY_FLOW_LABEL[t.toolName]
  if (!label) return
  if (t.toolName === 'read-cypher' && t.status === 'completed' && !parseNeo4jReadCypherGraph(t.content ?? '')) return
  if (t.toolName === 'evidence_subgraph' && t.status === 'completed') {
    const envelope = parseGraphifyEvidence('', t.content ?? '')
    const nodeKinds = new Set(['product', 'registration', 'organization', 'base', 'concept', 'attribute', 'catalog_record', 'source_file', 'import_batch'])
    const actualNodes = envelope?.evidence.nodes.filter((node) => nodeKinds.has(node.kind)) ?? []
    const actualIds = new Set(actualNodes.map((node) => node.id))
    const hasGraphEdges = envelope?.evidence.edges.some((edge) => actualIds.has(edge.source) && actualIds.has(edge.target)) ?? false
    // An empty/unsupported evidence projection is a completed optional step,
    // not a still-running one. Keep the run card truthful instead of leaving
    // the whole answer stuck at “等待查询数据管理” forever; the answer-level
    // graph button remains hidden because GraphifyAssistantMessage still
    // requires real Neo4j nodes and edges.
    if (!actualNodes.length || !hasGraphEdges) {
      const step = queryFlowActivities.value.find((activity) => activity.name === label)
      if (step) {
        step.status = 'completed'
        step.elapsed = Date.now() - step.startTime
      }
      return
    }
  }
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
  completedRunActivities.value = queryFlowActivities.value.map((activity) => ({ ...activity }))
}

/** 每轮运行开始时预置业务步骤，结束时收口回答整理状态。 */
function handleRunStateChanged(isRunning: boolean) {
  if (isRunning) {
    queryFlowActivities.value = createQueryFlowActivities()
    completedRunActivities.value = []
    answerCompositionStarted.value = false
  } else {
    completeAnswerComposition()
  }
}

/** 仅将医保问数的工具过程映射为业务步骤，状态卡仍由 Chat 消息流就近渲染。 */
function adaptQueryFlowActivities(_activities: readonly RunActivity[]): RunActivity[] {
  return queryFlowActivities.value
}

let hydrationGeneration = 0
async function onSessionMessagesChanged({ messages }: { sessionId: string | null; messages: readonly ChatMessageVO[] }) {
  const generation = ++hydrationGeneration
  const restored = buildSessionEvidence(messages)
  evidenceByMessageId.value = restored
  const hydrated = await hydrateGraphReferences(restored)
  if (generation === hydrationGeneration) evidenceByMessageId.value = hydrated
}

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
 * 每条助手消息绑定自己的证据；只有同轮 evidence_subgraph 返回真实 Neo4j nodes + edges 才提供查看数据管理。
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
</script>

<template>
  <section v-if="agentId" class="graphify-data-query-chat-shell">
    <Chat
      class="graphify-data-query-chat"
      :chat-agent-id="agentId"
      :show-account="true"
      :force-diy-config="true"
      :show-graph-explorer="true"
      :message-display-adapter="messageDisplayAdapter"
      :message-presentation-adapter="messagePresentationAdapter"
      :on-session-messages-changed="onSessionMessagesChanged"
      :on-tool-call-activity="handleToolCallActivity"
      :on-assistant-text-activity="beginAnswerComposition"
      :on-run-state-changed="handleRunStateChanged"
      :run-activity-adapter="adaptQueryFlowActivities"
      :completed-run-activities="completedRunActivities"
      run-activity-placement="after-latest-user"
      :force-run-activity="true"
      :retain-finished-run-activity="true"
      :hide-tool-messages="true"
      :tool-result-persistence-adapter="persistableGraphifyToolResult"
      @graph-explorer="graphExplorerOpen = true"
    />
    <GraphExplorerModal v-model:open="graphExplorerOpen" />
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
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  min-height: 100vh;

  @media (max-width: 767px) {
    height: 100dvh;
    min-height: 100dvh;
  }
}

:deep(.graphify-data-query-chat) {
  flex: 1 1 auto;
  min-height: 0;
}

/* 空状态保留上方快捷问题，同时把数据管理入口和输入框推到窗口底部。 */
:deep(.graphify-data-query-chat .chat-welcome-container) {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
}

:deep(.graphify-data-query-chat .chat-welcome) {
  height: 100%;
  min-height: 100%;
  justify-content: flex-start;
}

:deep(.graphify-data-query-chat .chat-welcome-input) {
  margin-top: auto;
  margin-bottom: clamp(24px, 4vh, 48px);
}

/* DIY 欢迎页有更高优先级的默认间距，必须在该层明确覆盖，才能落到红框区域。 */
:deep(.graphify-data-query-chat .chat-welcome.is-diy-welcome .chat-welcome-input) {
  margin-top: auto;
}

/* 当前问数入口的快捷卡片收窄一些，减少横向空白，同时保留三列可读布局。 */
:deep(.graphify-data-query-chat .diy-welcome-content) {
  width: min(100%, 1120px);
}

:deep(.graphify-data-query-chat .diy-question-grid) {
  gap: clamp(12px, 1.2vw, 16px);
}

:deep(.graphify-data-query-chat .diy-question-card) {
  flex: 1 1 clamp(260px, 30%, 360px);
  min-width: min(100%, 260px);
  max-width: 360px;
  min-height: clamp(96px, 11vh, 112px);
  gap: clamp(10px, 1.2vw, 14px);
  padding: clamp(14px, 1.6vw, 18px) clamp(16px, 1.8vw, 20px);
  border-radius: 18px;
}

:deep(.graphify-data-query-chat .diy-question-icon) {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  font-size: 24px;
}

:deep(.graphify-data-query-chat .diy-question-copy strong) {
  font-size: 16px;
}

:deep(.graphify-data-query-chat .diy-question-copy small) {
  font-size: 13px;
}

@media (max-width: 767px) {
  :deep(.graphify-data-query-chat .diy-welcome-content) {
    width: 100%;
  }

  :deep(.graphify-data-query-chat .diy-question-card) {
    flex: 1 1 100%;
    flex-basis: 100%;
    min-width: 100%;
    max-width: 100%;
    min-height: 96px;
  }
}

</style>
