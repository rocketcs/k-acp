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
  const position = dagrePositions(nodes, edges)

  const nodeElements: ElementDefinition[] = nodes.map((node) => {
    const visual = nodeVisual(node.kind)
    return {
      data: {
        id: node.id,
        label: `${visual.heading}\n${node.label}`,
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
