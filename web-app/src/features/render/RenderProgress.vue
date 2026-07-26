<script setup lang="ts">
/**
 * 渲染进度组件
 *
 * 展示字幕后置渲染的两步进度：ASR 转写 → 字幕烧录。
 * 支持轮询 mini Workflow 的状态，显示实时进度。
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { Loader2, FileText, Film, CheckCircle2, XCircle } from 'lucide-vue-next'
import { getWorkflowRun } from '@/api/workflows'
import { usePolling } from '@/shared/composables/usePolling'
import { RENDER_POLL_INTERVAL_MS } from '@/shared/constants'
import ProgressBar from '@/components/ProgressBar.vue'
import type { WorkflowRunDetail } from '@/api/types'

const props = defineProps<{
  /** mini Workflow run ID */
  renderRunId: string
}>()

const emit = defineEmits<{
  done: []
  error: [message: string]
}>()

const run = ref<WorkflowRunDetail | null>(null)
const error = ref<string | null>(null)

const { start, stop } = usePolling(async () => {
  try {
    run.value = await getWorkflowRun(props.renderRunId)
    if (run.value.status === 'SUCCEEDED') {
      stop()
      emit('done')
    } else if (run.value.status === 'FAILED') {
      stop()
      emit('error', (run.value as any).errorMessage ?? '渲染失败')
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '加载渲染状态失败'
  }
}, RENDER_POLL_INTERVAL_MS)

onMounted(() => start())
onUnmounted(() => stop())

const step1 = (): string => {
  const t = run.value?.tasks?.find((t) => t.nodeKey === 'transcribe_final')
  return t?.status ?? 'PENDING'
}

const step2 = (): string => {
  const t = run.value?.tasks?.find((t) => t.nodeKey === 'render_subtitles')
  return t?.status ?? 'PENDING'
}
</script>

<template>
  <div class="card mb-6">
    <h3 class="text-sm font-semibold text-surface-200 mb-4 flex items-center gap-2">
      <Film class="w-4 h-4 text-accent" />
      字幕渲染进度
    </h3>

    <div class="space-y-3">
      <!-- Step 1: ASR -->
      <div class="flex items-center gap-3">
        <div :class="['w-8 h-8 rounded-lg flex items-center justify-center shrink-0',
                      step1() === 'SUCCEEDED' ? 'bg-success/20 text-success' :
                      step1() === 'RUNNING' ? 'bg-accent/20 text-accent' :
                      step1() === 'FAILED' ? 'bg-danger/20 text-danger' :
                      'bg-surface-700 text-surface-500']">
          <Loader2 v-if="step1() === 'RUNNING'" class="w-4 h-4 animate-spin" />
          <CheckCircle2 v-else-if="step1() === 'SUCCEEDED'" class="w-4 h-4" />
          <XCircle v-else-if="step1() === 'FAILED'" class="w-4 h-4" />
          <FileText v-else class="w-4 h-4" />
        </div>
        <div class="flex-1">
          <p class="text-sm text-surface-300">语音转写（ASR）</p>
          <p class="text-xs text-surface-500">对成片音频做语音识别，生成 SRT 字幕</p>
        </div>
        <span class="text-xs text-surface-500">{{ step1() }}</span>
      </div>

      <!-- Step 2: Burn -->
      <div class="flex items-center gap-3">
        <div :class="['w-8 h-8 rounded-lg flex items-center justify-center shrink-0',
                      step2() === 'SUCCEEDED' ? 'bg-success/20 text-success' :
                      step2() === 'RUNNING' ? 'bg-accent/20 text-accent' :
                      step2() === 'FAILED' ? 'bg-danger/20 text-danger' :
                      'bg-surface-700 text-surface-500']">
          <Loader2 v-if="step2() === 'RUNNING'" class="w-4 h-4 animate-spin" />
          <CheckCircle2 v-else-if="step2() === 'SUCCEEDED'" class="w-4 h-4" />
          <XCircle v-else-if="step2() === 'FAILED'" class="w-4 h-4" />
          <Film v-else class="w-4 h-4" />
        </div>
        <div class="flex-1">
          <p class="text-sm text-surface-300">字幕烧录</p>
          <p class="text-xs text-surface-500">将 SRT 字幕烧录到视频中</p>
        </div>
        <span class="text-xs text-surface-500">{{ step2() }}</span>
      </div>
    </div>

    <!-- 进度条 -->
    <div class="mt-4">
      <ProgressBar
        :percent=" (run as any)?.progress ?? -1"
        :variant="run?.status === 'SUCCEEDED' ? 'success' : run?.status === 'FAILED' ? 'warning' : 'accent'"
      />
    </div>

    <!-- 错误 -->
    <div v-if="error" class="mt-3 text-xs text-danger">{{ error }}</div>
  </div>
</template>
