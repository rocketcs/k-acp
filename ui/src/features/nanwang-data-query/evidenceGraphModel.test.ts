import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphifyEvidenceEnvelope } from './types'
import { evidenceGraphCounts, evidenceGraphModel, type GraphElementDefinition } from './evidenceGraphModel.ts'

const envelope: GraphifyEvidenceEnvelope = {
  status: 'executed', trace_id: 't1', dataset_id: 'medical_catalog', question: 'q',
  result: { columns: ['catalog_name'], rows: [{ catalog_name: '覆膜气管支架' }], truncated: false },
  semantic_context: { graph_version: 'v1', recommended_models: ['medical_catalog'], recommended_columns: ['catalog_name'], rules: [], provenance: {} },
  evidence: {
    source_record_ids: ['consumable:main:C1'],
    nodes: [
      { id: 'model', label: 'medical_catalog', kind: 'model' },
      { id: 'product', label: '覆膜气管支架', kind: 'product' },
      { id: 'registration', label: '国械注准20173134669', kind: 'registration' },
      { id: 'catalog_record', label: '…:6:2', kind: 'catalog_record' },
      { id: 'record', label: 'record:C1', kind: 'record' },
      { id: 'field', label: 'catalog_name', kind: 'entity' },
    ],
    edges: [
      { id: 'q1', source: 'model', target: 'product', label: '查询返回', kind: 'query' },
      { id: 'b1', source: 'product', target: 'registration', label: '对应', kind: 'business' },
      { id: 'p1', source: 'product', target: 'catalog_record', label: '证据支持', kind: 'provenance' },
      { id: 'p2', source: 'catalog_record', target: 'record', label: '来源记录', kind: 'provenance' },
      { id: 's1', source: 'model', target: 'field', label: '字段来源', kind: 'semantic' },
    ],
  },
}

const ids = (elements: GraphElementDefinition[]) => elements.filter((e) => e.data?.kind && !e.data?.source).map((e) => String(e.data!.id)).sort()

test('focused view: business + provenance only, no model/record/query', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  assert.deepEqual(ids(elements), ['catalog_record', 'product', 'registration'])
  const kinds = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.kind))
  assert.equal(kinds.includes('model'), false)
  assert.equal(kinds.includes('record'), false)
  const edgeKinds = elements.filter((e) => e.data?.source).map((e) => String(e.data!.kind))
  assert.equal(edgeKinds.includes('query'), false)
})

test('full view with fields includes model, semantic field and their edges', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'full', showFields: true })
  const all = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.id))
  assert.ok(all.includes('model') && all.includes('field') && all.includes('product'))
  const edgeKinds = elements.filter((e) => e.data?.source).map((e) => String(e.data!.kind))
  assert.ok(edgeKinds.includes('semantic'))
})

test('full view without fields omits semantic entities', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'full', showFields: false })
  const all = elements.filter((e) => !e.data?.source).map((e) => String(e.data!.id))
  assert.equal(all.includes('field'), false)
})

test('full view keeps the model attached via a business edge, not an orphan', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'full', showFields: false })
  const modelEdges = elements
    .filter((e) => e.data?.source === 'model')
    .map((e) => ({ kind: String(e.data!.kind), label: String(e.data!.label) }))
  assert.ok(modelEdges.some((e) => e.kind === 'business' && e.label === '相关'), 'model must attach to the core entity as a business relation')
  assert.equal(modelEdges.some((e) => e.kind === 'query'), false, 'query-action edges stay hidden')
})

test('core entity node label is the business name, not a query-action heading', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  const product = elements.find((e) => e.data?.id === 'product')
  assert.equal(product?.data?.label, '覆膜气管支架')
  assert.equal(String(product?.data?.label).includes('查询'), false)
  const registration = elements.find((e) => e.data?.id === 'registration')
  assert.equal(String(registration?.data?.label).includes('\n'), true, 'relation-type entities keep heading + value')
})

test('long node labels receive a smaller per-node font size to remain inside the node', () => {
  const longLabelEnvelope: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      ...envelope.evidence,
      nodes: envelope.evidence.nodes.map((node) => node.id === 'registration'
        ? { ...node, label: '杭州民生药业股份有限公司' }
        : node),
    },
  }
  const elements = evidenceGraphModel(longLabelEnvelope, { viewMode: 'focused', showFields: false })
  const registration = elements.find((element) => element.data?.id === 'registration')
  const product = elements.find((element) => element.data?.id === 'product')

  assert.ok(Number(registration?.data?.fontSize) < Number(product?.data?.fontSize))
  assert.ok(Number(registration?.data?.fontSize) >= 8, 'long labels keep a readable minimum size')
})

test('nodes carry positions from dagre', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  const product = elements.find((e) => e.data?.id === 'product')
  assert.ok(product?.position, 'product node must have dagre position')
  assert.ok(Number.isFinite(product!.position!.x))
})

test('uses readable semantic labels for business and provenance edges', () => {
  const elements = evidenceGraphModel(envelope, { viewMode: 'focused', showFields: false })
  const labels = elements.filter((item) => item.data?.source).map((item) => String(item.data?.label))
  assert.ok(labels.includes('对应注册备案'))
  assert.ok(labels.includes('原始记录佐证'))
  assert.equal(labels.includes('业务关联'), false)
})

test('never stores raw internal node labels in rendered labels or tooltips', () => {
  const unsafeEnvelope: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      ...envelope.evidence,
      nodes: envelope.evidence.nodes.map((node) => node.id === 'catalog_record'
        ? { ...node, label: '耗材谈判记录·导入批次20260816' }
        : node.id === 'registration'
          ? { ...node, label: 'raw.registration_record' }
          : node),
    },
  }
  const elements = evidenceGraphModel(unsafeEnvelope, { viewMode: 'focused', showFields: false })
  const nodeText = elements
    .filter((item) => !item.data?.source)
    .flatMap((item) => [String(item.data?.label), String(item.data?.fullLabel)])
    .join('\n')

  assert.doesNotMatch(nodeText, /耗材谈判记录·导入批次20260816|raw\.registration_record/)
  assert.match(nodeText, /原始目录记录|注册备案号/)
})

test('relationship-less (isolated) nodes are hidden from the rendered graph', () => {
  const isoEnvelope: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      ...envelope.evidence,
      nodes: [
        ...envelope.evidence.nodes,
        // 一个没有任何边的业务节点：应被隐藏
        { id: 'orphan', label: '孤立记录', kind: 'organization' },
      ],
    },
  }
  const nodes = evidenceGraphModel(isoEnvelope, { viewMode: 'focused', showFields: false })
    .filter((e) => !e.data?.source)
    .map((e) => String(e.data!.id))
  assert.equal(nodes.includes('orphan'), false, 'node with no relationship must not be rendered')
  // 有关联关系的节点不受影响
  assert.ok(nodes.includes('product') && nodes.includes('registration') && nodes.includes('catalog_record'))
})

test('isolated nodes are excluded from summary counts', () => {
  const isoEnvelope: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      ...envelope.evidence,
      nodes: [
        ...envelope.evidence.nodes,
        { id: 'orphan', label: '孤立记录', kind: 'organization' },
      ],
    },
  }
  const { nodeCount, edgeCount, totalCount } = evidenceGraphCounts(isoEnvelope, {
    viewMode: 'focused',
    showFields: false,
  })
  // focused 视图关联节点：product / registration / catalog_record；孤立或查询过程节点不计入
  assert.equal(nodeCount, 3)
  assert.equal(edgeCount, 2)
  // totalCount 为全部非查询过程业务节点（含被隐藏的孤立节点），驱动「点击展开」提示
  // 基础 envelope 中非 record/source 节点：model/product/registration/catalog_record/field + orphan = 6
  assert.equal(totalCount, 6)
})

test('limits the graph to query roots and two relationship levels', () => {
  const deep: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      nodes: [
        { id: 'model', label: '医保目录', kind: 'model' },
        { id: 'root', label: '氯雷他定', kind: 'product' },
        { id: 'level-1', label: '收费类别', kind: 'concept' },
        { id: 'level-2', label: '甲类', kind: 'attribute' },
        { id: 'level-3', label: '更深层节点', kind: 'attribute' },
      ],
      edges: [
        { id: 'query', source: 'model', target: 'root', label: '查询返回', kind: 'query' },
        { id: 'one', source: 'root', target: 'level-1', label: '分类', kind: 'business' },
        { id: 'two', source: 'level-1', target: 'level-2', label: '取值', kind: 'attribute' },
        { id: 'three', source: 'level-2', target: 'level-3', label: '下级', kind: 'attribute' },
      ],
      source_record_ids: [],
    },
  }
  const selected = evidenceGraphModel(deep, { viewMode: 'focused', showFields: false, maxDepth: 2 })
  const ids = selected.filter((element) => element.data?.source === undefined).map((element) => element.data?.id)
  assert.deepEqual(ids.sort(), ['level-1', 'level-2', 'root'])
  assert.equal(selected.some((element) => element.data?.id === 'level-3'), false)
})

test('caps the rendered projection at maxNodes while keeping the nearest business nodes', () => {
  const broad: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      nodes: [
        { id: 'root', label: '氯雷他定', kind: 'product' },
        ...Array.from({ length: 120 }, (_, index) => ({
          id: `category-${index}`,
          label: `收费类别-${index}`,
          kind: 'concept' as const,
        })),
      ],
      edges: Array.from({ length: 120 }, (_, index) => ({
        id: `edge-${index}`,
        source: 'root',
        target: `category-${index}`,
        label: '收费类别',
        kind: 'business' as const,
      })),
      source_record_ids: [],
    },
  }

  const elements = evidenceGraphModel(broad, {
    viewMode: 'focused',
    showFields: false,
    maxDepth: 2,
    maxNodes: 100,
  })
  const nodes = elements.filter((element) => element.data?.source === undefined)
  assert.equal(nodes.length, 100)
  assert.ok(nodes.some((node) => node.data?.id === 'root'))
  assert.equal(nodes.some((node) => node.data?.id === 'category-98'), true)
  assert.equal(nodes.some((node) => node.data?.id === 'category-99'), false)
})

test('a node reachable only through a dropped query edge becomes isolated and hides', () => {
  const qEnv: GraphifyEvidenceEnvelope = {
    ...envelope,
    evidence: {
      ...envelope.evidence,
      nodes: [
        ...envelope.evidence.nodes,
        { id: 'extra_product', label: '另一产品', kind: 'product' },
      ],
      edges: [
        ...envelope.evidence.edges,
        // 该产品只通过 model 的 query 边相连；query 边不渲染 → 变成孤立节点
        { id: 'q2', source: 'model', target: 'extra_product', label: '查询返回', kind: 'query' },
      ],
    },
  }
  const nodes = evidenceGraphModel(qEnv, { viewMode: 'focused', showFields: false })
    .filter((e) => !e.data?.source)
    .map((e) => String(e.data!.id))
  assert.equal(nodes.includes('extra_product'), false)
})
