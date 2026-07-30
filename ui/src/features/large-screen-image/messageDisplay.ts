import { splitChatAttachmentContent } from '../../utils/chat/messageContent.ts'

export interface LargeScreenImageMessageContent {
  role: string
  content: string
}

const ANALYZE_INSTRUCTIONS = [
  '请根据当前参考图生成一份可编辑的大屏结构化模板 v2。',
  '请根据当前参考图生成一份可编辑的大屏创作方案。',
]
const ANALYZE_DISPLAY_TEXT = '已上传参考图，请分析其视觉风格并给出创作方案。'
const ANALYZE_ENVELOPE = /^\[large-screen-image action=analyze(?: [^\]\r\n]*)?\]\r?\n([\s\S]*)$/
const GENERATE_BRIEF_ENVELOPE = /^\[large-screen-image action=generate(?: [^\]\r\n]*)?\]\r?\n用户创作需求：\r?\n([\s\S]*)$/
const GENERATE_ENVELOPE = /^\[large-screen-image action=generate(?: [^\]\r\n]*)?\]\r?\n正向提示词：\r?\n([\s\S]*?)\r?\n\r?\n负向提示词：\r?\n[\s\S]*$/

/**
 * Converts historic large-screen control envelopes into ordinary Chat text.
 * The persisted payload remains untouched so replaying a session preserves its runtime contract.
 */
export function formatLargeScreenImageMessageContent({
  role,
  content,
}: LargeScreenImageMessageContent): string {
  if (role !== 'user') return content
  const { attachmentPrefix, text } = splitChatAttachmentContent(content)
  const display = formatLargeScreenImageMessageBody(text)
  return attachmentPrefix + display
}

function formatLargeScreenImageMessageBody(content: string): string {
  const analyze = content.match(ANALYZE_ENVELOPE)
  if (analyze) {
    const analyzeContent = analyze[1] ?? ''
    const instruction = ANALYZE_INSTRUCTIONS.find((value) => analyzeContent.startsWith(value))
    const requirement = instruction ? analyzeContent.slice(instruction.length).trim() : ''
    return requirement || ANALYZE_DISPLAY_TEXT
  }

  const generateBrief = content.match(GENERATE_BRIEF_ENVELOPE)
  if (generateBrief) return (generateBrief[1] ?? '').trim() || '已提交大屏图生成请求。'

  const generate = content.match(GENERATE_ENVELOPE)
  if (generate) return (generate[1] ?? '').trim() || '已提交大屏图生成请求。'

  return content
}
