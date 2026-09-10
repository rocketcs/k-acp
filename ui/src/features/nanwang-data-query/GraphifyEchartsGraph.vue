<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use, type ECElementEvent } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getEchartsGraphPresentation, toEchartsGraphData } from './echartsGraphAdapter.ts'
import type { GraphView } from './graphView.ts'

use([CanvasRenderer, GraphChart, TooltipComponent])

const props = defineProps<{
  graphView: GraphView
  fullscreen: boolean
}>()

const emit = defineEmits<{
  select: [nodeId: string]
}>()

const chartRef = ref<InstanceType<typeof VChart> | null>(null)
const fixedPositions = ref(new Map<string, { x: number; y: number }>())
const pressedNode = ref<{ id: string; x: number; y: number } | null>(null)
const draggedNodeId = ref<string | null>(null)
const graphData = computed(() => {
  const base = toEchartsGraphData(props.graphView)
  return {
    ...base,
    data: base.data.map((node) => {
      const position = fixedPositions.value.get(node.id)
      return position ? { ...node, ...position, fixed: true } : node
    }),
  }
})
const presentation = computed(() => getEchartsGraphPresentation(graphData.value.data.length, graphData.value.links.length))

function escapeHtml(value: unknown): string {
  return String(value ?? '').replace(/[&<>'"]/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
  })[character]!)
}

function tooltipFormatter(params: { data?: { label?: string; name?: string } }): string {
  return escapeHtml(params.data?.label ?? params.data?.name ?? '')
}

const option = computed(() => ({
  // 南网驾驶舱浅蓝底（与 dm8-graph-demo-cn.html 观感一致）
  backgroundColor: '#f0f6fe',
  tooltip: {
    formatter: tooltipFormatter,
  },
  color: graphData.value.colors,
  series: [{
    type: 'graph' as const,
    layout: 'force' as const,
    data: graphData.value.data,
    links: graphData.value.links,
    categories: graphData.value.categories,
    roam: true,
    draggable: true,
    force: {
      repulsion: presentation.value.repulsion,
      edgeLength: presentation.value.edgeLength,
      gravity: 0.09,
      friction: 0.18,
    },
    label: {
      show: true,
      position: 'right' as const,
      formatter: (params: { data?: { label?: string } }) => params.data?.label ?? '',
      fontSize: 11,
      color: '#1d4477',
    },
    edgeLabel: {
      show: presentation.value.showEdgeLabels,
      formatter: (params: { data?: { label?: string } }) => params.data?.label ?? '',
      fontSize: 9,
      color: '#92400e',
    },
    // 边随源节点着色 + 箭头 + 微弧，驾驶舱关系图观感
    lineStyle: { color: 'source' as const, curveness: 0.12, width: 0.8, opacity: 0.55 },
    edgeSymbol: ['none', 'arrow'],
    edgeSymbolSize: 7,
    emphasis: { focus: 'adjacency' as const, lineStyle: { width: 1.5, opacity: 1 } },
  }],
}))

function resize() {
  chartRef.value?.resize()
}

function fit() {
  nextTick(resize)
}

type GraphChartInternals = {
  chart?: {
    value?: {
      getModel?: () => {
        getSeriesByIndex?: (index: number) => {
          getData?: () => { getItemLayout?: (dataIndex: number) => unknown }
        }
      }
    }
  }
}

function readNodePosition(dataIndex: number | undefined, data: { x?: number; y?: number } | null) {
  if (dataIndex !== undefined) {
    const internals = chartRef.value as unknown as GraphChartInternals | null
    const layout = internals?.chart?.value?.getModel?.()?.getSeriesByIndex?.(0)?.getData?.()?.getItemLayout?.(dataIndex)
    if (Array.isArray(layout) && Number.isFinite(layout[0]) && Number.isFinite(layout[1])) {
      return { x: Number(layout[0]), y: Number(layout[1]) }
    }
  }
  if (data && Number.isFinite(data.x) && Number.isFinite(data.y)) {
    return { x: Number(data.x), y: Number(data.y) }
  }
  return undefined
}

function pointerPosition(params: ECElementEvent) {
  const event = params.event as { offsetX?: number; offsetY?: number; zrX?: number; zrY?: number } | undefined
  const x = event?.offsetX ?? event?.zrX
  const y = event?.offsetY ?? event?.zrY
  return Number.isFinite(x) && Number.isFinite(y) ? { x: Number(x), y: Number(y) } : undefined
}

function onChartMousedown(params: ECElementEvent) {
  if (params.dataType !== 'node') return
  const data = params.data as { id?: string } | null
  const point = pointerPosition(params)
  if (!data?.id || !point) return
  pressedNode.value = { id: String(data.id), ...point }
  draggedNodeId.value = null
}

function onChartMousemove(params: ECElementEvent) {
  const pressed = pressedNode.value
  if (!pressed || params.dataType !== 'node') return
  const data = params.data as { id?: string } | null
  const point = pointerPosition(params)
  if (!data?.id || String(data.id) !== pressed.id || !point) return
  if (Math.hypot(point.x - pressed.x, point.y - pressed.y) > 3) draggedNodeId.value = pressed.id
}

/**
 * ECharts unfixes a force node after its internal dragend event. Persist the
 * final layout as an explicit fixed node so a later reactive option update,
 * resize, or fullscreen toggle cannot scatter it again.
 */
function onChartMouseup(params: ECElementEvent) {
  const draggedId = draggedNodeId.value
  pressedNode.value = null
  draggedNodeId.value = null
  if (params.dataType !== 'node') return
  const data = params.data as { id?: string; x?: number; y?: number } | null
  if (!data?.id || draggedId !== String(data.id)) return
  const position = readNodePosition(params.dataIndex, data)
  if (!position) return
  fixedPositions.value = new Map(fixedPositions.value).set(String(data.id), position)
}

function onChartClick(params: ECElementEvent) {
  const data = params.data as { id?: string } | null
  if (params.dataType !== 'node' || !data?.id) return
  emit('select', String(data.id))
}

watch(() => props.fullscreen, async () => {
  await nextTick()
  resize()
})

watch(() => props.graphView.id, () => {
  fixedPositions.value = new Map()
  pressedNode.value = null
  draggedNodeId.value = null
})

defineExpose({ fit })
</script>

<template>
  <div class="graphify-echarts-graph" :class="{ 'is-fullscreen': fullscreen }" aria-label="业务逻辑关系图谱">
    <VChart ref="chartRef" class="graphify-echarts-graph-canvas" :option="option" autoresize
      @click="onChartClick" @mousedown="onChartMousedown" @mousemove="onChartMousemove" @mouseup="onChartMouseup" />
  </div>
</template>

<style scoped>
.graphify-echarts-graph { width: 100%; height: 100%; }
.graphify-echarts-graph.is-fullscreen { min-height: 0; }
.graphify-echarts-graph-canvas { width: 100%; height: 100%; }
</style>
