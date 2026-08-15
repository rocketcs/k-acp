import { Graph, layout } from '@dagrejs/dagre'
import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type DagreOptions = {
  nodeWidth?: number
  nodeHeight?: number
  nodesep?: number
  ranksep?: number
  rankdir?: 'LR' | 'TB'
}

export function dagrePositions(
  nodes: GraphifyEvidenceNode[],
  edges: GraphifyEvidenceEdge[],
  opts: DagreOptions = {},
): Map<string, { x: number; y: number }> {
  const { nodeWidth = 150, nodeHeight = 56, nodesep = 30, ranksep = 56, rankdir = 'LR' } = opts
  const graph = new Graph({ multigraph: true })
  graph.setGraph({ rankdir, nodesep, ranksep, edgesep: 14, marginx: 16, marginy: 16 })
  graph.setDefaultEdgeLabel(() => ({}))
  nodes.forEach((node) => graph.setNode(node.id, { width: nodeWidth, height: nodeHeight }))
  edges.forEach((edge) => {
    if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) {
      graph.setEdge(edge.source, edge.target, { weight: edge.kind === 'business' ? 2 : 1 }, edge.id)
    }
  })
  layout(graph)
  const positions = new Map<string, { x: number; y: number }>()
  nodes.forEach((node) => {
    const point = graph.node(node.id) as { x: number; y: number } | undefined
    if (!point) return
    // dagre reports center coordinates; cytoscape consumes centers directly.
    positions.set(node.id, { x: Math.round(point.x), y: Math.round(point.y) })
  })
  return positions
}
