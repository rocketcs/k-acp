import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveLargeScreenImageAgent } from './agent.ts'

const matchedAgent = {
  id: '2078675601634549762',
  agentCode: 'large-screen-image',
  name: '大屏生图',
}

test('仅解析固定的大屏生图 Agent Code', () => {
  assert.deepEqual(resolveLargeScreenImageAgent([matchedAgent]), matchedAgent)
  assert.equal(resolveLargeScreenImageAgent([{ ...matchedAgent, agentCode: 'other-agent' }]), null)
})

test('不存在或重复的大屏生图 Agent 时拒绝进入页面', () => {
  assert.equal(resolveLargeScreenImageAgent([]), null)
  assert.throws(
    () => resolveLargeScreenImageAgent([matchedAgent, matchedAgent]),
    /Duplicate large-screen-image agents/,
  )
})
