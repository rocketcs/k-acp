import type { GraphifyEvidenceNode } from './types'

export type NodeVisual = {
  shape: 'round-rectangle' | 'diamond' | 'ellipse'
  fill: string
  border: string
  color: string
  heading: string
}

/** 行业语义的域标签/标题由后端配置下发（semantic_context.domain_labels / .domain_headings），
 *  本模块只做展示形态（形状/配色）决策，绝不硬编码行业词（如“药品/耗材”）。 */
export type DomainSemantics = {
  labels?: Record<string, string>
  headings?: Record<string, string>
}

const BUSINESS_KINDS = new Set(['product', 'organization', 'registration', 'base', 'concept'])
const SOURCE_KINDS = new Set(['catalog_record', 'source_file', 'import_batch', 'record', 'source'])

const KIND_VISUALS: Record<string, NodeVisual> = {
  model: { shape: 'ellipse', fill: '#e8f1fb', border: '#2f6fb0', color: '#1e4f7d', heading: '业务模型' },
  product: { shape: 'round-rectangle', fill: '#fff4e4', border: '#c98b37', color: '#8a5410', heading: '目录项' },
  organization: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '生产企业' },
  registration: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '注册备案' },
  base: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '基础分类' },
  concept: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '映射概念' },
  catalog_record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '原始目录记录' },
  source_file: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源工作簿' },
  import_batch: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '导入批次' },
  entity: { shape: 'round-rectangle', fill: '#f7fafc', border: '#b9cbd6', color: '#5a7184', heading: '语义字段' },
  record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '查询记录' },
  source: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源记录' },
}

const DEFAULT_VISUAL: NodeVisual = { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '业务实体' }

// 节点 kind → 摘要类型标签（不含 product；product 按节点 domain 显示）。
// 均为行业无关的「类型」用语，行业具体词由后端配置下发。
const KIND_TYPE_LABELS: Record<string, string> = {
  model: '业务模型',
  record: '查询记录',
  entity: '业务实体',
  source: '来源记录',
  registration: '注册备案',
  organization: '生产企业',
  base: '基础分类',
  concept: '映射概念',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
  import_batch: '导入批次',
}

/**
 * 节点在“已选节点”摘要中的类型标签。产品节点按后端下发的域标签显示
 * （如 DRUG→药品 / CONSUMABLE→耗材），后端未下发时用中性词“目录项”，
 * 保证换行业/未知域时绝不误标为某一固定行业词。
 */
export function nodeTypeLabel(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>, semantics?: DomainSemantics): string {
  if (node.kind === 'product') {
    const label = node.domain ? semantics?.labels?.[node.domain] : undefined
    return label || '目录项'
  }
  return Object.prototype.hasOwnProperty.call(KIND_TYPE_LABELS, node.kind) ? KIND_TYPE_LABELS[node.kind]! : '业务实体'
}

export function nodeVisual(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>, semantics?: DomainSemantics): NodeVisual {
  const has = (key: string): boolean => Object.prototype.hasOwnProperty.call(KIND_VISUALS, key)
  if (node.kind === 'product') {
    const base: NodeVisual = has('product') ? KIND_VISUALS['product']! : DEFAULT_VISUAL
    const heading = node.domain ? semantics?.headings?.[node.domain] : undefined
    return heading ? { ...base, heading } : base
  }
  return has(node.kind) ? KIND_VISUALS[node.kind]! : DEFAULT_VISUAL
}

export function isBusinessEntity(kind: string): boolean {
  return BUSINESS_KINDS.has(kind)
}

export function isSourceKind(kind: string): boolean {
  return SOURCE_KINDS.has(kind)
}

// Re-exported so callers can narrow without importing GraphifyEvidenceNode type names.
export function nodeHeading(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>, semantics?: DomainSemantics): string {
  return nodeVisual(node, semantics).heading
}