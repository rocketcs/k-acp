import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveLoginRedirect } from './loginRedirect.ts'

test('登录后返回最初请求的 DIY 对话页面', () => {
  assert.equal(
    resolveLoginRedirect('/chat/diy/2078675601634549762'),
    '/chat/diy/2078675601634549762',
  )
})

test('无效的登录返回地址回到仪表盘', () => {
  assert.equal(resolveLoginRedirect('//external.example'), '/dashboard')
  assert.equal(resolveLoginRedirect(undefined), '/dashboard')
})
