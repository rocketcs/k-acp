/**
 * UIP (Apboa Interaction Protocol) 协议类型定义
 *
 * @author huxuehao
 */

/** 顶层 UIP 消息 */
export interface UIPMessage {
  role: 'assistant'
  content: string
  version: '2.0'
  interaction: InteractionItem
}

/** 交互项联合类型 */
export type InteractionItem = FormInteraction | ChoiceInteraction | ConfirmInteraction

export interface FormInteraction {
  id: string
  type: 'form'
  schemaVersion: string
  props?: InteractionProps
  fields: FormField[]
  /** 提交后回填的用户数据（历史消息只读用） */
  submittedData?: Record<string, unknown>
}

export interface ChoiceInteraction {
  id: string
  type: 'choice'
  question: string
  multiple?: boolean
  allowCustom?: boolean
  /** 单选项点击后立即提交，不显示额外确认按钮 */
  autoSubmit?: boolean
  options: ChoiceOption[]
  /** 提交后回填的用户数据 */
  submittedData?: { values: string[]; customInput?: string }
}

export interface ConfirmInteraction {
  id: string
  type: 'confirm'
  message: string
  confirmLabel?: string
  cancelLabel?: string
  payload?: Record<string, unknown>
  /** 提交后回填 */
  submittedData?: { confirmed: boolean; payload?: Record<string, unknown> }
}

export interface InteractionProps {
  title?: string
  submitLabel?: string
  cancelLabel?: string
  disabled?: boolean
  readonly?: boolean
}

export interface FormField {
  name: string
  label: string
  type: FieldType
  required?: boolean
  placeholder?: string
  defaultValue?: unknown
  options?: FieldOption[]
  multiple?: boolean
  dependsOn?: DependsOn
  validations?: ValidationRule[]
  helpText?: string
  disabled?: boolean
  readonly?: boolean
  hidden?: boolean
  errorText?: string
}

export type FieldType =
  | 'text'
  | 'textarea'
  | 'number'
  | 'select'
  | 'radio'
  | 'checkbox'
  | 'checkbox-group'
  | 'switch'
  | 'date'
  | 'datetime'
  | 'email'
  | 'tel'

export interface FieldOption {
  value: string | number
  label: string
  description?: string
  disabled?: boolean
}

export interface DependsOn {
  field: string
  value: string | number | boolean
}

export interface ValidationRule {
  type: 'required' | 'pattern' | 'min' | 'max' | 'email' | 'tel'
  value?: number | string
  message: string
}

export interface ChoiceOption {
  value: string
  label: string
  description?: string
  disabled?: boolean
}

/** 交互结果数据 */
export interface InteractionSubmitPayload {
  interactionId: string
  type: 'form' | 'choice' | 'confirm'
  data: Record<string, unknown>
  /** 原始 uip 代码块 JSON 内容（不含 ``` 标记），用于构造保存消息 */
  uipCode: string
}
