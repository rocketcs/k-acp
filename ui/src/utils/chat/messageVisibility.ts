/** Routes can hide persisted tool traces when the assistant message is the sole result surface. */
export function shouldDisplayChatMessage(role: string, hideToolMessages = false): boolean {
  return !(hideToolMessages && role === 'tool')
}
