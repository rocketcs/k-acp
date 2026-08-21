import type { Neo4jReadCypherGraph } from './evidenceAdapter.ts'
import type { GraphifyEvidenceEnvelope, GraphifyToolOutcome } from './types'

export type TurnEvidence = { evidence?: GraphifyEvidenceEnvelope; outcome?: GraphifyToolOutcome; neo4jGraph?: Neo4jReadCypherGraph }

function replaceWithOfficialNeo4jGraph(evidence: GraphifyEvidenceEnvelope, graph: Neo4jReadCypherGraph): GraphifyEvidenceEnvelope {
  return {
    ...evidence,
    evidence: { ...evidence.evidence, nodes: graph.nodes, edges: graph.edges },
  }
}

/**
 * A completed fact query is the authoritative result for a conversation turn.
 * A later preflight rejection only describes a follow-up attempt, so it must
 * not replace the successful query's evidence graph.
 */
export function mergeTurnEvidence(existing: TurnEvidence | undefined, incoming: TurnEvidence): TurnEvidence {
  if (incoming.evidence) {
    const neo4jGraph = incoming.neo4jGraph ?? existing?.neo4jGraph
    return { evidence: neo4jGraph ? replaceWithOfficialNeo4jGraph(incoming.evidence, neo4jGraph) : incoming.evidence }
  }
  if (incoming.neo4jGraph && existing?.evidence) return { evidence: replaceWithOfficialNeo4jGraph(existing.evidence, incoming.neo4jGraph) }
  if (incoming.neo4jGraph) return { neo4jGraph: incoming.neo4jGraph }
  if (existing?.evidence) return existing
  return incoming.outcome ? { outcome: incoming.outcome } : existing ?? {}
}
