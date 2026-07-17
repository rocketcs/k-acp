import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildOutputInstruction,
  extractPlaceholders,
  renderQuestionTemplate,
} from './questionTemplate.ts'

test('按出现顺序提取不重复的占位符', () => {
  assert.deepEqual(
    extractPlaceholders('查 {{公司}} 在 {{时间}} 的 {{公司}} 数据'),
    ['公司', '时间'],
  )
})

test('保留未替换占位符并报告缺失值', () => {
  assert.deepEqual(
    renderQuestionTemplate('查 {{公司}} 在 {{时间}} 的数据', { 公司: '金智维' }),
    {
      text: '查 金智维 在 {{时间}} 的数据',
      missing: ['时间'],
    },
  )
})

test('柱状图输出追加稳定的 VEP 协议', () => {
  const instruction = buildOutputInstruction('ECHARTS_BAR')
  assert.match(instruction, /VEP/)
  assert.match(instruction, /chartType/)
  assert.match(instruction, /bar/)
})

test('JSON 输出要求只返回合法 JSON', () => {
  assert.match(buildOutputInstruction('JSON'), /合法 JSON/)
})

test('可选占位符未填写时替换为空且不报告缺失', () => {
  assert.deepEqual(
    renderQuestionTemplate('查询 {{指标}}。补充：{{补充要求}}', { 指标: '收入' }, ['补充要求']),
    { text: '查询 收入。补充：', missing: [] },
  )
})
