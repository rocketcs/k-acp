const DEFAULT_LOGIN_REDIRECT = '/dashboard'

const VIEWER_CHAT_ROUTE_PATTERNS = [
  /^\/chat\/(?!diy$)[^/]+$/,
  /^\/chat\/diy\/[^/]+$/,
]

export function isViewerAllowedChatRoute(path: string): boolean {
  return VIEWER_CHAT_ROUTE_PATTERNS.some((pattern) => pattern.test(path))
}

export function resolveLoginRedirect(redirect: unknown): string {
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return DEFAULT_LOGIN_REDIRECT
  }
  return redirect
}

export function buildLoginRedirectUrl(currentRoute: unknown): string {
  return `/#/login?redirect=${encodeURIComponent(resolveLoginRedirect(currentRoute))}`
}
