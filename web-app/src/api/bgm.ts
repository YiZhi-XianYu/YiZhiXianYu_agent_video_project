import { post } from '@/api/client'

export interface BgmApplyResponse {
  workflowRunId: string
  statusUrl: string
}

export function selectBgm(runId: string, candidateArtifactId: string): Promise<BgmApplyResponse> {
  return post<BgmApplyResponse>(`/api/v1/workflow-runs/${runId}/bgm-selection`, {
    candidateArtifactId,
  })
}

export function skipBgm(runId: string): Promise<BgmApplyResponse> {
  return post<BgmApplyResponse>(`/api/v1/workflow-runs/${runId}/bgm-selection/skip`)
}
