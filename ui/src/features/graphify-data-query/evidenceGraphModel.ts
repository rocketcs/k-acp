import { dagrePositions } from './dagreLayout.ts'
import { nodeVisual, type DomainSemantics } from './evidenceStyles.ts'
import { graphEdgeLabel, graphNodeLabel } from './evidencePresentation.ts'
import type { GraphifyEvidenceEnvelope, GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type GraphModelOptions = {
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
  /** 从查询命中根节点向外展开的最大关系层级；未指定时保留完整选择行为。 */
  maxDepth?: number
  /** 仅限制渲染投影的节点数；不影响完整 evidence 数据或后端查询结果。 */
  maxNodes?: number
  /** 行业语义（域标签/标题），由 envelope.semantic_context 注入，缺省时用中性词。 */
  semantics?: DomainSemantics
}

export type GraphElementDefinition = {
  data?: Record<string, unknown>
  position?: { x: number; y: number }
}

const QUERY_PROCESS_KINDS = new Set(['record', 'source'])
const DISPLAY_INTERMEDIARY_KINDS = new Set(['model', 'catalog_record', 'source_file', 'import_batch'])
const NODE_TEXT_MAX_WIDTH = 104

/**
 * Cytoscape does not shrink text automatically when a Chinese label has no
 * whitespace to wrap on. Size each node label against the fixed text area so
 * names such as enterprises stay inside the visual node; the tooltip retains
 * the complete text at its normal readable size.
 */
function nodeFontSize(label: string, node: GraphifyEvidenceNode): number {
  const base = node.kind === 'product' ? 14 : node.kind === 'entity' ? 11 : 12
  const longestLine = Math.max(1, ...label.split('\n').map((line) => Array.from(line).length))
  const fitted = NODE_TEXT_MAX_WIDTH / (longestLine * 1.1)
  return Math.round(Math.max(8, Math.min(base, fitted)) * 10) / 10
}

/**
 * Full-view layout: a compact two-tier arrangement that dagre would stack
 * vertically (many same-rank siblings) or spread horizontally (TB). Zones:
 * - left column: model + core product
 * - middle column: key business facts (organization / registration / base)
 * - upper-right grid: mapping concepts (up to 3 columns)
 * - lower-right chain: catalog record -> source file -> import batch
 */
export function fullViewPositions(nodes: GraphifyEvidenceNode[]): Map<string, { x: number; y: number }> {
  const place = new Map<string, { x: number; y: number }>()
  const model = nodes.find((node) => node.kind === 'model')
  const product = nodes.find((node) => node.kind === 'product')
  if (model) place.set(model.id, { x: 60, y: 150 })
  if (product) place.set(product.id, { x: 60, y: 300 })

  const facts = nodes.filter((node) => ['organization', 'registration', 'base'].includes(node.kind))
  facts.forEach((node, index) => place.set(node.id, { x: 230, y: 50 + index * 110 }))

  const concepts = nodes.filter((node) => node.kind === 'concept')
  const cols = Math.min(3, Math.max(1, Math.ceil(concepts.length / 2)))
  concepts.forEach((node, index) => {
    const col = index % cols
    const row = Math.floor(index / cols)
    place.set(node.id, { x: 420 + col * 140, y: 50 + row * 110 })
  })

  const catalogRecord = nodes.find((node) => node.kind === 'catalog_record')
  const sourceFile = nodes.find((node) => node.kind === 'source_file')
  const importBatch = nodes.find((node) => node.kind === 'import_batch')
  if (catalogRecord) place.set(catalogRecord.id, { x: 230, y: 400 })
  if (sourceFile) place.set(sourceFile.id, { x: 420, y: 400 })
  if (importBatch) place.set(importBatch.id, { x: 610, y: 400 })

  // Anything else (e.g. semantic fields) drops into the lower-right column.
  nodes
    .filter((node) => !place.has(node.id))
    .forEach((node, index) => place.set(node.id, { x: 610, y: 80 + index * 110 }))
  return place
}

function nodeVisible(node: GraphifyEvidenceNode, opts: GraphModelOptions): boolean {
  if (QUERY_PROCESS_KINDS.has(node.kind)) return false
  if (node.kind === 'model') return opts.viewMode === 'full'
  if (node.kind === 'entity') return opts.viewMode === 'full' && opts.showFields
  return true
}

export type EvidenceGraphSelection = {
  nodes: GraphifyEvidenceNode[]
  edges: GraphifyEvidenceEdge[]
  /** 图谱内全部可供展示的业务节点数（不含查询过程节点），用于「点击展开」提示。 */
  totalCount: number
}

/**
 * 计算出当前选项下实际渲染的节点与边。
 *
 * 关键规则：孤立节点（与其它节点没有任何一条边——业务/来源/语义关系都不存在）
 * 会被剔除，保证查询出来的图谱只展示有关联关系的节点，不展示孤立节点。
 * 元素构建与汇总计数统一走这里，保证所见即所计。
 */
export function selectGraph(
  envelope: GraphifyEvidenceEnvelope,
  opts: GraphModelOptions,
): EvidenceGraphSelection {
  const allNodes = envelope.evidence.nodes.filter((node) => nodeVisible(node, opts))
  const initialNodes = opts.visibleIds
    ? allNodes.filter((node) => opts.visibleIds!.has(node.id))
    : allNodes
  const initialNodeIds = new Set(initialNodes.map((node) => node.id))
  const modelId = initialNodes.find((node) => node.kind === 'model')?.id

  // The query result defines the graph roots: product nodes are the requested
  // business objects. For non-product legacy envelopes, use model query-edge
  // targets instead.
  const queriedRoots = envelope.evidence.edges
    .filter((edge) => edge.kind === 'query' && (!modelId || edge.source === modelId) && initialNodeIds.has(edge.target))
    .map((edge) => edge.target)
  const productRoots = initialNodes.filter((node) => node.kind === 'product').map((node) => node.id)
  const rootIds = new Set(productRoots.length ? productRoots : queriedRoots)

  // Limit only the rendered projection. The complete evidence envelope stays
  // available for hydration and future expansion; this keeps ECharts readable
  // for broad queries while showing root + two relationship levels by default.
  let scopedNodeIds = initialNodeIds
  let depths = new Map<string, number>()
  if (opts.maxDepth !== undefined && rootIds.size) {
    const maxDepth = Math.max(0, Math.floor(opts.maxDepth))
    const adjacency = new Map<string, string[]>()
    for (const edge of envelope.evidence.edges) {
      if (!initialNodeIds.has(edge.source) || !initialNodeIds.has(edge.target)) continue
      adjacency.set(edge.source, [...(adjacency.get(edge.source) ?? []), edge.target])
      adjacency.set(edge.target, [...(adjacency.get(edge.target) ?? []), edge.source])
    }
    depths = new Map<string, number>([...rootIds].map((id) => [id, 0]))
    const queue = [...rootIds]
    while (queue.length) {
      const current = queue.shift()!
      const depth = depths.get(current) ?? 0
      if (depth >= maxDepth) continue
      for (const neighbor of adjacency.get(current) ?? []) {
        if (depths.has(neighbor)) continue
        depths.set(neighbor, depth + 1)
        queue.push(neighbor)
      }
    }
    scopedNodeIds = new Set(depths.keys())
  }

  // Keep the projection bounded for broad queries. The ordering is stable:
  // roots first, then nodes closer to a root, then the original evidence
  // order. This makes the cap deterministic and preserves the most useful
  // part of the graph while leaving the full envelope untouched.
  if (opts.maxNodes !== undefined) {
    const maxNodes = Math.max(0, Math.floor(opts.maxNodes))
    const originalOrder = new Map(initialNodes.map((node, index) => [node.id, index]))
    const initialNodeById = new Map(initialNodes.map((node) => [node.id, node]))
    const selected = [...scopedNodeIds]
      .sort((a, b) => {
        const depthDelta = (depths.get(a) ?? Number.POSITIVE_INFINITY) - (depths.get(b) ?? Number.POSITIVE_INFINITY)
        if (depthDelta !== 0) return depthDelta
        const aNode = initialNodeById.get(a)
        const bNode = initialNodeById.get(b)
        const visibilityDelta = Number(DISPLAY_INTERMEDIARY_KINDS.has(aNode?.kind ?? ''))
          - Number(DISPLAY_INTERMEDIARY_KINDS.has(bNode?.kind ?? ''))
        if (visibilityDelta !== 0) return visibilityDelta
        return (originalOrder.get(a) ?? Number.POSITIVE_INFINITY) - (originalOrder.get(b) ?? Number.POSITIVE_INFINITY)
      })
      .slice(0, maxNodes)
    scopedNodeIds = new Set(selected)
  }

  const scopedNodes = initialNodes.filter((node) => scopedNodeIds.has(node.id))
  const scopedNodeIdSet = new Set(scopedNodes.map((node) => node.id))
  // A query edge from the model root to a business entity *defines* that
  // entity in this model. Keep it as a business relation from the model so
  // the model is never rendered as an isolated node; other query edges
  // (query-time actions) are dropped.
  const selectedEdges = envelope.evidence.edges.flatMap((edge) => {
    const within = scopedNodeIdSet.has(edge.source) && scopedNodeIdSet.has(edge.target)
    if (!within) return []
    if (edge.kind === 'query' && modelId && edge.source === modelId) {
      return [{ ...edge, kind: 'business' as const, label: '业务模型' }]
    }
    return edge.kind === 'query' ? [] : [edge]
  })

  // 隐藏没有关联关系的孤立节点：只保留至少出现在一条边端点上的节点。
  const connected = new Set<string>()
  for (const edge of selectedEdges) {
    connected.add(edge.source)
    connected.add(edge.target)
  }
  const nodes = scopedNodes.filter((node) => connected.has(node.id))
  const nodeIds = new Set(nodes.map((node) => node.id))
  const edges = selectedEdges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))

  const totalCount = envelope.evidence.nodes
    .filter((node) => node.kind !== 'record' && node.kind !== 'source')
    .length
  return { nodes, edges, totalCount }
}

export function evidenceGraphCounts(
  envelope: GraphifyEvidenceEnvelope,
  opts: GraphModelOptions,
): { nodeCount: number; edgeCount: number; totalCount: number } {
  const { nodes, edges, totalCount } = selectGraph(envelope, opts)
  return { nodeCount: nodes.length, edgeCount: edges.length, totalCount }
}

export function evidenceGraphModel(envelope: GraphifyEvidenceEnvelope, opts: GraphModelOptions): GraphElementDefinition[] {
  const { nodes, edges } = selectGraph(envelope, opts)
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const position = opts.viewMode === 'full'
    ? fullViewPositions(nodes)
    : dagrePositions(nodes, edges, { rankdir: 'LR' })

  const nodeElements: GraphElementDefinition[] = nodes.map((node) => {
    const visual = nodeVisual(node, opts.semantics)
    const presentedLabel = graphNodeLabel(node)
    // Core entity shows its business name directly; related entities show
    // their relation type above the concrete value.
    const label = node.kind === 'product' ? presentedLabel : `${visual.heading}\n${presentedLabel}`
    return {
      data: {
        id: node.id,
        label,
        fullLabel: presentedLabel,
        fontSize: nodeFontSize(label, node),
        kind: node.kind,
        category: node.kind,
        heading: visual.heading,
        coreNode: node.kind === 'product',
        diamond: ['catalog_record', 'source_file', 'import_batch'].includes(node.kind),
      },
      position: position.get(node.id) ?? { x: 0, y: 0 },
    }
  })
  const edgeElements: GraphElementDefinition[] = edges.map((edge) => ({
    data: {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: graphEdgeLabel(edge, nodeById),
      kind: edge.kind,
    },
  }))
  return [...nodeElements, ...edgeElements]
}
