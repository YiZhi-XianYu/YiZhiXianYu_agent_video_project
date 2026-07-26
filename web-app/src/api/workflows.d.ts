import type { WorkflowRunDetail, WorkflowRun, CreateAnalysisRunRequest } from '@/api/types';
/**
 * 获取项目的 Workflow 运行历史列表
 */
export declare function listWorkflowRuns(projectId: string): Promise<WorkflowRun[]>;
/**
 * 获取 Workflow 运行详情（含 Task 列表）
 */
export declare function getWorkflowRun(runId: string): Promise<WorkflowRunDetail>;
/**
 * 创建多素材分析 Workflow
 */
export declare function createAnalysisRun(projectId: string, request: CreateAnalysisRunRequest): Promise<WorkflowRun>;
/**
 * 继续暂停的 Workflow（通过 Gate）
 */
export declare function continueWorkflow(runId: string): Promise<WorkflowRun>;
