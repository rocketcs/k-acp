export interface AssistantTextPresentation {
  hasContent: boolean
  isSelectedTurn: boolean
  hasEvidence: boolean
}

/**
 * 答案正文始终渲染（它承载结论、说明与后续选项）；证据表格另行展示。
 * 不再因选中+有证据而隐藏正文——此前会把智能体的结论和说明一起藏掉。
 */
export function shouldRenderAssistantText({ hasContent }: AssistantTextPresentation): boolean {
  return hasContent
}

export function shouldRenderAssistantPlaceholder({ hasContent, hasEvidence }: Pick<AssistantTextPresentation, 'hasContent' | 'hasEvidence'>): boolean {
  return !hasContent && !hasEvidence
}
