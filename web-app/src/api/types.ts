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
   fileName: string
   sizeBytes: number
   status: string
   createdAt: string
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
   definitionKey: string
   status: RunStatus
   autoMode: boolean
   currentGateKey: string | null
   gates: GateInfo[]
   createdAt: string
   updatedAt: string
 }

 /** Task 运行 */
 export interface TaskRun {
   id: string
   nodeKey: string
   toolName: string
   status: TaskStatus
   attempt: number
   maxAttempts: number
   errorMessage: string | null
   createdAt: string
   updatedAt: string
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

 // ============================================================
 // Artifact
 // ============================================================

 /** Artifact 内容（视频 / JSON / 图片 等） */
 export interface ArtifactContent {
   artifactId: string
   contentType: string
   /** 内容 URL（视频等大文件通过此 URL 访问） */
   url: string
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
 // ============================================================
 // Post-Render（字幕烧录）
 // ============================================================

 /** 字幕样式配置 */
 export interface SubtitleStyle {
   fontSize: number
   fontColor: string
   position: 'bottom' | 'top'
   outlineColor: string
 }

 /** 触发字幕烧录请求 */
 export interface PostRenderSubtitleRequest {
   workflowRunId: string
   style: SubtitleStyle
 }
