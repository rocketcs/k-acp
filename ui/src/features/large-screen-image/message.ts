import type { ChatMessageVO, UploadedFileItem } from '@/types'
import { splitChatAttachmentContent } from '@/utils/chat/messageContent'

/** UI shape only. The bound Skill is the single source of the template contract. */
export interface LargeScreenImageTemplate {
  version: string; title: string; confidence: string; observedVisualFacts: string[]
  canvas: { ratio: string; coordinateSystem: string; grid: string }
  visualTokens: { palette: string[]; surface: string; border: string; typography: string }
  regions: Array<{ id: string; label: string; bounds: { x: number; y: number; width: number; height: number }; layer: number; component: string; purpose: string; locked: boolean; replaceable: string[] }>
  relations: Array<{ from: string; to: string; kind: string; locked: boolean }>
  preservation: { mode: string; mustKeep: string[]; mayReplace: string[] }
  prompt: string; negativePrompt: string; iterationHints: string[]
}
export interface ActiveLargeScreenTemplateContext { sessionId: string; referenceFileId: string; referenceFile: UploadedFileItem; analyzeUserMessageId: string; templateMessageId: string; template: LargeScreenImageTemplate }
export interface LargeScreenImageSubmission { displayText: string; persistedText: string; runtimeText: string; titleText: string; fileIds: string[]; attachedFiles?: UploadedFileItem[] }
export interface LargeScreenImageSubmissionInput { text: string; fileIds: string[]; activeTemplate?: ActiveLargeScreenTemplateContext | null; referenceWorkflowActive?: boolean }
export type LargeScreenImagePresentation = { kind: 'markdown' } | { kind: 'template'; template: LargeScreenImageTemplate } | { kind: 'invalid-template'; reason: string }
const SAFE_REFERENCE_FILE_ID = /^\d+$/
const ANALYZE_ENVELOPE = /^\[large-screen-image action=analyze(?: [^\]\r\n]*)?\]\r?\n([\s\S]*)$/
const GENERATE_BRIEF_ENVELOPE = /^\[large-screen-image action=generate(?: [^\]\r\n]*)?\]\r?\n(?:[\s\S]*?\r?\n\r?\n)?用户创作需求：\r?\n([\s\S]*)$/
const GENERATE_ENVELOPE = /^\[large-screen-image action=generate(?: [^\]\r\n]*)?\]\r?\n正向提示词：\r?\n([\s\S]*?)\r?\n\r?\n负向提示词：\r?\n[\s\S]*$/
const FULL_GENERATE_PROMPT_ENVELOPE = /^\[large-screen-image action=generate(?: [^\]\r\n]*)?\]\r?\n(图像生成提示词：[\s\S]*?)\r?\n\r?\n结构化编辑状态/

export function extractGeneratedImageUrl(content: string): string | null {
  const markdown = content.match(/!\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)/)
  const html = content.match(/<img[^>]+src=["']([^"']+)["'][^>]*>/i)
  const value = markdown?.[1] ?? html?.[1]
  if (!value || value.startsWith('data:')) return null
  try {
    const url = new URL(value, window.location.origin)
    return /^https?:$/.test(url.protocol) ? url.href : null
  } catch { return null }
}

export function compileLargeScreenTemplatePrompt(template: LargeScreenImageTemplate): string {
  const componentVisuals: Record<string, string> = {
    'title-status': '标题与状态焦点', 'metric-grid': '指标卡组', 'kpi-card': '指标卡片', statistic: '统计数字',
    'tab-bar': '页签导航', 'filter-bar': '筛选栏', 'line-chart': '折线图', 'bar-chart': '柱状图',
    'area-chart': '面积图', 'pie-chart': '环形统计图', gauge: '仪表盘', map: '地图',
    'topology-cluster': '发光流程节点', 'core-topology': '核心流程拓扑', 'alert-feed': '告警信息流',
    list: '信息列表', timeline: '时间线', 'data-table': '数据表格', 'image-panel': '图像面板',
    legend: '图例', logo: '品牌标识', badge: '状态徽标', progress: '进度指示', 'footer-status': '底部状态栏',
  }
  const layoutContract = [...template.regions]
    .sort((left, right) => left.layer - right.layer || left.bounds.y - right.bounds.y || left.bounds.x - right.bounds.x)
    .map((region) => {
      const { x, y, width, height } = region.bounds
      return `[${region.locked ? '锁定' : '可调整'}] ${region.label}｜${componentVisuals[region.component] ?? region.component}｜x=${x}, y=${y}, w=${width}, h=${height}, z=${region.layer}｜${region.purpose}`
    })
  const lockedRelations = template.relations
    .filter((relation) => relation.locked)
    .map((relation) => `${relation.from} → ${relation.to}（${relation.kind}）`)
  const hasTopology = template.regions.some((region) => region.component === 'core-topology')
  const hasTopologyNodes = template.regions.some((region) => region.component === 'topology-cluster')
  const topologyDirection = hasTopology && hasTopologyNodes
    ? '中央拓扑区内以有序网格排布发光节点，用细箭头形成清晰执行路径；不要逐字复刻节点名称。'
    : ''
  return [
    `生成一张 ${template.canvas.ratio} 横向大屏：「${template.title}」。`,
    `视觉风格：${template.visualTokens.surface}；${template.visualTokens.border}；${template.visualTokens.typography}；主色使用 ${template.visualTokens.palette.slice(0, 6).join('、')}，保持克制、统一的发光层次。`,
    '布局合同：画布坐标系为 1000×1000，所有区域必须按下列 x、y、w、h、z 位置和尺寸绘制；锁定区域不得重排、缩放或改变层级。',
    layoutContract.join('\n'),
    lockedRelations.length ? `锁定关系：${lockedRelations.join('；')}。` : '',
    topologyDirection,
    '保持清晰的信息层级、整齐留白和可读的图表/列表；所有锁定区维持当前相对位置与比例。',
  ].filter(Boolean).join('\n')
}

export function formatLargeScreenImageMessageContent({ role, content }: { role: string; content: string }): string {
  if (role !== 'user') return content
  const { attachmentPrefix, text } = splitChatAttachmentContent(content)
  const analyze = text.match(ANALYZE_ENVELOPE)
  if (analyze) {
    const body = analyze[1] ?? ''
    const instruction = ['请根据当前参考图生成一份可编辑的大屏结构化模板 v2。', '请根据当前参考图生成一份可编辑的大屏创作方案。'].find((value) => body.startsWith(value))
    return attachmentPrefix + (instruction ? body.slice(instruction.length).trim() || '已上传参考图，请分析其视觉风格并给出创作方案。' : '已上传参考图，请分析其视觉风格并给出创作方案。')
  }
  const fullGenerated = text.match(FULL_GENERATE_PROMPT_ENVELOPE)
  if (fullGenerated) return attachmentPrefix + (fullGenerated[1] ?? '').trim()
  const brief = text.match(GENERATE_BRIEF_ENVELOPE)
  if (brief) return attachmentPrefix + ((brief[1] ?? '').trim() || '已提交大屏图生成请求。')
  const generated = text.match(GENERATE_ENVELOPE)
  return attachmentPrefix + (generated ? (generated[1] ?? '').trim() || '已提交大屏图生成请求。' : text)
}

export function classifyLargeScreenImagePresentation(input: { role: string; rawContent: string }): LargeScreenImagePresentation {
  if (input.role !== 'assistant') return { kind: 'markdown' }
  const content = input.rawContent.trim()
  const match = content.match(/^```large-screen-image-plan[ \t]*\r?\n([\s\S]*?)\r?\n```$/)
  if (!match) return content.includes('```large-screen-image-plan') ? { kind: 'invalid-template', reason: 'Skill 未返回一个完整的模板结果。' } : { kind: 'markdown' }
  try {
    const template = JSON.parse(match[1] ?? '')
    return typeof template === 'object' && template !== null && !Array.isArray(template)
      ? { kind: 'template', template: template as LargeScreenImageTemplate }
      : { kind: 'invalid-template', reason: 'Skill 返回的模板不是对象。' }
  } catch { return { kind: 'invalid-template', reason: 'Skill 返回的模板 JSON 无法读取。' } }
}

export function createLargeScreenAnalyzeSubmission(file: UploadedFileItem): LargeScreenImageSubmission | null {
  if (!SAFE_REFERENCE_FILE_ID.test(file.id)) return null
  const runtimeText = `[large-screen-image action=analyze ratio=16:9 referenceFileId=${file.id}]\n请根据当前参考图生成一份可编辑的大屏结构化模板 v2。`
  return { displayText: '已上传参考图，请分析其视觉风格并给出创作方案。', persistedText: runtimeText, runtimeText, titleText: '参考图识别', fileIds: [file.id], attachedFiles: [file] }
}

export function adaptLargeScreenImageSubmission({ text, fileIds, activeTemplate = null, referenceWorkflowActive = false }: LargeScreenImageSubmissionInput): LargeScreenImageSubmission | null {
  const prompt = text.trim()
  if (activeTemplate) {
    const businessPrompt = prompt || '请基于当前模板生成一版大屏图。'
    const templatePrompt = activeTemplate.template.prompt.trim() || compileLargeScreenTemplatePrompt(activeTemplate.template)
    const { prompt: _prompt, negativePrompt: _negativePrompt, ...templateState } = activeTemplate.template
    const runtimeText = `[large-screen-image action=generate referenceFileId=${activeTemplate.referenceFileId} templateMessageId=${activeTemplate.templateMessageId}]\n图像生成提示词：\n${templatePrompt}\n\n负向提示词：\n${activeTemplate.template.negativePrompt}\n\n结构化编辑状态（仅供 Agent 处理布局与可替换项；调用图像生成 Tool 时使用上方“图像生成提示词”，不要逐项转发本段）：\n${JSON.stringify(templateState)}\n\n用户创作需求：\n${businessPrompt}`
    return { displayText: businessPrompt, persistedText: runtimeText, runtimeText, titleText: activeTemplate.template.title || '参考图生图', fileIds: [activeTemplate.referenceFileId] }
  }
  if (referenceWorkflowActive) return null
  const referenceFileId = fileIds[0]
  if (referenceFileId && !SAFE_REFERENCE_FILE_ID.test(referenceFileId)) return null
  if (referenceFileId) {
    const businessPrompt = prompt || '请基于参考图生成一版大屏图。'
    const runtimeText = `[large-screen-image action=generate ratio=16:9 quality=high referenceFileId=${referenceFileId} referenceImageUrl=]\n用户创作需求：\n${businessPrompt}`
    return { displayText: prompt || '已上传参考图，请基于它生成一版大屏图。', persistedText: runtimeText, runtimeText, titleText: prompt || '参考图生图', fileIds: [referenceFileId] }
  }
  if (!prompt) return null
  const runtimeText = `[large-screen-image action=generate ratio=16:9 quality=high referenceFileId= referenceImageUrl=]\n用户创作需求：\n${prompt}`
  return { displayText: prompt, persistedText: runtimeText, runtimeText, titleText: prompt, fileIds: [] }
}

export function restoreLargeScreenImageTemplate(sessionId: string, messages: readonly ChatMessageVO[]): ActiveLargeScreenTemplateContext | null {
  let analyze: { message: ChatMessageVO; referenceFileId: string; referenceFile: UploadedFileItem } | null = null
  for (const message of messages) {
    if (message.sessionId !== sessionId || message.role !== 'user') continue
    const split = splitChatAttachmentContent(message.content ?? '')
    const match = split.text.match(/^\[large-screen-image action=analyze ratio=(?:16:9|21:9|9:16) referenceFileId=(\d+)\]\r?\n/)
    if (!match) continue
    const referenceFile = split.files.find((file): file is UploadedFileItem => typeof file === 'object' && file !== null && file.id === match[1])
    if (referenceFile) analyze = { message, referenceFileId: match[1]!, referenceFile }
  }
  if (!analyze) return null
  const index = messages.findIndex((message) => message === analyze!.message)
  for (const message of messages.slice(index + 1)) {
    if (message.sessionId !== sessionId || message.role !== 'assistant') continue
    const presentation = classifyLargeScreenImagePresentation({ role: 'assistant', rawContent: message.content ?? '' })
    if (presentation.kind === 'template') return { sessionId, referenceFileId: analyze.referenceFileId, referenceFile: analyze.referenceFile, analyzeUserMessageId: String(analyze.message.id), templateMessageId: String(message.id), template: presentation.template }
    if (presentation.kind === 'invalid-template') return null
  }
  return null
}
