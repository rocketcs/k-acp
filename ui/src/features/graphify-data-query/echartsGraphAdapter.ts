import type { GraphView } from './graphView.ts'

export type EChartsGraphData = {
  categories: EChartsGraphCategory[]
  colors: string[]
  data: EChartsGraphNode[]
  links: EChartsGraphLink[]
}

export type EChartsGraphCategory = {
  name: string
}

export type EChartsGraphNode = {
  id: string
  name: string
  label: string
  category: number
  symbolSize: number
  value: string
}

export type EChartsGraphLink = {
  id: string
  source: string
  target: string
  label: string
}

export type EChartsGraphPresentation = {
  showEdgeLabels: boolean
  repulsion: number
  edgeLength: [number, number]
}

/**
 * Keep the assistant graph in lockstep with the local Graph Explorer at
 * http://127.0.0.1:8770/. The explorer deliberately uses one stable force
 * profile for every bounded graph so the same evidence has the same visual
 * density and relation visibility in both surfaces.
 */
export function getEchartsGraphPresentation(_nodeCount: number, _edgeCount: number): EChartsGraphPresentation {
  return { showEdgeLabels: true, repulsion: 360, edgeLength: [90, 180] }
}

/** Adapts the renderer-neutral graph semantics to ECharts graph series data. */
export function toEchartsGraphData(graphView: GraphView): EChartsGraphData {
  const categoryIndex = new Map(graphView.categories.map((category, index) => [category.key, index]))
  return {
    categories: graphView.categories.map((category) => ({ name: category.name })),
    colors: graphView.categories.map((category) => category.color ?? '#64748b'),
    data: graphView.nodes.map((node) => ({
      id: node.id,
      name: node.id,
      label: node.label,
      category: categoryIndex.get(node.entityType) ?? 0,
      symbolSize: node.entityType === 'product' ? 58 : 44,
      value: node.label,
    })),
    links: graphView.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
    })),
  }
}
