/**
 * 聊天输入框附件管理 Composable
 * 负责附件类型校验、上传、删除等逻辑
 *
 * @author huxuehao
 */

import { message } from 'ant-design-vue'
import type { UploadedFileItem } from '@/types'

/** 可用于校验附件选择的最小文件形状。 */
export interface ChatAttachmentFileLike {
  name: string
  size: number
  type: string
}

/** 附件选择限制策略。未设置或无效的数值限制不会生效。 */
export interface ChatAttachmentPolicy {
  maxFileCount?: number
  maxFileSizeBytes?: number
  /** 指定时覆盖智能体的通用扩展名列表。 */
  allowedExtensions?: string[]
  /** 精确允许的 MIME 类型；与前缀规则同时配置时均需满足。 */
  allowedMimeTypes?: string[]
  allowedMimeTypePrefixes?: string[]
}

export type ChatAttachmentRejectionReason = 'extension' | 'mimeType' | 'size' | 'count'

export interface ChatAttachmentValidationResult<T extends ChatAttachmentFileLike> {
  accepted: T[]
  rejected: Array<{ file: T; reason: ChatAttachmentRejectionReason }>
}

/**
 * useChatAttachments 选项
 */
export interface UseChatAttachmentsOptions {
  /** 获取当前附件列表 */
  getFiles: () => UploadedFileItem[]
  /** 写回附件列表（用于 v-model:uploadedFiles 透传） */
  setFiles: (files: UploadedFileItem[]) => void
  /** 获取允许的文件扩展名列表（不含点号，例如 ['png','jpg']） */
  getAllowedTypes: () => string[] | undefined
  /** 获取可复用的附件选择限制策略 */
  getAttachmentPolicy?: () => ChatAttachmentPolicy | undefined
  /** 上传实现仅用于注入测试；默认仍延迟加载附件 API。 */
  uploadFile?: (file: File) => Promise<string | null | undefined>
  /** 删除实现仅用于注入测试；默认仍延迟加载附件 API。 */
  removeFiles?: (ids: string[]) => Promise<unknown>
  onUploadComplete?: (file: UploadedFileItem) => void
  onAttachmentRemoved?: (file: UploadedFileItem) => void
}

/**
 * 格式化文件大小显示
 *
 * @param bytes 字节数
 * @return 可读大小字符串
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`
}

/**
 * 从文件名解析扩展名（小写，不含点号）
 *
 * @param fileName 文件名
 * @return 扩展名
 */
export function getExtension(fileName: string): string {
  const lastDot = fileName.lastIndexOf('.')
  return lastDot > -1 ? fileName.slice(lastDot + 1).toLowerCase() : ''
}

/**
 * 按扩展名和可选策略筛选待上传文件。
 */
export function validateChatAttachmentSelection<T extends ChatAttachmentFileLike>(
  files: readonly T[],
  currentFileCount: number,
  allowedExtensions: string[] | undefined,
  policy?: ChatAttachmentPolicy,
): ChatAttachmentValidationResult<T> {
  const policyExtensions = policy?.allowedExtensions
    ?.map((extension) => extension.trim().toLowerCase())
    .filter((extension) => extension.length > 0)
  const allowed = policyExtensions?.length
    ? policyExtensions
    : allowedExtensions?.map((extension) => extension.toLowerCase())
  const maxFileCount = Number.isInteger(policy?.maxFileCount) && (policy?.maxFileCount ?? 0) > 0
    ? policy?.maxFileCount
    : undefined
  const maxFileSizeBytes = Number.isFinite(policy?.maxFileSizeBytes) && (policy?.maxFileSizeBytes ?? 0) > 0
    ? policy?.maxFileSizeBytes
    : undefined
  const allowedMimeTypes = policy?.allowedMimeTypes
    ?.map((mimeType) => mimeType.trim().toLowerCase())
    .filter((mimeType) => mimeType.length > 0)
  const allowedMimeTypePrefixes = policy?.allowedMimeTypePrefixes?.filter((prefix) => prefix.length > 0)
  const accepted: T[] = []
  const rejected: Array<{ file: T; reason: ChatAttachmentRejectionReason }> = []

  for (const file of files) {
    if (allowed?.length && !allowed.includes(getExtension(file.name))) {
      rejected.push({ file, reason: 'extension' })
    } else if (allowedMimeTypes?.length && !allowedMimeTypes.includes(file.type.toLowerCase())) {
      rejected.push({ file, reason: 'mimeType' })
    } else if (allowedMimeTypePrefixes?.length && !allowedMimeTypePrefixes.some((prefix) => file.type.startsWith(prefix))) {
      rejected.push({ file, reason: 'mimeType' })
    } else if (maxFileSizeBytes !== undefined && file.size > maxFileSizeBytes) {
      rejected.push({ file, reason: 'size' })
    } else if (maxFileCount !== undefined && currentFileCount + accepted.length >= maxFileCount) {
      rejected.push({ file, reason: 'count' })
    } else {
      accepted.push(file)
    }
  }

  return { accepted, rejected }
}

/**
 * 格式化附件校验失败提示。未启用策略时保留原有的扩展名提示文案。
 */
export function formatChatAttachmentRejectionWarning<T extends ChatAttachmentFileLike>(
  rejected: readonly { file: T; reason: ChatAttachmentRejectionReason }[],
  policy?: ChatAttachmentPolicy,
): string {
  if (!policy && rejected.every(({ reason }) => reason === 'extension')) {
    return `以下文件类型不允许上传: ${rejected.map(({ file }) => file.name).join(', ')}`
  }

  return `以下文件未上传: ${rejected.map(({ file, reason }) => `${file.name}（${reason}）`).join(', ')}`
}

/**
 * 聊天附件管理 Composable
 *
 * @param opts 选项
 * @return 附件操作方法
 */
export function useChatAttachments(opts: UseChatAttachmentsOptions) {
  const completedUploadIds = new Set<string>()

  const notify = (callback: ((file: UploadedFileItem) => void) | undefined, file: UploadedFileItem) => {
    if (!callback) return
    try {
      void Promise.resolve(callback(file)).catch(() => {})
    } catch {
      // 可选回调不得影响已经完成的本地附件状态。
    }
  }

  const uploadFile = async (file: File): Promise<string | null | undefined> => {
    if (opts.uploadFile) return opts.uploadFile(file)
    const attachApi = await import('@/api/attach')
    const result = await attachApi.upload(file)
    return result?.data?.data
  }

  const removeFiles = async (ids: string[]) => {
    if (opts.removeFiles) return opts.removeFiles(ids)
    const attachApi = await import('@/api/attach')
    return attachApi.remove(ids)
  }

  /**
   * 检查文件类型是否在允许列表中
   */
  const isFileTypeAllowed = (extension: string): boolean => {
    const allowed = opts.getAllowedTypes()
    if (!allowed?.length) return true
    return allowed.some((t) => t.toLowerCase() === extension)
  }

  /**
   * 根据允许的类型生成 input accept 属性值
   */
  const fileAcceptAttr = (): string => {
    const allowedMimeTypes = Array.from(new Set(
      opts.getAttachmentPolicy?.()?.allowedMimeTypes
        ?.map((mimeType) => mimeType.trim().toLowerCase())
        .filter((mimeType) => mimeType.length > 0) ?? [],
    ))
    if (allowedMimeTypes.length > 0) return allowedMimeTypes.join(',')

    const allowed = opts.getAllowedTypes()
    if (!allowed?.length) return '*/*'
    return allowed.map((t) => `.${t}`).join(',')
  }

  /**
   * 处理已选择或拖放的文件，支持批量上传与上传中状态展示。
   * 文件选择器和拖放入口必须共用此路径，保证校验、状态和失败清理一致。
   */
  const handleFiles = async (files: readonly File[]) => {
    if (!files.length) return

    const current = opts.getFiles()
    const attachmentPolicy = opts.getAttachmentPolicy?.()
    const result = validateChatAttachmentSelection(
      files,
      current.length,
      opts.getAllowedTypes(),
      attachmentPolicy,
    )
    const allowedFiles = result.accepted
    if (result.rejected.length > 0) {
      message.warning(formatChatAttachmentRejectionWarning(result.rejected, attachmentPolicy))
    }
    if (allowedFiles.length === 0) {
      return
    }

    const newList = [...current]
    const tempIds: string[] = []

    // 立即将文件加入列表并显示（上传中状态）
    for (let i = 0; i < allowedFiles.length; i++) {
      const file = allowedFiles[i]
      if (!file) continue
      const tempId = `temp-${Date.now()}-${i}-${Math.random().toString(36).slice(2, 9)}`
      tempIds.push(tempId)
      newList.push({
        id: tempId,
        name: file.name,
        extension: getExtension(file.name),
        size: formatFileSize(file.size),
        uploading: true
      })
    }
    opts.setFiles(newList)

    // 后台逐个上传，完成后更新对应项
    for (let i = 0; i < allowedFiles.length; i++) {
      const file = allowedFiles[i]
      const tempId = tempIds[i]
      if (!file || tempId === undefined) continue
      try {
        const data = await uploadFile(file)
        if (data) {
          // 文档类型需要同步等待服务端文本提取完成，再标记上传结束
          // 防止用户误以为已上传完成而提前发送或删除，导致僵尸 .apboa 文件
          const ext = getExtension(file.name)
          const docExtensions = ['doc', 'docx', 'xlsx', 'xls', 'csv', 'pptx', 'ppt', 'pdf', 'txt', 'md']
          if (docExtensions.includes(ext)) {
            let parseSuccess = false
            try {
              const attachApi = await import('@/api/attach')
              const parseRes = await attachApi.parseText(data)
              parseSuccess = !!parseRes?.data?.data
            } catch {
              // parseText 接口调用异常，视为解析失败
            }

            if (!parseSuccess) {
              // 解析失败：删除后端附件并从列表移除
              removeFiles([data]).catch(() => {})
              const filtered = opts.getFiles().filter((f) => f.id !== tempId)
              opts.setFiles(filtered)
              continue
            }
          }

          let uploadedFile: UploadedFileItem | undefined
          const updated = opts.getFiles().map((item) => {
            if (item.id !== tempId) return item
            uploadedFile = { ...item, id: data, uploading: false }
            return uploadedFile
          })
          opts.setFiles(updated)
          if (uploadedFile && !completedUploadIds.has(uploadedFile.id)) {
            completedUploadIds.add(uploadedFile.id)
            notify(opts.onUploadComplete, uploadedFile)
          }
        } else {
          const filtered = opts.getFiles().filter((f) => f.id !== tempId)
          opts.setFiles(filtered)
        }
      } catch {
        const filtered = opts.getFiles().filter((f) => f.id !== tempId)
        opts.setFiles(filtered)
      }
    }
  }

  /**
   * 处理文件选择 change 事件。
   * 选择器只负责读取并重置原生 input，上传逻辑统一交给 handleFiles。
   */
  const handleFileChange = async (e: Event) => {
    const input = e.target as HTMLInputElement
    const files = Array.from(input.files ?? [])
    input.value = ''
    await handleFiles(files)
  }

  /**
   * 移除单个附件，已完成上传的会调用后端删除接口
   *
   * @param item 待移除附件
   */
  const removeFile = async (item: UploadedFileItem) => {
    // 上传中的文件无需调用删除接口
    if (!item.uploading && !item.id.startsWith('temp-')) {
      try {
        await removeFiles([item.id])
      } catch {
        // 删除后端附件失败时仍移除本地项，避免阻塞用户继续操作
      }
    }
    const newList = opts.getFiles().filter((f) => f.id !== item.id)
    opts.setFiles(newList)
    notify(opts.onAttachmentRemoved, item)
  }

  return {
    formatFileSize,
    getExtension,
    isFileTypeAllowed,
    fileAcceptAttr,
    handleFiles,
    handleFileChange,
    removeFile
  }
}
