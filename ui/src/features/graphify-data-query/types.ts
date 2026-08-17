export type GraphifyEvidenceNode = {
  id: string
  label: string
  kind: 'model' | 'record' | 'entity' | 'source' | 'product' | 'registration' | 'organization' | 'base' | 'concept' | 'catalog_record' | 'source_file' | 'import_batch'
}

export type GraphifyEvidenceEdge = {
  id: string
  source: string
  target: string
  label: string
  kind: 'query' | 'semantic' | 'provenance' | 'business'
}

export type GraphifyEvidenceEnvelope = {
  status: 'executed'
  trace_id: string
  dataset_id: 'medical_catalog'
  question: string
  result: { columns: string[]; column_labels?: Record<string, string>; rows: Array<Record<string, unknown>>; truncated: boolean }
  semantic_context: {
    graph_version: string
    recommended_models: string[]
    recommended_columns: string[]
    rules: Array<{ code: string; message: string; severity: 'warning' | 'hard' }>
    provenance: Record<string, string>
  }
  evidence: { source_record_ids: string[]; source_record_count?: number; nodes: GraphifyEvidenceNode[]; edges: GraphifyEvidenceEdge[] }
}

export type GraphifyToolOutcome = {
  status: 'blocked' | 'unavailable'
  trace_id?: string
  reason?: string
}
