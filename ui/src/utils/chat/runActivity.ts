import type { RunActivity } from '@/types'

/** 所有聊天在正文出现之前都显示运行进度。 */
export function shouldShowRunActivity(
  _isDiyChat: boolean,
  isRunning: boolean,
  hasVisibleAnswer: boolean,
): boolean {
  return isRunning && !hasVisibleAnswer
}

/** 阶段性文本已出现但任务未结束时，用紧凑保活状态替代大尺寸思考卡。 */
export function shouldShowRunWaiting(
  _isDiyChat: boolean,
  isRunning: boolean,
  hasVisibleAnswer: boolean,
): boolean {
  return isRunning && hasVisibleAnswer
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
export function shouldShowLegacyToolCall(_isDiyChat: boolean, needConfirm?: boolean): boolean {
  return needConfirm === true
}

export interface AggregatedRunActivity {
  id: string
  label: string
  status: RunActivity['status']
  count: number
  elapsed?: number
}

export function getActivityLabel(name: string): string {
  return {
    query_graph: '查询知识图谱',
    get_graph_summary: '读取图谱概况',
    load_skill_through_path: '读取专业知识指引',
    wren_query: '查询业务数据',
    wren_models: '准备分析能力',
  }[name] ?? name
}

export function getActivityDetail(activity?: RunActivity): string {
  if (!activity?.args || activity.name !== 'query_graph') return ''
  try {
    const args = JSON.parse(activity.args)
    if (typeof args.query === 'string' && args.query.trim()) return `关键词：${args.query.trim()}`
    if (args.mode === 'neighbors') return '检索相关实体与关系'
    if (args.mode === 'node') return '读取实体详情'
  } catch { /* 参数流尚未接收完整。 */ }
  return ''
}

/**
 * 计算运行卡片耗时。
 *
 * 已结束的运行必须使用最后一个有耗时步骤的结束时间，不能继续用当前时间，
 * 否则被保留展示的完成卡片会看起来仍在运行。
 */
export function getRunElapsedMs(
  activities: readonly RunActivity[],
  startedAt: number | null | undefined,
  now: number,
  isRunning: boolean,
): number {
  const fallbackStartedAt = activities.reduce(
    (earliest, activity) => Math.min(earliest, activity.startTime),
    now,
  )
  const effectiveStartedAt = startedAt ?? fallbackStartedAt
  if (isRunning) return Math.max(0, now - effectiveStartedAt)

  const lastKnownEnd = activities.reduce((latest, activity) => {
    if (activity.elapsed == null) return latest
    return Math.max(latest, activity.startTime + Math.max(0, activity.elapsed))
  }, effectiveStartedAt)
  return Math.max(0, lastKnownEnd - effectiveStartedAt)
}

/** 合并同一工具的重复调用，保留首次出现顺序和本轮最新状态。 */
export function aggregateRunActivities(activities: RunActivity[]): AggregatedRunActivity[] {
  const grouped = new Map<string, {
    id: string
    label: string
    count: number
    elapsed: number
    hasPending: boolean
    hasRunning: boolean
    hasFailed: boolean
  }>()

  for (const activity of activities) {
    const current = grouped.get(activity.name) || {
      id: activity.id,
      label: getActivityLabel(activity.name),
      count: 0,
      elapsed: 0,
      hasPending: false,
      hasRunning: false,
      hasFailed: false,
    }
    current.count += 1
    current.elapsed += activity.elapsed || 0
    current.hasPending ||= activity.status === 'pending'
    current.hasRunning ||= activity.status === 'running'
    current.hasFailed ||= activity.status === 'failed'
    grouped.set(activity.name, current)
  }

  return [...grouped.values()].map((activity) => ({
    id: activity.id,
    label: activity.label,
    status: activity.hasRunning ? 'running' : activity.hasFailed ? 'failed' : activity.hasPending ? 'pending' : 'completed',
    count: activity.count,
    elapsed: activity.elapsed || undefined,
  }))
}
