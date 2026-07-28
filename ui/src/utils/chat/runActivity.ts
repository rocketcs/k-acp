import type { RunActivity } from '@/types'

/** 思考卡仅属于 DIY Chat，且只用于回答正文出现之前的等待阶段。 */
export function shouldShowRunActivity(
  isDiyChat: boolean,
  isRunning: boolean,
  hasVisibleAnswer: boolean,
): boolean {
  return isDiyChat && isRunning && !hasVisibleAnswer
}

/** 阶段性文本已出现但任务未结束时，用紧凑保活状态替代大尺寸思考卡。 */
export function shouldShowRunWaiting(
  isDiyChat: boolean,
  isRunning: boolean,
  hasVisibleAnswer: boolean,
): boolean {
  return isDiyChat && isRunning && hasVisibleAnswer
}

/** DIY Chat 运行时用状态卡代替输入框；普通 Chat 保持原交互。 */
export function shouldShowChatInput(isDiyChat: boolean, isRunning: boolean): boolean {
  return !isDiyChat || !isRunning
}

/** DIY 追问卡只提供设计好的选项，普通 Chat 仍可按协议展示自定义输入。 */
export function shouldShowChoiceCustomInput(isDiyChat: boolean, allowCustom?: boolean): boolean {
  return !isDiyChat && allowCustom === true
}

/** 普通 Chat 保留原工具条；DIY Chat 只在需要用户确认时展示它。 */
export function shouldShowLegacyToolCall(isDiyChat: boolean, needConfirm?: boolean): boolean {
  return !isDiyChat || needConfirm === true
}

export interface AggregatedRunActivity {
  id: string
  label: string
  status: RunActivity['status']
  count: number
  elapsed?: number
}

const ACTIVITY_LABELS: Record<string, string> = {
  wren_context_instructions: '加载查询上下文',
  wren_models: '准备分析能力',
  wren_memory_recall: '检索相关信息',
  wren_query: '查询业务数据',
}

/** 将技术工具名转为面向业务用户的动作；未知工具不暴露内部实现名称。 */
export function getActivityLabel(name: string): string {
  return ACTIVITY_LABELS[name] || '执行辅助操作'
}

/** 合并同一工具的重复调用，保留首次出现顺序和本轮最新状态。 */
export function aggregateRunActivities(activities: RunActivity[]): AggregatedRunActivity[] {
  const grouped = new Map<string, {
    id: string
    label: string
    count: number
    elapsed: number
    hasRunning: boolean
    hasFailed: boolean
  }>()

  for (const activity of activities) {
    const current = grouped.get(activity.name) || {
      id: activity.id,
      label: getActivityLabel(activity.name),
      count: 0,
      elapsed: 0,
      hasRunning: false,
      hasFailed: false,
    }
    current.count += 1
    current.elapsed += activity.elapsed || 0
    current.hasRunning ||= activity.status === 'running'
    current.hasFailed ||= activity.status === 'failed'
    grouped.set(activity.name, current)
  }

  return [...grouped.values()].map((activity) => ({
    id: activity.id,
    label: activity.label,
    status: activity.hasRunning ? 'running' : activity.hasFailed ? 'failed' : 'completed',
    count: activity.count,
    elapsed: activity.elapsed || undefined,
  }))
}
