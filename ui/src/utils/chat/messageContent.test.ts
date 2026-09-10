import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CHAT_FILE_CONTENT_SEPARATOR,
  prependChatAttachmentContent,
  splitChatAttachmentContent,
} from './messageContent.ts'

test('round-trips attachment metadata and control body', () => {
  const files = [{ id: '2082729274554626051', name: 'reference.jpg', extension: 'jpg', size: '1 KB' }]
  const text = '[large-screen-image action=analyze referenceFileId=2082729274554626051]'
  const content = prependChatAttachmentContent(files, text)

  assert.deepEqual(splitChatAttachmentContent(content), {
    attachmentPrefix: JSON.stringify({ files }) + CHAT_FILE_CONTENT_SEPARATOR,
    files,
    text,
  })
})

test('keeps malformed prefixes as ordinary text', () => {
  const content = '{not-json}' + CHAT_FILE_CONTENT_SEPARATOR + 'hello'

  assert.deepEqual(splitChatAttachmentContent(content), { attachmentPrefix: '', files: [], text: content })
})
