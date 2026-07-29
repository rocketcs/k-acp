export const LARGE_SCREEN_IMAGE_PLAN_VERSION = '1' as const
export const LARGE_SCREEN_IMAGE_PLAN_RATIOS = ['16:9', '21:9', '9:16'] as const
export const LARGE_SCREEN_IMAGE_PLAN_CONFIDENCES = ['HIGH', 'MEDIUM', 'LOW'] as const

export type LargeScreenImagePlanRatio = (typeof LARGE_SCREEN_IMAGE_PLAN_RATIOS)[number]
export type LargeScreenImagePlanConfidence = (typeof LARGE_SCREEN_IMAGE_PLAN_CONFIDENCES)[number]

export interface LargeScreenImageCreativeBrief {
  ratio: LargeScreenImagePlanRatio
  styleTags: string[]
  palette: string[]
  layout: string[]
  chartSuggestions: string[]
  prompt: string
  negativePrompt: string
  iterationHints: string[]
}

export interface LargeScreenImagePlan {
  version: typeof LARGE_SCREEN_IMAGE_PLAN_VERSION
  title: string
  confidence: LargeScreenImagePlanConfidence
  observedVisualFacts: string[]
  designSuggestions: string[]
  creativeBrief: LargeScreenImageCreativeBrief
}

export type LargeScreenImagePlanParseResult =
  | { kind: 'absent' }
  | { kind: 'invalid'; reason: string }
  | { kind: 'valid'; plan: LargeScreenImagePlan }

const PLAN_FENCE = /^```large-screen-image-plan[ \t]*\r?\n([\s\S]*?)\r?\n```$/
const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/
const MAX_TITLE_LENGTH = 48
const MAX_PROMPT_LENGTH = 1600
const MAX_NEGATIVE_PROMPT_LENGTH = 160

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function boundedString(value: unknown, maxLength: number): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized.length > 0 && normalized.length <= maxLength ? normalized : null
}

function boundedStringArray(value: unknown, minimum: number, maximum: number, itemMaxLength: number): string[] | null {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) return null
  const normalized = value.map((item) => boundedString(item, itemMaxLength))
  return normalized.every((item): item is string => item !== null) ? normalized : null
}

function isRatio(value: unknown): value is LargeScreenImagePlanRatio {
  return typeof value === 'string' && (LARGE_SCREEN_IMAGE_PLAN_RATIOS as readonly string[]).includes(value)
}

function isConfidence(value: unknown): value is LargeScreenImagePlanConfidence {
  return typeof value === 'string' && (LARGE_SCREEN_IMAGE_PLAN_CONFIDENCES as readonly string[]).includes(value)
}

function parsePlan(value: unknown): LargeScreenImagePlanParseResult {
  if (!isRecord(value)) return { kind: 'invalid', reason: '方案根节点必须是对象' }
  if (value.version !== LARGE_SCREEN_IMAGE_PLAN_VERSION) return { kind: 'invalid', reason: '不支持的方案版本' }
  const title = boundedString(value.title, MAX_TITLE_LENGTH)
  if (!title) return { kind: 'invalid', reason: '缺少方案标题' }
  if (!isConfidence(value.confidence)) return { kind: 'invalid', reason: '置信度不合法' }
  const observedVisualFacts = boundedStringArray(value.observedVisualFacts, 2, 6, 240)
  if (!observedVisualFacts) return { kind: 'invalid', reason: '缺少可观察事实' }
  const designSuggestions = boundedStringArray(value.designSuggestions, 2, 5, 240)
  if (!designSuggestions) return { kind: 'invalid', reason: '缺少设计建议' }
  if (!isRecord(value.creativeBrief)) return { kind: 'invalid', reason: '缺少创作简报' }

  const brief = value.creativeBrief
  if (!isRatio(brief.ratio)) return { kind: 'invalid', reason: '比例不合法' }
  const styleTags = boundedStringArray(brief.styleTags, 3, 6, 48)
  if (!styleTags) return { kind: 'invalid', reason: '缺少风格标签' }
  const palette = boundedStringArray(brief.palette, 3, 6, 7)
  if (!palette || !palette.every((color) => HEX_COLOR.test(color))) return { kind: 'invalid', reason: '配色不合法' }
  const layout = boundedStringArray(brief.layout, 3, 6, 240)
  if (!layout) return { kind: 'invalid', reason: '缺少布局' }
  const chartSuggestions = boundedStringArray(brief.chartSuggestions, 3, 6, 80)
  if (!chartSuggestions) return { kind: 'invalid', reason: '缺少图表建议' }
  const prompt = boundedString(brief.prompt, MAX_PROMPT_LENGTH)
  if (!prompt) return { kind: 'invalid', reason: '缺少正向提示词' }
  const negativePrompt = boundedString(brief.negativePrompt, MAX_NEGATIVE_PROMPT_LENGTH)
  if (!negativePrompt) {
    return { kind: 'invalid', reason: '负向提示词不合法' }
  }
  const iterationHints = boundedStringArray(brief.iterationHints, 2, 4, 160)
  if (!iterationHints) return { kind: 'invalid', reason: '缺少迭代建议' }

  return {
    kind: 'valid',
    plan: {
      version: LARGE_SCREEN_IMAGE_PLAN_VERSION,
      title,
      confidence: value.confidence,
      observedVisualFacts,
      designSuggestions,
      creativeBrief: {
        ratio: brief.ratio,
        styleTags,
        palette,
        layout,
        chartSuggestions,
        prompt,
        negativePrompt,
        iterationHints,
      },
    },
  }
}

export function parseLargeScreenImagePlan(content: string): LargeScreenImagePlanParseResult {
  const normalized = content.trim()
  const match = normalized.match(PLAN_FENCE)
  if (!match) {
    return normalized.includes('```large-screen-image-plan')
      ? { kind: 'invalid', reason: '方案代码块必须是唯一完整回复' }
      : { kind: 'absent' }
  }
  try {
    return parsePlan(JSON.parse(match[1] ?? ''))
  } catch {
    return { kind: 'invalid', reason: '方案 JSON 无法解析' }
  }
}
