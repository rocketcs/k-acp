/**
 * 「知识图谱」入口（智能医生 / 医疗问数类智能体）的 agent_code 白名单。
 *
 * 智能体定义由各环境独立导入，主键 ID 因环境而异（本地是 default-doctor，
 * 测试环境是 default-hospital），因此入口必须按 agent_code 判断，不能写死 ID。
 * 可用环境变量 VITE_SEMANTICA_DOCTOR_AGENT_CODES（逗号分隔）覆盖或扩展白名单。
 */
export const DEFAULT_SEMANTICA_AGENT_CODES = ['default-doctor', 'default-hospital']

/** 解析逗号分隔的 agent_code 配置，去掉空白与空项。 */
export function parseSemanticaAgentCodes(raw?: string | null): string[] {
  return (raw ?? '')
    .split(',')
    .map((code) => code.trim())
    .filter((code) => code.length > 0)
}

/** 环境变量优先；未配置（或配置为空）时回退到内置白名单。 */
export function resolveSemanticaAgentCodes(raw?: string | null): string[] {
  const parsed = parseSemanticaAgentCodes(raw)
  return parsed.length > 0 ? parsed : [...DEFAULT_SEMANTICA_AGENT_CODES]
}

/** 该 agent_code 是否属于需要展示知识图谱入口的智能体。 */
export function isSemanticaAgent(agentCode?: string | null, codes?: readonly string[]): boolean {
  const code = agentCode?.trim()
  if (!code) return false
  return (codes ?? DEFAULT_SEMANTICA_AGENT_CODES).includes(code)
}
