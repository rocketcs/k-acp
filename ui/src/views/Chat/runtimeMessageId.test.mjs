import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createRuntimeUserMessage,
  toAguiRuntimeMessages,
} from '../../utils/chat/runtimeMessages.ts'

test('serializes the persisted submit-message ID into the AG-UI runtime request', () => {
  const persistedUserMessage = { id: '2082778000000000000' }
  const runtimeText = '[large-screen-image action=analyze ratio=16:9 referenceFileId=2082729274554626051]\n自动识图'

  const runtimeUserMessage = createRuntimeUserMessage(persistedUserMessage, runtimeText)
  const [aguiMessage] = toAguiRuntimeMessages([runtimeUserMessage])

  assert.deepEqual(runtimeUserMessage, {
    id: '2082778000000000000',
    role: 'user',
    content: runtimeText,
  })
  assert.deepEqual(aguiMessage, {
    id: '2082778000000000000',
    role: 'user',
    content: runtimeText,
  })
  assert.notEqual(aguiMessage?.id, 'undefined')
})
