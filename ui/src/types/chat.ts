import type { Component } from 'vue'

export type ChatMessagePresentation =
  | { kind: 'markdown'; content: string }
  | { kind: 'custom'; component: Component; props: Record<string, unknown> }

export interface ChatMessagePresentationInput {
  id: string
  role: DisplayMessage['role']
  content: string
  rawContent: string
  isStreaming: boolean
  isCurrent: boolean
}

// 扩展消息类型用于展示（含流式标记）
export interface DisplayMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool' | 'thinking'
  content: string
  createdAt?: string
  isStreaming?: boolean
  presentation?: ChatMessagePresentation
}

/** 一次运行期间面向用户展示的工具执行状态 */
export interface RunActivity {
  id: string
  name: string
  args: string
  status: 'running' | 'completed' | 'failed'
  startTime: number
  elapsed?: number
}
