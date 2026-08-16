import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types.ts'

const NODE_HEADINGS: Record<string, string> = {
  registration: '注册备案号',
  organization: '生产企业',
  base: '基础耗材',
  concept: '映射概念',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
}

const readable = (node: GraphifyEvidenceNode | undefined): node is GraphifyEvidenceNode => {
  const label = node?.label.trim() ?? ''
  return Boolean(label) && !/^(record:|source:|import:|(?:raw|public)\.[a-z0-9_]+$|[a-f0-9]{32,})/i.test(label)
}

export function graphEdgeLabel(
  edge: GraphifyEvidenceEdge,
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): string {
  const target = nodesById.get(edge.target)
  if (edge.kind === 'provenance') {
    return target?.kind === 'source_file' ? '收录于来源工作簿' : '原始记录佐证'
  }
  if (edge.label === '对应' && target?.kind === 'registration') return '对应注册备案'
  // Uppercase machine labels are opaque custom relations; keep those on the
  // generic fallback even when the endpoint happens to be an organization.
  if (edge.label === '生产' || (target?.kind === 'organization' && !/^[A-Z_]+$/.test(edge.label))) return '生产企业'
  if (edge.label === '归类' || target?.kind === 'base') return '归属分类'
  if (edge.label.includes('映射') || target?.kind === 'concept') return '映射至'
  return '相关'
}

export function graphRelationSentence(
  edge: GraphifyEvidenceEdge,
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): string | null {
  const source = nodesById.get(edge.source)
  const target = nodesById.get(edge.target)
  if (!readable(source) || !readable(target)) return null

  if (edge.kind === 'provenance') return `该信息由${target.label}佐证。`
  if (target.kind === 'registration') return `${source.label}对应注册备案号：${target.label}。`
  if (target.kind === 'organization' && (edge.label === '生产' || !/^[A-Z_]+$/.test(edge.label))) return `${source.label}由${target.label}生产。`
  if (target.kind === 'base') return `${source.label}归属${target.label}。`
  if (target.kind === 'concept') return `${source.label}映射至${target.label}。`
  return `${source.label}与${target.label}相关。`
}

export function graphRelationSummary(
  envelope: GraphifyEvidenceEnvelope,
  maxSentences = 3,
): string | null {
  const nodesById = new Map(envelope.evidence.nodes.map((node) => [node.id, node]))
  const edges = [...envelope.evidence.edges].sort((left, right) => {
    const leftProvenance = left.kind === 'provenance' ? 1 : 0
    const rightProvenance = right.kind === 'provenance' ? 1 : 0
    return leftProvenance - rightProvenance
  })
  const sentences: string[] = []
  for (const edge of edges) {
    const sentence = graphRelationSentence(edge, nodesById)
    if (sentence && !sentences.includes(sentence)) sentences.push(sentence)
  }
  if (sentences.length === 0) return null

  const limit = Math.max(0, maxSentences)
  const selected = sentences.slice(0, limit)
  const remaining = sentences.length - selected.length
  return `${selected.join('')}${remaining > 0 ? `另有 ${remaining} 条关联。` : ''}`
}

export { NODE_HEADINGS }
