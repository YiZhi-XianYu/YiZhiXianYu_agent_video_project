import { post, upload } from '@/api/client'

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

export function refreshBgm(runId: string): Promise<BgmApplyResponse> {
  return post<BgmApplyResponse>(`/api/v1/workflow-runs/${runId}/bgm-selection/refresh`)
}

export function uploadBgm(
  runId: string,
  file: File,
  playbackMode: 'ONCE' | 'LOOP',
  durationMs: number,
): Promise<BgmApplyResponse> {
  const formData = new FormData()
  formData.append('file', file)
  return upload<BgmApplyResponse>(`/api/v1/workflow-runs/${runId}/bgm-selection/upload`, formData, {
    params: { playbackMode, durationMs: Math.max(0, Math.round(durationMs)) },
  })
}
