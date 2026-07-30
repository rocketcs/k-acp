import type { LargeScreenImageTemplateV2 } from './template'
import { parseLargeScreenImageTemplateV2 } from './templateParser.ts'

export type LargeScreenImagePresentation =
  | { kind: 'markdown' }
  | { kind: 'template'; template: LargeScreenImageTemplateV2 }
  | { kind: 'invalid-template'; reason: string }

/** Classifies only model output; SFC routing and editability belong to the route wrapper. */
export function classifyLargeScreenImagePresentation(input: {
  role: string
  rawContent: string
}): LargeScreenImagePresentation {
  if (input.role !== 'assistant') return { kind: 'markdown' }
  const result = parseLargeScreenImageTemplateV2(input.rawContent)
  if (result.kind === 'valid') return { kind: 'template', template: result.template }
  if (result.kind === 'invalid') return { kind: 'invalid-template', reason: result.reason }
  return { kind: 'markdown' }
}
