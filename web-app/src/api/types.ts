 /**
 * API 层类型定义
 *
 * 与 Java 后端 DTO 和响应结构一一对应。
 * 命名遵循 Java 侧的 camelCase 惯例。
 */

 // ============================================================
 // 项目
 // ============================================================

 /** 项目实体 */
 export interface Project {
   id: string
   name: string
   status: string
   createdAt: string
   updatedAt: string
 }

 /** 创建项目请求 */
 export interface CreateProjectRequest {
   name: string
 }

 // ============================================================
 // 素材
 // ============================================================

 /** 素材实体 */
 export interface Asset {
   id: string
   projectId: string
   type: string
   fileName: string
   sizeBytes: number
   status: string
   contentHash: string
   createdAt: string
   updatedAt: string
 }

 // ============================================================
 // Workflow
 // ============================================================

 /** Workflow 运行状态 */
 export type RunStatus = 'CREATED' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED'

 /** Task 状态 */
 export type TaskStatus =
   | 'PENDING'
   | 'READY'
   | 'DISPATCHING'
   | 'RUNNING'
   | 'RETRY_WAIT'
   | 'SUCCEEDED'
   | 'FAILED'
   | 'SKIPPED'

 /** Gate 定义（来自后端 WorkflowDefinition） */
 export interface GateInfo {
   gateKey: string
   label: string
   description: string
 }

 /** Workflow 运行 */
 export interface WorkflowRun {
   id: string
   projectId: string
   assetId: string | null
   workflowType: string
   definitionKey: string
   definitionVersion: number
   proxyQuality: '4K' | '2K' | '1080P' | '720P'
   status: RunStatus
   progress: number
   errorMessage: string | null
   autoMode: boolean
   currentGateKey: string | null
   gates: GateInfo[]
 }

 /** Task 运行 */
 export interface TaskRun {
   id: string
   assetId: string | null
   instanceKey: string | null
   nodeKey: string
   toolName: string
   toolVersion: string
   status: TaskStatus
   dependencyTaskRunIds: string[]
   progress: number
   attempt: number
   retryCount: number
   nextAttemptAt: string | null
   errorMessage: string | null
   artifacts: ArtifactSnapshot[]
 }

 /** 创建多素材分析 Workflow 请求 */
 export interface CreateAnalysisRunRequest {
   assetIds: string[]
   quality: '4K' | '2K' | '1080P' | '720P'
   durationPrompt?: string
   /** 是否开启全自动模式（跳过所有 Gate） */
   autoMode: boolean
 }

 /** Workflow 运行详情（含 Task 列表） */
 export interface WorkflowRunDetail extends WorkflowRun {
   tasks: TaskRun[]
 }

 /** 项目 Workflow 历史列表项。 */
 export interface WorkflowHistoryItem {
   id: string
   workflowType: string
   definitionKey: string
   definitionVersion: number
   proxyQuality: '4K' | '2K' | '1080P' | '720P'
   status: RunStatus
   progress: number
   errorMessage: string | null
   assetCount: number
   taskCount: number
   createdAt: string
   startedAt: string | null
   completedAt: string | null
 }

 // ============================================================
 // Artifact
 // ============================================================

 /** Workflow 快照中的不可变 Artifact。 */
 export interface ArtifactSnapshot {
   id: string
   externalArtifactId: string
   type: string
   storageUri: string
   mediaType: string
   metadataJson: string
   contentUrl: string
 }

 // ============================================================
 // Story Plan & 版本管理
 // ============================================================

 /** Story Plan（来自后端 custom_story_plans 表） */
 export interface CustomStoryPlan {
   id: string
   workflowRunId: string
   versionName: string
   planData: unknown
   createdAt: string
 }

 /** 版本列表项 */
 export interface VersionListItem {
   id: string
   versionName: string
   createdAt: string
 }
