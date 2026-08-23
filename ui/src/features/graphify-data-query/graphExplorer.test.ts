import assert from 'node:assert/strict'
import test from 'node:test'
import { DEFAULT_GRAPH_EXPLORER_URL, resolveGraphExplorerUrl } from './graphExplorer.ts'

test('resolveGraphExplorerUrl falls back to the local Graph Explorer', () => {
  assert.equal(resolveGraphExplorerUrl(), DEFAULT_GRAPH_EXPLORER_URL)
  assert.equal(resolveGraphExplorerUrl('   '), DEFAULT_GRAPH_EXPLORER_URL)
})

test('resolveGraphExplorerUrl trims configuration and removes trailing slashes', () => {
  assert.equal(resolveGraphExplorerUrl(' https://graph.example/ '), 'https://graph.example')
  assert.equal(resolveGraphExplorerUrl('https://graph.example///'), 'https://graph.example')
})
