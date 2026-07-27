import type { DiyOutputFormat } from '../../types/diy.ts'

const PLACEHOLDER_PATTERN = /{{\s*([^{}]+?)\s*}}/g

export function extractPlaceholders(template: string): string[] {
  const names = [...template.matchAll(PLACEHOLDER_PATTERN)]
    .map((match) => match[1]?.trim())
    .filter((name): name is string => Boolean(name))

  return names.filter((name, index) => names.indexOf(name) === index)
}

export function renderQuestionTemplate(
  template: string,
  values: Record<string, string>,
  optionalNames: string[] = [],
): { text: string; missing: string[] } {
  const missing: string[] = []
  const text = template.replace(PLACEHOLDER_PATTERN, (fullMatch, rawName: string) => {
    const name = rawName.trim()
    const value = values[name]?.trim()
    if (!value) {
      if (optionalNames.includes(name)) return ''
      if (!missing.includes(name)) missing.push(name)
      return fullMatch
    }
    return value
  })

  return { text, missing }
}

const VEP_BASE = [
  '请使用现有 VEP 1.0 协议输出图表。',
  '回复必须包含一个 ```vep JSON 代码块，顶层字段为 role、content、version、vision。',
  'vision.type 必须为 chart，vision.data 必须包含 chartType、xAxis（饼图可省略）和 series。',
].join('')

export function buildOutputInstruction(format: DiyOutputFormat): string {
  switch (format) {
    case 'ECHARTS_BAR':
      return `${VEP_BASE} vision.data.chartType 必须为 bar。`
    case 'ECHARTS_PIE':
      return `${VEP_BASE} vision.data.chartType 必须为 pie，series.data 使用 name/value 数据项。`
    case 'JSON':
      return '请只输出合法 JSON，不要添加 Markdown 代码围栏或 JSON 之外的解释文字。'
    case 'TEXT':
    default:
      return '请使用简洁的业务文本回答；数据适合表格时可以使用 Markdown 表格。'
  }
}
