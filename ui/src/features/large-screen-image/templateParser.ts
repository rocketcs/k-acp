import type {
  LargeScreenImageTemplateParseResult,
  LargeScreenImageTemplateV2,
  LargeScreenTemplateComponent,
  LargeScreenTemplateRelationKind,
  LargeScreenTemplateReplaceableField,
} from './template'

const TEMPLATE_FENCE = /^```large-screen-image-plan[ \t]*\r?\n([\s\S]*?)\r?\n```$/
const COLORS = /^#[0-9A-Fa-f]{6}$/
const COMPONENTS = new Set<LargeScreenTemplateComponent>([
  'title-status', 'metric-grid', 'line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'gauge', 'map',
  'topology-cluster', 'core-topology', 'alert-feed', 'list', 'timeline', 'data-table', 'image-panel', 'footer-status',
])
const RELATION_KINDS = new Set<LargeScreenTemplateRelationKind>([
  'topology-link', 'flow-link', 'dependency-link', 'hierarchy-link', 'data-link',
])
const REPLACEABLE_FIELDS = new Set<LargeScreenTemplateReplaceableField>([
  'title', 'statusText', 'businessLabels', 'metricMeanings', 'chartData', 'icons', 'copy', 'visualAccent',
])
const MUST_KEEP = new Set(['region-bounds', 'information-hierarchy', 'locked-relations', 'palette-proportion'])
const MAY_REPLACE = new Set(['business-labels', 'metric-meanings', 'chart-data', 'icons'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value)
  return actual.length === keys.length && actual.every((key) => keys.includes(key))
}

function string(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized.length > 0 && normalized.length <= maximum ? normalized : null
}

function strings(value: unknown, minimum: number, maximum: number, itemMaximum: number): string[] | null {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) return null
  const result = value.map((item) => string(item, itemMaximum))
  return result.every((item): item is string => item !== null) ? result : null
}

function enumValue<T extends string>(value: unknown, accepted: Set<T>): T | null {
  return typeof value === 'string' && accepted.has(value as T) ? value as T : null
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}

function invalid(reason: string): LargeScreenImageTemplateParseResult {
  return { kind: 'invalid', reason }
}

function parseTemplate(value: unknown): LargeScreenImageTemplateParseResult {
  if (!isRecord(value) || !hasExactKeys(value, [
    'version', 'title', 'confidence', 'observedVisualFacts', 'canvas', 'visualTokens', 'regions', 'relations',
    'preservation', 'prompt', 'negativePrompt', 'iterationHints',
  ])) return invalid('模板根节点字段不合法')
  if (value.version !== '2') return invalid('不支持的模板版本')
  const title = string(value.title, 48)
  if (!title) return invalid('模板标题不合法')
  if (value.confidence !== 'HIGH' && value.confidence !== 'MEDIUM' && value.confidence !== 'LOW') return invalid('置信度不合法')
  const observedVisualFacts = strings(value.observedVisualFacts, 0, 18, 160)
  if (!observedVisualFacts) return invalid('可观察事实不合法')

  if (!isRecord(value.canvas) || !hasExactKeys(value.canvas, ['ratio', 'coordinateSystem', 'grid'])) return invalid('画布字段不合法')
  const canvas = value.canvas
  if ((canvas.ratio !== '16:9' && canvas.ratio !== '21:9' && canvas.ratio !== '9:16')
    || canvas.coordinateSystem !== 'normalized-1000' || canvas.grid !== '12-column') return invalid('画布配置不合法')

  if (!isRecord(value.visualTokens) || !hasExactKeys(value.visualTokens, ['palette', 'surface', 'border', 'typography'])) return invalid('视觉令牌字段不合法')
  const palette = strings(value.visualTokens.palette, 1, 8, 7)
  const surface = string(value.visualTokens.surface, 120)
  const border = string(value.visualTokens.border, 120)
  const typography = string(value.visualTokens.typography, 120)
  if (!palette || !palette.every((color) => COLORS.test(color)) || !surface || !border || !typography) return invalid('视觉令牌不合法')

  if (!Array.isArray(value.regions) || value.regions.length < 1 || value.regions.length > 18) return invalid('区域数量不合法')
  const regionIds = new Set<string>()
  const regions: LargeScreenImageTemplateV2['regions'] = []
  for (const region of value.regions) {
    if (!isRecord(region) || !hasExactKeys(region, ['id', 'label', 'bounds', 'layer', 'component', 'purpose', 'locked', 'replaceable'])) return invalid('区域字段不合法')
    const id = string(region.id, 120)
    const label = string(region.label, 120)
    const purpose = string(region.purpose, 120)
    if (!id || !label || !purpose || regionIds.has(id) || !integer(region.layer) || region.layer <= 0
      || typeof region.locked !== 'boolean' || !isRecord(region.bounds)
      || !hasExactKeys(region.bounds, ['x', 'y', 'width', 'height'])) return invalid('区域属性不合法')
    const { x, y, width, height } = region.bounds
    if (!integer(x) || !integer(y) || !integer(width) || !integer(height)
      || x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1000 || y + height > 1000) return invalid('区域边界不合法')
    const component = enumValue(region.component, COMPONENTS)
    const replaceable = strings(region.replaceable, 0, 8, 120)
    if (!component || !replaceable || !replaceable.every((item) => REPLACEABLE_FIELDS.has(item as LargeScreenTemplateReplaceableField))) return invalid('区域组件或可替换字段不合法')
    regionIds.add(id)
    regions.push({ id, label, bounds: { x, y, width, height }, layer: region.layer, component, purpose, locked: region.locked,
      replaceable: replaceable as LargeScreenTemplateReplaceableField[] })
  }

  if (!Array.isArray(value.relations) || value.relations.length > 24) return invalid('关系数量不合法')
  const relations: LargeScreenImageTemplateV2['relations'] = []
  for (const relation of value.relations) {
    if (!isRecord(relation) || !hasExactKeys(relation, ['from', 'to', 'kind', 'locked'])) return invalid('关系字段不合法')
    const from = string(relation.from, 120)
    const to = string(relation.to, 120)
    const kind = enumValue(relation.kind, RELATION_KINDS)
    if (!from || !to || !kind || typeof relation.locked !== 'boolean' || !regionIds.has(from) || !regionIds.has(to)) return invalid('关系属性不合法')
    relations.push({ from, to, kind, locked: relation.locked })
  }

  if (!isRecord(value.preservation) || !hasExactKeys(value.preservation, ['mode', 'mustKeep', 'mayReplace'])) return invalid('保留策略字段不合法')
  const mustKeep = strings(value.preservation.mustKeep, 0, 4, 120)
  const mayReplace = strings(value.preservation.mayReplace, 0, 4, 120)
  if (value.preservation.mode !== 'preserve-layout' || !mustKeep || !mayReplace
    || !mustKeep.every((item) => MUST_KEEP.has(item)) || !mayReplace.every((item) => MAY_REPLACE.has(item))) return invalid('保留策略不合法')
  const prompt = string(value.prompt, 4000)
  const negativePrompt = string(value.negativePrompt, 320)
  const iterationHints = strings(value.iterationHints, 0, 12, 120)
  if (!prompt || !negativePrompt || !iterationHints) return invalid('提示词或迭代建议不合法')

  return { kind: 'valid', template: {
    version: '2', title, confidence: value.confidence, observedVisualFacts,
    canvas: { ratio: canvas.ratio, coordinateSystem: 'normalized-1000', grid: '12-column' },
    visualTokens: { palette, surface, border, typography }, regions, relations,
    preservation: { mode: 'preserve-layout', mustKeep, mayReplace }, prompt, negativePrompt, iterationHints,
  } }
}

export function parseLargeScreenImageTemplateV2(content: string): LargeScreenImageTemplateParseResult {
  const normalized = content.trim()
  const match = normalized.match(TEMPLATE_FENCE)
  if (!match) {
    return normalized.includes('```large-screen-image-plan')
      ? invalid('模板代码块必须是唯一完整回复')
      : { kind: 'absent' }
  }
  try {
    return parseTemplate(JSON.parse(match[1] ?? ''))
  } catch {
    return invalid('模板 JSON 无法解析')
  }
}
