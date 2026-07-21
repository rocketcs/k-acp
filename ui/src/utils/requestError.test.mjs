import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const requestSource = await readFile(
  fileURLToPath(new URL('./request.ts', import.meta.url)),
  'utf8'
)

test('displays the backend message for an API business failure', () => {
  assert.match(
    requestSource,
    /console\.error\('接口请求失败：', msg\)\s+AMessage\.error\(msg\)/
  )
})
