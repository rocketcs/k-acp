import assert from 'node:assert/strict'
import test from 'node:test'
import { parseGeneratedImages } from './gallery.ts'

const trusted = 'https://k-devs.tos-cn-guangzhou.volces.com/output/a.png'

test('只解析大屏 Tool 生成的一张可信 HTTPS 图片', () => {
  assert.deepEqual(parseGeneratedImages(`![large-screen-image](${trusted})`), [{ imageUrl: trusted }])
  assert.deepEqual(parseGeneratedImages('![other](https://k-devs.tos-cn-guangzhou.volces.com/output/a.png)'), [])
  assert.deepEqual(parseGeneratedImages('![large-screen-image](javascript:alert(1))'), [])
  assert.deepEqual(parseGeneratedImages('![large-screen-image](https://example.com/a.png)'), [])
  assert.deepEqual(parseGeneratedImages(`![large-screen-image](${trusted})\n![large-screen-image](https://k-devs.tos-cn-guangzhou.volces.com/output/b.png)`), [{ imageUrl: trusted }])
})
