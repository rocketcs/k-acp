import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const skill = await readFile(new URL('./SKILL.md', import.meta.url), 'utf8')
const systemPrompt = await readFile(
  new URL('../../../docs/operations/medical-catalog-question-semantics-system-prompt.md', import.meta.url),
  'utf8',
)

function section(document, heading) {
  const start = document.indexOf(`## ${heading}`)
  assert.notEqual(start, -1, `missing ${heading} section`)
  const bodyStart = document.indexOf('\n', start) + 1
  const nextHeading = document.indexOf('\n## ', bodyStart)
  return document.slice(bodyStart, nextHeading === -1 ? undefined : nextHeading)
}

function present(document, needle) {
  assert.ok(document.includes(needle), `expected to contain: ${needle}`)
}

test('skill explains question-to-semantic parsing', () => {
  present(skill, '已发布字段白名单')
  present(skill, 'insurance_category')
  present(skill, 'registration_no')
  present(skill, '语义依据与知识图谱')
  present(skill, 'MDL 结构展示')
})

test('skill requires evidence projections and covers detail fields', () => {
  const draw = section(skill, '证据字段要求')
  // 详情意图必须一次返回完整字段，不允许"再查一次"。
  assert.match(draw, /必须一次返回完整字段集/)
  assert.match(draw, /spec_model_count/)
  assert.match(draw, /max_limit_text/)
  assert.match(draw, /source_record_id/)
})

test('skill documents per-domain return fields without mixing', () => {
  const render = section(skill, '查询结果展示')
  assert.match(render, /药品（DRUG）/)
  assert.match(render, /耗材（CONSUMABLE）/)
  // 药品带最高价格，耗材带最高限额；不混用。
  assert.match(render, /max_price_text/)
  assert.match(render, /max_limit_text/)
  assert.match(render, /不要把/)
})

test('skill separates execution path from result subgraph', () => {
  const kg = section(skill, '语义依据与知识图谱')
  assert.match(kg, /执行链路/)
  assert.match(kg, /结果子图/)
  assert.match(kg, /Wren 语义层/)
  assert.match(kg, /PostgreSQL 数据源/)
  assert.match(kg, /Neo4j 证据子图/)
  assert.match(kg, /不得/)
})

test('skill requires a distinct Neo4j evidence step after non-empty fact results', () => {
  const protocol = section(skill, '工具协议（执行顺序）')
  assert.match(protocol, /evidence_subgraph/)
  assert.match(protocol, /trace_id/)
  assert.match(protocol, /所有可能返回目录项的查询都必须在内部投影 `catalog_code`、`registration_no` 与 `source_record_id`/)
  assert.match(protocol, /`evidence_subgraph` 是有数据回答的必经步骤/)
  assert.match(protocol, /CatalogAttributeValue/)
  assert.match(protocol, /nodes \+ edges/)
  assert.match(protocol, /仅在 `query` 返回至少一条记录后，调用/)
  assert.match(protocol, /绝不修改 PostgreSQL 表格数据/)
  assert.doesNotMatch(protocol, /调用官方只读 Neo4j MCP 的 `read-cypher`/)
})

test('医保问数澄清使用文字追问而不输出 UIP 卡片', () => {
  const clarification = section(skill, '澄清规则（问题不明确时，先结构化澄清再查询）')
  assert.match(clarification, /文字.*追问/)
  assert.match(clarification, /1~3 个最关键的不确定点/)
  assert.doesNotMatch(clarification, /UIP 追问卡|`choice`|`form`|`confirm`|```json/)
  assert.match(systemPrompt, /文字.*追问/)
  assert.doesNotMatch(systemPrompt, /UIP choice\/form|`choice`|`form`|```uip/)
})

test('医保问数将用户指定的返回条数传给查询上限', () => {
  const protocol = section(skill, '工具协议（执行顺序）')
  assert.match(protocol, /最多返回\s*N\s*条/)
  assert.match(protocol, /`limit = N`/)
  assert.match(protocol, /10、20、50、100/)
  assert.match(systemPrompt, /最多返回\s*N\s*条/)
  assert.match(systemPrompt, /`limit = N`/)
})
