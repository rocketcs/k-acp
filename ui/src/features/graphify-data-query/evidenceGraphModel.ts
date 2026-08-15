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
  const edges = envelope.evidence.edges.filter((edge) =>
    edge.kind !== 'query' && nodeIds.has(edge.source) && nodeIds.has(edge.target))
  // Focused view: narrow layered LR chain (core left, sources right).
  // Full view: LR chain, then re-layout same-rank concept siblings into a
  // compact multi-column grid so many concepts do not stack into one column.
  const position = dagrePositions(nodes, edges, { rankdir: 'LR' })
  if (opts.viewMode === 'full') {
    const conceptIds = nodes.filter((node) => node.kind === 'concept').map((node) => node.id)
    if (conceptIds.length > 2) {
      const productNode = nodes.find((node) => node.kind === 'product')
      const anchor = productNode ? position.get(productNode.id) : undefined
      if (anchor) {
        const cols = Math.min(3, Math.ceil(Math.sqrt(conceptIds.length)))
        conceptIds.forEach((id, index) => {
          const col = index % cols
          const row = Math.floor(index / cols)
          position.set(id, { x: Math.round(anchor.x + 70 + col * 138), y: Math.round(anchor.y - 60 + row * 70) })
        })
      }
    }
  }

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
