import assert from 'node:assert/strict'
import test from 'node:test'
import { isBusinessEntity, isSourceKind, nodeVisual } from './evidenceStyles.ts'

test('maps every business/source kind to a visual', () => {
  for (const kind of ['product', 'organization', 'registration', 'base', 'concept']) {
    assert.equal(nodeVisual(kind).heading.length > 0, true, kind)
  }
  for (const kind of ['catalog_record', 'source_file', 'import_batch']) {
    assert.equal(nodeVisual(kind).shape, 'diamond', kind)
  }
})

test('unknown kinds fall back to the default entity visual', () => {
  assert.equal(nodeVisual('whatever').heading, '业务实体')
})

test('classifies business entities and source kinds', () => {
  assert.equal(isBusinessEntity('product'), true)
  assert.equal(isBusinessEntity('organization'), true)
  assert.equal(isBusinessEntity('catalog_record'), false)
  assert.equal(isSourceKind('source_file'), true)
  assert.equal(isSourceKind('import_batch'), true)
  assert.equal(isSourceKind('product'), false)
})
