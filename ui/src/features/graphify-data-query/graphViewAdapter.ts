import { selectGraph } from './evidenceGraphModel.ts'
import { graphRelationSummary } from './evidencePresentation.ts'
import type {
  GraphView,
  GraphViewBuildOptions,
  GraphViewCategory,
} from './graphView.ts'
import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types.ts'

/** 与 wren-medical-catalog Graph Explorer 相同的 ECharts 图例和色板顺序。 */
const REFERENCE_CATEGORIES: GraphViewCategory[] = [
  { key: 'product', name: '业务对象', color: '#2563eb' },
  { key: 'organization', name: '组织/企业', color: '#16a34a' },
  { key: 'registration', name: '编号/标识', color: '#ea580c' },
  { key: 'concept', name: '概念/分类', color: '#7c3aed' },
  { key: 'attribute', name: '业务字段', color: '#0f766e' },
  { key: 'source', name: '来源依据', color: '#64748b' },
]

const HIDDEN_INTERMEDIARY_KINDS = new Set(['model', 'record', 'source', 'catalog_record', 'source_file', 'import_batch'])

function referenceEntityType(node: GraphifyEvidenceNode): string {
  if (node.kind === 'base') return 'concept'
  if (node.kind === 'catalog_record' || node.kind === 'source_file' || node.kind === 'import_batch') return 'source'
  return node.kind
}

function isReferenceNode(node: GraphifyEvidenceNode): boolean {
  return !HIDDEN_INTERMEDIARY_KINDS.has(node.kind)
}

function attributeLabel(label: string): { field: string; value: string } {
  const normalized = label.replace(/^目录字段[：:]/, '').trim()
  const separator = normalized.search(/[：:]/)
  if (separator < 0) return { field: '目录字段', value: normalized || label }
  return {
    field: normalized.slice(0, separator).trim() || '目录字段',
    value: normalized.slice(separator + 1).trim() || normalized,
  }
}

/**
 * Use the same business-key identity as the local Graph Explorer instead of
 * Neo4j element ids. Graph Explorer intentionally merges equal business values
 * (for example the same payment category) into one visible node; retaining
 * element ids here would render one visually disconnected copy per record.
 */
function graphNodeId(node: GraphifyEvidenceNode): string {
  if (node.kind === 'attribute') {
    const { field, value } = attributeLabel(node.label)
    return `attribute:${field}:${value}`
  }
  if (node.kind === 'product') return `product:${node.label}`
  if (node.kind === 'organization') return `organization:${node.label}`
  if (node.kind === 'registration') return `registration:${node.label}`
  if (node.kind === 'base' || node.kind === 'concept') return `concept:${node.label}`
  return `node:${node.label}`
}

function graphEdgeId(source: string, target: string, edge: GraphifyEvidenceEdge, label = edge.label): string {
  return `${edge.kind}:${source}:${target}:${label}`
}

function productByRecord(
  edges: readonly GraphifyEvidenceEdge[],
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): Map<string, string> {
  const result = new Map<string, string>()
  for (const edge of edges) {
    const source = nodesById.get(edge.source)
    const target = nodesById.get(edge.target)
    if (source?.kind === 'product' && target?.kind === 'catalog_record') result.set(target.id, source.id)
    if (target?.kind === 'product' && source?.kind === 'catalog_record') result.set(source.id, target.id)
  }
  return result
}

/** Converts the current evidence envelope into a renderer-neutral graph view. */
export function buildGraphView(
  envelope: GraphifyEvidenceEnvelope,
  options: GraphViewBuildOptions,
): GraphView {
  // Keep the full envelope intact, but make the default ECharts projection
  // readable: query roots plus two relationship levels.
  const { nodes, edges, totalCount } = selectGraph(envelope, {
    ...options,
    maxDepth: options.maxDepth ?? 2,
    maxNodes: options.maxNodes ?? 100,
  })
  const nodesById = new Map(nodes.map((node) => [node.id, node]))
  const recordProducts = productByRecord(edges, nodesById)
  const displayIdByRealId = new Map(nodes.filter(isReferenceNode).map((node) => [node.id, graphNodeId(node)]))
  const viewNodes = new Map<string, GraphView['nodes'][number]>()
  nodes.filter(isReferenceNode).forEach((node) => {
    const presentation = node.kind === 'attribute' ? attributeLabel(node.label) : undefined
    const id = displayIdByRealId.get(node.id) ?? graphNodeId(node)
    const existing = viewNodes.get(id)
    viewNodes.set(id, {
      ...(existing ?? {}),
      id,
      entityType: referenceEntityType(node),
      label: presentation?.value ?? node.label,
      rawKind: node.kind,
      domain: node.domain,
      displayProperties: {
        ...(existing?.displayProperties ?? {}),
        fullLabel: presentation?.value ?? node.label,
      },
    })
  })

  const viewEdges = new Map<string, GraphView['edges'][number]>()
  for (const edge of edges) {
    const source = nodesById.get(edge.source)
    const target = nodesById.get(edge.target)
    if (!source || !target) continue
    const attribute = source.kind === 'attribute' ? source : target.kind === 'attribute' ? target : undefined
    const record = source.kind === 'catalog_record' ? source : target.kind === 'catalog_record' ? target : undefined
    if (attribute && record) {
      const productId = recordProducts.get(record.id)
      const displayProductId = productId ? displayIdByRealId.get(productId) : undefined
      const displayAttributeId = displayIdByRealId.get(attribute.id)
      if (!displayProductId || !displayAttributeId || !viewNodes.has(displayProductId) || !viewNodes.has(displayAttributeId)) continue
      const label = attributeLabel(attribute.label).field
      const id = graphEdgeId(displayProductId, displayAttributeId, edge, label)
      viewEdges.set(id, {
        id,
        source: displayProductId,
        target: displayAttributeId,
        relationType: edge.kind,
        label,
      })
      continue
    }
    const sourceId = displayIdByRealId.get(edge.source)
    const targetId = displayIdByRealId.get(edge.target)
    if (!sourceId || !targetId || sourceId === targetId || !viewNodes.has(sourceId) || !viewNodes.has(targetId)) continue
    const id = graphEdgeId(sourceId, targetId, edge)
    viewEdges.set(id, {
      id,
      source: sourceId,
      target: targetId,
      relationType: edge.kind,
      label: edge.label,
    })
  }

  const connected = new Set<string>()
  for (const edge of viewEdges.values()) {
    connected.add(edge.source)
    connected.add(edge.target)
  }
  const renderedNodes = [...viewNodes.values()].filter((node) => connected.has(node.id))

  return {
    id: envelope.trace_id,
    title: envelope.question,
    summary: graphRelationSummary(envelope) ?? undefined,
    nodes: renderedNodes,
    edges: [...viewEdges.values()],
    categories: REFERENCE_CATEGORIES,
    stats: { nodeCount: renderedNodes.length, edgeCount: viewEdges.size, totalNodeCount: totalCount },
    warnings: envelope.semantic_context.rules
      .filter((rule) => rule.severity === 'warning')
      .map((rule) => rule.message),
  }
}

/** Whether the official Neo4j projection leaves a graph that can actually render. */
export function hasRenderableGraph(envelope: GraphifyEvidenceEnvelope): boolean {
  const view = buildGraphView(envelope, { viewMode: 'full', showFields: false })
  return view.stats.nodeCount > 0 && view.stats.edgeCount > 0
}
