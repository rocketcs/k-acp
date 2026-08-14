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
  product: { shape: 'round-rectangle', fill: '#fff4e4', border: '#c98b37', color: '#8a5410', heading: '查询目录项' },
  organization: { shape: 'round-rectangle', fill: '#eef7fb', border: '#2f8fb0', color: '#146a85', heading: '耗材企业' },
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

export function nodeVisual(kind: string): NodeVisual {
  return KIND_VISUALS[kind] ?? DEFAULT_VISUAL
}

export function isBusinessEntity(kind: string): boolean {
  return BUSINESS_KINDS.has(kind)
}

export function isSourceKind(kind: string): boolean {
  return SOURCE_KINDS.has(kind)
}

// Re-exported so callers can narrow without importing GraphifyEvidenceNode type names.
export function nodeHeading(node: Pick<GraphifyEvidenceNode, 'kind'>): string {
  return nodeVisual(node.kind).heading
}
