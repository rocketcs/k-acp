import assert from 'node:assert/strict'
import test from 'node:test'
import { shouldResetDeletedSession, shouldSubmitComposerShortcut, toggleEvidencePanel } from './composerControls.ts'

test('submits with Control + Enter on Windows and Linux', () => {
  assert.equal(shouldSubmitComposerShortcut({ key: 'Enter', ctrlKey: true, metaKey: false }), true)
})

test('submits with Command + Enter on macOS', () => {
  assert.equal(shouldSubmitComposerShortcut({ key: 'Enter', ctrlKey: false, metaKey: true }), true)
})

test('keeps plain Enter available for a newline', () => {
  assert.equal(shouldSubmitComposerShortcut({ key: 'Enter', ctrlKey: false, metaKey: false }), false)
})

test('toggles the evidence panel from its closed default', () => {
  assert.equal(toggleEvidencePanel(false), true)
  assert.equal(toggleEvidencePanel(true), false)
})

test('resets the current conversation only when that session is deleted', () => {
  assert.equal(shouldResetDeletedSession('current', 'current'), true)
  assert.equal(shouldResetDeletedSession('current', 'other'), false)
})
