import type { GraphifyEvidenceEnvelope, GraphifyToolOutcome } from './types'

export type TurnEvidence = { evidence?: GraphifyEvidenceEnvelope; outcome?: GraphifyToolOutcome }

/**
 * A completed fact query is the authoritative result for a conversation turn.
 * A later preflight rejection only describes a follow-up attempt, so it must
 * not replace the successful query's evidence graph.
 */
export function mergeTurnEvidence(existing: TurnEvidence | undefined, incoming: TurnEvidence): TurnEvidence {
  if (incoming.evidence) return { evidence: incoming.evidence }
  if (existing?.evidence) return existing
  return { outcome: incoming.outcome ?? existing?.outcome }
}
