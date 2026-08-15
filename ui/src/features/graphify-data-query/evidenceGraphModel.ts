import type { ElementDefinition } from 'cytoscape'
import { dagrePositions } from './dagreLayout.ts'
import { nodeVisual } from './evidenceStyles.ts'
import type { GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types'

export type GraphModelOptions = {
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
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

export function evidenceGraphModel(envelope: GraphifyEvidenceEnvelope, opts: GraphModelOptions): ElementDefinition[] {
  const allNodes = envelope.evidence.nodes.filter((node) => nodeVisible(node, opts))
  const nodes = opts.visibleIds
    ? allNodes.filter((node) => opts.visibleIds!.has(node.id))
    : allNodes
  const nodeIds = new Set(nodes.map((node) => node.id))
  const modelId = nodes.find((node) => node.kind === 'model')?.id
  // A query edge from the model root to a business entity *defines* that
  // entity in this model. Keep it as a business relation from the model so
  // the model is never rendered as an isolated node; other query edges
  // (query-time actions) are dropped.
  const edges = envelope.evidence.edges.flatMap((edge) => {
    const within = nodeIds.has(edge.source) && nodeIds.has(edge.target)
    if (!within) return []
    if (edge.kind === 'query' && modelId && edge.source === modelId) {
      return [{ ...edge, kind: 'business' as const, label: '业务模型' }]
    }
    return edge.kind === 'query' ? [] : [edge]
  })
  const position = opts.viewMode === 'full'
    ? fullViewPositions(nodes)
    : dagrePositions(nodes, edges, { rankdir: 'LR' })

  const nodeElements: ElementDefinition[] = nodes.map((node) => {
    const visual = nodeVisual(node.kind)
    // Core entity shows its business name directly; related entities show
    // their relation type above the concrete value.
    const label = node.kind === 'product' ? node.label : `${visual.heading}\n${node.label}`
    return {
      data: {
        id: node.id,
        label,
        fullLabel: node.label,
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
      label: edge.label,
      kind: edge.kind,
    },
  }))
  return [...nodeElements, ...edgeElements]
}
