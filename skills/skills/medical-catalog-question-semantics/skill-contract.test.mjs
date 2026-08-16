import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const skill = await readFile(new URL('./SKILL.md', import.meta.url), 'utf8')

test('medical catalog skill requires evidence projections for relationship queries', () => {
  assert.match(skill, /evidence_columns/)
  assert.match(skill, /registration_no.*source_record_id/s)
  assert.match(skill, /consumable_enterprise.*source_record_id/s)
  assert.match(skill, /category_level_1.*source_record_id/s)
})
