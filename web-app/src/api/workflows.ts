 /**
  * Workflow API 模块
  *
  * 封装 Workflow 启动、查询和继续的 HTTP 请求。
  */
 import { get, post } from '@/api/client'
 import type {
   WorkflowRunDetail,
   WorkflowRun,
   CreateAnalysisRunRequest,
 } from '@/api/types'

 /**
  * 获取项目的 Workflow 运行历史列表
  */
 export async function listWorkflowRuns(projectId: string): Promise<WorkflowRun[]> {
   return get<WorkflowRun[]>(`/api/v1/projects/${projectId}/workflow-runs`)
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
 ): Promise<WorkflowRun> {
   return post<WorkflowRun>(
     `/api/v1/projects/${projectId}/multi-asset-analysis-runs`,
     request,
   )
 }

 /**
  * 继续暂停的 Workflow（通过 Gate）
  */
 export async function continueWorkflow(runId: string): Promise<WorkflowRun> {
   return post<WorkflowRun>(`/api/v1/workflow-runs/${runId}/continue`)
 }
