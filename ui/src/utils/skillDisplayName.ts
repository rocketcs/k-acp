/**
 * 仅用于中文界面展示。内部技能标识仍保持稳定，避免影响已绑定智能体、
 * 文件目录及运行时触发规则。
 */
const CHINESE_SKILL_NAMES: Record<string, string> = {
  code_review_assistant: '代码审查助手',
  data_analysis_advisor: '数据分析顾问',
  document_summarizer: '文档总结助手',
  meeting_action_items: '会议行动项助手',
  requirement_clarifier: '需求澄清助手',
  'tender-search': '标书搜索与分析',
  'tender-opportunity-analysis': '标书商机分析',
  'tender-high-recall-search': '标书高召回检索',
  'commercial-tender-followup-curator': '标书追问精选',
}

export function displaySkillName(name?: string | null): string {
  if (!name) return ''
  return CHINESE_SKILL_NAMES[name] || name
}
