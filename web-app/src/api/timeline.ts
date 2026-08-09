import { post } from '@/api/client'

export async function startCustomTimelineRender(
  runId: string,
  timeline: unknown,
): Promise<{ workflowRunId: string; statusUrl: string }> {
  return post<{ workflowRunId: string; statusUrl: string }>(
    `/api/v1/workflow-runs/${runId}/custom-timeline-render`,
    { timeline },
  )
}
