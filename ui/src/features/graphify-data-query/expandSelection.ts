import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type ExpandSelectionOptions = {
  /**
   * 初始主节点种类。没有显式传入展开集时，从主节点（product/model/…）起步。
   */
  rootKinds?: readonly string[]
  /**
   * 默认是否纳入 query 动作边。渐进展开聚焦业务/来源/语义关系，
   * 真正的查询过程节点（record/source）始终不纳入。
   */
  includeQueryEdges?: boolean
}

/**
 * 计算「渐进展开」下的可见节点集：已展开节点 + 它们的一级邻居。
 *
 * 语义与用户期望一致——初始只给主节点和其 1 级节点，点击某节点把它加入
 * `expandedIds` 后再外扩一级。任何未展开节点的更深层邻居不会提前出现。
 */
export function expandSelection(
  nodes: GraphifyEvidenceNode[],
  edges: GraphifyEvidenceEdge[],
  expandedIds: ReadonlySet<string>,
  opts: ExpandSelectionOptions = {},
): Set<string> {
  const { includeQueryEdges = false, rootKinds = ['product', 'model'] } = opts
  const nodeById = new Map(nodes.map((node) => [node.id, node]))

  const defaultRoots = () => {
    // 优先核心业务实体 / 业务模型；否则取 query 边指向的查询结果节点；再退到模型。
    const byKind = rootKinds.flatMap((kind) => nodes.filter((node) => node.kind === kind).map((node) => node.id))
    if (byKind.length) return new Set(byKind)
    const queryTargets = edges
      .filter((edge) => edge.kind === 'query' && nodeById.has(edge.target) && nodeById.get(edge.target)!.kind !== 'record' && nodeById.get(edge.target)!.kind !== 'source')
      .map((edge) => edge.target)
    if (queryTargets.length) return new Set(queryTargets)
    const model = nodes.find((node) => node.kind === 'model')
    return new Set(model ? [model.id] : [])
  }

  // 允许直接提供初始展开集（例如记住用户点开过的节点）。
  const seed = expandedIds && expandedIds.size > 0
    ? expandedIds
    : defaultRoots()

  // 只保留业务/来源/语义关系，忽略 query 动作（除非显式要求）。
  const relevantEdges = edges.filter((edge) => includeQueryEdges || edge.kind !== 'query')
  const adjacency = new Map<string, Set<string>>()
  for (const edge of relevantEdges) {
    if (!nodeById.has(edge.source) || !nodeById.has(edge.target)) continue
    if (nodeById.get(edge.source)!.kind === 'record' || nodeById.get(edge.source)!.kind === 'source') continue
    if (nodeById.get(edge.target)!.kind === 'record' || nodeById.get(edge.target)!.kind === 'source') continue
    if (!adjacency.has(edge.source)) adjacency.set(edge.source, new Set())
    if (!adjacency.has(edge.target)) adjacency.set(edge.target, new Set())
    adjacency.get(edge.source)!.add(edge.target)
    adjacency.get(edge.target)!.add(edge.source)
  }

  const visible = new Set<string>()
  for (const id of seed) {
    if (!nodeById.has(id)) continue
    visible.add(id)
    const neighbors = adjacency.get(id)
    if (neighbors) neighbors.forEach((neighbor) => visible.add(neighbor))
  }
  return visible
}
