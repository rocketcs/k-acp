import type { LargeScreenImageSubmission, LargeScreenImageTemplateV2 } from './template'
import { parseLargeScreenImageTemplateV2 } from './templateParser.ts'

const SAFE_REFERENCE_FILE_ID = /^\d+$/
const DEFAULT_BUSINESS_PROMPT = '请基于当前模板生成一版大屏图。'

function validatedTemplate(template: unknown): LargeScreenImageTemplateV2 | null {
  try {
    const json = JSON.stringify(template)
    if (!json) return null
    const result = parseLargeScreenImageTemplateV2(`\`\`\`large-screen-image-plan\n${json}\n\`\`\``)
    return result.kind === 'valid' ? result.template : null
  } catch {
    return null
  }
}

function isExplicitRelayout(prompt: string): boolean {
  return /(?<!不要)(?<!无需)(?<!不需)重新布局/.test(prompt)
}

export function compileLargeScreenImageGeneration(input: {
  template: LargeScreenImageTemplateV2
  referenceFileId: string
  businessPrompt: string
}): LargeScreenImageSubmission | null {
  if (!input || typeof input.referenceFileId !== 'string' || !SAFE_REFERENCE_FILE_ID.test(input.referenceFileId)
    || typeof input.businessPrompt !== 'string') return null
  const template = validatedTemplate(input.template)
  if (!template) return null

  const businessPrompt = input.businessPrompt.trim() || DEFAULT_BUSINESS_PROMPT
  const strategy = isExplicitRelayout(businessPrompt) ? 'explicit-relayout' : 'preserve-layout'
  const lockedRegions = template.regions.filter((region) => region.locked)
  const lockedRelations = template.relations.filter((relation) => relation.locked)
  const constraints = [
    `[布局策略=${strategy}]`,
    `画布：ratio=${template.canvas.ratio}; coordinateSystem=${template.canvas.coordinateSystem}; grid=${template.canvas.grid}`,
    `视觉令牌：palette=${template.visualTokens.palette.join(',')}; surface=${template.visualTokens.surface}; border=${template.visualTokens.border}; typography=${template.visualTokens.typography}`,
    '锁定布局骨架：',
    ...lockedRegions.map((region) => `- ${region.id}: bounds=(${region.bounds.x},${region.bounds.y},${region.bounds.width},${region.bounds.height}); layer=${region.layer}; component=${region.component}; purpose=${region.purpose}; replaceable=${region.replaceable.join(',') || 'none'}`),
    '锁定关系：',
    ...lockedRelations.map((relation) => `- ${relation.from} -> ${relation.to}; kind=${relation.kind}`),
    `保留策略：mustKeep=${template.preservation.mustKeep.join(',') || 'none'}; mayReplace=${template.preservation.mayReplace.join(',') || 'none'}`,
    '未指定重新布局时，仅允许替换 replaceable 和 mayReplace 内容；必须保留锁定几何、信息层级、组件、关系和 palette-proportion。',
    `模板正向提示词：${template.prompt}`,
    `模板负向提示词：${template.negativePrompt}`,
  ].join('\n')
  const text = `[large-screen-image action=generate ratio=${template.canvas.ratio} quality=high referenceFileId=${input.referenceFileId} referenceImageUrl= templateVersion=2]\n布局模板约束（系统约束，必须保留）：\n${constraints}\n\n用户创作需求：\n${businessPrompt}`

  return {
    displayText: businessPrompt,
    persistedText: text,
    runtimeText: text,
    titleText: template.title,
    fileIds: [input.referenceFileId],
  }
}
