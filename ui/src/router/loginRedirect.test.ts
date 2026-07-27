import assert from 'node:assert/strict'
import test from 'node:test'
import { buildLoginRedirectUrl, resolveLoginRedirect } from './loginRedirect.ts'

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

test('为任意 DIY 对话地址生成保留回跳参数的登录链接', () => {
  assert.equal(
    buildLoginRedirectUrl('/chat/diy/2080000000000000001'),
    '/#/login?redirect=%2Fchat%2Fdiy%2F2080000000000000001',
  )
})
