<script setup lang="ts">
import cytoscape, { type Core, type ElementDefinition } from 'cytoscape'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types'

type RelationFilter = 'all' | 'query' | 'semantic' | 'provenance' | 'business'

const props = defineProps<{
  evidence: GraphifyEvidenceEnvelope
  relationFilter: RelationFilter
  fullscreen: boolean
}>()
const emit = defineEmits<{
  select: [nodeId: string]
  expand: [expanded: boolean]
}>()

const canvas = ref<HTMLElement>()
let cy: Core | undefined
let doubleTap: { id: string; at: number } = { id: '', at: 0 }
const queryResultsExpanded = ref(false)
const selectedId = ref<string | null>(null)

const queryEdges = computed(() => props.evidence.evidence.edges.filter((edge) => edge.kind === 'query'))
const queryResultIds = computed(() => new Set(queryEdges.value.map((edge) => edge.target)))
const selectedProductId = computed(() => {
  const queriedProduct = queryEdges.value.find((edge) => props.evidence.evidence.nodes.find((node) => node.id === edge.target)?.kind === 'product')
  return queriedProduct?.target ?? props.evidence.evidence.nodes.find((node) => node.kind === 'product')?.id
})
const visibleNodeIds = computed(() => queryResultsExpanded.value
  ? new Set(props.evidence.evidence.nodes.map((node) => node.id))
  : focusedBranchNodeIds())
const visibleNodeCount = computed(() => visibleNodeIds.value.size)
const visibleQueryResultCount = computed(() => queryResultsExpanded.value ? queryEdges.value.length : Math.min(queryEdges.value.length, 1))

function category(node: GraphifyEvidenceNode): 'mdl' | 'query' | 'entity' | 'provenance' {
  if (node.kind === 'model') return 'mdl'
  if (node.kind === 'record' || node.kind === 'source' || node.kind === 'catalog_record' || node.kind === 'source_file' || node.kind === 'import_batch') return 'provenance'
  if (node.kind === 'product') return 'query'
  return 'entity'
}

function displayLabel(node: GraphifyEvidenceNode): string {
  const heading = ({ model: '医疗目录', product: '查询目录项', registration: '注册备案', organization: '耗材企业', base: '基础耗材', concept: '映射概念', catalog_record: '原始目录记录', source_file: '来源工作簿', import_batch: '导入批次', record: '查询记录', entity: '业务实体', source: '来源记录' } as Record<string, string>)[node.kind] ?? '业务实体'
  const detail = node.label.length > 17 ? `${node.label.slice(0, 16)}…` : node.label
  return `${heading}\n${detail}`
}

function relationLabel(label: string, kind: string): string {
  return ({ query: '查询返回', semantic: '语义关联', provenance: '来源追溯', business: '业务关联' } as Record<string, string>)[label]
    ?? ({ query: '查询返回', semantic: '语义关联', provenance: '来源追溯', business: '业务关联' } as Record<string, string>)[kind]
    ?? label
}

function focusedBranchNodeIds(): Set<string> {
  const nodes = props.evidence.evidence.nodes
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const productId = selectedProductId.value
  const visible = new Set(nodes.filter((node) => node.kind === 'model').map((node) => node.id))
  if (!productId) return visible
  visible.add(productId)

  const outgoing = (source: string, kind?: 'business' | 'provenance') => props.evidence.evidence.edges
    .filter((edge) => edge.source === source && (!kind || edge.kind === kind))
    .map((edge) => edge.target)

  // Keep the default view to one understandable evidence chain. The rest of
  // the real result graph remains available through a double-click expansion.
  const businessTargets = outgoing(productId, 'business')
  const enterprise = businessTargets.find((id) => nodeById.get(id)?.kind === 'organization')
  const primaryFact = businessTargets.find((id) => ['registration', 'base', 'concept'].includes(nodeById.get(id)?.kind ?? ''))
  ;[enterprise, primaryFact].filter((id): id is string => Boolean(id)).forEach((id) => visible.add(id))

  const catalogRecord = outgoing(productId, 'provenance').find((id) => nodeById.get(id)?.kind === 'catalog_record')
  if (!catalogRecord) return visible
  visible.add(catalogRecord)
  let current = catalogRecord
  for (let depth = 0; depth < 2; depth += 1) {
    const next = outgoing(current, 'provenance')[0]
    if (!next || visible.has(next)) break
    visible.add(next)
    current = next
  }
  return visible
}

function positions(nodeIds: Set<string>) {
  const nodes = props.evidence.evidence.nodes.filter((node) => nodeIds.has(node.id))
  if (!queryResultsExpanded.value) {
    const place = new Map<string, { x: number; y: number }>()
    const model = nodes.find((node) => node.kind === 'model')
    const product = selectedProductId.value ? nodes.find((node) => node.id === selectedProductId.value) : undefined
    if (model) place.set(model.id, { x: 58, y: 208 })
    if (product) place.set(product.id, { x: 236, y: 208 })

    const business = product ? props.evidence.evidence.edges
      .filter((edge) => edge.source === product.id && edge.kind === 'business' && nodeIds.has(edge.target))
      .map((edge) => nodes.find((node) => node.id === edge.target))
      .filter((node): node is GraphifyEvidenceNode => Boolean(node)) : []
    business.forEach((node, index) => place.set(node.id, { x: 414, y: 132 + index * 112 }))

    const catalog = product ? props.evidence.evidence.edges
      .filter((edge) => edge.source === product.id && edge.kind === 'provenance' && nodeIds.has(edge.target))
      .map((edge) => nodes.find((node) => node.id === edge.target))
      .find((node) => node?.kind === 'catalog_record') : undefined
    if (catalog) {
      place.set(catalog.id, { x: 234, y: 392 })
      let current = catalog.id
      const lineagePositions = [{ x: 58, y: 512 }, { x: 236, y: 512 }]
      lineagePositions.forEach((position) => {
        const next = props.evidence.evidence.edges.find((edge) => edge.source === current && edge.kind === 'provenance' && nodeIds.has(edge.target))?.target
        if (!next) return
        place.set(next, position)
        current = next
      })
    }
    nodes.filter((node) => !place.has(node.id)).forEach((node, index) => place.set(node.id, { x: 414, y: 430 + index * 64 }))
    return place
  }
  const resultNodes = nodes.filter((node) => queryResultIds.value.has(node.id))
  const others = nodes.filter((node) => !queryResultIds.value.has(node.id))
  const byCategory = (value: ReturnType<typeof category>) => others.filter((node) => category(node) === value)
  const place = new Map<string, { x: number; y: number }>()
  const model = byCategory('mdl')[0]
  if (model) place.set(model.id, { x: queryResultsExpanded.value ? 172 : 56, y: queryResultsExpanded.value ? 158 : 160 })
  const primary = byCategory('query')[0] ?? byCategory('entity')[0]
  if (primary) place.set(primary.id, { x: queryResultsExpanded.value ? 172 : 198, y: queryResultsExpanded.value ? 250 : 160 })

  byCategory('entity').filter((node) => node.id !== primary?.id).forEach((node, index) => {
    place.set(node.id, { x: 290, y: (queryResultsExpanded.value ? 212 : 66) + index * 74 })
  })
  byCategory('provenance').forEach((node, index) => {
    const column = index % 2
    const row = Math.floor(index / 2)
    place.set(node.id, { x: 56 + column * 142, y: (queryResultsExpanded.value ? 322 : 246) + row * 60 })
  })
  resultNodes.forEach((node, index) => {
    if (index === 0 && !queryResultsExpanded.value) return
    const offset = queryResultsExpanded.value ? index : 0
    place.set(node.id, { x: 40 + (offset % 4) * 92, y: 50 + Math.floor(offset / 4) * 52 })
  })
  nodes.filter((node) => !place.has(node.id)).forEach((node, index) => place.set(node.id, { x: 290, y: 98 + index * 52 }))
  return place
}

function elements(): ElementDefinition[] {
  const nodeIds = visibleNodeIds.value
  const coordinates = positions(nodeIds)
  const nodes = props.evidence.evidence.nodes.filter((node) => nodeIds.has(node.id)).map((node) => ({
    data: { id: node.id, label: displayLabel(node), category: category(node) },
    position: coordinates.get(node.id),
  }))
  const edges = props.evidence.evidence.edges
    .filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
    .map((edge) => ({ data: { id: edge.id, source: edge.source, target: edge.target, label: relationLabel(edge.label, edge.kind), category: edge.kind } }))
  return [...nodes, ...edges]
}

function applyFilter() {
  if (!cy) return
  cy.edges().forEach((edge) => { edge.toggleClass('filtered', props.relationFilter !== 'all' && edge.data('category') !== props.relationFilter) })
}

function layout(animate = true) {
  if (!cy) return
  if (queryResultsExpanded.value) {
    cy.layout({
      name: 'breadthfirst',
      directed: true,
      roots: cy.nodes('[category = "mdl"]').map((node) => node.id()),
      spacingFactor: 1.22,
      padding: props.fullscreen ? 32 : 18,
      animate,
      animationDuration: 300,
    }).run()
    return
  }
  const coordinates = positions(visibleNodeIds.value)
  const positionMap = Object.fromEntries(coordinates)
  cy.layout({ name: 'preset', positions: positionMap, fit: true, padding: props.fullscreen ? 32 : 16, animate, animationDuration: 300 }).run()
}

function render(animate = false) {
  if (!cy) return
  cy.elements().remove()
  cy.add(elements())
  applyFilter()
  layout(animate)
  const selected = selectedId.value ? cy.getElementById(selectedId.value) : undefined
  if (selected && selected.length) selected.select()
  else {
    const first = cy.nodes()[0]
    if (first) select(first.id())
  }
}

function select(id: string) {
  selectedId.value = id
  cy?.nodes().unselect()
  cy?.getElementById(id).select()
  emit('select', id)
}

function toggleQueryResults() {
  queryResultsExpanded.value = !queryResultsExpanded.value
  emit('expand', queryResultsExpanded.value)
  render(true)
}

function zoomIn() { cy?.zoom({ level: Math.min(2.4, cy.zoom() + 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function zoomOut() { cy?.zoom({ level: Math.max(0.45, cy.zoom() - 0.16), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function fit() { cy?.fit(cy.elements(), props.fullscreen ? 32 : 16) }
function relayout() { layout(true) }

function initialize() {
  if (!canvas.value) return
  cy = cytoscape({
    container: canvas.value,
    elements: elements(),
    minZoom: 0.45,
    maxZoom: 2.4,
    wheelSensitivity: 0.16,
    style: [
      { selector: 'node', style: { shape: 'round-rectangle', width: '114px', height: '43px', 'background-color': '#ffffff', 'border-width': '1.3px', 'border-color': '#9cbad2', label: 'data(label)', color: '#21445f', 'font-family': 'PingFang SC, Microsoft YaHei, sans-serif', 'font-size': '9px', 'font-weight': 600, 'text-wrap': 'wrap', 'text-max-width': '96px', 'text-valign': 'center', 'text-halign': 'center', 'overlay-padding': 7 } },
      { selector: 'node[category = "mdl"]', style: { 'border-color': '#4d8fc7', 'background-color': '#f0f7fd', color: '#245f8f' } },
      { selector: 'node[category = "query"]', style: { 'border-color': '#c98b37', 'background-color': '#fff9ef', color: '#925811' } },
      { selector: 'node[category = "entity"]', style: { 'border-color': '#48a0bd', 'background-color': '#f1f9fc', color: '#176c88' } },
      { selector: 'node[category = "provenance"]', style: { 'border-color': '#5799bb', 'background-color': '#f1f7fb', color: '#28617e' } },
      { selector: 'node:selected', style: { 'border-width': '3px', 'border-color': '#2f80c5', 'background-color': '#e6f2fb', 'underlay-color': '#5a9fd0', 'underlay-opacity': 0.24, 'underlay-padding': '6px' } },
      { selector: 'edge', style: { width: '1.25px', 'curve-style': 'bezier', 'line-color': '#89a9bf', 'target-arrow-color': '#89a9bf', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.72, label: 'data(label)', 'font-size': '8px', 'font-family': 'PingFang SC, Microsoft YaHei, sans-serif', color: '#587286', 'text-background-color': '#f3f8fc', 'text-background-opacity': 0.92, 'text-background-padding': '2px', 'text-rotation': 'autorotate' } },
      { selector: 'edge[category = "business"]', style: { 'line-color': '#4c9db8', 'target-arrow-color': '#4c9db8' } },
      { selector: 'edge[category = "query"]', style: { 'line-color': '#4f86b0', 'target-arrow-color': '#4f86b0', 'line-style': 'dashed' } },
      { selector: '.filtered', style: { opacity: 0.1 } },
    ],
    layout: { name: 'preset' },
  })
  cy.on('tap', 'node', (event) => {
    const id = event.target.id()
    select(id)
    const now = Date.now()
    if (doubleTap.id === id && now - doubleTap.at < 340) toggleQueryResults()
    doubleTap = { id, at: now }
  })
  cy.on('tap', (event) => {
    if (event.target === cy) {
      cy?.nodes().unselect()
      doubleTap = { id: '', at: 0 }
    }
  })
  layout(false)
  const first = cy.nodes()[0]
  if (first) select(first.id())
}

watch(() => props.evidence.trace_id, () => { queryResultsExpanded.value = false; selectedId.value = null; nextTick(() => render(false)) })
watch(() => props.relationFilter, applyFilter)
watch(() => props.fullscreen, async () => { await nextTick(); cy?.resize(); layout(false) })

onMounted(() => initialize())
onBeforeUnmount(() => { cy?.destroy(); cy = undefined })
defineExpose({ zoomIn, zoomOut, fit, relayout, toggleQueryResults, queryResultsExpanded })
</script>

<template>
  <div class="prototype-graph-canvas" :class="{ 'is-fullscreen': fullscreen }" aria-label="当前问题的语义图谱">
    <div ref="canvas" class="cy-canvas" />
    <div class="graph-summary">{{ visibleNodeCount }} 个节点 · {{ evidence.evidence.edges.length }} 条 Neo4j 关系 · 双击节点{{ queryResultsExpanded ? '收起' : '展开' }}</div>
  </div>
</template>

<style scoped>
.prototype-graph-canvas { position:relative; height:392px; overflow:hidden; border:1px solid #c5dbea; border-radius:4px; background-color:#f2f8fc; background-image:linear-gradient(to right,rgb(75 137 181 / 12%) 1px,transparent 1px),linear-gradient(to bottom,rgb(75 137 181 / 12%) 1px,transparent 1px); background-size:18px 18px; }
.prototype-graph-canvas.is-fullscreen { height:100%; min-height:0; }
.cy-canvas { position:absolute; inset:0; z-index:1; }
.graph-summary { position:absolute; z-index:3; right:10px; bottom:10px; padding:4px 7px; border:1px solid #cbdfea; border-radius:3px; background:rgb(255 255 255 / 94%); color:#286fa8; font-size:11px; font-weight:650; pointer-events:none; }
</style>
