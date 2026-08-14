import { Graph, layout } from '@dagrejs/dagre'
import { Position, type Edge, type Node } from '@vue-flow/core'
import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode, GraphifyToolOutcome } from './types'

const FINAL_QUERY_TOOLS = new Set(['run_template_query', 'query'])
const OUTCOME_TOOLS = new Set([...FINAL_QUERY_TOOLS, 'query_preflight'])

const DISPLAY_LABELS: Record<string, string> = {
  medical_catalog: '医疗目录',
  catalog_code: '目录编码',
  catalog_name: '目录名称',
  catalog_domain: '目录领域',
  approval_number: '批准文号',
  manufacturer: '药品生产企业',
  registration_no: '注册备案号',
  consumable_enterprise: '耗材企业',
  specification: '规格',
  model: '型号',
  registrant_name: '注册备案人',
  valid_from: '生效日期',
  valid_to: '失效日期',
  max_price_text: '最高价格',
  price_semantics: '价格语义',
  source_record_id: '原始来源记录',
  product: '耗材目录项',
  registration: '注册备案号',
  organization: '生产企业',
  base: '基础耗材编码',
  concept: '映射概念',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
  import_batch: '导入批次',
  query: '查询返回',
  semantic: '语义关联',
  provenance: '来源追溯',
  business: '业务关联',
  '生产企业': '生产企业',
  '耗材企业': '耗材企业',
  '注册备案': '注册备案',
  '对应基础耗材': '对应基础耗材',
  '目录映射': '目录映射',
  '原始目录记录': '原始目录记录',
  '来源工作簿': '来源工作簿',
  '导入批次': '导入批次',
  model_node: '业务模型',
  record_node: '查询记录',
  entity_node: '业务实体',
  source_node: '来源记录',
  'model-node': '业务模型',
  'record-node': '查询记录',
  'entity-node': '业务实体',
  'source-node': '来源记录',
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isStrings = (value: unknown): value is string[] => Array.isArray(value) && value.every((item) => typeof item === 'string')

export function displayGraphifyLabel(value: string, fallback = '业务字段'): string {
  return DISPLAY_LABELS[value] ?? fallback
}

export function displayGraphifyNodeLabel(node: GraphifyEvidenceNode): string {
  if (node.kind === 'model') return displayGraphifyLabel(node.label, '业务模型')
  if (node.kind === 'record') return displayGraphifyLabel(node.label, '查询记录')
  if (node.kind === 'source') return '来源记录'
  return DISPLAY_LABELS[node.label] ?? node.label
}

function validNode(value: unknown): value is GraphifyEvidenceNode {
  return isRecord(value) && typeof value.id === 'string' && typeof value.label === 'string'
    && ['model', 'record', 'entity', 'source', 'product', 'registration', 'organization', 'base', 'concept', 'catalog_record', 'source_file', 'import_batch'].includes(String(value.kind))
}

function validEdge(value: unknown): value is GraphifyEvidenceEdge {
  return isRecord(value) && typeof value.id === 'string' && typeof value.source === 'string'
    && typeof value.target === 'string' && typeof value.label === 'string'
    && ['query', 'semantic', 'provenance', 'business'].includes(String(value.kind))
}

export function parseGraphifyEvidence(toolName: string, content: string): GraphifyEvidenceEnvelope | null {
  if (!FINAL_QUERY_TOOLS.has(toolName)) return null
  try {
    const value: unknown = JSON.parse(content)
    if (!isRecord(value) || value.status !== 'executed' || value.dataset_id !== 'medical_catalog'
      || typeof value.trace_id !== 'string' || !value.trace_id || typeof value.question !== 'string'
      || !isRecord(value.result) || !isStrings(value.result.columns) || !Array.isArray(value.result.rows)
      || !value.result.rows.every(isRecord) || typeof value.result.truncated !== 'boolean'
      || !isRecord(value.semantic_context) || typeof value.semantic_context.graph_version !== 'string'
      || !isStrings(value.semantic_context.recommended_models) || !isStrings(value.semantic_context.recommended_columns)
      || !Array.isArray(value.semantic_context.rules) || !isRecord(value.semantic_context.provenance)
      || !isRecord(value.evidence) || !isStrings(value.evidence.source_record_ids)
      || !Array.isArray(value.evidence.nodes) || !value.evidence.nodes.every(validNode)
      || !Array.isArray(value.evidence.edges) || !value.evidence.edges.every(validEdge)) return null
    const rules = value.semantic_context.rules
    if (!rules.every((rule) => isRecord(rule) && typeof rule.code === 'string' && typeof rule.message === 'string' && ['warning', 'hard'].includes(String(rule.severity)))) return null
    if (!Object.values(value.semantic_context.provenance).every((item) => typeof item === 'string')) return null
    return value as GraphifyEvidenceEnvelope
  } catch { return null }
}

export function parseGraphifyToolOutcome(toolName: string, content: string): GraphifyToolOutcome | null {
  if (!OUTCOME_TOOLS.has(toolName)) return null
  try {
    const value: unknown = JSON.parse(content)
    if (!isRecord(value) || !['blocked', 'unavailable'].includes(String(value.status))) return null
    const finding = Array.isArray(value.findings) && isRecord(value.findings[0]) ? value.findings[0] : null
    return {
      status: value.status as GraphifyToolOutcome['status'],
      trace_id: typeof value.trace_id === 'string' ? value.trace_id : undefined,
      reason: typeof value.reason === 'string' ? value.reason : typeof finding?.message === 'string' ? finding.message : undefined,
    }
  } catch { return null }
}

export function baseEvidenceNodeIds(evidence: GraphifyEvidenceEnvelope): Set<string> {
  const queryEdges = evidence.evidence.edges.filter((edge) => edge.kind === 'query')
  if (queryEdges.length <= 1) return new Set(evidence.evidence.nodes.map((node) => node.id))

  const selectedResultId = queryEdges[0]?.target
  const queryResultIds = new Set(queryEdges.map((edge) => edge.target))
  return new Set(evidence.evidence.nodes
    .filter((node) => !queryResultIds.has(node.id) || node.id === selectedResultId)
    .map((node) => node.id))
}

export function toVueFlowEvidence(evidence: GraphifyEvidenceEnvelope, visibleNodeIds?: ReadonlySet<string>): { nodes: Node[]; edges: Edge[] } {
  const nodes = visibleNodeIds ? evidence.evidence.nodes.filter((node) => visibleNodeIds.has(node.id)) : evidence.evidence.nodes
  const edges = visibleNodeIds
    ? evidence.evidence.edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target))
    : evidence.evidence.edges
  const graph = new Graph({ multigraph: true })
  graph.setGraph({ rankdir: 'LR', ranker: 'network-simplex', nodesep: 78, ranksep: 150, edgesep: 32, marginx: 44, marginy: 44 })
  graph.setDefaultEdgeLabel(() => ({}))
  nodes.forEach((node) => graph.setNode(node.id, { width: 174, height: 60 }))
  edges.forEach((edge) => graph.setEdge(edge.source, edge.target, { weight: edge.kind === 'query' ? 3 : 1 }, edge.id))
  layout(graph)
  const positions = new Map(nodes.map((node) => {
    const point = graph.node(node.id) as { x: number; y: number }
    return [node.id, { x: Math.round(point.x - 87), y: Math.round(point.y - 30) }]
  }))
  return {
    nodes: nodes.map((item) => ({
      id: item.id, type: 'evidence', label: displayGraphifyNodeLabel(item), class: `${item.kind}-node`, position: positions.get(item.id) ?? { x: 0, y: 0 },
    })),
    edges: edges.map((item) => {
      const source = positions.get(item.source) ?? { x: 0, y: 0 }
      const target = positions.get(item.target) ?? { x: 0, y: 0 }
      const handles = edgeHandles(source, target)
      return {
        id: item.id, source: item.source, target: item.target, label: DISPLAY_LABELS[item.label] ?? DISPLAY_LABELS[item.kind] ?? '业务关联',
        class: `edge-${item.kind}`, type: 'bezier', markerEnd: 'arrowclosed', ...handles,
      }
    }),
  }
}

function edgeHandles(source: { x: number; y: number }, target: { x: number; y: number }) {
  const horizontal = Math.abs(target.x - source.x) >= Math.abs(target.y - source.y)
  const [sourcePosition, targetPosition] = horizontal
    ? target.x >= source.x ? [Position.Right, Position.Left] : [Position.Left, Position.Right]
    : target.y >= source.y ? [Position.Bottom, Position.Top] : [Position.Top, Position.Bottom]
  return {
    sourcePosition,
    targetPosition,
    sourceHandle: `${sourcePosition}-source`,
    targetHandle: `${targetPosition}-target`,
  }
}
