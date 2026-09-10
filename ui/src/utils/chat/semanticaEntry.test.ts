import assert from 'node:assert/strict'
import test from 'node:test'
import {
  DEFAULT_SEMANTICA_AGENT_CODES,
  isSemanticaAgent,
  parseSemanticaAgentCodes,
  resolveSemanticaAgentCodes,
} from './semanticaEntry.ts'

test('parseSemanticaAgentCodes 按逗号切分并清理空白', () => {
  assert.deepEqual(parseSemanticaAgentCodes(' default-doctor , default-hospital '), [
    'default-doctor',
    'default-hospital',
  ])
  assert.deepEqual(parseSemanticaAgentCodes('a,,  ,b'), ['a', 'b'])
  assert.deepEqual(parseSemanticaAgentCodes(''), [])
  assert.deepEqual(parseSemanticaAgentCodes(undefined), [])
  assert.deepEqual(parseSemanticaAgentCodes(null), [])
})

test('resolveSemanticaAgentCodes 未配置时回退内置白名单', () => {
  assert.deepEqual(resolveSemanticaAgentCodes(undefined), DEFAULT_SEMANTICA_AGENT_CODES)
  assert.deepEqual(resolveSemanticaAgentCodes('   '), DEFAULT_SEMANTICA_AGENT_CODES)
  assert.deepEqual(resolveSemanticaAgentCodes(',,'), DEFAULT_SEMANTICA_AGENT_CODES)
})

test('resolveSemanticaAgentCodes 环境变量覆盖内置白名单', () => {
  assert.deepEqual(resolveSemanticaAgentCodes('default-tender'), ['default-tender'])
  assert.deepEqual(resolveSemanticaAgentCodes('default-doctor,default-hospital,default-tender'), [
    'default-doctor',
    'default-hospital',
    'default-tender',
  ])
})

test('isSemanticaAgent 命中各环境的医疗智能体 code', () => {
  assert.equal(isSemanticaAgent('default-doctor'), true)
  assert.equal(isSemanticaAgent('default-hospital'), true)
  assert.equal(isSemanticaAgent(' default-hospital '), true)
})

test('isSemanticaAgent 不命中其他智能体与空值', () => {
  assert.equal(isSemanticaAgent('default-tender'), false)
  assert.equal(isSemanticaAgent('default-query-data-001'), false)
  assert.equal(isSemanticaAgent(''), false)
  assert.equal(isSemanticaAgent('   '), false)
  assert.equal(isSemanticaAgent(undefined), false)
  assert.equal(isSemanticaAgent(null), false)
})

test('isSemanticaAgent 支持按传入的配置集合判断', () => {
  assert.equal(isSemanticaAgent('default-tender', ['default-tender']), true)
  assert.equal(isSemanticaAgent('default-doctor', ['default-tender']), false)
  assert.equal(isSemanticaAgent('default-doctor', []), false)
})
