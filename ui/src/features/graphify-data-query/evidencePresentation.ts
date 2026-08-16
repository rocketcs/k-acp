import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types.ts'

const NODE_HEADINGS: Record<string, string> = {
  registration: '注册备案号',
  organization: '生产企业',
  base: '基础耗材',
  concept: '映射概念',
  record: '查询记录',
  source: '来源记录',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
  import_batch: '导入批次',
}

const NEVER_INTERPOLATE_SOURCE_KINDS = new Set<GraphifyEvidenceNode['kind']>(['import_batch', 'record', 'source'])

const internalLabel = (label: string): boolean =>
  /\b(?:record|source|import)\s*[:=]\s*[^\s,;，。]+/i.test(label)
  || /\b[a-z][a-z0-9]*\.[a-z][a-z0-9_]*\b/i.test(label)
  || /\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b/i.test(label)
  || /\b[a-z][a-z0-9_-]*\s*[:=]\s*[a-f0-9]{32,}\b/i.test(label)
  || /\b[a-f0-9]{32,}\b/i.test(label)
  || /\b(?:batch(?:[_:-]|$)|raw\.)/i.test(label)
  || /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i.test(label)

const humanReadableSourceName = (label: string): boolean =>
  /^(?=.*\p{Script=Han})[\p{Script=Han}\d\s《》·、，。()（）\-：；！？“”‘’【】〔〕〈〉…—]+$/u.test(label)

const sentenceLabel = (node: GraphifyEvidenceNode | undefined): string | null => {
  if (!node) return null

  const label = node.label.trim()
  const heading = NODE_HEADINGS[node.kind]
  if (NEVER_INTERPOLATE_SOURCE_KINDS.has(node.kind)) return heading ?? null
  if (node.kind === 'catalog_record' || node.kind === 'source_file') {
    return humanReadableSourceName(label) ? label : heading ?? null
  }
  return label && !internalLabel(label) ? label : null
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
  const sourceLabel = sentenceLabel(source)
  const targetLabel = sentenceLabel(target)
  if (!source || !target || !sourceLabel || !targetLabel) return null

  if (edge.kind === 'provenance') return `该信息由${targetLabel}佐证。`
  if (target.kind === 'registration') return `${sourceLabel}对应注册备案号：${targetLabel}。`
  if (target.kind === 'organization' && (edge.label === '生产' || !/^[A-Z_]+$/.test(edge.label))) return `${sourceLabel}由${targetLabel}生产。`
  if (target.kind === 'base') return `${sourceLabel}归属${targetLabel}。`
  if (target.kind === 'concept') return `${sourceLabel}映射至${targetLabel}。`
  return `${sourceLabel}与${targetLabel}相关。`
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
