import type { GraphifyEvidenceEnvelope } from './types'

export type ExecutionPathStage = NonNullable<GraphifyEvidenceEnvelope['execution_path']>['stages'][number]

const FALLBACK_STAGES: ExecutionPathStage[] = [
  { step: 'agent', system: 'agent', title: 'Agent 语义分析', detail: '识别医保目录查询意图', icon: 'analysis' },
  { step: 'wren_mdl', system: 'wren_mdl', title: 'Wren 语义层（MDL）', detail: '匹配业务模型与查询字段', icon: 'mdl' },
  { step: 'postgres', system: 'postgres', title: 'PostgreSQL 数据源', detail: '查询统一目录视图 medical_catalog', icon: 'postgres' },
  { step: 'neo4j', system: 'neo4j', title: '官方只读 Neo4j 证据子图', detail: '本轮未返回可展示的关系图', icon: 'neo4j' },
  { step: 'result', system: 'result', title: '查询结果', detail: '汇总本轮命中记录', icon: 'result' },
]

/**
 * 服务端旧证据信封可能含有结果驱动图谱的历史文案。
 * 当本回答已绑定官方 Neo4j 关系时，始终以实际 nodes + edges 覆盖该阶段。
 */
export function stagesForEvidence(evidence: GraphifyEvidenceEnvelope): ExecutionPathStage[] {
  const path = evidence.execution_path
  if (!path) return []

  const stages = path.stages?.length ? path.stages : FALLBACK_STAGES.map((stage) => ({ ...stage }))
  const nodes = evidence.evidence.nodes.length
  const edges = evidence.evidence.edges.length
  if (!nodes || !edges) return stages

  const officialNeo4jStage: ExecutionPathStage = {
    step: 'neo4j',
    system: 'neo4j',
    title: '官方只读 Neo4j 证据子图',
    detail: `本轮返回 ${nodes} 个节点、${edges} 条关系`,
    icon: 'neo4j',
  }
  const neo4jIndex = stages.findIndex((stage) => stage.system === 'neo4j' || stage.step === 'neo4j')
  return neo4jIndex < 0
    ? [...stages, officialNeo4jStage]
    : stages.map((stage, index) => index === neo4jIndex ? officialNeo4jStage : stage)
}
