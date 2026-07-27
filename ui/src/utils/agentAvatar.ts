const FALLBACK_AGENT_AVATAR = 'agent-avatar-01.png'

/**
 * 将后端保存的头像文件名解析为适配当前 Vite base 的静态资源地址。
 */
export function resolveAgentAvatar(avatar?: string | null): string {
  const filename = avatar?.trim() || FALLBACK_AGENT_AVATAR
  return `${import.meta.env.BASE_URL}agent-avatars/${encodeURIComponent(filename)}`
}
