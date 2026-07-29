import assert from 'node:assert/strict'
import test from 'node:test'
import { validateReferenceFiles } from './upload.ts'

const image = { type: 'image/png', name: 'screen.png' } as File
const text = { type: 'text/plain', name: 'notes.txt' } as File

test('仅接受单张图片作为大屏生图参考图', () => {
  assert.deepEqual(validateReferenceFiles([]), { ok: false, code: 'EMPTY' })
  assert.deepEqual(validateReferenceFiles([text]), { ok: false, code: 'NOT_IMAGE' })
  assert.deepEqual(validateReferenceFiles([image, image]), { ok: false, code: 'MULTIPLE_IMAGES' })
  assert.deepEqual(validateReferenceFiles([image]), { ok: true, file: image })
})
