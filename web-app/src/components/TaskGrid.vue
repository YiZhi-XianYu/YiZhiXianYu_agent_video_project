<script setup lang="ts">
import { computed } from 'vue'
import { AlertTriangle, Check, Clock3, LoaderCircle, RotateCcw } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import { TASK_STATUS_LABEL } from '@/shared/constants'
import type { TaskRun } from '@/api/types'

const props = defineProps<{ tasks: TaskRun[]; loading?: boolean }>()

const labels: Record<string, string> = {
  'video.probe': '读取视频信息', 'video.proxy-generate': '生成代理视频', 'video.shot-detect': '检测镜头边界',
  'vision.quality-score': '分析镜头画质', 'vision.vlm-analyze': '理解画面语义',
  'decision.shot-rank': '计算镜头排序', 'planning.story-template': '生成故事安排',
  'decision.highlight-select': '选择高光镜头', 'timeline.compose': '编排视频时间线',
  'audio.bgm-select': '匹配背景音乐', 'audio.source-transcribe': '转写素材语音',
  'subtitle.compose': '生成时间线字幕', 'video.render': '渲染最终成片',
}

const order = ['video_probe','video_proxy_generate','video_shot_detect','vision_quality_score','vision_vlm_analyze','source_transcribe','shot_ranking','story_plan','highlight_selection','timeline_compose','bgm_select','subtitle_compose','video_render']
const sorted = computed(() => [...props.tasks].sort((a, b) => order.indexOf(a.nodeKey) - order.indexOf(b.nodeKey)))

function progress(task: TaskRun): number {
  if (task.status === 'SUCCEEDED' || task.status === 'FAILED' || task.status === 'SKIPPED') return 100
  return Math.max(0, Math.min(99, task.progress || 0))
}
</script>

<template>
  <div v-if="loading" class="loading-panel"><LoaderCircle class="animate-spin" />正在加载子任务...</div>
  <div v-else class="task-list">
    <article v-for="(task, index) in sorted" :key="task.id" class="task-row" :class="task.status.toLowerCase()">
      <div class="task-index"><Check v-if="task.status === 'SUCCEEDED'" /><RotateCcw v-else-if="task.status === 'RETRY_WAIT'" /><LoaderCircle v-else-if="['RUNNING','DISPATCHING'].includes(task.status)" class="animate-spin" /><Clock3 v-else /></div>
      <div class="task-main">
        <div class="task-title"><span><strong>{{ labels[task.toolName] || task.toolName }}</strong><small>{{ task.nodeKey }} · {{ task.toolName }}@{{ task.toolVersion }}</small></span><StatusBadge :status="task.status" :label-map="TASK_STATUS_LABEL" /></div>
        <div class="task-progress-line"><ProgressBar :percent="progress(task)" size="sm" :variant="task.status === 'SUCCEEDED' ? 'success' : task.status === 'FAILED' ? 'warning' : 'accent'" /><span>{{ progress(task) }}%</span></div>
        <div class="task-foot"><span>步骤 {{ index + 1 }}/{{ sorted.length }}</span><span>尝试 {{ task.attempt || 0 }} 次</span><span v-if="task.retryCount">已重试 {{ task.retryCount }} 次</span></div>
        <p v-if="task.errorMessage" class="task-error"><AlertTriangle />{{ task.errorMessage }}</p>
      </div>
    </article>
  </div>
</template>
