/**
 * uip 协议解析工具
 *
 * 提供 uip 代码块提取、JSON 解析、消息构造等纯函数工具。
 *
 * @author huxuehao
 */

import type { UIPMessage, InteractionSubmitPayload, FormInteraction, ChoiceInteraction } from '@/components/markdown/uip/types'

export type UIPPolicy = 'default' | 'tenderStrict'

export interface UIPValidationResult {
  message: UIPMessage | null
  reason?: string
}

export interface NormalizedUIPContent {
  content: string
  validBlocks: UIPMessage[]
  invalidReasons: string[]
}

/** uip 代码块的 HTML 正则（renderMarkdown 后 `<pre><code class="language-uip">`） */
const UIP_HTML_REGEX = /<pre[^>]*>\s*<code[^>]*class="[^"]*language-uip[^"]*"[^>]*>([\s\S]*?)<\/code>\s*<\/pre>/gi
/** 原始 markdown 中的 ```uip 匹配（兜底）
 *  使用 \s* 替代 \n 以兼容 LLM 输出可能缺少尾部换行的情况 */
const UIP_MD_REGEX = /```uip\s*\n([\s\S]*?)\s*```/g
const UIP_BLOCK_START_REGEX = /```uip[ \t]*\r?\n/gi
const UIP_BLOCK_END_REGEX = /\r?\n```[ \t]*(?=\r?\n|$)/g
const SUPPORTED_FIELD_TYPES = new Set([
  'text', 'textarea', 'number', 'select', 'radio', 'checkbox',
  'checkbox-group', 'switch', 'date', 'datetime', 'email', 'tel',
])
const OPTION_FIELD_TYPES = new Set(['select', 'radio', 'checkbox-group'])

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function areValidOptions(value: unknown): boolean {
  if (!Array.isArray(value) || value.length === 0) return false
  const values = new Set<string>()

  return value.every((option) => {
    if (!isRecord(option) || !isNonEmptyString(option.label)) return false
    if (!isNonEmptyString(option.value)) return false
    if (values.has(option.value)) return false
    values.add(option.value)
    return true
  })
}

/**
 * 校验当前前端能够完整渲染的 UIP。它不尝试修复模型输出，失败只返回原因码。
 */
export function validateUIP(code: string, policy: UIPPolicy = 'default'): UIPValidationResult {
  let parsed: unknown
  try {
    parsed = JSON.parse(code)
  } catch {
    return { message: null, reason: 'json_parse_failed' }
  }

  if (!isRecord(parsed)) return { message: null, reason: 'invalid_root' }
  if (policy === 'tenderStrict' && typeof parsed.content !== 'string') {
    return { message: null, reason: 'invalid_content' }
  }
  if (!isRecord(parsed.interaction)) return { message: null, reason: 'invalid_interaction' }

  const interaction = parsed.interaction
  if (!isNonEmptyString(interaction.id)) return { message: null, reason: 'invalid_interaction_id' }
  if (!isNonEmptyString(interaction.type)) return { message: null, reason: 'unknown_interaction_type' }

  switch (interaction.type) {
    case 'choice':
      if (!isNonEmptyString(interaction.question)) {
        return { message: null, reason: 'invalid_choice_question' }
      }
      if (!areValidOptions(interaction.options)) {
        return { message: null, reason: 'invalid_choice_options' }
      }
      break
    case 'form': {
      if (!Array.isArray(interaction.fields) || interaction.fields.length === 0) {
        return { message: null, reason: 'invalid_form_fields' }
      }
      const names = new Set<string>()
      for (const field of interaction.fields) {
        if (!isRecord(field) || !isNonEmptyString(field.name) || !isNonEmptyString(field.label)) {
          return { message: null, reason: 'invalid_form_field' }
        }
        if (!isNonEmptyString(field.type) || !SUPPORTED_FIELD_TYPES.has(field.type)) {
          return { message: null, reason: 'unsupported_form_field_type' }
        }
        if (names.has(field.name)) return { message: null, reason: 'duplicate_form_field_name' }
        names.add(field.name)
        if (OPTION_FIELD_TYPES.has(field.type) && !areValidOptions(field.options)) {
          return { message: null, reason: 'invalid_form_field_options' }
        }
        if (field.options !== undefined && !areValidOptions(field.options)) {
          return { message: null, reason: 'invalid_form_field_options' }
        }
      }
      break
    }
    case 'confirm':
      if (!isNonEmptyString(interaction.message)) {
        return { message: null, reason: 'invalid_confirm_message' }
      }
      break
    default:
      return { message: null, reason: 'unknown_interaction_type' }
  }

  return { message: parsed as unknown as UIPMessage }
}

/**
 * 规范化原始 Markdown 中的 UIP 块：只保留当前前端可完整渲染的卡片。
 * 未闭合的 UIP 围栏在终态会被作为无效块删除至文本末尾，避免泄露截断 JSON。
 */
export function normalizeUIPContent(content: string, policy: UIPPolicy = 'default'): NormalizedUIPContent {
  const validBlocks: UIPMessage[] = []
  const invalidReasons: string[] = []
  const keptParts: string[] = []
  const startRegex = new RegExp(UIP_BLOCK_START_REGEX.source, UIP_BLOCK_START_REGEX.flags)
  let cursor = 0
  let match: RegExpExecArray | null

  while ((match = startRegex.exec(content)) !== null) {
    keptParts.push(content.slice(cursor, match.index))
    const codeStart = startRegex.lastIndex
    const endRegex = new RegExp(UIP_BLOCK_END_REGEX.source, UIP_BLOCK_END_REGEX.flags)
    endRegex.lastIndex = codeStart
    const endMatch = endRegex.exec(content)

    if (!endMatch) {
      invalidReasons.push('unclosed')
      cursor = content.length
      break
    }

    const code = content.slice(codeStart, endMatch.index).trim()
    const validation = validateUIP(code, policy)
    if (validation.message && (policy !== 'tenderStrict' || validBlocks.length === 0)) {
      keptParts.push(content.slice(match.index, endMatch.index + endMatch[0].length))
      validBlocks.push(validation.message)
    } else if (validation.message) {
      invalidReasons.push('duplicate_tender_card')
    } else {
      invalidReasons.push(validation.reason || 'invalid_uip')
    }

    cursor = endMatch.index + endMatch[0].length
    startRegex.lastIndex = cursor
  }

  keptParts.push(content.slice(cursor))
  return {
    content: keptParts.join('').trim(),
    validBlocks,
    invalidReasons,
  }
}

/** 仅有损坏 UIP 也表示模型尝试请求用户交互，问标助手应改用稳定默认卡。 */
export function needsTenderFallback(normalized: NormalizedUIPContent): boolean {
  return normalized.validBlocks.length === 0
    && (normalized.content.trim().length > 0 || normalized.invalidReasons.length > 0)
}

/**
 * 高召回工作流只提供经过确定性整理的事实正文；下一步卡片只能来自外层
 * 策展 Skill 的最终输出。这样工作流中的历史卡片不会覆盖动态策展结果。
 */
export function composeTenderResponse(primaryContent: string, curatorContent: string): string {
  const primary = stripUIPBlock(normalizeUIPContent(primaryContent, 'tenderStrict').content).trim()
  const curator = normalizeUIPContent(curatorContent, 'tenderStrict')
  const curatorCard = curator.validBlocks[0]

  if (!primary) return curator.content
  if (!curatorCard) return primary

  return `${primary}\n\n${wrapUIPBlock(JSON.stringify(curatorCard))}`
}

/** HTML 实体解码 */
function decodeHtmlEntities(str: string): string {
  return str
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}

/** 剥离 HTML 标签（语法高亮注入的 span 等） */
function stripHtmlTags(str: string): string {
  return str.replace(/<[^>]*>/g, '')
}

/** uip 代码块外封套正则（从外层 div.md-code-block 到闭合 div，精准移除整个代码块） */
const CODE_BLOCK_WRAPPER_RE = /<div class="md-code-block"(?!-html)[^>]*>/g

/**
 * 从渲染后 HTML 中提取 uip 代码块
 */
export function extractUIPBlocks(
  html: string
): Array<{ placeholder: string; code: string; fullMatch: string }> {
  const blocks: Array<{ placeholder: string; code: string; fullMatch: string }> = []
  const regex = new RegExp(UIP_HTML_REGEX.source, UIP_HTML_REGEX.flags)
  let match: RegExpExecArray | null
  let idx = 0

  while ((match = regex.exec(html)) !== null) {
    const preBlock = match[0]
    const preStart = match.index
    const preEnd = preStart + preBlock.length

    // 向前查找外层 <div class="md-code-block" 封套（排除 -html 变体）
    const beforeText = html.substring(0, preStart)
    CODE_BLOCK_WRAPPER_RE.lastIndex = 0
    let wrapperMatch: RegExpExecArray | null
    let lastWrapperStart = -1
    while ((wrapperMatch = CODE_BLOCK_WRAPPER_RE.exec(beforeText)) !== null) {
      lastWrapperStart = wrapperMatch.index
    }

    let fullMatch: string
    if (lastWrapperStart !== -1) {
      // 向后查找对应的闭合标签
      const afterText = html.substring(preEnd)
      const closingIdx = afterText.indexOf('</div>')
      if (closingIdx !== -1) {
        fullMatch = html.substring(lastWrapperStart, preEnd + closingIdx + 6)
      } else {
        fullMatch = preBlock
      }
    } else {
      fullMatch = preBlock
    }

    const code = stripHtmlTags(decodeHtmlEntities((match[1] || '').trim()))
    blocks.push({ placeholder: `__UIP_PLACEHOLDER_${idx}__`, code, fullMatch })
    idx++
  }
  return blocks
}

/**
 * 安全解析 UIP JSON，失败返回 null
 */
export function parseUIPJson(code: string): UIPMessage | null {
  try {
    const parsed = JSON.parse(code)
    if (
      parsed &&
      typeof parsed === 'object' &&
      parsed.interaction &&
      !Array.isArray(parsed.interaction)
    ) {
      return parsed as UIPMessage
    }
    return null
  } catch {
    return null
  }
}

/**
 * 构造用户交互提交消息的 content 字段
 * 格式参考 ChatLogHook.getLongTextContent: {"reasoning":"","content":"..."}
 */
export function buildInteractionContent(uipJson: string): string {
  return JSON.stringify({ reasoning: '', content: uipJson })
}

/**
 * 将用户交互数据转为自然语言消息文本（发送给 agent 继续对话）
 * 优先使用 uip 中定义的 label，无 label 时回退为原始字段名/值
 */
export function buildUserTextFromPayload(payload: InteractionSubmitPayload): string {
  const { type, data, uipCode } = payload
  if (type === 'confirm') {
    const { confirmed } = data
    return confirmed ? '已确认' : '已取消'
  }

  // 解析 uipCode 获取 label 映射
  const uip = parseUIPJson(uipCode)

  if (type === 'choice') {
    const d = data as { values: string[]; customInput?: string }
    const parts: string[] = []
    // 优先用选项 label
    if (uip) {
      const interaction = uip.interaction as ChoiceInteraction
      const optionMap = new Map(interaction.options?.map(o => [o.value, o.label]) || [])
      for (const v of d.values) {
        parts.push(optionMap.get(v) || v)
      }
    } else {
      parts.push(...d.values)
    }
    if (d.customInput) parts.push(d.customInput)
    return `已选择：${parts.join('，')}`
  }

  if (type === 'form') {
    // 构建 name→label 映射，以及 select/radio 的 value→label 映射
    const labelMap: Map<string, string> = new Map()
    const optionMaps: Map<string, Map<string, string>> = new Map()
    if (uip) {
      const interaction = uip.interaction as FormInteraction
      for (const f of interaction.fields || []) {
        labelMap.set(f.name, f.label)
        if (f.options && f.options.length > 0) {
          optionMaps.set(f.name, new Map(f.options.map(o => [String(o.value), o.label])))
        }
      }
    }

    const entries = Object.entries(data)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([name, value]) => {
        const displayLabel = labelMap.get(name) || name
        // 数组值用逗号拼接（checkbox-group）
        if (Array.isArray(value)) {
          const optMap = optionMaps.get(name)
          const labelValues = value.map((v: unknown) => optMap ? (optMap.get(String(v)) || String(v)) : String(v))
          return `${displayLabel}=${labelValues.join('、')}`
        }
        // 单值：有选项映射则用 label，否则直接用值
        const optMap = optionMaps.get(name)
        const displayValue = optMap ? (optMap.get(String(value)) || String(value)) : String(value)
        return `${displayLabel}=${displayValue}`
      })
    if (entries.length === 0) return '已提交表单'
    return `已填写表单：${entries.join(', ')}`
  }
  return ''
}

/**
 * 从保存的消息 content 中恢复 UIP 代码块字符串
 * content 格式: {"reasoning":"","content":"```uip\\n{...}\\n```"}
 */
export function extractUIPFromSavedContent(content: string): string | null {
  try {
    const parsed = JSON.parse(content)
    if (parsed && typeof parsed === 'object' && parsed.content) {
      const inner = parsed.content as string
      const match = UIP_MD_REGEX.exec(inner)
      UIP_MD_REGEX.lastIndex = 0
      if (match) return match[1] ?? null
      if (inner.trim().startsWith('{')) return inner.trim()
    }
    return null
  } catch {
    return null
  }
}

/**
 * 将用户提交数据回填到原始 UIP JSON 中
 */
export function fillUIPWithUserData(
  uipCode: string,
  _interactionId: string,
  data: Record<string, unknown>
): string {
  const msg = parseUIPJson(uipCode)
  if (!msg || !msg.interaction) return uipCode

  const interaction = msg.interaction
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const interactionAny = interaction as any

  if (interaction.type === 'form') {
    interactionAny.submittedData = data
    if (interaction.fields) {
      for (const field of interactionAny.fields) {
        if (data[field.name] !== undefined) {
          field.defaultValue = data[field.name]
        }
      }
    }
  } else if (interaction.type === 'choice') {
    interactionAny.submittedData = data
  } else if (interaction.type === 'confirm') {
    interactionAny.submittedData = data
  }

  return JSON.stringify(msg)
}

/**
 * 在 raw markdown content 中定位 ```uip 代码块，对匹配 interactionId 的块注入 submittedData
 *
 * @param content       原始 markdown 消息内容
 * @param interactionId 目标 UIP 交互组件 ID
 * @param submittedData 用户提交的数据
 * @returns 注入 submittedData 后的 content，未匹配时返回原 content
 */
export function injectSubmissionToRawContent(
  content: string,
  interactionId: string,
  submittedData: Record<string, unknown>
): string {
  UIP_MD_REGEX.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = UIP_MD_REGEX.exec(content)) !== null) {
    const fullBlock = match[0]
    const blockStart = match.index
    const jsonStr = (match[1] || '').trim()
    const uip = parseUIPJson(jsonStr)
    if (!uip || !uip.interaction) continue

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const interaction = uip.interaction as any
    if (interaction.id !== interactionId) continue

    try {
      const updatedJson = fillUIPWithUserData(jsonStr, interactionId, submittedData)
      const updatedBlock = wrapUIPBlock(updatedJson)
      // 子串拼接精准替换，避免相同文本的误替换
      return content.substring(0, blockStart) + updatedBlock + content.substring(blockStart + fullBlock.length)
    } catch {
      continue
    }
  }

  return content
}

/**
 * 从文本中剔除 uip 代码块
 */
export function stripUIPBlock(text: string): string {
  UIP_MD_REGEX.lastIndex = 0
  return text.replace(UIP_MD_REGEX, '').trim()
}

/**
 * 判断文本是否包含 uip 代码块
 */
export function hasUIPBlock(text: string): boolean {
  UIP_MD_REGEX.lastIndex = 0
  return UIP_MD_REGEX.test(text)
}

/**
 * 构造 uip 代码块字符串（含标记）
 */
export function wrapUIPBlock(json: string): string {
  return '```uip\n' + json + '\n```'
}
