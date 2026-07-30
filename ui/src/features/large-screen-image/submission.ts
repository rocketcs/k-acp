import type { UploadedFileItem } from '@/types'
import type { ActiveLargeScreenTemplateContext } from './template'
import { compileLargeScreenImageGeneration } from './templateCompiler.ts'

export interface LargeScreenImageSubmissionInput {
  text: string
  fileIds: string[]
  activeTemplate?: ActiveLargeScreenTemplateContext | null
  /** A failed or pending reference workflow must never degrade into text-to-image. */
  referenceWorkflowActive?: boolean
}

export interface LargeScreenImageSubmission {
  displayText: string
  persistedText?: string
  runtimeText: string
  titleText: string
  fileIds: string[]
  attachedFiles?: UploadedFileItem[]
}

const REFERENCE_FILE_DISPLAY_TEXT = '已上传参考图，请基于它生成一版大屏图。'
const REFERENCE_FILE_TITLE_TEXT = '参考图生图'
const REFERENCE_FILE_DEFAULT_PROMPT = '请基于参考图生成一版大屏图。'
const REFERENCE_GENERATE_RUNTIME_PREFIX =
  '[large-screen-image action=generate ratio=16:9 quality=high referenceFileId=%FILE_ID% referenceImageUrl=]\n用户创作需求：\n'
const GENERATE_RUNTIME_PREFIX =
  '[large-screen-image action=generate ratio=16:9 quality=high referenceFileId= referenceImageUrl=]\n用户创作需求：\n'
const SAFE_REFERENCE_FILE_ID = /^\d+$/
const ANALYZE_RUNTIME_INSTRUCTION = '请根据当前参考图生成一份可编辑的大屏结构化模板 v2。'
const ANALYZE_DISPLAY_TEXT = '已上传参考图，请分析其视觉风格并给出创作方案。'
const ANALYZE_TITLE_TEXT = '参考图识别'

export function createLargeScreenAnalyzeSubmission(uploadedFile: UploadedFileItem): LargeScreenImageSubmission | null {
  if (!SAFE_REFERENCE_FILE_ID.test(uploadedFile.id)) return null
  const runtimeText = `[large-screen-image action=analyze ratio=16:9 referenceFileId=${uploadedFile.id}]\n${ANALYZE_RUNTIME_INSTRUCTION}`
  return {
    displayText: ANALYZE_DISPLAY_TEXT,
    persistedText: runtimeText,
    runtimeText,
    titleText: ANALYZE_TITLE_TEXT,
    fileIds: [uploadedFile.id],
    attachedFiles: [uploadedFile],
  }
}

export function adaptLargeScreenImageSubmission({
  text,
  fileIds,
  activeTemplate = null,
  referenceWorkflowActive = false,
}: LargeScreenImageSubmissionInput): LargeScreenImageSubmission | null {
  const trimmedText = text.trim()
  const referenceFileId = fileIds[0]

  if (activeTemplate) {
    return compileLargeScreenImageGeneration({
      template: activeTemplate.template,
      referenceFileId: activeTemplate.referenceFileId,
      businessPrompt: trimmedText,
    })
  }

  if (referenceWorkflowActive) return null

  if (referenceFileId && !SAFE_REFERENCE_FILE_ID.test(referenceFileId)) return null

  if (referenceFileId) {
    const prompt = trimmedText || REFERENCE_FILE_DEFAULT_PROMPT
    return {
      displayText: trimmedText || REFERENCE_FILE_DISPLAY_TEXT,
      runtimeText: `${REFERENCE_GENERATE_RUNTIME_PREFIX.replace('%FILE_ID%', referenceFileId)}${prompt}`,
      titleText: trimmedText || REFERENCE_FILE_TITLE_TEXT,
      fileIds: [referenceFileId],
    }
  }

  if (!trimmedText) return null

  return {
    displayText: trimmedText,
    runtimeText: `${GENERATE_RUNTIME_PREFIX}${trimmedText}`,
    titleText: trimmedText,
    fileIds: [],
  }
}
