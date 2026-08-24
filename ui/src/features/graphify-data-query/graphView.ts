/**
 * Renderer-neutral representation of a graphify evidence graph.
 *
 * This module intentionally models display semantics only. Renderers adapt
 * this shape to their own data structures without leaking renderer types back
 * into the evidence graph layer.
 */
export type GraphView = {
  id: string
  title: string
  summary?: string
  nodes: GraphViewNode[]
  edges: GraphViewEdge[]
  categories: GraphViewCategory[]
  stats: GraphViewStats
  warnings?: string[]
}

export type GraphViewNode = {
  id: string
  entityType: string
  label: string
  subtitle?: string
  badges?: string[]
  rawKind: string
  domain?: string
  displayProperties?: Record<string, unknown>
  expandable?: boolean
  style?: GraphViewNodeStyle
  position?: GraphViewPosition
}

export type GraphViewEdge = {
  id: string
  source: string
  target: string
  relationType: string
  label: string
  style?: GraphViewEdgeStyle
}

export type GraphViewCategory = {
  key: string
  name: string
  color?: string
  icon?: GraphViewNodeStyle['shape']
}

export type GraphViewBuildOptions = {
  viewMode: 'focused' | 'full'
  showFields: boolean
  visibleIds?: ReadonlySet<string>
  maxDepth?: number
  /** 仅限制 ECharts 的可见投影节点数；完整 evidence 仍保留。 */
  maxNodes?: number
}

export type GraphViewStats = {
  nodeCount: number
  edgeCount: number
  totalNodeCount: number
}

export type GraphViewPosition = {
  x: number
  y: number
}

export type GraphViewNodeStyle = {
  shape?: 'rounded-rectangle' | 'ellipse' | 'diamond'
  fill?: string
  borderColor?: string
  color?: string
  symbolSize?: number
}

export type GraphViewEdgeStyle = {
  lineType?: 'solid' | 'dashed' | 'dotted'
  color?: string
  width?: number
}
