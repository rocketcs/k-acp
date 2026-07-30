import type { UploadedFileItem } from '@/types'

export const CHAT_FILE_CONTENT_SEPARATOR = '@==##::::##==@' as const

export interface SplitChatAttachmentContent {
  attachmentPrefix: string
  files: UploadedFileItem[]
  text: string
}

/** Splits the persisted attachment prefix from the user-visible message text. */
export function splitChatAttachmentContent(content: string): SplitChatAttachmentContent {
  const separatorIndex = content.indexOf(CHAT_FILE_CONTENT_SEPARATOR)
  if (separatorIndex === -1) {
    return { attachmentPrefix: '', files: [], text: content }
  }

  const attachmentPrefix = content.slice(0, separatorIndex + CHAT_FILE_CONTENT_SEPARATOR.length)
  const serializedFiles = content.slice(0, separatorIndex)

  try {
    const parsed = JSON.parse(serializedFiles) as { files?: unknown }
    if (!Array.isArray(parsed?.files)) {
      return { attachmentPrefix: '', files: [], text: content }
    }

    return {
      attachmentPrefix,
      files: parsed.files as UploadedFileItem[],
      text: content.slice(separatorIndex + CHAT_FILE_CONTENT_SEPARATOR.length),
    }
  } catch {
    return { attachmentPrefix: '', files: [], text: content }
  }
}

/** Reassembles a persisted attachment prefix with its user-visible message text. */
export function prependChatAttachmentContent(files: UploadedFileItem[], text: string): string {
  return JSON.stringify({ files }) + CHAT_FILE_CONTENT_SEPARATOR + text
}
