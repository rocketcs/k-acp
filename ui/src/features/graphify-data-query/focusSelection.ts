import type { GraphifyEvidenceEdge, GraphifyEvidenceNode } from './types'

export type FocusSelectionOptions = {
  lineageDepth?: number
}

export function focusSelection(
  nodes: GraphifyEvidenceNode[],
  edges: GraphifyEvidenceEdge[],
  opts: FocusSelectionOptions = {},
): Set<string> {
  const { lineageDepth = 2 } = opts
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const product =
    nodes.find((node) => node.kind === 'product' && edges.some((edge) => edge.kind === 'query' && edge.target === node.id))
    ?? nodes.find((node) => node.kind === 'product')
  const visible = new Set<string>()
  if (!product) {
    nodes.forEach((node) => {
      if (['product', 'organization', 'registration', 'base', 'concept', 'catalog_record', 'source_file', 'import_batch'].includes(node.kind)) {
        visible.add(node.id)
      }
    })
    return visible
  }
  visible.add(product.id)
  const outgoing = (source: string, kind?: 'business' | 'provenance') => edges
    .filter((edge) => edge.source === source && (!kind || edge.kind === kind))
    .map((edge) => edge.target)
  outgoing(product.id, 'business').forEach((id) => visible.add(id))
  const catalogRecord = outgoing(product.id, 'provenance').find((id) => nodeById.get(id)?.kind === 'catalog_record')
  if (catalogRecord) {
    visible.add(catalogRecord)
    let current = catalogRecord
    for (let depth = 0; depth < lineageDepth; depth += 1) {
      const next = outgoing(current, 'provenance')[0]
      if (!next || visible.has(next)) break
      visible.add(next)
      current = next
    }
  }
  return visible
}
