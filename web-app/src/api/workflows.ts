 /**
  * Workflow API 模块
  *
  * 封装 Workflow 启动、查询和继续的 HTTP 请求。
  */
 import { get, post, put } from '@/api/client'
 import type {
   WorkflowRunDetail,
   WorkflowHistoryItem,
   CreateAnalysisRunRequest,
  PreviewWorkflowPlanRequest,
  WorkflowPlanPreview,
  ConfirmWorkflowPlanRequest,
  WorkflowPlanValidationResult,
 } from '@/api/types'

 /**
  * 获取项目的 Workflow 运行历史列表
  */
export async function listWorkflowRuns(projectId: string): Promise<WorkflowHistoryItem[]> {
  return get<WorkflowHistoryItem[]>(`/api/v1/projects/${projectId}/workflow-runs`)
}

export async function validateWorkflowPlan(projectId: string, request: ConfirmWorkflowPlanRequest): Promise<WorkflowPlanValidationResult> {
  return post<WorkflowPlanValidationResult>(`/api/v1/projects/${projectId}/workflow-plans/validate`, request)
}

 /**
  * 获取 Workflow 运行详情（含 Task 列表）
  */
 export async function getWorkflowRun(runId: string): Promise<WorkflowRunDetail> {
   return get<WorkflowRunDetail>(`/api/v1/workflow-runs/${runId}`)
 }

 /**
  * 创建多素材分析 Workflow
  */
 export async function createAnalysisRun(
   projectId: string,
   request: CreateAnalysisRunRequest,
 ): Promise<{ workflowRunId: string; status: string; statusUrl: string }> {
   return post<{ workflowRunId: string; status: string; statusUrl: string }>(
     `/api/v1/projects/${projectId}/multi-asset-analysis-runs`,
     request,
   )
 }

 export async function previewWorkflowPlan(
   projectId: string,
   request: PreviewWorkflowPlanRequest,
 ): Promise<WorkflowPlanPreview> {
  return post<WorkflowPlanPreview>(`/api/v1/projects/${projectId}/workflow-plans/preview`, request)
 }

 export async function confirmWorkflowPlan(
   projectId: string,
   request: ConfirmWorkflowPlanRequest,
 ): Promise<{ workflowRunId: string; status: string; statusUrl: string }> {
   return post<{ workflowRunId: string; status: string; statusUrl: string }>(
     `/api/v1/projects/${projectId}/workflow-plans/confirm`,
     request,
   )
 }

 /**
  * 继续暂停的 Workflow（通过 Gate）
  */
export async function continueWorkflow(runId: string): Promise<{ workflowRunId: string; status: string; statusUrl: string }> {
   return post<{ workflowRunId: string; status: string; statusUrl: string }>(`/api/v1/workflow-runs/${runId}/continue`)
 }

export async function saveDagDraft(projectId: string, draftId: string, body: unknown): Promise<{ saved: boolean; key: string }> {
  return put<{ saved: boolean; key: string }>(`/api/v1/projects/${projectId}/dag-drafts/${draftId}`, body)
}

export async function getDagDraft<T = unknown>(projectId: string, draftId: string): Promise<T> {
  return get<T>(`/api/v1/projects/${projectId}/dag-drafts/${draftId}`)
}

export async function saveGateDraft(workflowRunId: string, gateKey: string, body: unknown): Promise<{ saved: boolean; key: string }> {
  return put<{ saved: boolean; key: string }>(`/api/v1/workflow-runs/${workflowRunId}/gate-drafts/${encodeURIComponent(gateKey)}`, body)
}

export async function getGateDraft<T = unknown>(workflowRunId: string, gateKey: string): Promise<T> {
  return get<T>(`/api/v1/workflow-runs/${workflowRunId}/gate-drafts/${encodeURIComponent(gateKey)}`)
}
