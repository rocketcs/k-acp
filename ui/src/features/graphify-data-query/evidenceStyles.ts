import type { GraphifyEvidenceNode } from './types'

export type NodeVisual = {
  shape: 'round-rectangle' | 'diamond' | 'ellipse'
  fill: string
  border: string
  color: string
  heading: string
}

const BUSINESS_KINDS = new Set(['product', 'organization', 'registration', 'base', 'concept'])
const SOURCE_KINDS = new Set(['catalog_record', 'source_file', 'import_batch', 'record', 'source'])

const KIND_VISUALS: Record<string, NodeVisual> = {
  model: { shape: 'ellipse', fill: '#e8f1fb', border: '#2f6fb0', color: '#1e4f7d', heading: '业务模型' },
  product: { shape: 'round-rectangle', fill: '#fff4e4', border: '#c98b37', color: '#8a5410', heading: '耗材目录项' },
  organization: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '生产企业' },
  registration: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '注册备案' },
  base: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '基础耗材' },
  concept: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '映射概念' },
  catalog_record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '原始目录记录' },
  source_file: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源工作簿' },
  import_batch: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '导入批次' },
  entity: { shape: 'round-rectangle', fill: '#f7fafc', border: '#b9cbd6', color: '#5a7184', heading: '语义字段' },
  record: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '查询记录' },
  source: { shape: 'diamond', fill: '#f0f6fb', border: '#5d8fb5', color: '#2b5d80', heading: '来源记录' },
}

const DEFAULT_VISUAL: NodeVisual = { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '业务实体' }

// 目录域 → 产品节点标题（结果驱动图谱：药品/耗材/服务/诊疗共用 product 节点类型）。
const DOMAIN_PRODUCT_HEADINGS: Record<string, string> = {
  DRUG: '药品目录项',
  CONSUMABLE: '耗材目录项',
  SERVICE: '医疗服务项目',
  DIAGNOSIS: '诊疗项目',
}

// 目录域 → 面向用户的简体名称（节点摘要 / 类型标签用）。
const DOMAIN_LABELS: Record<string, string> = {
  DRUG: '药品',
  CONSUMABLE: '耗材',
  SERVICE: '医疗服务项目',
  DIAGNOSIS: '诊疗项目',
}

// 节点 kind → 摘要类型标签（不含 product；product 按节点 domain 显示）。
const KIND_TYPE_LABELS: Record<string, string> = {
  model: '业务模型',
  record: '查询记录',
  entity: '业务实体',
  source: '来源记录',
  registration: '注册备案',
  organization: '生产企业',
  base: '基础耗材',
  concept: '映射概念',
  catalog_record: '原始目录记录',
  source_file: '来源工作簿',
  import_batch: '导入批次',
}

/**
 * 节点在“已选节点”摘要中的类型标签。产品节点按目录域显示
 * （药品/耗材/医疗服务项目/诊疗项目），避免他达拉非这类药品被误标为“耗材”。
 */
export function nodeTypeLabel(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>): string {
  if (node.kind === 'product') {
    return (node.domain && DOMAIN_LABELS[node.domain]) || '目录项'
  }
  return KIND_TYPE_LABELS[node.kind] ?? '业务实体'
}

export function nodeVisual(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>): NodeVisual {
  if (node.kind === 'product' && node.domain && DOMAIN_PRODUCT_HEADINGS[node.domain]) {
    const base = Object.prototype.hasOwnProperty.call(KIND_VISUALS, 'product') ? KIND_VISUALS['product'] : DEFAULT_VISUAL
    return { ...base, heading: DOMAIN_PRODUCT_HEADINGS[node.domain] }
  }
  return Object.prototype.hasOwnProperty.call(KIND_VISUALS, node.kind) ? KIND_VISUALS[node.kind]! : DEFAULT_VISUAL
}

export function isBusinessEntity(kind: string): boolean {
  return BUSINESS_KINDS.has(kind)
}

export function isSourceKind(kind: string): boolean {
  return SOURCE_KINDS.has(kind)
}

// Re-exported so callers can narrow without importing GraphifyEvidenceNode type names.
export function nodeHeading(node: Pick<GraphifyEvidenceNode, 'kind' | 'domain'>): string {
  return nodeVisual(node).heading
}
