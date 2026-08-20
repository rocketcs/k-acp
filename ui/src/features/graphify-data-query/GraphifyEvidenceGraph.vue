<script setup lang="ts">
import cytoscape, { type Core } from 'cytoscape'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { evidenceGraphCounts, evidenceGraphModel } from './evidenceGraphModel'
import { expandSelection } from './expandSelection'
import type { GraphifyEvidenceEnvelope } from './types'

type RelationFilter = 'all' | 'business' | 'provenance' | 'semantic'

const props = withDefaults(defineProps<{
  evidence: GraphifyEvidenceEnvelope
  relationFilter: RelationFilter
  fullscreen: boolean
  viewMode?: 'focused' | 'full'
  showFields?: boolean
}>(), { showFields: false, viewMode: 'focused' })
const emit = defineEmits<{
  select: [nodeId: string]
}>()

const canvas = ref<HTMLElement>()
const tooltipEl = ref<HTMLElement>()
let cy: Core | undefined
const selectedId = ref<string | null>(null)
const expandedIds = ref<ReadonlySet<string>>(new Set())

/** 当前图谱渲染选项：focused 走渐进展开，showFields 展示全视图。 */
const graphOpts = computed<{
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
}>(() => ({
  viewMode: props.showFields ? 'full' : 'focused',
  showFields: props.showFields,
  visibleIds: props.showFields
    ? undefined
    : expandSelection(
        props.evidence.evidence.nodes,
        props.evidence.evidence.edges,
        expandedIds.value,
      ),
}))

// 汇总计数与实际渲染保持一致：evidenceGraphCounts 会剔除无关联关系的孤立节点。
const visibleNodeCount = computed(() =>
  evidenceGraphCounts(props.evidence, graphOpts.value).nodeCount)
const visibleEdgeCount = computed(() =>
  evidenceGraphCounts(props.evidence, graphOpts.value).edgeCount)
const totalNodeCount = computed(() =>
  evidenceGraphCounts(props.evidence, graphOpts.value).totalCount)

function buildElements() {
  return evidenceGraphModel(props.evidence, {
    ...graphOpts.value,
    semantics: {
      labels: props.evidence.semantic_context.domain_labels,
      headings: props.evidence.semantic_context.domain_headings,
    },
  })
}

function applyFilter() {
  if (!cy) return
  cy.edges().forEach((edge) => {
    const hide = props.relationFilter !== 'all' && edge.data('kind') !== props.relationFilter
    edge.toggleClass('filtered-out', hide)
  })
}

function render(animate = false) {
  if (!cy) return
  cy.elements().remove()
  cy.add(buildElements())
  applyFilter()
  cy.layout({ name: 'preset', fit: true, padding: props.fullscreen ? 32 : 16, animate, animationDuration: 300 }).run()
  const selected = selectedId.value ? cy.getElementById(selectedId.value) : undefined
  if (selected && selected.length) selected.select()
  else {
    const first = cy.nodes().first()
    if (first.length) select(first.id(), false)
  }
}

/**
 * 选中节点。`expand=true`（默认）用于用户点击交互：把节点并入展开集，
 * 显露其下一级邻居；由渲染内部自动选中时传 `false`，避免重启递归。
 */
function select(id: string, expand = true) {
  selectedId.value = id
  const needExpand = id && expand
    && !expandSelection(
      props.evidence.evidence.nodes,
      props.evidence.evidence.edges,
      expandedIds.value,
    ).has(id)
  if (needExpand) {
    expandedIds.value = new Set([...expandedIds.value, id])
    nextTick(() => render(true))
  } else {
    cy?.nodes().unselect()
    cy?.getElementById(id).select()
  }
  emit('select', id)
}

function showTooltip(event: cytoscape.EventObject) {
  const node = event.target as cytoscape.NodeSingular
  if (!tooltipEl.value) return
  tooltipEl.value.textContent = `${node.data('heading')}：${node.data('fullLabel')}`
  tooltipEl.value.style.display = 'block'
  const box = (event.cy.container() as HTMLElement).getBoundingClientRect()
  const width = tooltipEl.value.offsetWidth || 220
  const height = tooltipEl.value.offsetHeight || 32
  const left = Math.min(Math.max(event.renderedPosition.x - 80, 4), box.width - width - 4)
  const top = Math.min(Math.max(event.renderedPosition.y - 42, 4), box.height - height - 4)
  tooltipEl.value.style.left = `${left}px`
  tooltipEl.value.style.top = `${top}px`
}
function hideTooltip() {
  if (!tooltipEl.value) return
  tooltipEl.value.style.display = 'none'
}

function zoomIn() { cy?.zoom({ level: Math.min(2.4, cy.zoom() + 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function zoomOut() { cy?.zoom({ level: Math.max(0.3, cy.zoom() - 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function fit() { cy?.fit(cy.elements(), props.fullscreen ? 32 : 16) }
function relayout() { render(true) }

/** 汇总条点击：一次展示整张证据图（全部非查询过程节点并入展开集）。 */
function expandAll() {
  const roots = props.evidence.evidence.nodes
    .filter((node) => node.kind !== 'record' && node.kind !== 'source')
    .map((node) => node.id)
  expandedIds.value = new Set([...expandedIds.value, ...roots])
  nextTick(() => render(true))
}

function initialize() {
  if (!canvas.value) return
  const style = [
    {
      selector: 'node',
        style: {
          shape: 'round-rectangle',
          width: 118,
          height: 46,
          'background-color': '#eef7fb',
          'border-width': 2,
          'border-color': '#2f8fb0',
          label: 'data(label)',
          color: '#146a85',
          'font-family': 'PingFang SC, Microsoft YaHei, sans-serif',
          'font-size': 12,
          'font-weight': 650,
          'text-wrap': 'wrap',
          'text-max-width': '104px',
          'text-valign': 'center',
          'text-halign': 'center',
          'overlay-padding': 7,
          'underlay-color': '#9db9c9',
          'underlay-opacity': 0.22,
          'underlay-padding': 4,
          'underlay-corner-radius': 8,
        },
      },
      {
        selector: 'node[?coreNode]',
        style: { 'border-width': 3, 'border-color': '#c98b37', 'background-color': '#fff4e4', color: '#8a5410', 'font-size': 14, 'font-weight': 750 },
      },
      {
        selector: 'node[category = "entity"]',
        style: { 'border-style': 'dashed', 'border-color': '#b9cbd6', 'background-color': '#f7fafc', color: '#5a7184', 'font-size': 11 },
      },
      {
        selector: 'node[category = "model"]',
        style: { shape: 'ellipse', 'border-color': '#2f6fb0', 'background-color': '#e8f1fb', color: '#1e4f7d' },
      },
      {
        selector: 'node[?diamond]',
        style: { shape: 'diamond', 'border-color': '#5d8fb5', 'background-color': '#f0f6fb', color: '#2b5d80' },
      },
      { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#2f80c5', 'background-color': '#e6f2fb', 'underlay-color': '#5a9fd0', 'underlay-opacity': 0.24, 'underlay-padding': 6 } },
      { selector: 'node[?coreNode]:selected', style: { 'border-width': 3, 'border-color': '#c98b37', 'background-color': '#fff4e4', color: '#8a5410', 'underlay-color': '#5a9fd0', 'underlay-opacity': 0.24, 'underlay-padding': 6 } },
      { selector: 'node.hover', style: { 'border-width': 3, 'border-color': '#e8a23a' } },
      { selector: 'node.dimmed', style: { opacity: 0.15 } },
      { selector: 'edge.dimmed', style: { opacity: 0.15 } },
      { selector: 'edge', style: { width: 2, 'curve-style': 'bezier', 'line-color': '#8faec6', 'target-arrow-color': '#8faec6', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.9, label: 'data(label)', 'font-size': 10, 'font-family': 'PingFang SC, Microsoft YaHei, sans-serif', color: '#4d6675', 'text-background-color': '#f3f8fc', 'text-background-opacity': 0.95, 'text-background-padding': '3px', 'text-rotation': 'autorotate' } },
      { selector: 'edge[kind = "business"]', style: { 'line-color': '#4c9db8', 'target-arrow-color': '#4c9db8' } },
      { selector: 'edge[kind = "provenance"]', style: { 'line-color': '#7d9cbb', 'target-arrow-color': '#7d9cbb', 'line-style': 'dashed' } },
      { selector: 'edge[kind = "semantic"]', style: { 'line-color': '#b7c8d6', 'target-arrow-color': '#b7c8d6', 'line-style': 'dotted' } },
      { selector: '.filtered-out', style: { display: 'none' } },
    ] as unknown as cytoscape.StylesheetStyle[]
  cy = cytoscape({
    container: canvas.value,
    elements: buildElements(),
    minZoom: 0.3,
    maxZoom: 2.4,
    wheelSensitivity: 0.16,
    style,
    layout: { name: 'preset' },
  })
  cy.on('mouseover', 'node', (event) => {
    const node = event.target
    const keep = node.closedNeighborhood().union(node)
    cy?.nodes().forEach((n) => { if (!keep.has(n)) n.addClass('dimmed') })
    const keepEdges = node.connectedEdges()
    cy?.edges().forEach((edge) => { if (!keepEdges.has(edge)) edge.addClass('dimmed') })
    node.addClass('hover')
    showTooltip(event)
  })
  cy.on('mouseout', 'node', () => {
    cy?.nodes().removeClass('dimmed hover')
    cy?.edges().removeClass('dimmed')
    hideTooltip()
  })
  cy.on('tap', 'node', (event) => select(event.target.id()))
  cy.on('dbltap', 'node', relayout)
  cy.on('tap', (event) => {
    if (event.target === cy) {
      cy?.nodes().unselect()
      selectedId.value = null
      emit('select', '')
    }
  })
  render(false)
  const first = cy.nodes().first()
  if (first.length) select(first.id(), false)
}

watch(() => props.evidence.trace_id, () => {
  selectedId.value = null
  expandedIds.value = new Set()
  nextTick(() => render(false))
})
watch(() => props.relationFilter, applyFilter)
watch(() => props.fullscreen, async () => { await nextTick(); cy?.resize(); render(false) })

onMounted(() => initialize())
onBeforeUnmount(() => { cy?.destroy(); cy = undefined })
defineExpose({ zoomIn, zoomOut, fit, relayout })
</script>

<template>
  <div class="evidence-graph" :class="{ 'is-fullscreen': fullscreen }" aria-label="业务逻辑关系图谱">
    <div ref="canvas" class="cy-canvas" />
    <div ref="tooltipEl" class="cy-tooltip" role="tooltip" />
    <div class="graph-summary" :class="{ expandable: totalNodeCount > visibleNodeCount }" role="button" tabindex="0" aria-label="图谱节点统计" @click="expandAll" @keydown.enter="expandAll">
      {{ visibleNodeCount }} 个节点 · {{ visibleEdgeCount }} 条关系
      <template v-if="totalNodeCount > visibleNodeCount">
        · 另有 {{ totalNodeCount - visibleNodeCount }} 个节点，点击展开
      </template>
    </div>
  </div>
</template>

<style scoped>
.evidence-graph { position: relative; height: 392px; overflow: hidden; border: 1px solid #c5dbea; border-radius: 4px; background-color: #f2f8fc; background-image: linear-gradient(to right, rgb(75 137 181 / 10%) 1px, transparent 1px), linear-gradient(to bottom, rgb(75 137 181 / 10%) 1px, transparent 1px); background-size: 18px 18px; }
.evidence-graph.is-fullscreen { height: 100%; min-height: 0; }
.cy-canvas { position: absolute; inset: 0; z-index: 1; }
.cy-tooltip { position: absolute; z-index: 8; display: none; max-width: 220px; padding: 5px 9px; border: 1px solid #b8d0dd; border-radius: 4px; background: rgb(255 255 255 / 96%); box-shadow: 0 4px 14px rgb(31 58 58 / 14%); color: #21445f; font-size: 11px; line-height: 1.45; pointer-events: none; }
.graph-summary { position: absolute; z-index: 3; right: 10px; bottom: 10px; padding: 4px 7px; border: 1px solid #cbdfea; border-radius: 3px; background: rgb(255 255 255 / 94%); color: #286fa8; font-size: 11px; font-weight: 650; }.graph-summary.expandable { cursor: pointer; }.graph-summary.expandable:hover { border-color: #7fb8dc; background: #eef7fc; }
</style>
