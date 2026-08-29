import assert from 'node:assert/strict'
import test from 'node:test'
import { DEFAULT_GRAPH_EXPLORER_URL, resolveGraphExplorerUrl } from './graphExplorer.ts'

test('resolveGraphExplorerUrl falls back to the test Graph Explorer on 137', () => {
  const expected = 'http://kg.demo.pine.kingsware.cn:6800'
  assert.equal(DEFAULT_GRAPH_EXPLORER_URL, expected)
  assert.equal(resolveGraphExplorerUrl(), expected)
  assert.equal(resolveGraphExplorerUrl('   '), expected)
})

test('resolveGraphExplorerUrl trims configuration and removes trailing slashes', () => {
  assert.equal(resolveGraphExplorerUrl(' https://graph.example/ '), 'https://graph.example')
  assert.equal(resolveGraphExplorerUrl('https://graph.example///'), 'https://graph.example')
})
