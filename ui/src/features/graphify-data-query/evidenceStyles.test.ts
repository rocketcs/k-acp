import assert from 'node:assert/strict'
import test from 'node:test'
import { isBusinessEntity, isSourceKind, nodeVisual } from './evidenceStyles.ts'

const visualFor = (kind: string, domain?: string) => nodeVisual({ kind, domain })

test('maps every business/source kind to a visual', () => {
  for (const kind of ['product', 'organization', 'registration', 'base', 'concept']) {
    assert.equal(visualFor(kind).heading.length > 0, true, kind)
  }
  for (const kind of ['catalog_record', 'source_file', 'import_batch']) {
    assert.equal(visualFor(kind).shape, 'diamond', kind)
  }
})

test('product heading reflects the catalog domain', () => {
  assert.equal(visualFor('product', 'DRUG').heading, '药品目录项')
  assert.equal(visualFor('product', 'CONSUMABLE').heading, '耗材目录项')
  assert.equal(visualFor('product', 'SERVICE').heading, '医疗服务项目')
  assert.equal(visualFor('product', 'DIAGNOSIS').heading, '诊疗项目')
  assert.equal(visualFor('product').heading, '耗材目录项')
})

test('unknown kinds fall back to the default entity visual', () => {
  assert.equal(visualFor('whatever').heading, '业务实体')
})

test('prototype members do not leak through the kind lookup', () => {
  assert.equal(visualFor('__proto__').heading, '业务实体')
  assert.equal(visualFor('toString').heading, '业务实体')
})

test('classifies business entities and source kinds', () => {
  assert.equal(isBusinessEntity('product'), true)
  assert.equal(isBusinessEntity('organization'), true)
  assert.equal(isBusinessEntity('catalog_record'), false)
  assert.equal(isSourceKind('source_file'), true)
  assert.equal(isSourceKind('import_batch'), true)
  assert.equal(isSourceKind('product'), false)
})