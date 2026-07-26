 /**
 * Workflow 状态管理
 *
 * 管理 Workflow 运行状态、Task 列表、Gate 信息。
 * 分层轮询：Workflow 1.5s 刷新一次。
 */
 import { ref, computed } from 'vue'
 import { defineStore } from 'pinia'
 import * as api from '@/api/client'
 import type { WorkflowRunDetail, TaskRun, GateInfo, RunStatus } from '@/api/types'

 export const useWorkflowStore = defineStore('workflow', () => {
   // ============================================================
   // State
   // ============================================================

   /** 当前 Workflow 运行详情 */
   const run = ref<WorkflowRunDetail | null>(null)

   /** 加载状态 */
   const loading = ref(false)

   /** 错误信息 */
   const error = ref<string | null>(null)

   // ============================================================
   // Getters
   // ============================================================

   /** 运行状态 */
   const status = computed<RunStatus | null>(() => run.value?.status ?? null)

   /** 是否暂停中（等待用户审核） */
   const isPaused = computed(() => run.value?.status === 'PAUSED')

   /** 是否运行中 */
   const isRunning = computed(() => run.value?.status === 'RUNNING')

   /** 是否已结束（成功或失败） */
   const isTerminal = computed(() => {
     const s = run.value?.status
     return s === 'SUCCEEDED' || s === 'FAILED'
   })

   /** 当前 Gate */
   const currentGate = computed<GateInfo | null>(() => {
     if (!run.value?.currentGateKey || !run.value.gates) return null
     return run.value.gates.find((g) => g.gateKey === run.value!.currentGateKey) ?? null
   })

   /** Task 列表 */
   const tasks = computed<TaskRun[]>(() => run.value?.tasks ?? [])

   /** 已完成 Task 数量 */
   const completedTaskCount = computed(() =>
     tasks.value.filter((t) => ['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(t.status)).length,
   )

   /** Task 总数 */
   const totalTaskCount = computed(() => tasks.value.length)

   /** 进度百分比 */
   const progressPercent = computed(() => {
     return run.value?.progress ?? 0
   })

   // ============================================================
   // Actions
   // ============================================================

   /** 刷新 Workflow 运行详情 */
   async function fetchRun(workflowRunId: string): Promise<void> {
     error.value = null
     try {
       run.value = await api.get<WorkflowRunDetail>(`/api/v1/workflow-runs/${workflowRunId}`)
     } catch (e: unknown) {
       error.value = e instanceof Error ? e.message : '获取 Workflow 状态失败'
     }
   }

   /** 触发 Workflow 继续（从 Gate 恢复） */
   async function continueWorkflow(workflowRunId: string): Promise<void> {
     error.value = null
     try {
       await api.post(`/api/v1/workflow-runs/${workflowRunId}/continue`)
       // 立即刷新
       await fetchRun(workflowRunId)
     } catch (e: unknown) {
       error.value = e instanceof Error ? e.message : '继续 Workflow 失败'
       throw e
     }
   }

   /** 清除当前 Workflow */
   function clear(): void {
     run.value = null
     error.value = null
   }

   return {
     // state
     run,
     loading,
     error,
     // getters
     status,
     isPaused,
     isRunning,
     isTerminal,
     currentGate,
     tasks,
     completedTaskCount,
     totalTaskCount,
     progressPercent,
     // actions
     fetchRun,
     continueWorkflow,
     clear,
   }
 })
