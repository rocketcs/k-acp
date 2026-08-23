export const DEFAULT_GRAPH_EXPLORER_URL = 'http://127.0.0.1:8770'

export function resolveGraphExplorerUrl(configuredUrl?: string): string {
  return (configuredUrl?.trim() || DEFAULT_GRAPH_EXPLORER_URL).replace(/\/+$/, '')
}
