<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  AimOutlined, DatabaseOutlined, DeploymentUnitOutlined, FieldStringOutlined, FilterOutlined,
  MedicineBoxOutlined, ReloadOutlined, ShareAltOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons-vue'
import GraphifyEvidenceGraph from './GraphifyEvidenceGraph.vue'
import GraphifyExecutionPath from './GraphifyExecutionPath.vue'
import { graphNodeLabel, graphRelationSentences, graphRelationSummary } from './evidencePresentation'
import { nodeTypeLabel, type DomainSemantics } from './evidenceStyles'
import type { GraphifyEvidenceEnvelope } from './types'

const props = defineProps<{ evidence: GraphifyEvidenceEnvelope }>()

const open = ref(false)
const showFields = ref(false)
const relationFilter = ref<'all' | 'business' | 'provenance' | 'semantic'>('all')
const selectedId = ref<string | null>(null)
const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)

const domainSemantics = computed<DomainSemantics>(() => ({
  labels: props.evidence.semantic_context.domain_labels,
  headings: props.evidence.semantic_context.domain_headings,
}))
const relationSummary = computed(() => graphRelationSummary(props.evidence))
const selectedNode = computed(() => props.evidence.evidence.nodes.find((node) => node.id === selectedId.value) ?? props.evidence.evidence.nodes[0])
const nodeCount = computed(() => props.evidence.evidence.nodes.length)
const edgeCount = computed(() => props.evidence.evidence.edges.length)

const selectedNodeSummary = computed(() => {
  const node = selectedNode.value
  if (!node) return null
  const relations = graphRelationSentences(props.evidence, node.id)
  const detail = node.kind === 'product' ? props.evidence.product_details?.[node.label] : undefined
  return {
    type: nodeTypeLabel(node, domainSemantics.value),
    label: graphNodeLabel(node),
    edgeCount: relations.length,
    relations: relations.slice(0, 4),
    extraCount: Math.max(0, relations.length - 4),
    productDetail: detail,
  }
})

// 收起后再展开时，重置视图以适配当前证据（避免 canvas 尺寸残留）。
watch(open, async (isOpen) => {
  if (!isOpen) return
  await nextTick()
  graphRef.value?.fit()
})
</script>

<template>
  <div class="graphify-evidence-card">
    <button class="graphify-evidence-toggle" type="button" :aria-expanded="open" @click="open = !open">
      <ShareAltOutlined />
      <span>{{ open ? '收起语义依据' : '查看语义依据' }}</span>
      <span class="graphify-evidence-meta">{{ nodeCount }} 节点 · {{ edgeCount }} 关系</span>
    </button>

    <div v-show="open" class="graphify-evidence-body">
      <template v-if="evidence">
        <header class="graphify-evidence-summary">
          <small>当前对话轮次 · {{ evidence.trace_id }}</small>
          <strong>{{ evidence.question }}</strong>
          <span>{{ evidence.result.rows.length }} 条结果 · {{ evidence.evidence.source_record_count ?? evidence.evidence.source_record_ids.length }} 条来源记录</span>
          <p v-if="relationSummary" class="graphify-evidence-relation-summary">关系摘要：{{ relationSummary }}</p>
        </header>
        <GraphifyExecutionPath :evidence="evidence" />
        <div class="graphify-evidence-legend">
          <span><DatabaseOutlined class="graphify-blue" /> Wren MDL</span>
          <span><MedicineBoxOutlined class="graphify-teal" /> 业务实体</span>
          <span><ShareAltOutlined class="graphify-green" /> 来源追溯</span>
          <span><DeploymentUnitOutlined class="graphify-plum" /> 业务关系</span>
        </div>
        <div class="graphify-evidence-workspace">
          <GraphifyEvidenceGraph ref="graphRef" :evidence="evidence" :relation-filter="relationFilter"
            :show-fields="showFields" :fullscreen="false" @select="selectedId = $event" />
          <div class="graphify-evidence-tools" aria-label="图谱工具栏">
            <button class="icon-btn" title="放大" @click="graphRef?.zoomIn()"><ZoomInOutlined /></button>
            <button class="icon-btn" title="缩小" @click="graphRef?.zoomOut()"><ZoomOutOutlined /></button>
            <button class="icon-btn" title="适应画布" @click="graphRef?.fit()"><AimOutlined /></button>
            <button class="icon-btn" title="重新布局" @click="graphRef?.relayout()"><ReloadOutlined /></button>
            <button class="icon-btn" :class="{ active: showFields }" title="显示/隐藏语义字段"
              @click="showFields = !showFields"><FieldStringOutlined /></button>
          </div>
          <div class="graphify-evidence-actions">
            <label><FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系">
                <option value="all">全部关系</option>
                <option value="business">业务关系</option>
                <option value="provenance">来源追溯</option>
                <option value="semantic">语义关系</option>
              </select></label>
          </div>
        </div>
        <section v-if="selectedNodeSummary" class="graphify-node-summary">
          <header>
            <span class="graphify-node-summary-type">{{ selectedNodeSummary.type }}</span>
            <b>已选节点</b>
            <span class="graphify-node-summary-count">{{ selectedNodeSummary.edgeCount }} 个关联</span>
          </header>
          <h3>{{ selectedNodeSummary.label }}</h3>
          <template v-if="selectedNodeSummary.productDetail">
            <div class="graphify-node-summary-detail">
              <p class="graphify-node-summary-meta">
                <span>{{ selectedNodeSummary.productDetail.count }} 条目录记录</span>
                <span v-if="selectedNodeSummary.productDetail.specifications.length">规格：{{ selectedNodeSummary.productDetail.specifications.join(' / ') }}</span>
                <span v-if="selectedNodeSummary.productDetail.enterprises.length">生产企业：{{ selectedNodeSummary.productDetail.enterprises.join(' / ') }}</span>
                <span v-if="selectedNodeSummary.productDetail.categories.length">支付类别：{{ selectedNodeSummary.productDetail.categories.join(' / ') }}</span>
              </p>
              <details v-if="selectedNodeSummary.productDetail.codes.length" class="graphify-node-summary-codes">
                <summary>目录编码（{{ selectedNodeSummary.productDetail.codes.length }}）</summary>
                <ol>
                  <li v-for="code in selectedNodeSummary.productDetail.codes" :key="code"><code>{{ code }}</code></li>
                </ol>
              </details>
            </div>
          </template>
          <div v-if="selectedNodeSummary.relations.length" class="graphify-node-summary-relations">
            <span v-for="relation in selectedNodeSummary.relations" :key="relation">{{ relation }}</span>
            <span v-if="selectedNodeSummary.extraCount">另有 {{ selectedNodeSummary.extraCount }} 条关联</span>
          </div>
          <p v-else-if="!selectedNodeSummary.productDetail">当前节点暂无可展示的关联。</p>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.graphify-evidence-card {
  margin-top: 14px;
  border: 1px solid #d5e3ef;
  border-radius: 8px;
  background: #fbfdff;
  overflow: hidden;
}

.graphify-evidence-toggle {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  background: #eef6fc;
  color: #276fa9;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  text-align: left;
}

.graphify-evidence-toggle:hover {
  background: #e4f1fb;
}

.graphify-evidence-meta {
  margin-left: auto;
  color: #7891a6;
  font-size: 11px;
  font-weight: 500;
}

.graphify-evidence-body {
  padding: 14px;
  border-top: 1px solid #d5e3ef;
  background: #fbfdff;
}

.graphify-evidence-summary {
  display: grid;
  gap: 6px;
  padding-bottom: 12px;
  border-bottom: 1px solid #d5e3ef;
}

.graphify-evidence-summary small {
  color: #667b8e;
  font-size: 11px;
}

.graphify-evidence-summary strong {
  font-size: 13px;
  line-height: 1.5;
}

.graphify-evidence-summary span {
  width: max-content;
  padding: 3px 6px;
  background: #e7f2fb;
  color: #286da5;
  font-size: 11px;
  font-weight: 700;
}

.graphify-evidence-relation-summary {
  margin: 0;
  color: #627b8e;
  font-size: 11px;
  line-height: 1.55;
}

.graphify-evidence-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin: 14px 0 10px;
  color: #627b8e;
  font-size: 11px;
}

.graphify-evidence-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.graphify-evidence-legend svg {
  width: 12px;
  height: 12px;
}

.graphify-blue { color: #3c91d3; }
.graphify-teal { color: #3d9fbe; }
.graphify-green { color: #4d9d9b; }
.graphify-plum { color: #805e93; }

.graphify-evidence-workspace {
  position: relative;
  height: 380px;
  overflow: hidden;
  border: 1px solid #c5dbea;
  border-radius: 6px;
  background: #f2f8fc;
}

.graphify-evidence-tools {
  position: absolute;
  z-index: 6;
  top: 10px;
  right: 10px;
  display: flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid #cfdfeb;
  border-radius: 4px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 4px 14px rgb(31 58 58 / 8%);
}

.graphify-evidence-tools .icon-btn {
  width: 27px;
  height: 27px;
  display: grid;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #567187;
  cursor: pointer;
}

.graphify-evidence-tools .icon-btn:hover {
  border-color: #bed9ed;
  background: #eaf4fc;
  color: #2f80c5;
}

.graphify-evidence-tools .icon-btn.active {
  border-color: #b8d8ed;
  background: #e8f3fb;
  color: #2f80c5;
}

.graphify-evidence-actions {
  position: absolute;
  z-index: 6;
  left: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  padding: 4px;
  border: 1px solid #cfdfeb;
  border-radius: 4px;
  background: rgb(255 255 255 / 94%);
}

.graphify-evidence-actions label {
  display: flex;
  align-items: center;
  gap: 5px;
  padding-right: 5px;
  color: #2c79b5;
}

.graphify-evidence-actions select {
  border: 0;
  outline: 0;
  background: transparent;
  color: #526d82;
  font-size: 11px;
}

.graphify-node-summary {
  margin-top: 12px;
  padding: 11px 0 2px;
  border-top: 1px solid #d5e3ef;
}

.graphify-node-summary header {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #627b8e;
  font-size: 11px;
}

.graphify-node-summary-type {
  padding: 3px 6px;
  border: 1px solid #b8d7eb;
  border-radius: 3px;
  background: #e8f3fb;
  color: #286fa8;
  font-size: 10px;
  font-weight: 700;
}

.graphify-node-summary-count {
  margin-left: auto;
  color: #2b75af;
  font-size: 10px;
}

.graphify-node-summary h3 {
  margin: 7px 0 8px;
  color: #1e4667;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.graphify-node-summary-detail {
  margin: 0 0 8px;
  padding: 8px 9px;
  border: 1px solid #d5e3ed;
  border-radius: 4px;
  background: #f7fbfe;
}

.graphify-node-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 0 0 6px;
  color: #567186;
  font-size: 10.5px;
  line-height: 1.5;
}

.graphify-node-summary-meta span {
  display: inline-flex;
  gap: 3px;
}

.graphify-node-summary-codes summary {
  color: #286fa8;
  font-size: 10.5px;
  font-weight: 650;
  cursor: pointer;
}

.graphify-node-summary-codes ol {
  max-height: 150px;
  margin: 6px 0 0;
  padding-left: 20px;
  overflow-y: auto;
  color: #425f72;
  font-size: 10.5px;
  line-height: 1.6;
}

.graphify-node-summary-codes code {
  color: #1d5f95;
  user-select: all;
}

.graphify-node-summary-relations {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.graphify-node-summary-relations span {
  padding: 4px 6px;
  border: 1px solid #d5e3ed;
  border-radius: 3px;
  background: #f7fbfe;
  color: #567186;
  font-size: 10px;
  line-height: 1.35;
}

.graphify-node-summary > p {
  margin: 0;
  color: #718187;
  font-size: 11px;
}

@media (max-width: 680px) {
  .graphify-evidence-workspace {
    height: 340px;
  }
}
</style>
