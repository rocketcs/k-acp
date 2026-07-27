import request from '@/utils/request'
import type { AgentDiyPageConfigVO, ApiResponse, DiyPageConfig } from '@/types'

export function getDraft(agentId: string) {
  return request.get<ApiResponse<AgentDiyPageConfigVO | null>>(`/api/agent/diy-page/${agentId}`)
}

export function getPublished(agentId: string) {
  return request.get<ApiResponse<DiyPageConfig | null>>(`/api/agent/diy-page/${agentId}/published`)
}

export function saveDraft(agentId: string, config: DiyPageConfig) {
  return request.put<ApiResponse<AgentDiyPageConfigVO>>(`/api/agent/diy-page/${agentId}/draft`, config)
}

export function publish(agentId: string) {
  return request.post<ApiResponse<boolean>>(`/api/agent/diy-page/${agentId}/publish`)
}

export function setEnabled(agentId: string, enabled: boolean) {
  return request.put<ApiResponse<boolean>>(`/api/agent/diy-page/${agentId}/enabled`, null, {
    params: { enabled },
  })
}
