<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import {
  AimOutlined, CloseOutlined, ReloadOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons-vue'
import GraphifyEvidenceGraph from './GraphifyEvidenceGraph.vue'
import type { GraphifyEvidenceEnvelope } from './types'

/**
 * 知识图谱查看（大屏态）。由对应回答的图谱按钮打开，展示该轮证据子图。
 */
const props = defineProps<{
  open: boolean
  evidence?: GraphifyEvidenceEnvelope
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return
    await nextTick()
    graphRef.value?.fit()
  },
)

</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="graphify-graph-view" role="dialog" aria-modal="true" aria-label="知识图谱查看"
      @click.self="emit('close')">
      <div class="graphify-graph-view-panel">
        <div class="graphify-graph-view-body">
          <div v-if="evidence" class="graphify-graph-view-canvas">
            <GraphifyEvidenceGraph ref="graphRef" :evidence="evidence" relation-filter="all"
              view-mode="full" :show-summary="false" fullscreen />
              <div class="graphify-graph-view-tools" aria-label="图谱工具栏">
                <button type="button" title="关闭" aria-label="关闭" @click="emit('close')"><CloseOutlined /></button>
                <button type="button" title="放大" @click="graphRef?.zoomIn()"><ZoomInOutlined /></button>
                <button type="button" title="缩小" @click="graphRef?.zoomOut()"><ZoomOutOutlined /></button>
                <button type="button" title="适应画布" @click="graphRef?.fit()"><AimOutlined /></button>
                <button type="button" title="重新布局" @click="graphRef?.relayout()"><ReloadOutlined /></button>
              </div>
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

.graphify-graph-view-body {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

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

</style>
