import type { TracingResultStatus, TracingStatusTone, TracingUserIdentity } from '@/types/sessionTracing'

export function formatTracingUserLabel(user: TracingUserIdentity): string {
  const identityParts = [user.nickname, user.username, user.email].filter(
    (value): value is string => typeof value === 'string' && value.trim().length > 0,
  )

  return identityParts.length > 0 ? identityParts.join(' / ') : `未知用户 / ${user.userId}`
}

export function tracingStatusTone(status: TracingResultStatus | string): TracingStatusTone {
  switch (status) {
    case 'COMPLETE':
      return 'success'
    case 'PARTIAL':
      return 'warning'
    case 'ERROR':
      return 'error'
    default:
      return 'default'
  }
}
