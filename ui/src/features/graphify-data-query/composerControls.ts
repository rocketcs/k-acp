export type ComposerKeyEvent = Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey'>

export function shouldSubmitComposerShortcut(event: ComposerKeyEvent): boolean {
  return event.key === 'Enter' && (event.ctrlKey || event.metaKey)
}

export function toggleEvidencePanel(isOpen: boolean): boolean {
  return !isOpen
}

export function shouldResetDeletedSession(currentSessionId: string | null, deletedSessionId: string | number): boolean {
  return currentSessionId === String(deletedSessionId)
}
