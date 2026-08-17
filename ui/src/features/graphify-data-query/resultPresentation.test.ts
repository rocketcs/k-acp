import assert from 'node:assert/strict'
import test from 'node:test'
import { shouldRenderAssistantPlaceholder, shouldRenderAssistantText } from './resultPresentation.ts'

test('always renders assistant text when content exists, even with selected evidence', () => {
  // 正文承载结论与说明；证据表格另行展示，二者不再互相排斥。
  assert.equal(shouldRenderAssistantText({
    hasContent: true,
    isSelectedTurn: true,
    hasEvidence: true,
  }), true)
})

test('never renders assistant text without content', () => {
  assert.equal(shouldRenderAssistantText({
    hasContent: false,
    isSelectedTurn: false,
    hasEvidence: true,
  }), false)
})

test('hides the pending placeholder when structured table evidence exists', () => {
  assert.equal(shouldRenderAssistantPlaceholder({ hasContent: false, hasEvidence: true }), false)
})
