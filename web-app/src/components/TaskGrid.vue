<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, LoaderCircle, AlertTriangle, Layers3, ListChecks } from 'lucide-vue-next'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { TASK_STATUS_LABEL } from '@/shared/constants'
import type { TaskRun, TaskStatus } from '@/api/types'

const props = defineProps<{ tasks: TaskRun[]; loading?: boolean }>()

const expanded = ref<'parallel' | 'unified' | null>(null)

const labels: Record<string, string> = {
  video_probe: '读取视频信息', video_proxy_generate: '生成代理视频', video_shot_detect: '镜头切分',
  vision_quality_score: '分析镜头画质', vision_vlm_analyze: '理解画面语义', source_transcribe: '转写素材语音',
  shot_ranking: '计算镜头排序', story_plan: '生成故事安排', highlight_selection: '选择高光镜头',
  timeline_compose: '编排视频时间线', bgm_select: '匹配背景音乐', subtitle_compose: '生成时间线字幕',
  video_render: '渲染最终成片',
}

const order = ['video_probe','video_proxy_generate','video_shot_detect','vision_quality_score','vision_vlm_analyze','source_transcribe','shot_ranking','story_plan','highlight_selection','timeline_compose','bgm_select','subtitle_compose','video_render']

const parallelTasks = computed(() => props.tasks.filter((task) => Boolean(task.assetId)))
const unifiedTasks = computed(() => props.tasks.filter((task) => !task.assetId))
const assetIds = computed(() => [...new Set(parallelTasks.value.map((task) => task.assetId as string))])

function taskOrder(task: TaskRun): number {
  const index = order.indexOf(task.nodeKey)
  return index < 0 ? order.length : index
}

function progress(task: TaskRun): number {
  if (['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(task.status)) return 100
  return Math.max(0, Math.min(99, task.progress || 0))
}

function statusClass(status: TaskStatus): string {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING' || status === 'DISPATCHING') return 'active'
  return 'pending'
}

function statusCounts(tasks: TaskRun[]) {
  return {
    ready: tasks.filter((task) => task.status === 'READY').length,
    running: tasks.filter((task) => task.status === 'RUNNING' || task.status === 'DISPATCHING').length,
    queued: tasks.filter((task) => task.status === 'PENDING' || task.status === 'RETRY_WAIT').length,
  }
}

const parallelCounts = computed(() => statusCounts(parallelTasks.value))
const unifiedCounts = computed(() => statusCounts(unifiedTasks.value))
const parallelActiveAssets = computed(() => assetIds.value.filter((id) => {
  const tasks = parallelTasks.value.filter((task) => task.assetId === id)
  return tasks.some((task) => task.status === 'RUNNING' || task.status === 'DISPATCHING' || task.status === 'READY')
}).length)

function assetTasks(assetId: string): TaskRun[] {
  return parallelTasks.value.filter((task) => task.assetId === assetId).sort((a, b) => taskOrder(a) - taskOrder(b))
}

function assetProgress(assetId: string): number {
  const tasks = assetTasks(assetId)
  if (!tasks.length) return 0
  return Math.round(tasks.reduce((sum, task) => sum + progress(task), 0) / tasks.length)
}

function currentAssetTask(assetId: string): TaskRun | undefined {
  const tasks = assetTasks(assetId)
  return tasks.find((task) => !['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(task.status)) ?? tasks[tasks.length - 1]
}

function toggle(section: 'parallel' | 'unified'): void {
  expanded.value = expanded.value === section ? null : section
}
</script>

<template>
  <div v-if="loading" class="loading-panel"><LoaderCircle class="animate-spin" />正在加载子任务...</div>
  <div v-else class="task-summary-grid">
    <section class="task-summary-section">
      <button class="task-summary-card" :class="{ expanded: expanded === 'parallel' }" @click="toggle('parallel')">
        <div class="task-summary-icon parallel"><Layers3 /></div>
        <div class="task-summary-main">
          <div class="task-summary-heading"><strong>多素材并行处理</strong><ChevronDown :class="{ 'rotate-180': expanded === 'parallel' }" /></div>
          <p>正在并行处理 {{ parallelActiveAssets }}/{{ assetIds.length }} 个素材</p>
          <ProgressBar :percent="assetIds.length ? Math.round(parallelTasks.reduce((sum, task) => sum + progress(task), 0) / parallelTasks.length) : 0" size="sm" />
          <div class="task-summary-meta"><span>READY {{ parallelCounts.ready }}</span><span>RUNNING {{ parallelCounts.running }}</span><span>QUEUED {{ parallelCounts.queued }}</span></div>
        </div>
      </button>

      <div v-if="expanded === 'parallel'" class="task-summary-details parallel-details">
      <div v-for="(assetId, index) in assetIds" :key="assetId" class="asset-progress-row">
        <div class="asset-progress-title"><strong>素材 {{ index + 1 }}</strong><span>{{ currentAssetTask(assetId) ? labels[currentAssetTask(assetId)!.nodeKey] || currentAssetTask(assetId)!.nodeKey : '等待中' }}</span></div>
        <ProgressBar :percent="assetProgress(assetId)" size="sm" :variant="currentAssetTask(assetId) && statusClass(currentAssetTask(assetId)!.status) === 'danger' ? 'warning' : 'accent'" />
        <div class="asset-progress-foot"><span>{{ assetProgress(assetId) }}%</span><span>{{ currentAssetTask(assetId)?.status ? TASK_STATUS_LABEL[currentAssetTask(assetId)!.status] : '等待中' }}</span></div>
        <p v-if="currentAssetTask(assetId)?.errorMessage" class="task-error"><AlertTriangle />{{ currentAssetTask(assetId)?.errorMessage }}</p>
      </div>
      <p v-if="!assetIds.length" class="task-empty">当前没有素材级任务</p>
      </div>
    </section>

    <section class="task-summary-section">
      <button class="task-summary-card" :class="{ expanded: expanded === 'unified' }" @click="toggle('unified')">
        <div class="task-summary-icon unified"><ListChecks /></div>
        <div class="task-summary-main">
          <div class="task-summary-heading"><strong>统一任务处理</strong><ChevronDown :class="{ 'rotate-180': expanded === 'unified' }" /></div>
          <p>{{ unifiedTasks.length ? '按依赖关系依次推进后续任务' : '等待前置素材处理完成' }}</p>
          <ProgressBar :percent="unifiedTasks.length ? Math.round(unifiedTasks.reduce((sum, task) => sum + progress(task), 0) / unifiedTasks.length) : 0" size="sm" />
          <div class="task-summary-meta"><span>READY {{ unifiedCounts.ready }}</span><span>RUNNING {{ unifiedCounts.running }}</span><span>QUEUED {{ unifiedCounts.queued }}</span></div>
        </div>
      </button>

      <div v-if="expanded === 'unified'" class="task-summary-details unified-details">
      <div v-for="task in [...unifiedTasks].sort((a, b) => taskOrder(a) - taskOrder(b))" :key="task.id" class="unified-progress-row">
        <div class="unified-progress-title"><span><strong>{{ labels[task.nodeKey] || task.toolName }}</strong><small>{{ task.nodeKey }}</small></span><StatusBadge :status="task.status" :label-map="TASK_STATUS_LABEL" /></div>
        <div class="task-progress-line"><ProgressBar :percent="progress(task)" size="sm" :variant="task.status === 'SUCCEEDED' ? 'success' : task.status === 'FAILED' ? 'warning' : 'accent'" /><span>{{ progress(task) }}%</span></div>
        <p v-if="task.errorMessage" class="task-error"><AlertTriangle />{{ task.errorMessage }}</p>
      </div>
      <p v-if="!unifiedTasks.length" class="task-empty">统一任务将在素材处理完成后进入队列</p>
      </div>
    </section>
  </div>
</template>
