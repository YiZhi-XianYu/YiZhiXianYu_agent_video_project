 /**
  * Story Plan 版本管理 API 模块
  *
  * 封装版本列表、详情、保存、恢复和删除的 HTTP 请求。
  */
 import { get, put, post, del } from '@/api/client'
import type { CustomStoryPlan, VersionListItem } from '@/api/types'

interface CustomStoryPlanResponse {
  id: string | null
  sourceWorkflowRunId: string
  plan: unknown
  versionName: string | null
  createdAt: string | null
}

function normalizePlan(response: CustomStoryPlanResponse): CustomStoryPlan {
  return {
    id: response.id ?? '',
    workflowRunId: response.sourceWorkflowRunId,
    versionName: response.versionName ?? '',
    planData: response.plan,
    createdAt: response.createdAt ?? '',
  }
}

 /** 获取版本列表 */
 export async function listVersions(runId: string): Promise<VersionListItem[]> {
   return get<VersionListItem[]>(`/api/v1/workflow-runs/${runId}/custom-story-plan/version-list`)
 }

 /** 获取指定版本详情 */
export async function getVersion(runId: string, planId: string): Promise<CustomStoryPlan> {
   return normalizePlan(await get<CustomStoryPlanResponse>(`/api/v1/workflow-runs/${runId}/custom-story-plan/versions/${planId}`))
}

 /** 获取当前 Story Plan */
export async function getCurrentPlan(runId: string): Promise<CustomStoryPlan> {
   return normalizePlan(await get<CustomStoryPlanResponse>(`/api/v1/workflow-runs/${runId}/custom-story-plan`))
}

 /** 保存 Story Plan（含版本名） */
export async function savePlan(runId: string, planData: unknown, versionName?: string): Promise<CustomStoryPlan> {
   const response = await put<CustomStoryPlanResponse>(
     `/api/v1/workflow-runs/${runId}/custom-story-plan`,
     { plan: planData, versionName },
   )
   return normalizePlan(response)
}

/** Start a render workflow from the active custom Story Plan. */
export async function applyPlan(runId: string): Promise<{ workflowRunId: string; statusUrl: string }> {
  return post<{ workflowRunId: string; statusUrl: string }>(
    `/api/v1/workflow-runs/${runId}/custom-story-plan/apply`,
  )
}

 /** 恢复指定版本 */
export async function restoreVersion(runId: string, planId: string): Promise<CustomStoryPlan> {
   return normalizePlan(await post<CustomStoryPlanResponse>(`/api/v1/workflow-runs/${runId}/custom-story-plan/restore/${planId}`))
}

 /** 删除指定版本 */
 export async function deleteVersion(runId: string, planId: string): Promise<void> {
   return del(`/api/v1/workflow-runs/${runId}/custom-story-plan/versions/${planId}`)
 }
