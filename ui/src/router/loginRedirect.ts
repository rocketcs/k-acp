const DEFAULT_LOGIN_REDIRECT = '/dashboard'

export function resolveLoginRedirect(redirect: unknown): string {
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return DEFAULT_LOGIN_REDIRECT
  }
  return redirect
}

export function buildLoginRedirectUrl(currentRoute: unknown): string {
  return `/#/login?redirect=${encodeURIComponent(resolveLoginRedirect(currentRoute))}`
}
