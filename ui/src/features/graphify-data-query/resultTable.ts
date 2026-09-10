import { displayGraphifyLabel } from './evidenceAdapter.ts'
import type { GraphifyEvidenceEnvelope } from './types.ts'

export type ResultColumn = {
  key: string
  label: string
  formatValue: (value: unknown) => string
}

export type DomainLabels = Record<string, string> | undefined

/**
 * 由证据信封构建结果表格列定义。
 *
 * - 过滤全空列：某列在全部结果行里都无值（null/空串）时不展示，避免药品/耗材
 *   不同域共用字段造成一整列“-”的无效展示。
 * - 目录域等枚举值面向用户用后端下发的域标签中文呈现（行业配置驱动，前端不硬编码）。
 */
export function buildResultColumns(evidence: GraphifyEvidenceEnvelope, domainLabels?: DomainLabels): ResultColumn[] {
  const rows = evidence.result.rows ?? []
  return (evidence.result.columns ?? [])
    .filter((key) => rows.some((row) => {
      const value = row[key]
      return value !== null && value !== undefined && String(value).trim() !== ''
    }))
    .map((key) => ({
      key,
      label: evidence.result.column_labels?.[key] ?? displayGraphifyLabel(key),
      formatValue: (value: unknown): string => {
        const text = value === null || value === undefined ? '' : String(value)
        if (key === 'catalog_domain' || key === 'domain') {
          return domainLabels?.[text] ?? text
        }
        return text
      },
    }))
}
