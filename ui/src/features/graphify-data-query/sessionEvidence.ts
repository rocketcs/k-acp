import { parseGraphifyEvidence, parseGraphifyToolOutcome, parseNeo4jReadCypherGraph } from './evidenceAdapter.ts'
import { mergeTurnEvidence, type TurnEvidence } from './turnEvidence.ts'
import type { ChatMessageVO } from '@/types'

const evidenceStoragePrefix = 'graphify:evidence:'
const evidenceStorageKey = (messageId: string) => `${evidenceStoragePrefix}${messageId}`

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
  if (directEvidence) return { evidence: directEvidence }

  try {
    const saved: unknown = JSON.parse(content)
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return null
    const record = saved as Record<string, unknown>
    if (typeof record.result !== 'string') return null
    const toolName = typeof record.name === 'string' ? record.name : ''
    const evidence = parseGraphifyEvidence(toolName, record.result)
    const outcome = parseGraphifyToolOutcome(toolName, record.result)
    const neo4jGraph = toolName === 'read-cypher' ? parseNeo4jReadCypherGraph(record.result) : null
    return evidence || outcome || neo4jGraph ? { evidence: evidence ?? undefined, outcome: outcome ?? undefined, neo4jGraph: neo4jGraph ?? undefined } : null
  } catch {
    return null
  }
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
      if (turn?.evidence || turn?.outcome) restored[messageId] = turn
      pending = undefined
    }
  }
  return restored
}
