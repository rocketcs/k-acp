import type { GraphifyEvidenceEdge, GraphifyEvidenceEnvelope, GraphifyEvidenceNode, GraphifyGraphReference, GraphifyToolOutcome } from './types'

export type Neo4jReadCypherGraph = {
  nodes: GraphifyEvidenceNode[]
  edges: GraphifyEvidenceEdge[]
}

const FINAL_QUERY_TOOLS = new Set(['query'])
const OUTCOME_TOOLS = new Set([...FINAL_QUERY_TOOLS, 'query_preflight'])

const DISPLAY_LABELS: Record<string, string> = {
  power_inventory: '南网电力物资库存',
  WAREHOUSE_DISTR_ID: '库存分配编号',
  ACTUAL_WAREHOUSE_ID: '所属仓库编码',
  MATERIAL_ID: '物资编码',
  MATERIAL_NAME: '物资名称',
  MATERIAL_MODEL: '物资型号',
  MATERIAL_CATEGORY: '物资类别',
  VENDOR_ID: '供应商编码',
  VENDOR_NAME: '供应商名称',
  PROJECT_ID: '项目编码',
  PROJECT_NAME: '项目名称',
  ACTUAL_QTY: '数量',
  UNIT_PRICE: '单价',
  ACTUAL_TOTAL_PRICE: '库存金额',
  ACTUAL_TOTAL_TAX: '税额',
  UNIT_NAME: '计量单位',
  USAGE_NAME: '用途',
  RECEIPT_DATE: '入库日期',
  INVENTORY_AGE_NAME: '库龄',
  PROVINCE_CODE: '省份编码',
  BUREAU_CODE: '地市局编码',
  VOLTAGE_LEVEL_KV: '电压等级',
  RATED_CAPACITY_KVA: '额定容量',
  RATED_CURRENT_A: '额定电流',
  DATA_SOURCE: '数据来源',
  WAREHOUSE_ID: '仓库编码',
  WAREHOUSE_NAME: '仓库名称',
  WAREHOUSE_CODE: '仓库代码',
  WAREHOUSE_LEVEL: '仓库等级编码',
  WAREHOUSE_LEVEL_NAME: '仓库等级',
  WAREHOUSE_ADDR: '仓库地址',
  GEOGRAPHICAL_LNG: '经度',
  GEOGRAPHICAL_LAT: '纬度',
  IDLE_AMT: '闲置物资金额',
  SCRAP_AMT: '报废物资金额',
  RESERVE_AMT: '储备物资金额',
  PROJECT_AMT: '项目物资金额',
  TOTAL_AMT: '库存总金额',
  MANUFACTURER: '制造商',
  RATED_VOLTAGE_KV: '额定电压',
  BASE_PRICE: '基准单价',
  SOURCE_ID: '来源编码',
  SOURCE_NAME: '来源名称',
  SOURCE_URL: '来源地址',
  SOURCE_NOTE: '来源说明',
  RETRIEVED_ON: '取数日期',
  TIME_ID: '时间编号',
  USAGE_TYPE_ID: '用途编码',
  WAREHOUSE_LEVEL_ID: '仓库等级编码',
  WAREHOUSE_MONEY: '库存金额',
  WAREHOUSE_PRE_MONEY: '库存期初金额',
  TOTAL_AMOUNT: '合计金额',
  RECORD_COUNT: '记录数',
  '仓库': '仓库',
  '库存记录': '库存记录',
  '物资': '物资',
  '供应商': '供应商',
  '项目': '项目',
  '区域': '区域',
  '资料来源': '资料来源',
  '包含库存': '包含库存',
  '对应仓库': '对应仓库',
  '对应物资': '对应物资',
  '由供应商提供': '由供应商提供',
  '归属项目': '归属项目',
  '位于区域': '位于区域',
  '校准自': '校准自',
  entity: '业务实体',
  record_node: '查询记录',
  query: '查询返回',
  semantic: '语义关联',
  provenance: '来源追溯',
  business: '业务关联',
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const isStrings = (value: unknown): value is string[] => Array.isArray(value) && value.every((item) => typeof item === 'string')

const NEO4J_KIND_BY_LABEL: Record<string, GraphifyEvidenceNode['kind']> = {
  DrugProduct: 'product',
  ConsumableProduct: 'product',
  ServiceItem: 'product',
  DiagnosisItem: 'product',
  DrugGeneric: 'concept',
  RegistrationIdentifier: 'registration',
  Organization: 'organization',
  ConsumableBase: 'base',
  MappingConcept: 'concept',
  CatalogAttributeValue: 'attribute',
  CatalogRecord: 'catalog_record',
  SourceFile: 'source_file',
  ImportBatch: 'import_batch',
}

const NEO4J_DOMAIN_BY_LABEL: Record<string, string> = {
  DrugProduct: 'DRUG',
  ConsumableProduct: 'CONSUMABLE',
  ServiceItem: 'SERVICE',
  DiagnosisItem: 'DIAGNOSIS',
}

const NEO4J_RELATIONS: Record<string, Pick<GraphifyEvidenceEdge, 'label' | 'kind'>> = {
  MANUFACTURED_BY: { label: '生产企业', kind: 'business' },
  HAS_GENERIC: { label: '通用名', kind: 'business' },
  REGISTERED_AS: { label: '注册备案', kind: 'business' },
  PRODUCT_OF: { label: '对应基础耗材', kind: 'business' },
  ASSERTED_MAPS_TO_CONCEPT: { label: '目录映射', kind: 'semantic' },
  EVIDENCE_FOR: { label: '原始目录记录', kind: 'provenance' },
  CONTAINS_RECORD: { label: '来源工作簿', kind: 'provenance' },
  CONTAINS_SOURCE: { label: '导入批次', kind: 'provenance' },
  HAS_ATTRIBUTE: { label: '记录字段', kind: 'provenance' },
}

function neo4jNodeKind(labels: string[]): GraphifyEvidenceNode['kind'] | null {
  return labels.map((label) => NEO4J_KIND_BY_LABEL[label]).find((kind): kind is GraphifyEvidenceNode['kind'] => Boolean(kind)) ?? null
}

function neo4jNodeLabel(kind: GraphifyEvidenceNode['kind'], properties: Record<string, unknown>): string | null {
  const candidates = kind === 'product'
    ? ['catalog_name', 'generic_name', 'product_name', 'single_product_name', 'name', 'drug_code', 'base_code']
    : kind === 'organization'
      // 官方 Neo4j 导入将去重后的企业名称保存为 normalized_name；优先使用
      // 可读名称，避免真实关系行因节点无标签而被过滤。
      ? ['name', 'normalized_name', 'organization_name', 'manufacturer', 'enterprise_name']
      : kind === 'registration'
        ? ['registration_no', 'approval_number', 'raw_value', 'name']
        : kind === 'base'
          ? ['name', 'base_name', 'base_code']
          : kind === 'concept'
            ? ['name', 'concept_name', 'code']
            : kind === 'attribute'
              ? []
            : kind === 'catalog_record'
              ? ['catalog_name', 'name', 'source_record_id']
              : kind === 'source_file'
                ? ['file_name', 'name', 'source_name']
                : kind === 'import_batch'
                  ? ['batch_name', 'name', 'batch_id']
                  : ['name']
  for (const key of candidates) {
    const value = properties[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  if (kind === 'attribute') {
    const field = properties.field_label
    const value = properties.value
    if (typeof field === 'string' && field.trim() && typeof value === 'string' && value.trim()) return `${field.trim()}：${value.trim()}`
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return kind === 'catalog_record' ? '原始目录记录'
    : kind === 'source_file' ? '来源工作簿'
      : kind === 'import_batch' ? '导入批次'
        : null
}

function neo4jNode(id: string, labels: string[], properties: Record<string, unknown>): GraphifyEvidenceNode | null {
  const kind = neo4jNodeKind(labels)
  const label = kind ? neo4jNodeLabel(kind, properties) : null
  if (!kind || !label) return null
  const domain = labels.map((item) => NEO4J_DOMAIN_BY_LABEL[item]).find(Boolean)
  return { id: `neo4j:${id}`, label, kind, ...(domain ? { domain } : {}) }
}

/**
 * 将官方只读 MCP 的固定 Cypher 投影转换为前端图谱契约。
 * 不接受任意 Cypher/任意 JSON：调用方必须返回 source/relationship/target 的扁平关系行。
 */
export function parseNeo4jReadCypherGraph(content: string): Neo4jReadCypherGraph | null {
  if (!content || !content.trim()) return null
  try {
    const value: unknown = JSON.parse(content)
    if (!Array.isArray(value)) return null
    const nodes = new Map<string, GraphifyEvidenceNode>()
    const edges = new Map<string, GraphifyEvidenceEdge>()
    for (const row of value) {
      if (!isRecord(row) || typeof row.source_id !== 'string' || typeof row.target_id !== 'string'
        || !isStrings(row.source_labels) || !isStrings(row.target_labels)
        || !isRecord(row.source_properties) || !isRecord(row.target_properties)
        || typeof row.relation_type !== 'string') continue
      const source = neo4jNode(row.source_id, row.source_labels, row.source_properties)
      const target = neo4jNode(row.target_id, row.target_labels, row.target_properties)
      if (!source || !target || source.id === target.id) continue
      nodes.set(source.id, source)
      nodes.set(target.id, target)
      const relation = NEO4J_RELATIONS[row.relation_type] ?? { label: '相关', kind: 'semantic' as const }
      const id = `${source.id}:${row.relation_type}:${target.id}`
      edges.set(id, { id, source: source.id, target: target.id, ...relation })
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()] }
  } catch {
    return null
  }
}

export function displayGraphifyLabel(value: string, fallback?: string): string {
  // 未映射的键回显原值（诚实），绝不显示"业务字段"这类误导性占位表头。
  return DISPLAY_LABELS[value] ?? fallback ?? value
}

export function displayGraphifyNodeLabel(node: GraphifyEvidenceNode): string {
  if (node.kind === 'model') return displayGraphifyLabel(node.label, '业务模型')
  if (node.kind === 'record') return displayGraphifyLabel(node.label, '查询记录')
  if (node.kind === 'source') return '来源记录'
  return DISPLAY_LABELS[node.label] ?? node.label
}

function validNode(value: unknown): value is GraphifyEvidenceNode {
  return isRecord(value) && typeof value.id === 'string' && typeof value.label === 'string'
    && ['model', 'record', 'entity', 'source', 'product', 'registration', 'organization', 'base', 'concept', 'attribute', 'catalog_record', 'source_file', 'import_batch'].includes(String(value.kind))
}

function validEdge(value: unknown): value is GraphifyEvidenceEdge {
  return isRecord(value) && typeof value.id === 'string' && typeof value.source === 'string'
    && typeof value.target === 'string' && typeof value.label === 'string'
    && ['query', 'semantic', 'provenance', 'business', 'attribute'].includes(String(value.kind))
}

export function parseGraphifyEvidence(toolName: string, content: string): GraphifyEvidenceEnvelope | null {
  // Runtime tool names can be absent or mismatched after a reconnect. The
  // envelope itself is the authority: parse only a complete executed result,
  // never a partial/legacy payload, regardless of the reported tool name.
  return parseEnvelopeByContent(content)
}

/** 不依赖工具名，仅凭内容是否为完整 executed evidence envelope 判定。 */
function parseEnvelopeByContent(content: string): GraphifyEvidenceEnvelope | null {
  if (!content || !content.trim()) return null
  try {
    const value: unknown = JSON.parse(content)
    if (!isRecordingEnvelope(value)) return null
    return value as GraphifyEvidenceEnvelope
  } catch { return null }
}

/** 结构从严校验，等价于旧版 parseGraphifyEvidence 的全部约束。 */
function isRecordingEnvelope(value: unknown): value is GraphifyEvidenceEnvelope {
  if (!isRecord(value) || value.status !== 'executed' || value.dataset_id !== 'power_inventory'
    || typeof value.trace_id !== 'string' || typeof value.question !== 'string'
    || !isRecord(value.result) || !isStrings(value.result.columns) || !Array.isArray(value.result.rows)
    || !value.result.rows.every(isRecord) || typeof value.result.truncated !== 'boolean'
    || !isRecord(value.semantic_context) || typeof value.semantic_context.graph_version !== 'string'
    || !isStrings(value.semantic_context.recommended_models) || !isStrings(value.semantic_context.recommended_columns)
    || !Array.isArray(value.semantic_context.rules) || !isRecord(value.semantic_context.provenance)
    || !isRecord(value.evidence) || !isStrings(value.evidence.source_record_ids)
    || !Array.isArray(value.evidence.nodes) || !value.evidence.nodes.every(validNode)
    || !Array.isArray(value.evidence.edges) || !value.evidence.edges.every(validEdge)) return false
  const rules = value.semantic_context.rules
  if (!rules.every((rule) => isRecord(rule) && typeof rule.code === 'string' && typeof rule.message === 'string' && ['warning', 'hard'].includes(String(rule.severity)))) return false
  if (!Object.values(value.semantic_context.provenance).every((item) => typeof item === 'string')) return false
  return true
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

/** Parse the compact evidence_subgraph response without loading graph nodes into chat history. */
export function parseGraphifyGraphReference(content: string): GraphifyGraphReference | null {
  try {
    const value: unknown = JSON.parse(content)
    if (!isRecord(value) || value.status !== 'executed' || value.dataset_id !== 'power_inventory'
      || typeof value.trace_id !== 'string' || typeof value.graph_ref !== 'string'
      || !Number.isFinite(value.node_count) || !Number.isFinite(value.edge_count)) return null
    return {
      status: 'executed',
      trace_id: value.trace_id,
      dataset_id: 'power_inventory',
      graph_ref: value.graph_ref,
      node_count: Number(value.node_count),
      edge_count: Number(value.edge_count),
      ...(Number.isFinite(value.source_record_count) ? { source_record_count: Number(value.source_record_count) } : {}),
    }
  } catch { return null }
}
