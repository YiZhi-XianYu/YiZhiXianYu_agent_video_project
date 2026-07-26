<script setup lang="ts">
/**
 * Workflow 运行监控页
 *
 * 实时展示 Workflow DAG 中 Task 状态、整体进度和 Gate 人在回路审核界面。
 * 分层轮询：1.5s 刷新 Workflow 状态，进入 PAUSED 后展示对应审核视图。
 */
import { onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Loader2, CheckCircle2, XCircle, PauseCircle } from 'lucide-vue-next'
import { useWorkflowStore } from '@/stores/workflow'
import { useReviewStore } from '@/stores/review'
import { useUiStore } from '@/stores/ui'
import { usePolling } from '@/shared/composables/usePolling'
import { WORKFLOW_POLL_INTERVAL_MS, RUN_STATUS_LABEL } from '@/shared/constants'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TaskGrid from '@/components/TaskGrid.vue'
import ShotRankingReview from '@/features/review/ShotRankingReview.vue'
import StoryEditor from '@/features/review/StoryEditor.vue'
import TimelinePreview from '@/features/review/TimelinePreview.vue'
import FinalReview from '@/features/review/FinalReview.vue'
import FinalDownload from '@/features/review/FinalDownload.vue'

const props = defineProps<{
  projectId: string
  runId: string
}>()

const router = useRouter()
const workflowStore = useWorkflowStore()
const reviewStore = useReviewStore()
const uiStore = useUiStore()

// ===================== 分层轮询 =====================

const { start: startPolling, stop: stopPolling } = usePolling(
  () => workflowStore.fetchRun(props.runId),
  WORKFLOW_POLL_INTERVAL_MS,
)

onMounted(async () => {
  workflowStore.clear()
  await workflowStore.fetchRun(props.runId)
  syncGate()
  if (!workflowStore.isTerminal) startPolling()
})

watch(() => workflowStore.isTerminal, (terminal) => {
  if (terminal) stopPolling()
})

// Gate 变化时同步到 review store
watch(() => workflowStore.currentGate, () => {
  syncGate()
})

onUnmounted(() => {
  stopPolling()
  workflowStore.clear()
  reviewStore.resetAll()
})

// ===================== Gate 同步 =====================

function syncGate(): void {
  reviewStore.activateGate(workflowStore.currentGate)
}

// ===================== Actions =====================

async function handleContinue(): Promise<void> {
  try {
    await workflowStore.continueWorkflow(props.runId)
    startPolling()
  } catch {
    // 错误已在 Store 中处理
  }
}

function goBack(): void {
  router.push(`/projects/${props.projectId}`)
}
</script>

<template>
  <div class="max-w-5xl mx-auto px-6 py-8">
    <!-- 页头 -->
    <header class="flex items-center gap-4 mb-8">
      <button class="w-9 h-9 rounded-lg flex items-center justify-center
                     text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors shrink-0"
              @click="goBack">
        <ArrowLeft class="w-5 h-5" />
      </button>
      <div class="min-w-0 flex-1">
        <p class="section-eyebrow mb-1">WORKFLOW MONITOR</p>
        <h1 class="text-xl font-bold text-surface-100">Workflow 运行监控</h1>
      </div>
      <StatusBadge v-if="workflowStore.status" :status="workflowStore.status" :label-map="RUN_STATUS_LABEL" />
    </header>

    <!-- 错误信息（无论 run 是否为 null 都显示） -->
    <div v-if="workflowStore.error" class="card border-danger/30 mb-6">
      <div class="flex items-start gap-3">
        <XCircle class="w-5 h-5 text-danger shrink-0 mt-0.5" />
        <div class="flex-1">
          <p class="text-sm text-danger">{{ workflowStore.error }}</p>
          <button class="btn-secondary mt-2 text-xs" @click="workflowStore.fetchRun(runId)">重试</button>
        </div>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="!workflowStore.run && !workflowStore.error" class="flex items-center justify-center py-16">
      <Loader2 class="w-6 h-6 animate-spin text-surface-500" />
      <span class="ml-3 text-sm text-surface-400">正在加载 Workflow 状态...</span>
    </div>

    <template v-else>
      <!-- 进度 -->
      <div class="card mb-6">
        <div class="flex items-center justify-between mb-3">
          <h2 class="section-heading">执行进度</h2>
          <span class="text-sm text-surface-400">
            {{ workflowStore.completedTaskCount }} / {{ workflowStore.totalTaskCount }}
          </span>
        </div>
        <ProgressBar
          :percent="workflowStore.progressPercent"
          :variant="workflowStore.isTerminal ? (workflowStore.status === 'SUCCEEDED' ? 'success' : 'warning') : 'accent'"
        />
      </div>

      <!-- Task 网格 -->
      <div class="mb-6">
        <h2 class="section-heading mb-4">Task 状态</h2>
        <TaskGrid :tasks="workflowStore.tasks" />
      </div>

      <!-- ===== Gate 人在回路审核区 ===== -->
      <ShotRankingReview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_shot_ranking'"
        @confirm="handleContinue"
      />
      <StoryEditor
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_story_edit'"
        @confirm="handleContinue"
      />
      <TimelinePreview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_timeline_preview'"
        @confirm="handleContinue"
      />
      <FinalReview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_render_review'"
        @confirm="handleContinue"
      />
      <FinalDownload
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_final_download'"
        @confirm="handleContinue"
      />

      <!-- 未知 Gate 兜底 -->
      <div v-if="workflowStore.isPaused && !workflowStore.currentGate" class="card mb-6 ring-1 ring-warning/40">
        <div class="flex items-center gap-4">
          <PauseCircle class="w-6 h-6 text-warning shrink-0" />
          <div class="flex-1">
            <h3 class="text-sm font-semibold text-warning">Workflow 已暂停</h3>
            <p class="text-sm text-surface-400 mt-1">等待用户审核中...</p>
          </div>
          <button class="btn-primary" @click="handleContinue">
            <CheckCircle2 class="w-4 h-4" />
            确认并继续
          </button>
        </div>
      </div>

      <!-- 完成状态 -->
      <div v-if="workflowStore.isTerminal" class="card mb-6"
           :class="workflowStore.status === 'SUCCEEDED' ? 'ring-1 ring-success/30' : 'ring-1 ring-danger/30'">
        <div class="flex items-center gap-4">
          <CheckCircle2 v-if="workflowStore.status === 'SUCCEEDED'" class="w-8 h-8 text-success" />
          <XCircle v-else class="w-8 h-8 text-danger" />
          <div>
            <h3 class="text-sm font-semibold text-surface-200">
              {{ workflowStore.status === 'SUCCEEDED' ? 'Workflow 执行完成' : 'Workflow 执行失败' }}
            </h3>
            <p class="text-sm text-surface-400 mt-1">
              {{ workflowStore.status === 'SUCCEEDED'
                ? '所有任务已成功完成，可在项目详情页查看结果。'
                : '部分任务失败，请检查错误信息。' }}
            </p>
          </div>
        </div>
      </div>

      <!-- 错误 -->
      <div v-if="workflowStore.error" class="card border-danger/30 mb-6">
        <p class="text-sm text-danger">{{ workflowStore.error }}</p>
      </div>
    </template>

    <!-- Toast -->
    <div class="fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none">
      <transition-group name="fade">
        <div v-for="toast in uiStore.toasts" :key="toast.id"
             :class="['px-4 py-2.5 rounded-lg text-sm shadow-lg pointer-events-auto border',
                      toast.type === 'success' ? 'bg-success/20 text-success border-success/30' :
                      toast.type === 'error' ? 'bg-danger/20 text-danger border-danger/30' :
                      toast.type === 'warning' ? 'bg-warning/20 text-warning border-warning/30' :
                      'bg-surface-800 text-surface-200 border-surface-600']">
          {{ toast.message }}
        </div>
      </transition-group>
    </div>
  </div>
</template>
