import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatChatAttachmentRejectionWarning,
  useChatAttachments,
  validateChatAttachmentSelection,
} from './useChatAttachments.ts'

const file = (name: string, type = '', size = 1) => ({ name, type, size })

test('keeps matching extensions and rejects non-matching extensions', () => {
  const result = validateChatAttachmentSelection(
    [file('photo.PNG'), file('notes.txt')],
    0,
    ['png', 'jpg'],
  )

  assert.deepEqual(result.accepted.map((item) => item.name), ['photo.PNG'])
  assert.deepEqual(result.rejected, [{ file: file('notes.txt'), reason: 'extension' }])
})

test('uses the legacy extension warning when no attachment policy is active', () => {
  const result = validateChatAttachmentSelection(
    [file('notes.txt'), file('archive.zip')],
    0,
    ['png', 'jpg'],
  )

  assert.equal(
    formatChatAttachmentRejectionWarning(result.rejected),
    '以下文件类型不允许上传: notes.txt, archive.zip',
  )
})

test('uses detailed rejection reasons when an attachment policy is active', () => {
  const result = validateChatAttachmentSelection(
    [file('notes.txt')],
    0,
    ['png'],
    { maxFileCount: 4 },
  )

  assert.equal(
    formatChatAttachmentRejectionWarning(result.rejected, { maxFileCount: 4 }),
    '以下文件未上传: notes.txt（extension）',
  )
})

test('accepts only configured raster image MIME types within the 30 MiB limit', () => {
  const result = validateChatAttachmentSelection(
    [
      file('photo.png', 'image/png', 30 * 1024 * 1024),
      file('photo.jpeg', 'image/jpeg', 1024),
      file('photo.webp', 'image/webp', 1024),
      file('document.pdf', 'application/pdf', 1024),
      file('animated.gif', 'image/gif', 1024),
      file('vector.svg', 'image/svg+xml', 1024),
      file('large.jpg', 'image/jpeg', 30 * 1024 * 1024 + 1),
    ],
    0,
    undefined,
    {
      allowedMimeTypes: ['image/png', 'image/jpeg', 'image/webp'],
      maxFileSizeBytes: 30 * 1024 * 1024,
    },
  )

  assert.deepEqual(result.accepted.map((item) => item.name), ['photo.png', 'photo.jpeg', 'photo.webp'])
  assert.deepEqual(result.rejected.map((item) => ({ name: item.file.name, reason: item.reason })), [
    { name: 'document.pdf', reason: 'mimeType' },
    { name: 'animated.gif', reason: 'mimeType' },
    { name: 'vector.svg', reason: 'mimeType' },
    { name: 'large.jpg', reason: 'size' },
  ])
})

test('lets a large-screen policy allow jpg even when the generic agent capability only lists jpeg', () => {
  const result = validateChatAttachmentSelection(
    [file('reference.jpg', 'image/jpeg', 1024)],
    0,
    ['png', 'jpeg', 'gif', 'webp'],
    {
      allowedExtensions: ['png', 'jpg', 'jpeg', 'webp'],
      allowedMimeTypes: ['image/png', 'image/jpeg', 'image/webp'],
    },
  )

  assert.deepEqual(result.accepted.map((item) => item.name), ['reference.jpg'])
  assert.deepEqual(result.rejected, [])
})

test('uses exact MIME types in the picker accept attribute when configured', () => {
  const attachments = useChatAttachments({
    getFiles: () => [],
    setFiles: () => {},
    getAllowedTypes: () => ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'],
    getAttachmentPolicy: () => ({
      allowedMimeTypes: ['image/png', 'image/jpeg', 'image/webp'],
    }),
  })

  assert.equal(attachments.fileAcceptAttr(), 'image/png,image/jpeg,image/webp')
})

test('applies max file count cumulatively in selection order', () => {
  const result = validateChatAttachmentSelection(
    [file('first.png'), file('second.png'), file('third.png')],
    2,
    ['png'],
    { maxFileCount: 4 },
  )

  assert.deepEqual(result.accepted.map((item) => item.name), ['first.png', 'second.png'])
  assert.deepEqual(result.rejected.map((item) => ({ name: item.file.name, reason: item.reason })), [
    { name: 'third.png', reason: 'count' },
  ])
})

test('removes temporary items when the deferred attachment API import fails', async () => {
  const files: Array<Record<string, unknown>> = []
  const attachments = useChatAttachments({
    getFiles: () => files,
    setFiles: (updated) => {
      files.splice(0, files.length, ...updated)
    },
    getAllowedTypes: () => undefined,
  })
  const input = {
    files: [file('first.png', 'image/png'), file('second.png', 'image/png')] as unknown as FileList,
    value: 'selected',
  }

  await attachments.handleFileChange({ target: input } as unknown as Event)

  assert.deepEqual(files, [])
  assert.equal(input.value, '')
})

test('accepts dropped files through the same upload entry point as the file picker', async () => {
  const files: Array<Record<string, unknown>> = []
  const attachments = useChatAttachments({
    getFiles: () => files,
    setFiles: (updated) => {
      files.splice(0, files.length, ...updated)
    },
    getAllowedTypes: () => undefined,
  })

  await attachments.handleFiles([file('dropped.png', 'image/png')] as unknown as File[])

  // The test runtime deliberately cannot resolve the browser upload API. The important
  // contract is that dropped files reach the same validation/upload entry point and
  // temporary UI state is cleaned up on that failure just as picker files are.
  assert.deepEqual(files, [])
})

test('removes completed items locally when the deferred attachment API import fails', async () => {
  const files = [{ id: 'uploaded-1', name: 'first.png', extension: 'png', size: '1 B' }]
  const attachments = useChatAttachments({
    getFiles: () => files,
    setFiles: (updated) => {
      files.splice(0, files.length, ...updated)
    },
    getAllowedTypes: () => undefined,
  })

  await attachments.removeFile(files[0]!)

  assert.deepEqual(files, [])
})

test('notifies once after a successful real upload replacement and never on a failed upload', async () => {
  const files: Array<Record<string, unknown>> = []
  const completed: Array<Record<string, unknown>> = []
  const stateTransitions: Array<Array<Record<string, unknown>>> = []
  const attachments = useChatAttachments({
    getFiles: () => files,
    setFiles: (updated) => {
      files.splice(0, files.length, ...updated)
      stateTransitions.push([...files])
    },
    getAllowedTypes: () => undefined,
    uploadFile: async (input) => input.name === 'failed.png' ? null : '2082729274554626051',
    onUploadComplete: (uploaded) => {
      assert.equal(uploaded.uploading, false)
      assert.equal(files.some((item) => item.id === uploaded.id && item.uploading === false), true)
      completed.push(uploaded)
    },
  })

  await attachments.handleFiles([file('ok.png', 'image/png'), file('failed.png', 'image/png')] as File[])

  assert.deepEqual(completed.map((item) => item.id), ['2082729274554626051'])
  assert.equal(stateTransitions.some((items) => items.some((item) => item.id === '2082729274554626051' && item.uploading === false)), true)

  await attachments.handleFiles([file('duplicate.png', 'image/png')] as File[])
  assert.deepEqual(completed.map((item) => item.id), ['2082729274554626051'])

  await attachments.handleFiles([file('failed.png', 'image/png')] as File[])
  assert.deepEqual(completed.map((item) => item.id), ['2082729274554626051'])
})

test('notifies after local removal even when the configured removal callback fails', async () => {
  const files = [{ id: '2082729274554626051', name: 'reference.png', extension: 'png', size: '1 B' }]
  let observedLocalRemoval = false
  const attachments = useChatAttachments({
    getFiles: () => files,
    setFiles: (updated) => {
      files.splice(0, files.length, ...updated)
    },
    getAllowedTypes: () => undefined,
    removeFiles: async () => {},
    onAttachmentRemoved: (removed) => {
      observedLocalRemoval = files.length === 0 && removed.id === '2082729274554626051'
      throw new Error('callback failure must not restore the item')
    },
  })

  await attachments.removeFile(files[0]!)

  assert.equal(observedLocalRemoval, true)
  assert.deepEqual(files, [])
})
