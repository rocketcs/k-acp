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
  /** 南网驾驶舱风格：按业务类型着色与分级 */
  rawKind?: string
  itemStyle?: { color: string; shadowBlur: number; shadowColor: string }
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
 * http://kg.demo.pine.kingsware.cn:6800/. The explorer deliberately uses one stable force
 * profile for every bounded graph so the same evidence has the same visual
 * density and relation visibility in both surfaces.
 */
export function getEchartsGraphPresentation(_nodeCount: number, _edgeCount: number): EChartsGraphPresentation {
  return { showEdgeLabels: true, repulsion: 330, edgeLength: [90, 160] }
}

/** 南网驾驶舱色板（与 docs/ontology/dm8-graph-demo-cn.html 一致） */
export const NANWANG_KIND_PALETTE: Record<string, string> = {
  '仓库': '#d97706',
  '库存记录': '#059669',
  '物资': '#2563eb',
  '供应商': '#7c3aed',
  '项目': '#e11d48',
  '区域': '#ca8a04',
  '资料来源': '#0284c7',
}

/** 节点尺寸分级（仓库最大 → 库存记录最小） */
export function nanwangNodeSize(rawKind?: string): number {
  switch (rawKind) {
    case '仓库': return 54
    case '物资': return 30
    case '库存记录': return 22
    case '区域': return 32
    case '资料来源': return 24
    default: return 26
  }
}

/** Adapts the renderer-neutral graph semantics to ECharts graph series data. */
export function toEchartsGraphData(graphView: GraphView): EChartsGraphData {
  const categoryIndex = new Map(graphView.categories.map((category, index) => [category.key, index]))
  return {
    categories: graphView.categories.map((category) => ({ name: category.name })),
    colors: graphView.categories.map((category) => category.color ?? '#64748b'),
    data: graphView.nodes.map((node) => {
      const rawKind = (node as GraphView['nodes'][number] & { rawKind?: string }).rawKind
      const color = NANWANG_KIND_PALETTE[rawKind ?? '']
      return {
        id: node.id,
        name: node.id,
        label: node.label,
        category: categoryIndex.get(node.entityType) ?? 0,
        symbolSize: nanwangNodeSize(rawKind),
        value: node.label,
        ...(color
          ? { itemStyle: { color, shadowBlur: rawKind === '仓库' ? 12 : 5, shadowColor: color } }
          : {}),
      }
    }),
    links: graphView.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
    })),
  }
}
