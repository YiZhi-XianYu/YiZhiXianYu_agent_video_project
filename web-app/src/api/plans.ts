 /**
  * Story Plan 版本管理 API 模块
  *
  * 封装版本列表、详情、保存、恢复和删除的 HTTP 请求。
  */
 import { get, put, post, del } from '@/api/client'
 import type { CustomStoryPlan, VersionListItem } from '@/api/types'

 /** 获取版本列表 */
 export async function listVersions(runId: string): Promise<VersionListItem[]> {
   return get<VersionListItem[]>(`/api/v1/workflow-runs/${runId}/custom-story-plan/version-list`)
 }

 /** 获取指定版本详情 */
 export async function getVersion(runId: string, planId: string): Promise<CustomStoryPlan> {
   return get<CustomStoryPlan>(`/api/v1/workflow-runs/${runId}/custom-story-plan/versions/${planId}`)
 }

 /** 获取当前 Story Plan */
 export async function getCurrentPlan(runId: string): Promise<CustomStoryPlan> {
   return get<CustomStoryPlan>(`/api/v1/workflow-runs/${runId}/custom-story-plan`)
 }

 /** 保存 Story Plan（含版本名） */
 export async function savePlan(runId: string, planData: unknown, versionName?: string): Promise<CustomStoryPlan> {
   return put<CustomStoryPlan>(
     `/api/v1/workflow-runs/${runId}/custom-story-plan`,
     { planData, versionName },
   )
 }

 /** 恢复指定版本 */
 export async function restoreVersion(runId: string, planId: string): Promise<CustomStoryPlan> {
   return post<CustomStoryPlan>(`/api/v1/workflow-runs/${runId}/custom-story-plan/restore/${planId}`)
 }

 /** 删除指定版本 */
 export async function deleteVersion(runId: string, planId: string): Promise<void> {
   return del(`/api/v1/workflow-runs/${runId}/custom-story-plan/versions/${planId}`)
 }
