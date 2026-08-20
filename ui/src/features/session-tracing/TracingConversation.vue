<script setup lang="ts">
import { computed } from 'vue'
import { CopyOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import type { TracingDetail, TracingRaw } from './types'
import { formatTracingUserLabel } from './utils'

export type TracingDetailTab = 'conversation' | 'trace' | 'raw'

const props = defineProps<{
  detail: TracingDetail | null
  raw: TracingRaw | null
  activeTab: TracingDetailTab
  detailLoading: boolean
  rawLoading: boolean
  detailError: string | null
  rawError: string | null
}>()

const emit = defineEmits<{
  tabChange: [key: string | number]
  retryRaw: []
}>()

const rawSections = computed(() => {
  if (!props.raw) return []
  return [
    ['llmAnalysisJson', props.raw.llmAnalysisJson],
    ['qaPairsJson', props.raw.qaPairsJson],
    ['conversationJson', props.raw.conversationJson],
    ['envelopeJson', props.raw.envelopeJson],
    ['warningsJson', props.raw.warningsJson],
  ] as const
})

function formatTime(value: string | null | undefined): string {
  if (!value) return '-'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

async function copyConversation(): Promise<void> {
  if (!props.detail) return
  const text = props.detail.turns
    .map(
      (turn) =>
        `第 ${turn.turn} 轮\n用户问题：${turn.userQuestion || '未记录用户问题'}\nAgent 最终回答：${turn.agentAnswer || '未记录最终回答'}`,
    )
    .join('\n\n')
  try {
    await copyText(text)
    await message.success('对话已复制')
  } catch {
    await message.error('复制失败')
  }
}

/** 复制文本：优先 Clipboard API，非安全上下文（http LAN）降级为 execCommand */
async function copyText(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-1000vh'
  textarea.style.left = '-1000vw'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const ok = document.execCommand('copy')
  textarea.remove()
  if (!ok) throw new Error('copy failed')
}
</script>

<template>
  <main class="detail-panel panel-surface">
    <ASpin v-if="detailLoading" class="center-state" />
    <AAlert v-else-if="detailError" :message="detailError" type="error" show-icon />
    <AEmpty v-else-if="!detail" description="请选择一条用户对话" />
    <template v-else>
      <div class="detail-heading">
        <div>
          <h2>{{ detail.user.nickname || detail.user.username || '未知用户' }}</h2>
          <p>{{ formatTracingUserLabel(detail.user) }}</p>
        </div>
        <ATooltip title="复制对话">
          <AButton class="icon-button" aria-label="复制对话" @click="copyConversation">
            <template #icon><CopyOutlined /></template>
          </AButton>
        </ATooltip>
      </div>

      <ATabs :active-key="activeTab" class="detail-tabs" @change="emit('tabChange', $event)">
        <ATabPane key="conversation" tab="用户对话">
          <div v-if="detail.turns.length" class="turn-list">
            <section v-for="turn in detail.turns" :key="`${detail.id}-${turn.turn}`" class="turn-block">
              <div class="turn-index">{{ turn.turn }}</div>
              <div class="turn-content">
                <article class="message-block user-message">
                  <header>
                    <strong>用户问题</strong>
                    <time>{{ formatTime(turn.userTimestamp) }}</time>
                  </header>
                  <p>{{ turn.userQuestion || '未记录用户问题' }}</p>
                </article>
                <article class="message-block agent-message">
                  <header>
                    <strong>Agent 最终回答</strong>
                    <time>{{ formatTime(turn.agentTimestamp) }}</time>
                  </header>
                  <p>{{ turn.agentAnswer || '未记录最终回答' }}</p>
                </article>
              </div>
            </section>
          </div>
          <AEmpty v-else description="暂无可展示问答" />
        </ATabPane>

        <ATabPane key="trace" tab="Trace 信息">
          <div class="trace-grid">
            <div class="trace-item"><span>用户</span><strong>{{ formatTracingUserLabel(detail.user) }}</strong></div>
            <div class="trace-item"><span>项目</span><strong>{{ detail.projectId || '-' }}</strong></div>
            <div class="trace-item technical"><span>Session ID</span><strong>{{ detail.sessionId }}</strong></div>
            <div class="trace-item"><span>结果状态</span><strong>{{ detail.status }}</strong></div>
            <div class="trace-item"><span>Trace</span><strong>{{ detail.traceSummary.traceCount }}</strong></div>
            <div class="trace-item"><span>种子 Observation</span><strong>{{ detail.traceSummary.seedObservationCount }}</strong></div>
            <div class="trace-item"><span>完整 Observation</span><strong>{{ detail.traceSummary.fullObservationCount }}</strong></div>
            <div class="trace-item"><span>Score</span><strong>{{ detail.traceSummary.scoreCount }}</strong></div>
            <div class="trace-item"><span>QA 对</span><strong>{{ detail.traceSummary.qaPairCount }}</strong></div>
            <div class="trace-item"><span>开始时间</span><strong>{{ formatTime(detail.traceSummary.firstObservationStartTime) }}</strong></div>
            <div class="trace-item"><span>结束时间</span><strong>{{ formatTime(detail.traceSummary.lastObservationEndTime) }}</strong></div>
            <div class="trace-item"><span>处理时间</span><strong>{{ formatTime(detail.traceSummary.processedAt) }}</strong></div>
          </div>
          <div class="trace-section">
            <h3>类型分布</h3>
            <div class="tag-list">
              <ATag v-for="(count, type) in detail.traceSummary.typeCounts" :key="type">
                {{ type }} {{ count }}
              </ATag>
              <span v-if="Object.keys(detail.traceSummary.typeCounts).length === 0" class="muted">暂无</span>
            </div>
          </div>
          <div v-if="detail.traceSummary.warnings.length" class="trace-section">
            <h3>处理告警</h3>
            <AAlert
              v-for="warning in detail.traceSummary.warnings"
              :key="warning"
              :message="warning"
              type="warning"
              show-icon
            />
          </div>
        </ATabPane>

        <ATabPane key="raw" tab="入库 JSON">
          <ASpin v-if="rawLoading" class="center-state" />
          <AAlert v-else-if="rawError" :message="rawError" type="error" show-icon>
            <template #action><AButton size="small" @click="emit('retryRaw')">重试</AButton></template>
          </AAlert>
          <AEmpty v-else-if="!raw" description="暂无原始数据" />
          <div v-else class="raw-sections">
            <section v-for="section in rawSections" :key="section[0]">
              <h3>{{ section[0] }}</h3>
              <pre>{{ JSON.stringify(section[1], null, 2) }}</pre>
            </section>
          </div>
        </ATabPane>
      </ATabs>
    </template>
  </main>
</template>

<style scoped lang="scss">
.panel-surface {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e6e9ef;
  border-radius: 8px;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 18px;
}

.center-state {
  display: block;
  margin: 48px auto;
}

.detail-heading,
.message-block header {
  display: flex;
  align-items: center;
}

.detail-heading {
  justify-content: space-between;
  gap: 12px;
}

.detail-heading h2 {
  margin: 0;
  font-size: 18px;
  letter-spacing: 0;
}

.detail-heading > div {
  min-width: 0;
}

.detail-heading p {
  margin: 5px 0 0;
  color: #667085;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.icon-button {
  width: 34px;
  height: 34px;
  padding: 0;
}

.detail-tabs {
  min-width: 0;
  margin-top: 8px;
}

.detail-tabs :deep(.ant-tabs-content-holder) {
  max-height: calc(100vh - 300px);
  overflow-y: auto;
}

.turn-list {
  display: grid;
  gap: 16px;
}

.turn-block {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 11px;
}

.turn-index {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: #315ee8;
  font-weight: 700;
  background: #edf2ff;
  border-radius: 7px;
}

.turn-content {
  display: grid;
  gap: 9px;
  min-width: 0;
}

.message-block {
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid #e6e9ef;
  border-radius: 7px;
}

.user-message {
  background: #fafbff;
}

.agent-message {
  border-left: 3px solid #4772ff;
}

.message-block header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  color: #667085;
  font-size: 12px;
}

.message-block time {
  flex: 0 0 auto;
  font-weight: 400;
}

.message-block p {
  margin: 0;
  overflow-wrap: anywhere;
  color: #252a34;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.trace-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.trace-item {
  min-width: 0;
  padding: 12px;
  background: #fafbfc;
  border: 1px solid #eceff3;
  border-radius: 6px;
}

.trace-item span,
.trace-item strong {
  display: block;
}

.trace-item span {
  margin-bottom: 5px;
  color: #667085;
  font-size: 12px;
}

.trace-item strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.trace-item.technical strong,
.raw-sections pre {
  font-family: "SFMono-Regular", Consolas, monospace;
}

.trace-section {
  margin-top: 18px;
}

.trace-section h3,
.raw-sections h3 {
  margin: 0 0 9px;
  font-size: 14px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.muted {
  color: #98a2b3;
}

.raw-sections {
  display: grid;
  gap: 14px;
}

.raw-sections pre {
  max-width: 100%;
  overflow: auto;
  margin: 0;
  padding: 12px;
  color: #273142;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f6f8fb;
  border: 1px solid #e6e9ef;
  border-radius: 6px;
}

@media (max-width: 760px) {
  .detail-panel {
    padding: 14px;
  }

  .trace-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .turn-block {
    grid-template-columns: minmax(0, 1fr);
  }

  .turn-index {
    width: 32px;
    height: 28px;
  }

  .message-block header {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
}
</style>
