import assert from 'node:assert/strict'
import test from 'node:test'
import { parseGeneratedImages } from './gallery.ts'

test('只解析大屏生图工具生成的 HTTPS 图片', () => {
  assert.deepEqual(
    parseGeneratedImages('![large-screen-image](https://k-dvs.tos-cn-guangzhou.volces.com/output/a.png)'),
    [{ imageUrl: 'https://k-dvs.tos-cn-guangzhou.volces.com/output/a.png' }],
  )
  assert.deepEqual(parseGeneratedImages('![other](https://example.com/a.png)'), [])
  assert.deepEqual(parseGeneratedImages('![large-screen-image](javascript:alert(1))'), [])
  assert.deepEqual(parseGeneratedImages('普通文字'), [])
})
