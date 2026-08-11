import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types'
import type {
  TracingDetail,
  TracingPageItem,
  TracingPageQuery,
  TracingRaw,
  TracingSummary,
  TracingUser,
} from '@/types/sessionTracing'

export function getTracingUsers() {
  return request.get<ApiResponse<TracingUser[]>>('/api/langfuse/session-tracing/users')
}

export function getTracingPage(query: TracingPageQuery) {
  return request.get<ApiResponse<PageResult<TracingPageItem>>>('/api/langfuse/session-tracing/page', {
    params: query,
  })
}

export function getTracingDetail(id: string) {
  return request.get<ApiResponse<TracingDetail>>(`/api/langfuse/session-tracing/${id}`)
}

export function getTracingRaw(id: string) {
  return request.get<ApiResponse<TracingRaw>>(`/api/langfuse/session-tracing/${id}/raw`)
}

export function getTracingSummary() {
  return request.get<ApiResponse<TracingSummary>>('/api/langfuse/session-tracing/summary')
}
