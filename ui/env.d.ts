/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 「知识图谱」入口的智能体 agent_code 白名单，逗号分隔；未设置时使用内置默认值。 */
  readonly VITE_SEMANTICA_DOCTOR_AGENT_CODES?: string
}
