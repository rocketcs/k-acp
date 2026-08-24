import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode } from './types.ts'

const NODE_HEADINGS: Record<string, string> = {
  model: '业务模型',
  entity: '业务实体',
  product: '目录项',
  registration: '注册备案号',
  organization: '生产企业',
  base: '基础分类',
  concept: '映射概念',
  attribute: '目录字段',
  record: '查询记录',
  source: '来源记录',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
  import_batch: '导入批次',
}

const NEVER_INTERPOLATE_SOURCE_KINDS = new Set<GraphifyEvidenceNode['kind']>(['import_batch', 'record', 'source'])

const internalBatchLabel = (label: string): boolean =>
  /(?:^|[·\s：:()（）\-])(?:导入)?批次(?:编号|号)?\s*(?:\d{6,}|[A-Z0-9_-]{8,})/iu.test(label)

const internalLabel = (label: string): boolean =>
  /\b(?:record|source|import)\s*[:=]\s*[^\s,;，。]+/i.test(label)
  || /\b[a-z][a-z0-9]*\.[a-z][a-z0-9_]*\b/i.test(label)
  || /\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b/i.test(label)
  || /\b[a-z][a-z0-9_-]*\s*[:=]\s*[a-f0-9]{32,}\b/i.test(label)
  || /\b(?:sha(?:-?\d+)?|md\d+|crc\d*|blake\d*)\s*[:=]\s*[a-z0-9+/]{16,}={0,2}/i.test(label)
  || /\b[a-f0-9]{32,}\b/i.test(label)
  || /\b(?:node|edge)[-_:=][a-z0-9]+(?:[-_][a-z0-9]+)*\b/i.test(label)
  || /\b(?:batch(?:[_:-]|$)|raw\.)/i.test(label)
  || /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i.test(label)
  || internalBatchLabel(label)

const humanReadableSourceName = (label: string): boolean =>
  /^(?=.*\p{Script=Han})[\p{Script=Han}\d\s《》·、，。()（）\-：；！？“”‘’【】〔〕〈〉…—]+$/u.test(label)
  && !internalBatchLabel(label)

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

export function graphNodeLabel(node: GraphifyEvidenceNode): string {
  return sentenceLabel(node) ?? NODE_HEADINGS[node.kind] ?? '业务实体'
}

type BusinessRelationSemantic = 'registration' | 'organization' | 'base' | 'concept'

function businessRelationSemantic(
  edge: GraphifyEvidenceEdge,
  target: GraphifyEvidenceNode | undefined,
): BusinessRelationSemantic | null {
  if (edge.kind !== 'business' || !target) return null
  if (target.kind === 'registration' && edge.label === '对应') return 'registration'
  if (target.kind === 'organization' && edge.label === '生产') return 'organization'
  if (target.kind === 'base' && ['归类', '对应基础耗材'].includes(edge.label)) return 'base'
  if (target.kind === 'concept' && ['归类', '映射', '映射至', '目录映射'].includes(edge.label)) return 'concept'
  return null
}

export function graphEdgeLabel(
  edge: GraphifyEvidenceEdge,
  nodesById: ReadonlyMap<string, GraphifyEvidenceNode>,
): string {
  const target = nodesById.get(edge.target)
  if (edge.kind === 'provenance') {
    return target?.kind === 'source_file' ? '收录于来源工作簿' : '原始记录佐证'
  }
  const semantic = businessRelationSemantic(edge, target)
  if (semantic === 'registration') return '对应注册备案'
  if (semantic === 'organization') return '生产企业'
  if (semantic === 'base') return '归属分类'
  if (semantic === 'concept') return '映射至'
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
  const semantic = businessRelationSemantic(edge, target)
  if (semantic === 'registration') return `${sourceLabel}对应注册备案号：${targetLabel}。`
  if (semantic === 'organization') return `${sourceLabel}由${targetLabel}生产。`
  if (semantic === 'base') return `${sourceLabel}归属${targetLabel}。`
  if (semantic === 'concept') return `${sourceLabel}映射至${targetLabel}。`
  return `${sourceLabel}与${targetLabel}相关。`
}

const SUMMARY_SUBJECT_KINDS = new Set<GraphifyEvidenceNode['kind']>(['product'])
const SUMMARY_EXCLUDED_KINDS = new Set<GraphifyEvidenceNode['kind']>(['model', 'entity', 'record', 'source'])

export function graphRelationSentences(
  envelope: GraphifyEvidenceEnvelope,
  selectedNodeId?: string,
): string[] {
  const nodesById = new Map(envelope.evidence.nodes.map((node) => [node.id, node]))
  const edges = envelope.evidence.edges
    .filter((edge) => {
      if (edge.kind !== 'business' && edge.kind !== 'provenance') return false
      const source = nodesById.get(edge.source)
      const target = nodesById.get(edge.target)
      if (!source || !target || !SUMMARY_SUBJECT_KINDS.has(source.kind) || SUMMARY_EXCLUDED_KINDS.has(target.kind)) return false
      return selectedNodeId === undefined || edge.source === selectedNodeId || edge.target === selectedNodeId
    })
    .sort((left, right) => Number(left.kind === 'provenance') - Number(right.kind === 'provenance'))

  const sentences: string[] = []
  for (const edge of edges) {
    const sentence = graphRelationSentence(edge, nodesById)
    if (sentence && !sentences.includes(sentence)) sentences.push(sentence)
  }
  return sentences
}

export function graphRelationSummary(
  envelope: GraphifyEvidenceEnvelope,
  maxSentences = 3,
): string | null {
  const sentences = graphRelationSentences(envelope)
  if (sentences.length === 0) return null

  const limit = Math.max(0, maxSentences)
  const selected = sentences.slice(0, limit)
  const remaining = sentences.length - selected.length
  return `${selected.join('')}${remaining > 0 ? `另有 ${remaining} 条关联。` : ''}`
}

export { NODE_HEADINGS }
