/**
 * 南网图谱页与平台同源，由前端 Nginx 提供静态资源并代理到语义容器。
 *
 * URL 和 API 都使用浏览器可访问的相对路径；容器服务名只存在于 Nginx
 * 的运行时配置中，浏览器不会感知或解析 Docker 网络地址。
 */
export const DEFAULT_GRAPH_EXPLORER_URL = '/web/nanwang-graph/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json&api=/api/nanwang-graph'
export const DEFAULT_GRAPH_EXPLORER_API_BASE = '/api/nanwang-graph'

export function resolveGraphExplorerUrl(configuredUrl?: string): string {
  return (configuredUrl?.trim() || DEFAULT_GRAPH_EXPLORER_URL).replace(/\/+$/, '')
}

export function resolveGraphExplorerApiBase(configuredUrl?: string): string {
  const resolved = configuredUrl?.trim() || DEFAULT_GRAPH_EXPLORER_API_BASE
  if (!configuredUrl?.trim()) return resolved.replace(/\/+$/, '')
  try {
    const parsed = new URL(resolved)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.origin : resolved
  } catch {
    return resolved
  }
}
