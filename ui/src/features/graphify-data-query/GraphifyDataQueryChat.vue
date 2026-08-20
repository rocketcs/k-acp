<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import { ShareAltOutlined } from '@ant-design/icons-vue'
import GraphifyAssistantMessage from './GraphifyAssistantMessage.vue'
import GraphifyGraphView from './GraphifyGraphView.vue'
import { buildSessionEvidence } from './sessionEvidence'
import { splitAssistantContent } from './tablePlacement'
import type { ChatMessageVO, ChatMessagePresentation, ChatMessagePresentationInput } from '@/types'
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
/** 最近一次查询的证据（供“知识图谱查看”入口使用）。 */
const latestEvidence = ref<TurnEvidence | null>(null)
/** 知识图谱查看弹窗开关。 */
const graphViewOpen = ref(false)
const chatRef = ref<{
  submitExternalSubmission: (submission: GraphifyChatSubmission) => Promise<boolean>
} | null>(null)
const quickSending = ref(false)

function onSessionMessagesChanged({ messages }: { sessionId: string | null; messages: readonly ChatMessageVO[] }) {
  evidenceByMessageId.value = buildSessionEvidence(messages)
  hasMessages.value = messages.some((m) => m.role === 'user' || m.role === 'assistant')
  const latestAssistant = [...messages].reverse().find((m) => m.role === 'assistant')
  latestEvidence.value = latestAssistant ? evidenceByMessageId.value[String(latestAssistant.id)] ?? null : null
}

// 空状态快捷问题池：覆盖药品/耗材/服务/诊疗各域的随机高质量问题池。
const QUICK_QUESTIONS: readonly string[] = [
  '查询医保支付类别为甲类的药品目录',
  '查询药名含“阿莫西林”的药品及生产企业',
  '列出医保支付类别为乙类的药品及最高价格',
  '查询医保通用名为“布洛芬”的药品记录',
  '查询名称含“胰岛素”的药品及其生产企业',
  '查询有效期内的高血压用药目录',
  '按生产企业查询感冒类药品目录',
  '查询医保支付类别为丙类的药品及最高价格',
  '查询名称含“一次性”的耗材目录及自付比例',
  '列出医保支付类别为乙类的耗材及最高限额',
  '查询某耗材企业的注册备案号与材质信息',
  '查询名称含“导管”的耗材及其管理类别',
  '查询名称含“口罩”的耗材及自付比例',
  '查询名称含“支架”的高值耗材及最高限额',
  '查询名称含“康复”的医疗服务项目及支付类别',
  '列出医疗服务项目的最省级一档最高限额',
  '查询名称含“透析”的医疗服务及最高限额',
  '查询名称含“检查”的诊疗项目及其价格',
  '统计药品目录中一共收录了多少条记录',
  '按注册备案号查询某耗材的完整目录详情',
  '查询自付比例为 20% 的医疗服务项目',
  '查询名称含“采样”的耗材目录及医用支付类别',
  '查询最近新生效（2026-03-10 起）的目录记录',
  '列出支付类别为丙类的医疗服务项目',
]
/** 每次进入空状态随机取 16 条、乱序。 */
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
  quickQuestions.value = shuffle(QUICK_QUESTIONS).slice(0, 16)
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

/** 剔除 [[data-table]] 占位符，避免普通 chat 气泡里出现字面占位符。 */
function messageDisplayAdapter(input: { role: string; content: string }): string {
  if (input.role === 'assistant') return assistantBody(input.content)
  return input.content
}

/** 助手正文：剔除 [[data-table]] 占位符（普通 chat 气泡/流式都不应出现字面占位符）。 */
function assistantBody(content: string): string {
  return splitAssistantContent(content).before
}

/**
 * 助手回答的展示适配：有语义依据的非流式回答以自定义组件渲染
 * （正文 markdown + 内联数据表 + 可折叠语义依据）；其余保持普通 markdown。
 */
function messagePresentationAdapter(input: ChatMessagePresentationInput): ChatMessagePresentation {
  if (input.role !== 'assistant' || input.isStreaming) {
    return { kind: 'markdown', content: assistantBody(input.content) }
  }
  const turn = evidenceByMessageId.value[input.id]
  if (!turn) {
    return { kind: 'markdown', content: assistantBody(input.content) }
  }
  return {
    kind: 'custom',
    component: GraphifyAssistantMessage,
    props: {
      content: input.content,
      isStreaming: false,
      evidence: turn.evidence,
      outcome: turn.outcome,
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
      loadError.value = '医药问数助手尚未配置或未启用'
      return
    }
    agentId.value = String(agents[0].id)
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的医药问数助手，请联系管理员处理'
      : '医药问数助手加载失败，请稍后重试'
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
      :force-tool-process-active="true"
      :message-display-adapter="messageDisplayAdapter"
      :message-presentation-adapter="messagePresentationAdapter"
      :on-session-messages-changed="onSessionMessagesChanged"
    />

    <!-- 空状态快捷问题：16 个高质量问题胶囊，整齐两列排布（无整块表格容器） -->
    <div v-if="showQuickQuestions" class="graphify-quick-pills" aria-label="快捷问题">
      <span class="graphify-quick-caption">试试这些问题</span>
      <div class="graphify-quick-pills-list">
        <button v-for="(question, i) in quickQuestions" :key="`${i}-${question}`" type="button"
          class="graphify-quick-pill" :disabled="quickSending" @click="sendQuickQuestion(question)">
          {{ question }}
        </button>
      </div>
    </div>

    <!-- 知识图谱查看入口 -->
    <button v-if="agentId" type="button" class="graphify-graph-entry" title="查看知识图谱" :aria-label="`查看知识图谱（${latestEvidence ? '最近一次查询' : '暂无查询'}）`"
      @click="graphViewOpen = true">
      <ShareAltOutlined />
      <span>知识图谱</span>
    </button>

    <GraphifyGraphView :open="graphViewOpen" :evidence="latestEvidence?.evidence" @close="graphViewOpen = false" />
  </section>
  <main v-else class="graphify-route-state" aria-live="polite">
    <ASpin v-if="loading" tip="正在加载医药问数助手…" />
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

/* 空状态快捷问题：深浅蓝边框圆角胶囊，整齐两列排布（无整块表格容器） */
.graphify-quick-pills {
  position: absolute;
  z-index: 20;
  top: 13%;
  left: calc(50% + 130px);
  transform: translateX(-50%);
  width: min(780px, 92%);
}

.graphify-quick-caption {
  display: block;
  margin-bottom: 14px;
  color: #667b8e;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
}

.graphify-quick-pills-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 14px;
}

.graphify-quick-pill {
  padding: 11px 16px;
  border: 1px solid #bcd9ec;
  border-radius: 999px;
  background: rgb(255 255 255 / 96%);
  color: #2c6fa8;
  font-size: 13px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 1px 4px rgb(31 58 58 / 6%);
  transition: border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
  overflow-wrap: anywhere;
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

/* 知识图谱查看入口（主列左下、输入区上方） */
.graphify-graph-entry {
  position: absolute;
  z-index: 20;
  left: 276px;
  bottom: 118px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 14px;
  border: 1px solid #c3d9ec;
  border-radius: 999px;
  background: rgb(255 255 255 / 94%);
  color: #2f6fa8;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 14px rgb(31 58 58 / 10%);
  transition: border-color 0.15s ease, background 0.15s ease, transform 0.15s ease;
}

.graphify-graph-entry:hover {
  transform: translateY(-1px);
  border-color: #69a7d4;
  background: #eaf4fb;
}

@media (max-width: 900px) {
  .graphify-quick-pills {
    left: 50%;
    top: 12%;
    width: min(660px, 94%);
  }
}

@media (max-width: 680px) {
  .graphify-graph-entry {
    left: 16px;
    bottom: 96px;
  }
}
</style>
