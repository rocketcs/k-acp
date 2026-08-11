import assert from 'node:assert/strict'
import test from 'node:test'
import { formatTracingUserLabel, tracingStatusTone } from './sessionTracing.ts'

test('用户标签优先显示昵称、账号和邮箱', () => {
  assert.equal(
    formatTracingUserLabel({ userId: '1', nickname: '管理员', username: 'admin', email: 'admin@gmail.com' }),
    '管理员 / admin / admin@gmail.com',
  )
})

test('账号缺失时仍显示可识别用户', () => {
  assert.equal(
    formatTracingUserLabel({ userId: '9', nickname: null, username: null, email: null }),
    '未知用户 / 9',
  )
})

test('列表状态只映射结果表状态', () => {
  assert.equal(tracingStatusTone('COMPLETE'), 'success')
  assert.equal(tracingStatusTone('PARTIAL'), 'warning')
  assert.equal(tracingStatusTone('ERROR'), 'error')
})
