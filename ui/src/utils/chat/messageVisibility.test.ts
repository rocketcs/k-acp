import assert from 'node:assert/strict'
import test from 'node:test'
import { shouldDisplayChatMessage } from './messageVisibility.ts'

test('hides persisted tool messages only when the route requests a single result table', () => {
  assert.equal(shouldDisplayChatMessage('tool', true), false)
  assert.equal(shouldDisplayChatMessage('assistant', true), true)
  assert.equal(shouldDisplayChatMessage('tool', false), true)
})
