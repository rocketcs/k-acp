export const LARGE_SCREEN_IMAGE_AGENT_CODE = 'large-screen-image' as const

export interface LargeScreenImageAgent {
  id: string | number
  agentCode: string
  name?: string
}

export function resolveLargeScreenImageAgent<T extends LargeScreenImageAgent>(records: T[]): T | null {
  const matches = records.filter((item) => item.agentCode === LARGE_SCREEN_IMAGE_AGENT_CODE)
  if (matches.length > 1) {
    throw new Error('Duplicate large-screen-image agents')
  }
  return matches[0] ?? null
}
