<script setup lang="ts">
import dayjs from 'dayjs'
import type { TracingPageItem, TracingSummary } from '@/types/sessionTracing'

defineProps<{
  summary: TracingSummary | null
  selectedRow: TracingPageItem | null
  loading: boolean
  error: string | null
}>()

function formatTime(value: string | null | undefined): string {
  if (!value) return '-'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}
</script>

<template>
  <aside class="summary-panel panel-surface">
    <div class="summary-heading">
      <h2>处理概览</h2>
      <p>最近处理 {{ formatTime(summary?.lastProcessedAt) }}</p>
    </div>
    <ASpin v-if="loading" class="center-state" />
    <AAlert v-else-if="error" :message="error" type="error" show-icon />
    <template v-else-if="summary">
      <section class="summary-section">
        <h3>结果状态</h3>
        <div class="summary-metrics">
          <div><span>COMPLETE</span><strong>{{ summary.resultStatusCounts.COMPLETE }}</strong></div>
          <div><span>PARTIAL</span><strong>{{ summary.resultStatusCounts.PARTIAL }}</strong></div>
          <div><span>ERROR</span><strong>{{ summary.resultStatusCounts.ERROR }}</strong></div>
        </div>
      </section>
      <section class="summary-section">
        <h3>处理游标</h3>
        <dl class="status-list">
          <div><dt>DISCOVERED</dt><dd>{{ summary.cursorStatusCounts.DISCOVERED }}</dd></div>
          <div><dt>PROCESSING</dt><dd>{{ summary.cursorStatusCounts.PROCESSING }}</dd></div>
          <div><dt>COMPLETE</dt><dd>{{ summary.cursorStatusCounts.COMPLETE }}</dd></div>
          <div><dt>FAILED</dt><dd>{{ summary.cursorStatusCounts.FAILED }}</dd></div>
        </dl>
      </section>
      <AAlert
        v-if="summary.staleProcessingCount > 0"
        :message="`${summary.staleProcessingCount} 条 PROCESSING 已超过租约时间`"
        type="warning"
        show-icon
      />
      <section v-if="selectedRow" class="summary-section selected-summary">
        <h3>当前对话</h3>
        <p>{{ selectedRow.nickname || selectedRow.username || '未知用户' }}</p>
        <dl class="status-list">
          <div><dt>对话轮数</dt><dd>{{ selectedRow.turnCount }}</dd></div>
          <div><dt>Trace 数</dt><dd>{{ selectedRow.traceCount }}</dd></div>
          <div><dt>Observation</dt><dd>{{ selectedRow.fullObservationCount }}</dd></div>
        </dl>
      </section>
    </template>
  </aside>
</template>

<style scoped lang="scss">
.panel-surface {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e6e9ef;
  border-radius: 8px;
}

.summary-panel {
  display: flex;
  flex-direction: column;
  padding: 16px;
}

.summary-heading {
  padding-bottom: 14px;
  border-bottom: 1px solid #eceff3;
}

.summary-heading h2 {
  margin: 0;
  font-size: 18px;
  letter-spacing: 0;
}

.summary-heading p {
  margin: 5px 0 0;
  color: #667085;
  font-size: 13px;
}

.center-state {
  display: block;
  margin: 48px auto;
}

.summary-section {
  margin-top: 16px;
}

.summary-section h3 {
  margin: 0 0 9px;
  font-size: 14px;
}

.summary-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.summary-metrics div {
  min-width: 0;
  padding: 9px 7px;
  text-align: center;
  background: #f7f8fa;
  border-radius: 6px;
}

.summary-metrics span,
.summary-metrics strong {
  display: block;
}

.summary-metrics span {
  overflow: hidden;
  color: #667085;
  font-size: 10px;
  text-overflow: ellipsis;
}

.summary-metrics strong {
  margin-top: 4px;
  font-size: 18px;
}

.status-list {
  margin: 0;
}

.status-list div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
}

.status-list dt {
  color: #667085;
}

.status-list dd {
  margin: 0;
  font-weight: 700;
}

.selected-summary p {
  margin: 0 0 6px;
  overflow-wrap: anywhere;
  font-weight: 600;
}

@media (max-width: 1180px) {
  .summary-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .summary-panel {
    grid-column: auto;
    padding: 14px;
  }
}
</style>
