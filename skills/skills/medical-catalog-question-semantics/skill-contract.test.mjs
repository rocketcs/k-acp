import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const skill = await readFile(new URL('./SKILL.md', import.meta.url), 'utf8')

function section(document, heading) {
  const start = document.indexOf(`## ${heading}`)
  assert.notEqual(start, -1, `missing ${heading} section`)
  const bodyStart = document.indexOf('\n', start) + 1
  const nextHeading = document.indexOf('\n## ', bodyStart)
  return document.slice(bodyStart, nextHeading === -1 ? undefined : nextHeading)
}

function markdownTableRows(documentSection) {
  return documentSection.split('\n')
    .filter((line) => /^\|.+\|$/.test(line.trim()) && !/^\|\s*-/.test(line.trim()))
    .map((line) => line.split('|').slice(1, -1).map((cell) => cell.trim()))
}

test('medical catalog skill requires evidence projections for relationship queries', () => {
  const rows = markdownTableRows(section(skill, '证据字段要求'))
  assert.deepEqual(rows, [
    ['问题语义', '`evidence_columns`'],
    ['注册备案号/批准文号', '`catalog_name`, `registration_no`, `source_record_id`'],
    ['药品/耗材企业', '`catalog_name`, `manufacturer` or `consumable_enterprise`, `source_record_id`'],
    ['耗材分类', '`catalog_name`, `category_level_1`, `category_level_2`, `category_level_3`, `source_record_id`'],
    ['支付类别/限额/价格', '`catalog_name`, asked field, `source_record_id`; prices also require `price_semantics`'],
    ['有效期/政策', '`catalog_name`, `valid_from`, `valid_to` or `policy_no`, `source_record_id`'],
  ])
})
