import assert from 'node:assert/strict'
import test from 'node:test'
import { DEFAULT_GRAPH_EXPLORER_API_BASE, DEFAULT_GRAPH_EXPLORER_URL, resolveGraphExplorerApiBase, resolveGraphExplorerUrl } from './graphExplorer.ts'

test('resolveGraphExplorerUrl falls back to the Nanwang graph explorer page', () => {
  const expected = '/web/nanwang-graph/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json&api=/api/nanwang-graph'
  assert.equal(DEFAULT_GRAPH_EXPLORER_URL, expected)
  assert.equal(resolveGraphExplorerUrl(), expected)
  assert.equal(resolveGraphExplorerUrl('   '), expected)
})

test('resolveGraphExplorerUrl trims configuration and removes trailing slashes', () => {
  assert.equal(resolveGraphExplorerUrl(' https://graph.example/ '), 'https://graph.example')
  assert.equal(resolveGraphExplorerUrl('https://graph.example///'), 'https://graph.example')
})

test('resolveGraphExplorerApiBase extracts the origin for API requests', () => {
  assert.equal(DEFAULT_GRAPH_EXPLORER_API_BASE, '/api/nanwang-graph')
  assert.equal(resolveGraphExplorerApiBase('https://graph.example/path/to/page'), 'https://graph.example')
  assert.equal(resolveGraphExplorerApiBase('http://graph.example///'), 'http://graph.example')
  assert.equal(resolveGraphExplorerApiBase(), DEFAULT_GRAPH_EXPLORER_API_BASE)
})
