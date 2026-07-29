import assert from 'node:assert/strict'
import test from 'node:test'
import { MAX_REFERENCE_IMAGE_BYTES, validateReferenceFiles } from './upload.ts'

const image = { type: 'image/png', name: 'screen.png', size: 1 } as File
const text = { type: 'text/plain', name: 'notes.txt', size: 1 } as File

test('仅接受单张图片作为大屏生图参考图', () => {
  assert.deepEqual(validateReferenceFiles([]), { ok: false, code: 'EMPTY' })
  assert.deepEqual(validateReferenceFiles([text]), { ok: false, code: 'NOT_IMAGE' })
  assert.deepEqual(validateReferenceFiles([image, image]), { ok: false, code: 'MULTIPLE_IMAGES' })
  assert.deepEqual(validateReferenceFiles([image]), { ok: true, file: image })
})

test('允许 30 MiB 图片并拒绝多一个字节的图片', () => {
  const atLimit = { ...image, size: MAX_REFERENCE_IMAGE_BYTES } as File
  const overLimit = { ...image, size: MAX_REFERENCE_IMAGE_BYTES + 1 } as File
  assert.deepEqual(validateReferenceFiles([atLimit]), { ok: true, file: atLimit })
  assert.deepEqual(validateReferenceFiles([overLimit]), { ok: false, code: 'TOO_LARGE' })
})
