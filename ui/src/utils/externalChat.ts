export interface ClipboardEnvironment {
  navigator?: {
    clipboard?: {
      writeText(text: string): Promise<void>
    }
  }
  document?: Document
}

function normalizeContextPath(contextPath: string): string {
  const normalized = contextPath.trim().replace(/^\/+|\/+$/g, '')
  return normalized ? `/${normalized}` : ''
}

export function buildExternalChatUrl(origin: string, contextPath: string, chatKey: string): string {
  return `${origin.replace(/\/+$/, '')}${normalizeContextPath(contextPath)}/#/communication/${encodeURIComponent(chatKey)}`
}

export async function copyText(
  text: string,
  environment: ClipboardEnvironment = { navigator, document },
): Promise<void> {
  try {
    await environment.navigator?.clipboard?.writeText(text)
    if (environment.navigator?.clipboard?.writeText) return
  } catch {
    // HTTP 页面不具备 Clipboard API 权限时，降级到传统复制方式。
  }

  const doc = environment.document
  if (!doc) throw new Error('Clipboard is unavailable')

  const textarea = doc.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  Object.assign(textarea.style, {
    position: 'fixed',
    opacity: '0',
    pointerEvents: 'none',
  })
  doc.body.appendChild(textarea)
  textarea.select()
  const copied = doc.execCommand('copy')
  doc.body.removeChild(textarea)

  if (!copied) throw new Error('Clipboard copy failed')
}
