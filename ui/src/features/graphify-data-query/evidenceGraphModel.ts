import type { ElementDefinition } from 'cytoscape'
import { dagrePositions } from './dagreLayout.ts'
import { nodeVisual, type DomainSemantics } from './evidenceStyles.ts'
import { graphEdgeLabel, graphNodeLabel } from './evidencePresentation.ts'
import type { GraphifyEvidenceEnvelope, GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type GraphModelOptions = {
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
  /** 行业语义（域标签/标题），由 envelope.semantic_context 注入，缺省时用中性词。 */
  semantics?: DomainSemantics
}

const QUERY_PROCESS_KINDS = new Set(['record', 'source'])

/**
 * Full-view layout: a compact two-tier arrangement that dagre would stack
 * vertically (many same-rank siblings) or spread horizontally (TB). Zones:
 * - left column: model + core product
 * - middle column: key business facts (organization / registration / base)
 * - upper-right grid: mapping concepts (up to 3 columns)
 * - lower-right chain: catalog record -> source file -> import batch
 */
function fullViewPositions(nodes: GraphifyEvidenceNode[]): Map<string, { x: number; y: number }> {
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
  // A query edge from the model root to a business entity *defines* that
  // entity in this model. Keep it as a business relation from the model so
  // the model is never rendered as an isolated node; other query edges
  // (query-time actions) are dropped.
  const selectedEdges = envelope.evidence.edges.flatMap((edge) => {
    const within = initialNodeIds.has(edge.source) && initialNodeIds.has(edge.target)
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
  const nodes = initialNodes.filter((node) => connected.has(node.id))
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

export function evidenceGraphModel(envelope: GraphifyEvidenceEnvelope, opts: GraphModelOptions): ElementDefinition[] {
  const { nodes, edges } = selectGraph(envelope, opts)
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const position = opts.viewMode === 'full'
    ? fullViewPositions(nodes)
    : dagrePositions(nodes, edges, { rankdir: 'LR' })

  const nodeElements: ElementDefinition[] = nodes.map((node) => {
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
        kind: node.kind,
        category: node.kind,
        heading: visual.heading,
        coreNode: node.kind === 'product',
        diamond: ['catalog_record', 'source_file', 'import_batch'].includes(node.kind),
      },
      position: position.get(node.id) ?? { x: 0, y: 0 },
    }
  })
  const edgeElements: ElementDefinition[] = edges.map((edge) => ({
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
