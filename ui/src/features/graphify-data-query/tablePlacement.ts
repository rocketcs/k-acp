/**
 * 平台证据表格的正文内插入占位。
 *
 * Agent 在回答正文“关键信息摘要”之后写一个占位标记，前端据此把平台
 * 证据表格渲染到正文中间（摘要后、说明/可选项前），而不是固定在正文末尾。
 * 占位标记在渲染前从正文剔除，不会出现在用户看到的文本里。
 */

// 占位符：唯一、agent 易稳定生成、不会与正常业务文本冲突。
export const DATA_TABLE_PLACEHOLDER = '[[data-table]]'

export type TablePlacement = {
  /** 是否有占位符 */
  hasPlaceholder: boolean
  /** 占位符之前的正文（结论 + 关键信息摘要） */
  before: string
  /** 占位符之后的正文（说明 + 后续可选项） */
  after: string
}

/** 把回答正文在表格占位符处拆分；无占位符时整段作 before、after 为空。 */
export function splitAssistantContent(content: string): TablePlacement {
  const idx = content.indexOf(DATA_TABLE_PLACEHOLDER)
  if (idx === -1) {
    return { hasPlaceholder: false, before: content, after: '' }
  }
  return {
    hasPlaceholder: true,
    before: content.slice(0, idx).trim(),
    after: content.slice(idx + DATA_TABLE_PLACEHOLDER.length).trim(),
  }
}
