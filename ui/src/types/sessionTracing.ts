export type TracingResultStatus = 'COMPLETE' | 'PARTIAL' | 'ERROR'

export type TracingStatusTone = 'success' | 'warning' | 'error' | 'default'

export type TracingCursorStatus = 'DISCOVERED' | 'PROCESSING' | 'COMPLETE' | 'FAILED'

export interface TracingUserIdentity {
  userId: string
  nickname: string | null
  username: string | null
  email: string | null
}

export interface TracingUser extends TracingUserIdentity {
  conversationCount: number
  lastProcessedAt: string | null
}

export interface TracingPageQuery {
  userId?: string
  status?: TracingResultStatus
  page: number
  size: number
}

export interface TracingPageItem extends TracingUserIdentity {
  id: string
  status: TracingResultStatus
  turnCount: number
  firstUserQuestion: string
  lastAgentAnswer: string
  traceCount: number
  fullObservationCount: number
  processedAt: string
}

export interface TracingConversationTurn {
  turn: number
  userQuestion: string
  agentAnswer: string
  userTimestamp: string | null
  agentTimestamp: string | null
}

export interface TracingTraceSummary {
  traceCount: number
  seedObservationCount: number
  fullObservationCount: number
  scoreCount: number
  qaPairCount: number
  typeCounts: Record<string, number>
  warnings: string[]
  firstObservationStartTime: string | null
  lastObservationEndTime: string | null
  processedAt: string
}

export interface TracingDetail {
  id: string
  sessionId: string
  projectId: string | null
  user: TracingUserIdentity
  status: TracingResultStatus
  turns: TracingConversationTurn[]
  traceSummary: TracingTraceSummary
}

export interface TracingRaw {
  llmAnalysisJson: unknown
  qaPairsJson: unknown
  conversationJson: unknown
  envelopeJson: unknown
  warningsJson: unknown
}

export type TracingResultStatusCounts = Record<TracingResultStatus, number>

export type TracingCursorStatusCounts = Record<TracingCursorStatus, number>

export interface TracingSummary {
  resultStatusCounts: TracingResultStatusCounts
  cursorStatusCounts: TracingCursorStatusCounts
  staleProcessingCount: number
  lastProcessedAt: string | null
}
