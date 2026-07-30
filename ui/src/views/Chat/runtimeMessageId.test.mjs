import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const chatSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')
const streamSource = readFileSync(new URL('../../composables/chat/useChatStream.ts', import.meta.url), 'utf8')
const submitMessageSource = chatSource.slice(
  chatSource.indexOf('async function submitMessage'),
  chatSource.indexOf('\nfunction withAttachmentPrefix'),
)

test('forwards the persisted user-message ID to the AG-UI runtime mapper', () => {
  assert.match(
    submitMessageSource,
    /await sendMessage\(\s*options\.runtimeText,\s*\[\{\s*id:\s*userMsg\.data\.data\.id,\s*role:\s*'user',\s*content:\s*options\.runtimeText\s*}\] as ChatMessageVO\[\],/s,
  )
  assert.match(streamSource, /id:\s*String\(m\.id\),/)
})
