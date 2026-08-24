import { parseGraphifyEvidence, parseGraphifyGraphReference, parseGraphifyToolOutcome, parseNeo4jReadCypherGraph } from './evidenceAdapter.ts'
import { mergeTurnEvidence, type TurnEvidence } from './turnEvidence.ts'
import type { GraphifyEvidenceEnvelope } from './types'
import type { ChatMessageVO } from '@/types'

const evidenceStoragePrefix = 'graphify:evidence:'
const evidenceStorageKey = (messageId: string) => `${evidenceStoragePrefix}${messageId}`

const neo4jNodeKinds = new Set(['product', 'registration', 'organization', 'base', 'concept', 'attribute', 'catalog_record', 'source_file', 'import_batch'])
const legacyEvidenceSubgraphKinds = new Set([...neo4jNodeKinds, 'model'])

/** 从 evidence_subgraph 的信封中只保留真实 Neo4j 节点与其完整关系。 */
export function neo4jGraphFromEvidence(evidence: GraphifyEvidenceEnvelope): NonNullable<TurnEvidence['neo4jGraph']> | null {
  const nodes = evidence.evidence.nodes.filter((node) => neo4jNodeKinds.has(node.kind))
  const nodeIds = new Set(nodes.map((node) => node.id))
  const edges = evidence.evidence.edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
  return nodes.length && edges.length ? { nodes, edges } : null
}

/**
 * 旧版落库会丢失工具名。仅当信封的全部节点均为 Neo4j 实体且存在完整关系时，
 * 才将其识别为 evidence_subgraph，避免把普通查询 evidence 误当成图谱。
 */
/**
 * 从未携带工具名的历史信封恢复官方 Neo4j 子图。
 * 该函数也供消息组件兜底使用：会话重放过程中若图谱投影丢失，仍可从同一份可信证据恢复。
 */
export function recoverLegacyNeo4jGraph(evidence: GraphifyEvidenceEnvelope): NonNullable<TurnEvidence['neo4jGraph']> | null {
  const nodes = evidence.evidence.nodes
  if (!nodes.length || !nodes.every((node) => legacyEvidenceSubgraphKinds.has(node.kind))) return null
  if (!nodes.some((node) => node.kind === 'catalog_record')) return null
  return neo4jGraphFromEvidence(evidence)
}

/**
 * 由一条 assistant 消息 id 读取本地缓存的可执行证据信封（刷新重放兜底）。
 * 与历史 useGraphifyDataQueryChat 的缓存格式保持一致。
 */
export function readTurnEvidence(messageId: string): TurnEvidence | null {
  try {
    const raw = localStorage.getItem(evidenceStorageKey(messageId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as TurnEvidence
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || !parsed.evidence) return null
    return parsed
  } catch {
    return null
  }
}

export function persistTurnEvidence(messageId: string, turn: TurnEvidence) {
  try {
    if (!messageId || !turn?.evidence) return
    localStorage.setItem(evidenceStorageKey(messageId), JSON.stringify(turn))
  } catch {
    /* 忽略 localStorage 不可用 */
  }
}

function parseSavedToolResult(content: string): TurnEvidence | null {
  const directEvidence = parseGraphifyEvidence('', content)
  if (directEvidence) {
    const neo4jGraph = recoverLegacyNeo4jGraph(directEvidence)
    return neo4jGraph ? { evidence: directEvidence, neo4jGraph } : { evidence: directEvidence }
  }
  const graphRef = parseGraphifyGraphReference(content)

  try {
    const saved: unknown = JSON.parse(content)
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return null
    const record = saved as Record<string, unknown>
    if (typeof record.result !== 'string') return graphRef ? { graphRef } : null
    const toolName = typeof record.name === 'string' ? record.name : ''
    const evidence = parseGraphifyEvidence(toolName, record.result)
    const outcome = parseGraphifyToolOutcome(toolName, record.result)
    const neo4jGraph = toolName === 'evidence_subgraph' && evidence
      ? neo4jGraphFromEvidence(evidence)
      : toolName === 'read-cypher' ? parseNeo4jReadCypherGraph(record.result) : null
    const savedGraphRef = toolName === 'evidence_subgraph' ? parseGraphifyGraphReference(record.result) : null
    return evidence || outcome || neo4jGraph || savedGraphRef ? { evidence: evidence ?? undefined, outcome: outcome ?? undefined, neo4jGraph: neo4jGraph ?? undefined, graphRef: savedGraphRef ?? undefined } : null
  } catch {
    return null
  }
}

export async function hydrateGraphReferences(
  evidenceMap: Record<string, TurnEvidence>,
  fetcher: (graphRef: string) => Promise<GraphifyEvidenceEnvelope | null> = fetchEvidenceEnvelope,
): Promise<Record<string, TurnEvidence>> {
  const entries = Object.entries(evidenceMap).filter(([, turn]) => Boolean(turn.graphRef && !turn.neo4jGraph))
  if (!entries.length) return evidenceMap
  const hydrated = { ...evidenceMap }
  await Promise.all(entries.map(async ([messageId, turn]) => {
    const envelope = await fetcher(turn.graphRef!.graph_ref)
    const neo4jGraph = envelope ? neo4jGraphFromEvidence(envelope) : null
    if (envelope) hydrated[messageId] = mergeTurnEvidence(turn, { evidence: envelope, ...(neo4jGraph ? { neo4jGraph } : {}) })
  }))
  return hydrated
}

async function fetchEvidenceEnvelope(graphRef: string): Promise<GraphifyEvidenceEnvelope | null> {
  try {
    const base = import.meta.env.VITE_GRAPH_EXPLORER_URL || 'http://127.0.0.1:8770'
    const response = await fetch(`${base.replace(/\/$/, '')}/api/evidence/${encodeURIComponent(graphRef)}`)
    if (!response.ok) return null
    const payload = await response.json()
    return parseGraphifyEvidence('', JSON.stringify(payload))
  } catch { return null }
}

/**
 * 从某会话的消息列表重建「助手消息 id → 该轮证据」映射。
 * 规则：tool 结果累积到随后的 assistant 消息；本地缓存的 executed 信封优先（覆盖
 * 后端仅落库 blocked/warning 预检诊断、executed 只在本浏览器缓存的情况）。
 */
export function buildSessionEvidence(messages: readonly ChatMessageVO[]): Record<string, TurnEvidence> {
  const restored: Record<string, TurnEvidence> = {}
  let pending: TurnEvidence | undefined
  for (const message of messages) {
    if (message.role === 'tool') {
      const toolResult = parseSavedToolResult(message.content)
      if (toolResult) pending = mergeTurnEvidence(pending, toolResult)
    } else if (message.role === 'assistant') {
      const messageId = String(message.id)
      const turn = pending ? mergeTurnEvidence(readTurnEvidence(messageId) ?? {}, pending) : readTurnEvidence(messageId)
      if (turn?.evidence || turn?.outcome || turn?.neo4jGraph) restored[messageId] = turn
      pending = undefined
    }
  }
  return restored
}
