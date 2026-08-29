/** 测试环境图谱服务地址；不能使用浏览器所在机器的 loopback 地址。 */
export const DEFAULT_GRAPH_EXPLORER_URL = 'http://kg.demo.pine.kingsware.cn:6800'

export function resolveGraphExplorerUrl(configuredUrl?: string): string {
  return (configuredUrl?.trim() || DEFAULT_GRAPH_EXPLORER_URL).replace(/\/+$/, '')
}
