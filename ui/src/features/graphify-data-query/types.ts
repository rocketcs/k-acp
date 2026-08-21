export type GraphifyEvidenceNode = {
  id: string
  label: string
  kind: 'model' | 'record' | 'entity' | 'source' | 'product' | 'registration' | 'organization' | 'base' | 'concept' | 'catalog_record' | 'source_file' | 'import_batch'
  /** 可选目录域（DRUG/CONSUMABLE/SERVICE/DIAGNOSIS），结果驱动图谱用于区分产品标题。 */
  domain?: string
}

export type GraphifyEvidenceEdge = {
  id: string
  source: string
  target: string
  label: string
  kind: 'query' | 'semantic' | 'provenance' | 'business'
}

export type GraphifyProductDetail = {
  codes: string[]
  specifications: string[]
  enterprises: string[]
  categories: string[]
  registrations: string[]
  count: number
}

export type GraphifyEvidenceEnvelope = {
  status: 'executed'
  trace_id: string
  dataset_id: 'medical_catalog'
  question: string
  result: { columns: string[]; column_labels?: Record<string, string>; rows: Array<Record<string, unknown>>; truncated: boolean }
  execution_path?: {
    question: string
    intent: string
    stages: Array<{ step: string; system: string; title: string; detail: string; icon: string }>
    models: string[]
    columns: string[]
    source_record_count: number
    truncated: boolean
  }
  semantic_context: {
    graph_version: string
    recommended_models: string[]
    recommended_columns: string[]
    /** 行业语义配置下发的域标签（如 DRUG→药品）。前端渲染节点类型时只查表，不做行业拼接。 */
    domain_labels?: Record<string, string>
    /** 行业语义配置下发的产品节点标题（如 DRUG→药品目录项），前端原样展示。 */
    domain_headings?: Record<string, string>
    rules: Array<{ code: string; message: string; severity: 'warning' | 'hard' }>
    provenance: Record<string, string>
  }
  /** 按产品名聚合的目录明细（编码/规格/生产企业），摘要面板展示用。 */
  product_details?: Record<string, GraphifyProductDetail>
  evidence: { source_record_ids: string[]; source_record_count?: number; nodes: GraphifyEvidenceNode[]; edges: GraphifyEvidenceEdge[] }
}

export type GraphifyToolOutcome = {
  status: 'blocked' | 'unavailable'
  trace_id?: string
  reason?: string
}
