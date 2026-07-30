import type { ChatMessageVO, UploadedFileItem } from '@/types'

export interface LargeScreenImageTemplateV2 {
  version: '2'
  title: string
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  observedVisualFacts: string[]
  canvas: { ratio: '16:9' | '21:9' | '9:16'; coordinateSystem: 'normalized-1000'; grid: '12-column' }
  visualTokens: { palette: string[]; surface: string; border: string; typography: string }
  regions: LargeScreenTemplateRegion[]
  relations: LargeScreenTemplateRelation[]
  preservation: { mode: 'preserve-layout'; mustKeep: string[]; mayReplace: string[] }
  prompt: string
  negativePrompt: string
  iterationHints: string[]
}

export type LargeScreenTemplateComponent =
  | 'title-status' | 'metric-grid' | 'line-chart' | 'bar-chart' | 'area-chart'
  | 'pie-chart' | 'gauge' | 'map' | 'topology-cluster' | 'core-topology'
  | 'alert-feed' | 'list' | 'timeline' | 'data-table' | 'image-panel' | 'footer-status'

export type LargeScreenTemplateReplaceableField =
  | 'title' | 'statusText' | 'businessLabels' | 'metricMeanings'
  | 'chartData' | 'icons' | 'copy' | 'visualAccent'

export interface LargeScreenTemplateBounds {
  x: number
  y: number
  width: number
  height: number
}

export interface LargeScreenTemplateRegion {
  id: string
  label: string
  bounds: LargeScreenTemplateBounds
  layer: number
  component: LargeScreenTemplateComponent
  purpose: string
  locked: boolean
  replaceable: LargeScreenTemplateReplaceableField[]
}

export type LargeScreenTemplateRelationKind =
  | 'topology-link' | 'flow-link' | 'dependency-link' | 'hierarchy-link' | 'data-link'

export interface LargeScreenTemplateRelation {
  from: string
  to: string
  kind: LargeScreenTemplateRelationKind
  locked: boolean
}

export interface LargeScreenImageSubmission {
  displayText: string
  persistedText: string
  runtimeText: string
  titleText: string
  fileIds: string[]
  attachedFiles?: UploadedFileItem[]
}

export type LargeScreenImageTemplateParseResult =
  | { kind: 'absent' }
  | { kind: 'invalid'; reason: string }
  | { kind: 'valid'; template: LargeScreenImageTemplateV2 }

export interface ActiveLargeScreenTemplateContext {
  sessionId: string
  referenceFileId: string
  referenceFile: UploadedFileItem
  analyzeUserMessageId: string
  templateMessageId: string
  template: LargeScreenImageTemplateV2
}

export type { ChatMessageVO }
