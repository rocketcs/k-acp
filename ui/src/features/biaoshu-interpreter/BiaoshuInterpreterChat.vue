<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Chat from '@/views/Chat/index.vue'
import * as agentApi from '@/api/agent'
import type { ChatMessageVO } from '@/types'

const BIAOSHU_INTERPRETER_AGENT_CODE = 'biaoshu-interpreter'

const agentId = ref('')
const loading = ref(true)
const loadError = ref('')
const currentSessionId = ref<string | null>(null)
const toolCallCount = ref(0)

const toolCallSummaryText = computed(() => `已隐藏 ${toolCallCount.value} 次工具调用`)

function onSessionMessagesChanged({ sessionId, messages }: { sessionId: string | null; messages: readonly ChatMessageVO[] }) {
  currentSessionId.value = sessionId
  toolCallCount.value = sessionId
    ? messages.filter((item) => item.sessionId === sessionId && item.role === 'tool').length
    : 0
}

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  agentId.value = ''
  try {
    const response = await agentApi.page({ agentCode: BIAOSHU_INTERPRETER_AGENT_CODE, page: 1, size: 2 })
    const agents = (response.data?.data?.records ?? []).filter((item) => item.agentCode === BIAOSHU_INTERPRETER_AGENT_CODE)
    if (agents.length > 1) throw new Error('Duplicate biaoshu-interpreter agents')
    const agent = agents[0]
    if (!agent) {
      loadError.value = '标书智能解读助手尚未配置或未启用'
      return
    }
    agentId.value = String(agent.id)
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的标书智能解读助手，请联系管理员处理'
      : '标书智能解读助手加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadAgent() })
</script>

<template>
  <section v-if="agentId" class="biaoshu-interpreter-chat-shell">
    <Chat
      class="biaoshu-interpreter-chat"
      :chat-agent-id="agentId"
      :show-account="true"
      :on-session-messages-changed="onSessionMessagesChanged"
    />
    <div
      v-if="currentSessionId && toolCallCount > 0"
      class="biaoshu-tool-summary"
      aria-live="polite"
    >
      <span class="biaoshu-tool-summary-dot" />
      <span>{{ toolCallSummaryText }}</span>
    </div>
  </section>
  <main v-else class="chat-route-state">
    <ASpin v-if="loading" tip="正在加载标书智能解读助手…" />
    <section v-else aria-live="polite">
      <p>{{ loadError }}</p>
      <AButton type="primary" @click="loadAgent">重新加载</AButton>
    </section>
  </main>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-route-state {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: 24px;
  background: $chat-bg-main;
  color: var(--color-text-primary);
  text-align: center;
}

.chat-route-state section {
  display: grid;
  gap: 16px;
  max-width: 32rem;
}

.chat-route-state p {
  margin: 0;
}

.biaoshu-interpreter-chat-shell {
  position: relative;
  min-width: 0;
}

.biaoshu-tool-summary {
  position: fixed;
  z-index: 19;
  top: 18px;
  right: 24px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: #475569;
  font-size: 13px;
  line-height: 1;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(8px);
}

.biaoshu-tool-summary-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #1677ff;
}

:deep(.chat-message:has(.chat-tool-panel)) {
  display: none;
}

:deep(.chat-tool-call) {
  display: none;
}

@media (max-width: 760px) {
  .biaoshu-tool-summary {
    top: 14px;
    right: 14px;
    max-width: calc(100vw - 28px);
  }
}
</style>
