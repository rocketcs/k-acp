<script setup lang="ts">
import { computed, ref } from 'vue'
import { ShareAltOutlined } from '@ant-design/icons-vue'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import GraphifyGraphView from './GraphifyGraphView.vue'
import { buildGraphView, hasRenderableGraph } from './graphViewAdapter'
import { buildResultColumns } from './resultTable'
import { recoverLegacyNeo4jGraph } from './sessionEvidence'
import { splitAssistantContent } from './tablePlacement'
import type { GraphifyEvidenceEnvelope, GraphifyToolOutcome } from './types'
import type { Neo4jReadCypherGraph } from './evidenceAdapter'

/**
 * 医保问数助手回答的普通 chat 展示：正文 markdown + 内联数据表 + 可折叠语义依据。
 * 由 messagePresentationAdapter 的 kind:'custom' 分支渲染（替代默认 markdown 气泡内容）。
 */
const props = defineProps<{
  content: string
  isStreaming: boolean
  evidence?: GraphifyEvidenceEnvelope
  outcome?: GraphifyToolOutcome
  /** 仅由只读 Neo4j evidence_subgraph 结果生成，绝不以 Wren 查询结果兜底。 */
  neo4jGraph?: Neo4jReadCypherGraph
}>()

// 仅当正文显式给出 [[data-table]] 占位符时，才以平台结果表替换该位置；否则保留模型的原表格。
const contentParts = computed(() => splitAssistantContent(props.content))
const bodyText = computed(() => contentParts.value.before)
const afterTableText = computed(() => contentParts.value.after)
const resultColumns = computed(() => props.evidence ? buildResultColumns(props.evidence, props.evidence.semantic_context.domain_labels) : [])
const hasRows = computed(() => Boolean(props.evidence?.result.rows.length))
const shouldRenderResultTable = computed(() => Boolean(contentParts.value.hasPlaceholder && props.evidence && hasRows.value))
const shouldShowEmptyResult = computed(() => Boolean(contentParts.value.hasPlaceholder && props.evidence && !hasRows.value && !props.isStreaming))
const graphViewOpen = ref(false)
/** 会话重放偶发丢失 neo4jGraph 投影时，直接由同一份官方证据信封恢复；普通查询信封不会通过严格校验。 */
const resolvedNeo4jGraph = computed(() => props.neo4jGraph ?? (props.evidence ? recoverLegacyNeo4jGraph(props.evidence) : null))
/** 图谱弹窗只接收真实 Neo4j 节点和关系，不混入 Wren 语义/查询执行节点。 */
const graphEvidence = computed<GraphifyEvidenceEnvelope | undefined>(() => {
  const graph = resolvedNeo4jGraph.value
  if (!props.evidence || !graph) return undefined
  return { ...props.evidence, evidence: { ...props.evidence.evidence, nodes: graph.nodes, edges: graph.edges } }
})
const displayedGraphStats = computed(() => graphEvidence.value
  ? buildGraphView(graphEvidence.value, { viewMode: 'full', showFields: false }).stats
  : { nodeCount: 0, edgeCount: 0 })
/** Must have a non-empty rendered projection, not merely raw Neo4j rows. */
const hasNeo4jGraph = computed(() => Boolean(graphEvidence.value && hasRenderableGraph(graphEvidence.value)))
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
    <div v-if="shouldRenderResultTable && evidence" class="graphify-result-table">
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
    <p v-else-if="shouldShowEmptyResult" class="graphify-inline-state">本次查询未返回业务记录。</p>

    <MarkdownRenderer
      v-if="afterTableText"
      class="graphify-assistant-text graphify-assistant-text--after-table"
      :content="afterTableText"
      :is-streaming="isStreaming"
      :is-diy-chat="true"
    />

    <!-- 被拦截 / 暂不可用的提示 -->
    <p v-if="outcome && !evidence" class="graphify-state-error">
      {{ outcome.status === 'blocked' ? '该查询已被安全规则拦截，未执行任何数据操作。' : '语义依据暂不可用，请稍后重试。' }}
      {{ outcome.reason || '' }} <template v-if="outcome.trace_id">Trace: {{ outcome.trace_id }}</template>
    </p>

    <!-- 每条回答只展示自身官方 Neo4j 子图；没有真实 nodes + edges 时不出现按钮。 -->
    <div v-if="hasNeo4jGraph" class="graphify-answer-graph-action">
      <button type="button" class="graphify-answer-graph-button" title="查看数据管理" aria-label="查看数据管理"
        @click="graphViewOpen = true">
        <ShareAltOutlined />
        <span>查看数据管理</span>
        <small>{{ displayedGraphStats.nodeCount }} 节点 · {{ displayedGraphStats.edgeCount }} 关系（两级）</small>
      </button>
    </div>

    <GraphifyGraphView :open="graphViewOpen" :evidence="graphEvidence" @close="graphViewOpen = false" />
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

.graphify-answer-graph-action {
  display: flex;
  margin-top: 14px;
}

.graphify-answer-graph-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 11px;
  border: 1px solid #c3d9ec;
  border-radius: 7px;
  background: #f7fbff;
  color: #2f6fa8;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.graphify-answer-graph-button:hover {
  border-color: #69a7d4;
  background: #eaf4fb;
}

.graphify-answer-graph-button svg {
  color: #2f80c5;
}

.graphify-answer-graph-button small {
  padding-left: 2px;
  color: #7891a6;
  font-size: 11px;
  font-weight: 500;
}
</style>
