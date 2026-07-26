/**
 * Workflow API 模块
 *
 * 封装 Workflow 启动、查询和继续的 HTTP 请求。
 */
import { get, post } from '@/api/client';
/**
 * 获取项目的 Workflow 运行历史列表
 */
export async function listWorkflowRuns(projectId) {
    return get(`/api/v1/projects/${projectId}/workflow-runs`);
}
/**
 * 获取 Workflow 运行详情（含 Task 列表）
 */
export async function getWorkflowRun(runId) {
    return get(`/api/v1/workflow-runs/${runId}`);
}
/**
 * 创建多素材分析 Workflow
 */
export async function createAnalysisRun(projectId, request) {
    return post(`/api/v1/projects/${projectId}/multi-asset-analysis-runs`, request);
}
/**
 * 继续暂停的 Workflow（通过 Gate）
 */
export async function continueWorkflow(runId) {
    return post(`/api/v1/workflow-runs/${runId}/continue`);
}
