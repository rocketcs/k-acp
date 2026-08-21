<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import {
  AimOutlined, CloseOutlined, DatabaseOutlined, DeploymentUnitOutlined, FieldStringOutlined,
  FilterOutlined, MedicineBoxOutlined, ReloadOutlined, ShareAltOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons-vue'
import GraphifyEvidenceGraph from './GraphifyEvidenceGraph.vue'
import GraphifyExecutionPath from './GraphifyExecutionPath.vue'
import type { GraphifyEvidenceEnvelope } from './types'

/**
 * 知识图谱查看（大屏态）。graphify 特性专属：从聊天下方入口打开，
 * 展示最近一次查询的证据图谱；尚无查询时给出引导提示。
 */
const props = defineProps<{
  open: boolean
  evidence?: GraphifyEvidenceEnvelope
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)
const showFields = ref(false)
const relationFilter = ref<'all' | 'business' | 'provenance' | 'semantic'>('all')

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return
    await nextTick()
    graphRef.value?.fit()
  },
)

const nodeCount = computed(() => props.evidence?.evidence.nodes.length ?? 0)
const edgeCount = computed(() => props.evidence?.evidence.edges.length ?? 0)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="graphify-graph-view" role="dialog" aria-modal="true" aria-label="知识图谱查看"
      @click.self="emit('close')">
      <div class="graphify-graph-view-panel">
        <header class="graphify-graph-view-head">
          <h2><ShareAltOutlined /> 知识图谱查看</h2>
          <button class="graphify-graph-view-close" type="button" title="关闭" aria-label="关闭" @click="emit('close')">
            <CloseOutlined />
          </button>
        </header>

        <div class="graphify-graph-view-body">
          <template v-if="evidence">
            <header class="graphify-graph-view-summary">
              <small>最近一次查询 · {{ evidence.trace_id }}</small>
              <strong>{{ evidence.question }}</strong>
              <span>{{ evidence.result.rows.length }} 条结果 · {{ evidence.evidence.source_record_count ?? evidence.evidence.source_record_ids.length }} 条来源记录</span>
            </header>
            <GraphifyExecutionPath :evidence="evidence" />
            <div class="graphify-graph-view-legend">
              <span><DatabaseOutlined class="graphify-blue" /> Wren MDL</span>
              <span><MedicineBoxOutlined class="graphify-teal" /> 业务实体</span>
              <span><ShareAltOutlined class="graphify-green" /> 来源追溯</span>
              <span><DeploymentUnitOutlined class="graphify-plum" /> 业务关系</span>
            </div>
            <div class="graphify-graph-view-canvas">
              <GraphifyEvidenceGraph ref="graphRef" :evidence="evidence" :relation-filter="relationFilter"
                :show-fields="showFields" fullscreen />
              <div class="graphify-graph-view-tools" aria-label="图谱工具栏">
                <button type="button" title="放大" @click="graphRef?.zoomIn()"><ZoomInOutlined /></button>
                <button type="button" title="缩小" @click="graphRef?.zoomOut()"><ZoomOutOutlined /></button>
                <button type="button" title="适应画布" @click="graphRef?.fit()"><AimOutlined /></button>
                <button type="button" title="重新布局" @click="graphRef?.relayout()"><ReloadOutlined /></button>
                <button type="button" :class="{ active: showFields }" title="显示/隐藏语义字段"
                  @click="showFields = !showFields"><FieldStringOutlined /></button>
              </div>
              <div class="graphify-graph-view-actions">
                <label><FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系">
                    <option value="all">全部关系</option>
                    <option value="business">业务关系</option>
                    <option value="provenance">来源追溯</option>
                    <option value="semantic">语义关系</option>
                  </select><span>{{ nodeCount }} 节点 · {{ edgeCount }} 关系</span></label>
              </div>
            </div>
          </template>
          <div v-else class="graphify-graph-view-empty">
            <ShareAltOutlined />
            <p>暂无知识图谱。请先提出一个医保问数问题，查询完成后即可查看结果图谱。</p>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.graphify-graph-view {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 14px;
  background: rgb(20 30 38 / 55%);
}

.graphify-graph-view-panel {
  display: flex;
  flex-direction: column;
  width: min(1600px, 96vw);
  height: min(1080px, 94vh);
  overflow: hidden;
  border: 1px solid #b9cdd9;
  border-radius: 10px;
  background: #fbfdff;
  box-shadow: 0 18px 60px rgb(15 35 45 / 35%);
}

.graphify-graph-view-head {
  display: flex;
  height: 54px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 0 20px;
  border-bottom: 1px solid #d5e3ef;
  background: #fff;
}

.graphify-graph-view-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: #1e4667;
  font-size: 16px;
}

.graphify-graph-view-head h2 svg {
  color: #2f80c5;
}

.graphify-graph-view-close {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: #567187;
  cursor: pointer;
}

.graphify-graph-view-close:hover {
  border-color: #bed9ed;
  background: #eaf4fc;
  color: #2f80c5;
}

.graphify-graph-view-body {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  padding: 16px;
  overflow: hidden;
}

.graphify-graph-view-summary {
  display: grid;
  gap: 6px;
  padding: 0 2px 14px;
  border-bottom: 1px solid #d5e3ef;
}

.graphify-graph-view-summary small {
  color: #667b8e;
  font-size: 11px;
}

.graphify-graph-view-summary strong {
  font-size: 14px;
  line-height: 1.5;
}

.graphify-graph-view-summary span {
  width: max-content;
  padding: 3px 6px;
  background: #e7f2fb;
  color: #286da5;
  font-size: 11px;
  font-weight: 700;
}

.graphify-graph-view-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin: 14px 0 10px;
  color: #627b8e;
  font-size: 11px;
}

.graphify-graph-view-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.graphify-graph-view-legend svg {
  width: 12px;
  height: 12px;
}

.graphify-blue { color: #3c91d3; }
.graphify-teal { color: #3d9fbe; }
.graphify-green { color: #4d9d9b; }
.graphify-plum { color: #805e93; }

.graphify-graph-view-canvas {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #c5dbea;
  border-radius: 8px;
  background: #f2f8fc;
}

.graphify-graph-view-tools {
  position: absolute;
  z-index: 6;
  top: 12px;
  right: 12px;
  display: flex;
  gap: 3px;
  padding: 4px;
  border: 1px solid #cfdfeb;
  border-radius: 6px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 4px 14px rgb(31 58 58 / 8%);
}

.graphify-graph-view-tools button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: #567187;
  cursor: pointer;
}

.graphify-graph-view-tools button:hover {
  border-color: #bed9ed;
  background: #eaf4fc;
  color: #2f80c5;
}

.graphify-graph-view-tools button.active {
  border-color: #b8d8ed;
  background: #e8f3fb;
  color: #2f80c5;
}

.graphify-graph-view-actions {
  position: absolute;
  z-index: 6;
  left: 12px;
  bottom: 12px;
  display: flex;
  align-items: center;
  padding: 6px 8px;
  border: 1px solid #cfdfeb;
  border-radius: 6px;
  background: rgb(255 255 255 / 94%);
}

.graphify-graph-view-actions label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #2c79b5;
}

.graphify-graph-view-actions select {
  border: 0;
  outline: 0;
  background: transparent;
  color: #526d82;
  font-size: 11px;
}

.graphify-graph-view-actions span {
  color: #7891a6;
  font-size: 11px;
}

.graphify-graph-view-empty {
  flex: 1;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 14px;
  color: #6a8091;
  text-align: center;
}

.graphify-graph-view-empty svg {
  color: #3c91d3;
  font-size: 40px;
}

.graphify-graph-view-empty p {
  margin: 0;
  font-size: 13px;
}
</style>
