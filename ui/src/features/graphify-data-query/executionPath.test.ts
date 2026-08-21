import assert from 'node:assert/strict'
import test from 'node:test'
import { stagesForEvidence } from './executionPath.ts'
import type { GraphifyEvidenceEnvelope } from './types.ts'

const evidence = {
  status: 'executed',
  trace_id: 'trace-neo4j',
  dataset_id: 'medical_catalog',
  question: '查询目录项',
  result: { columns: [], rows: [], truncated: false },
  execution_path: {
    question: '查询目录项', intent: '目录查询', models: [], columns: [], source_record_count: 3, truncated: false,
    stages: [
      { step: 'postgres', system: 'postgres', title: 'PostgreSQL 数据源', detail: '查询业务事实', icon: 'postgres' },
      { step: 'neo4j', system: 'neo4j', title: 'Neo4j 语义图谱', detail: '图谱节点 12 个、关系 11 条（含结果驱动兜底）', icon: 'neo4j' },
    ],
  },
  semantic_context: { graph_version: 'v1', recommended_models: [], recommended_columns: [], rules: [], provenance: {} },
  evidence: {
    source_record_ids: ['record-1'],
    nodes: [
      { id: 'catalog-1', label: '目录项', kind: 'catalog_record' },
      { id: 'enterprise-1', label: '生产企业', kind: 'organization' },
      { id: 'file-1', label: '来源文件', kind: 'source_file' },
      { id: 'batch-1', label: '导入批次', kind: 'import_batch' },
    ],
    edges: [
      { id: '1', source: 'catalog-1', target: 'enterprise-1', label: 'MANUFACTURED_BY', kind: 'business' },
      { id: '2', source: 'file-1', target: 'catalog-1', label: 'CONTAINS_RECORD', kind: 'provenance' },
      { id: '3', source: 'batch-1', target: 'file-1', label: 'CONTAINS_SOURCE', kind: 'provenance' },
    ],
  },
} satisfies GraphifyEvidenceEnvelope

test('replaces stale Neo4j execution detail with this answer official graph counts', () => {
  const neo4j = stagesForEvidence(evidence).find((stage) => stage.system === 'neo4j')

  assert.deepEqual(neo4j, {
    step: 'neo4j',
    system: 'neo4j',
    title: '官方只读 Neo4j 证据子图',
    detail: '本轮返回 4 个节点、3 条关系',
    icon: 'neo4j',
  })
})
