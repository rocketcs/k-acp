/**
 * 南网问数 · 统计图表受限 spec（方案 A）
 *
 * LLM 在聚合类回答的 Markdown 表格之后输出 ```chart 围栏代码块；
 * 本模块负责解析与严格校验（幻觉面最小化：type 3 枚举、字段 6 个、data ≤ 50），
 * ChartCard 将 spec 确定性转换为 ECharts option。任何校验失败 → 返回 null，
 * 前端静默降级为纯表格（数据始终以表格为准）。
 */

export type ChartSpecType = 'bar' | 'line' | 'pie'

export type ChartSpec = {
  type: ChartSpecType
  title: string
  xLabel?: string
  yLabel?: string
  yUnit?: string
  data: Array<{ name: string; value: number }>
}

export type ContentSegment =
  | { kind: 'md'; text: string }
  | { kind: 'chart'; spec: ChartSpec }
  | { kind: 'chart-pending' }

const MAX_DATA_POINTS = 50
const CHART_FENCE = /```chart\s*\n([\s\S]*?)```/g

/** 严格校验并解析一段 ```chart 围栏内容；失败返回 null。 */
export function parseChartSpec(raw: string): ChartSpec | null {
  let value: unknown
  try {
    value = JSON.parse(raw.trim())
  } catch {
    return null
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (record.type !== 'bar' && record.type !== 'line' && record.type !== 'pie') return null
  if (typeof record.title !== 'string' || !record.title.trim()) return null
  if (!Array.isArray(record.data) || record.data.length < 1 || record.data.length > MAX_DATA_POINTS) return null

  const data: Array<{ name: string; value: number }> = []
  for (const item of record.data) {
    if (!item || typeof item !== 'object') return null
    const entry = item as Record<string, unknown>
    const name = typeof entry.name === 'string' ? entry.name.trim() : ''
    const value = typeof entry.value === 'number' ? entry.value : Number(entry.value)
    if (!name || !Number.isFinite(value)) return null
    data.push({ name, value })
  }
  if (record.type === 'pie' && data.length > 8) return null

  const spec: ChartSpec = { type: record.type, title: record.title.trim(), data }
  if (typeof record.xLabel === 'string' && record.xLabel.trim()) spec.xLabel = record.xLabel.trim()
  if (typeof record.yLabel === 'string' && record.yLabel.trim()) spec.yLabel = record.yLabel.trim()
  if (typeof record.yUnit === 'string' && record.yUnit.trim()) spec.yUnit = record.yUnit.trim()
  return spec
}

/**
 * 将助手正文拆分为 [markdown | chart | chart-pending] 片段。
 * - 无 chart 围栏 → 单一 md 段（保持既有渲染路径不变）
 * - 流式期间未闭合的 ```chart → chart-pending 段（渲染占位，避免 JSON 文本闪现）
 */
export function splitChartSegments(content: string): ContentSegment[] {
  if (!content.includes('```chart')) return [{ kind: 'md', text: content }]

  const segments: ContentSegment[] = []
  let cursor = 0
  CHART_FENCE.lastIndex = 0
  for (const match of content.matchAll(CHART_FENCE)) {
    const before = content.slice(cursor, match.index)
    if (before.trim()) segments.push({ kind: 'md', text: before })
    const spec = parseChartSpec(match[1] ?? '')
    if (spec) segments.push({ kind: 'chart', spec })
    cursor = match.index + match[0].length
  }
  const tail = content.slice(cursor)
  if (tail.includes('```chart')) {
    // 未闭合围栏（流式输出中）：闭合之前的文字保留，围栏起渲染占位
    const idx = tail.indexOf('```chart')
    if (tail.slice(0, idx).trim()) segments.push({ kind: 'md', text: tail.slice(0, idx) })
    segments.push({ kind: 'chart-pending' })
  } else if (tail.trim()) {
    segments.push({ kind: 'md', text: tail })
  }
  return segments.length ? segments : [{ kind: 'md', text: content }]
}
