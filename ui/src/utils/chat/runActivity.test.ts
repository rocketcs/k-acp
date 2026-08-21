import assert from 'node:assert/strict'
import test from 'node:test'
import {
  aggregateRunActivities,
  shouldShowLegacyToolCall,
  shouldShowChatInput,
  shouldShowChoiceCustomInput,
  shouldShowRunActivity,
  shouldShowRunWaiting,
} from './runActivity.ts'

test('执行卡仅在尚未开始输出正文时显示', () => {
  assert.equal(shouldShowRunActivity(true, true, false), true)
  assert.equal(shouldShowRunActivity(true, true, true), false)
})

test('运行结束后不显示执行卡', () => {
  assert.equal(shouldShowRunActivity(true, false, false), false)
})

test('阶段性文本后维持紧凑等待状态', () => {
  assert.equal(shouldShowRunWaiting(true, true, true), true)
  assert.equal(shouldShowRunWaiting(true, true, false), false)
  assert.equal(shouldShowRunWaiting(false, true, true), false)
})

test('DIY 运行时隐藏输入框，普通 Chat 保持输入框', () => {
  assert.equal(shouldShowChatInput(true, true), false)
  assert.equal(shouldShowChatInput(true, false), true)
  assert.equal(shouldShowChatInput(false, true), true)
})

test('DIY 追问卡隐藏自定义输入，普通 Chat 保留协议行为', () => {
  assert.equal(shouldShowChoiceCustomInput(true, true), false)
  assert.equal(shouldShowChoiceCustomInput(false, true), true)
  assert.equal(shouldShowChoiceCustomInput(false, false), false)
})

test('思考卡与工具条只改变 DIY Chat，普通 Chat 保持原样', () => {
  assert.equal(shouldShowRunActivity(false, true, false), false)
  assert.equal(shouldShowLegacyToolCall(false, false), true)
  assert.equal(shouldShowLegacyToolCall(true, false), false)
  assert.equal(shouldShowLegacyToolCall(true, true), true)
})

test('连续同类工具调用聚合为一个业务步骤', () => {
  const steps = aggregateRunActivities([
    { id: '1', name: 'wren_query', args: '', status: 'completed', startTime: 1, elapsed: 500 },
    { id: '2', name: 'wren_query', args: '', status: 'running', startTime: 2 },
    { id: '3', name: 'wren_models', args: '', status: 'completed', startTime: 3, elapsed: 100 },
  ])

  assert.deepEqual(steps, [
    { id: '1', label: '查询业务数据', status: 'running', count: 2, elapsed: 500 },
    { id: '3', label: '准备分析能力', status: 'completed', count: 1, elapsed: 100 },
  ])
})

test('待执行的预置步骤保持等待状态', () => {
  const steps = aggregateRunActivities([
    { id: 'semantic', name: '语义解析', status: 'pending', startTime: 1 },
    { id: 'query', name: '执行查询', status: 'pending', startTime: 1 },
  ])

  assert.deepEqual(steps, [
    { id: 'semantic', label: '语义解析', status: 'pending', count: 1, elapsed: undefined },
    { id: 'query', label: '执行查询', status: 'pending', count: 1, elapsed: undefined },
  ])
})
