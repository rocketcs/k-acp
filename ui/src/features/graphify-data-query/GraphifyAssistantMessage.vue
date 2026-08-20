<script setup lang="ts">
import { computed } from 'vue'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import GraphifyEvidenceCard from './GraphifyEvidenceCard.vue'
import { buildResultColumns } from './resultTable'
import { splitAssistantContent } from './tablePlacement'
import type { GraphifyEvidenceEnvelope, GraphifyToolOutcome } from './types'

/**
 * 医药问数助手回答的普通 chat 展示：正文 markdown + 内联数据表 + 可折叠语义依据。
 * 由 messagePresentationAdapter 的 kind:'custom' 分支渲染（替代默认 markdown 气泡内容）。
 */
const props = defineProps<{
  content: string
  isStreaming: boolean
  evidence?: GraphifyEvidenceEnvelope
  outcome?: GraphifyToolOutcome
}>()

// 正文剔除 [[data-table]] 占位符（平台证据表格改为下方内联渲染）。
const bodyText = computed(() => splitAssistantContent(props.content).before)
const resultColumns = computed(() => props.evidence ? buildResultColumns(props.evidence, props.evidence.semantic_context.domain_labels) : [])
const hasRows = computed(() => Boolean(props.evidence?.result.rows.length))
</script>

<template>
  <div class="graphify-assistant-message">
    <MarkdownRenderer
      v-if="bodyText"
      class="graphify-assistant-text"
      :content="bodyText"
      :is-streaming="isStreaming"
      :is-diy-chat="true"
    />

    <!-- 内联数据结果表 -->
    <div v-if="evidence && hasRows" class="graphify-result-table">
      <table>
        <thead>
          <tr>
            <th v-for="column in resultColumns" :key="column.key">{{ column.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in evidence.result.rows" :key="index">
            <td v-for="column in resultColumns" :key="column.key"
              :title="column.formatValue(row[column.key])">{{ column.formatValue(row[column.key]) || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="evidence.result.truncated" class="graphify-result-truncated">结果较多，已截断展示。</p>
    </div>
    <p v-else-if="evidence && !hasRows && !isStreaming" class="graphify-inline-state">本次查询未返回业务记录。</p>

    <!-- 被拦截 / 暂不可用的提示 -->
    <p v-if="outcome && !evidence" class="graphify-state-error">
      {{ outcome.status === 'blocked' ? '该查询已被安全规则拦截，未执行任何数据操作。' : '语义依据暂不可用，请稍后重试。' }}
      {{ outcome.reason || '' }} <template v-if="outcome.trace_id">Trace: {{ outcome.trace_id }}</template>
    </p>

    <!-- 可折叠语义依据 / 图谱 -->
    <GraphifyEvidenceCard v-if="evidence" :evidence="evidence" />
  </div>
</template>

<style scoped lang="scss">
.graphify-assistant-text {
  margin: 0;
  line-height: 1.72;
  overflow-wrap: anywhere;
}

.graphify-result-table {
  margin-top: 14px;
  overflow-x: auto;
  max-width: 100%;
  border: 1px solid #d4e2ed;
  border-radius: 6px;
  background: #fff;
}

.graphify-result-table table {
  min-width: max-content;
  border-collapse: collapse;
}

.graphify-result-table th,
.graphify-result-table td {
  min-width: 140px;
  padding: 7px 10px;
  border-top: 1px solid #e1ebf2;
  color: #425f72;
  font-size: 11px;
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
}

.graphify-result-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  border-top: 0;
  background: #edf5fb;
  color: #5b7387;
  font-size: 10px;
  font-weight: 700;
}

.graphify-result-truncated {
  margin: 8px 10px;
  color: #7891a6;
  font-size: 11px;
}

.graphify-inline-state {
  margin: 12px 0 0;
  color: #6a8091;
  font-size: 12px;
}

.graphify-state-error {
  margin: 12px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #b6523a;
  background: #fff5f2;
  color: #50605d;
  font-size: 12px;
  line-height: 1.6;
}
</style>
