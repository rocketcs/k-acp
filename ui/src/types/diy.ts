export type DiyOutputFormat = 'TEXT' | 'ECHARTS_BAR' | 'ECHARTS_PIE' | 'JSON'

export type DiyPlaceholderInputType = 'INPUT' | 'TEXTAREA' | 'SELECT'

export interface DiyPlaceholderConfig {
  name: string
  inputType: DiyPlaceholderInputType
  required: boolean
  placeholder?: string
  defaultValue?: string
  options?: string[]
}

export interface DiyQuestionCard {
  id: string
  title: string
  description?: string
  icon?: string
  template: string
  enabled: boolean
  placeholders: DiyPlaceholderConfig[]
}

export interface DiyPageConfig {
  headline: string
  description?: string
  inputPlaceholder?: string
  questions: DiyQuestionCard[]
}

export interface AgentDiyPageConfigVO {
  id?: string
  agentDefinitionId: string
  draftConfig: DiyPageConfig | null
  publishedConfig: DiyPageConfig | null
  publishedAt?: string | null
  enabled: boolean
  updatedAt?: string
}
