<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import GraphifyEchartsGraph from './GraphifyEchartsGraph.vue'
import { buildGraphView } from './graphViewAdapter'
import type { GraphView } from './graphView'
import type { GraphifyEvidenceEnvelope } from './types'

/**
 * 数据管理查看（大屏态）。由对应回答的数据管理按钮打开，展示该轮证据子图。
 */
const props = defineProps<{
  open: boolean
  evidence?: GraphifyEvidenceEnvelope
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const graphRef = ref<InstanceType<typeof GraphifyEchartsGraph> | null>(null)
const viewRef = ref<HTMLElement | null>(null)
const previousActiveElement = ref<HTMLElement | null>(null)
const previousBodyOverflow = ref('')
const graphView = computed<GraphView | undefined>(() => props.evidence
  ? buildGraphView(props.evidence, { viewMode: 'full', showFields: false })
  : undefined)

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) {
      document.body.style.overflow = previousBodyOverflow.value
      previousActiveElement.value?.focus()
      return
    }
    previousActiveElement.value = document.activeElement instanceof HTMLElement ? document.activeElement : null
    previousBodyOverflow.value = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    graphRef.value?.fit()
    viewRef.value?.focus()
  },
)

onBeforeUnmount(() => {
  document.body.style.overflow = previousBodyOverflow.value
})

</script>

<template>
  <Teleport to="body">
    <div v-if="open" ref="viewRef" class="graphify-graph-view" role="dialog" aria-modal="true" aria-label="数据管理"
      tabindex="-1" @keydown.esc="emit('close')" @click.self="emit('close')">
      <button type="button" class="graphify-graph-close" aria-label="关闭数据管理" title="关闭数据管理"
        @click="emit('close')">
        <CloseOutlined />
      </button>
      <GraphifyEchartsGraph v-if="graphView" ref="graphRef" :graph-view="graphView" fullscreen />
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.graphify-graph-view {
  position: fixed;
  z-index: 1000;
  inset: 0;
}

.graphify-graph-close {
  position: absolute;
  z-index: 2;
  top: 18px;
  right: 18px;
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid rgb(148 163 184 / 45%);
  border-radius: 50%;
  background: rgb(255 255 255 / 92%);
  color: #334155;
  cursor: pointer;
}

.graphify-graph-close:hover { background: #f1f5f9; }
</style>
