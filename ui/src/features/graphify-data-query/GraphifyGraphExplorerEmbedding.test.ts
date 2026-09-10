import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const route = readFileSync(new URL('./GraphifyDataQueryChat.vue', import.meta.url), 'utf8')
const modal = readFileSync(new URL('./GraphExplorerModal.vue', import.meta.url), 'utf8')
const chat = readFileSync(new URL('../../views/Chat/index.vue', import.meta.url), 'utf8')
const chatMain = readFileSync(new URL('../../components/chat/ChatMain.vue', import.meta.url), 'utf8')
const welcome = readFileSync(new URL('../../components/chat/Welcome.vue', import.meta.url), 'utf8')

test('医保问数路由 enables a route-scoped full-page Graph Explorer modal', () => {
  assert.match(route, /import GraphExplorerModal from '\.\/GraphExplorerModal\.vue'/)
  assert.match(route, /const graphExplorerOpen = ref\(false\)/)
  assert.match(route, /:show-graph-explorer="true"/)
  assert.match(route, /@graph-explorer="graphExplorerOpen = true"/)
  assert.match(route, /<GraphExplorerModal v-model:open="graphExplorerOpen" \/>/)
})

test('Graph Explorer modal embeds the complete external page in an iframe', () => {
  assert.match(modal, /resolveGraphExplorerUrl\(import\.meta\.env\.VITE_GRAPH_EXPLORER_URL\)/)
  assert.match(modal, /<iframe[\s\S]*:src="graphExplorerUrl"[\s\S]*title="医保数据管理"/)
  assert.match(modal, /<span>医保数据管理<\/span>/)
  assert.match(modal, /正在加载数据管理/)
  assert.match(modal, /关闭数据管理/)
  assert.match(modal, /allow="fullscreen"/)
  assert.match(modal, /role="dialog"/)
  assert.match(modal, /aria-modal="true"/)
  assert.match(modal, /@keydown\.esc="close"/)
})

test('Graph Explorer action is forwarded through the shared chat header', () => {
  for (const content of [chat, chatMain, welcome]) {
    assert.match(content, /showGraphExplorer\?: boolean/)
    assert.match(content, /graphExplorer/)
  }
  assert.match(chatMain, /class="chat-data-management-btn"/)
  assert.match(chatMain, /<span>数据管理<\/span>/)
  assert.match(chatMain, /@click="\$emit\('graphExplorer'\)"/)
  assert.doesNotMatch(chatMain, /:show-graph-explorer="showGraphExplorer"/)
})

test('Graph Explorer action is prominent in the chat header', () => {
  assert.match(chatMain, /DatabaseOutlined/)
  assert.match(route, /:deep\(\.graphify-data-query-chat \.chat-main-header\)\s*\{[\s\S]*?position:\s*relative/)
  assert.match(route, /:deep\(\.graphify-data-query-chat \.chat-data-management-btn\)\s*\{[\s\S]*?min-height:\s*42px[\s\S]*?background:\s*#2d7fbd[\s\S]*?color:\s*#fff[\s\S]*?font-weight:\s*600/)
})
