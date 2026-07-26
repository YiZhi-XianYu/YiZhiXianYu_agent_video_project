 <script setup lang="ts">
 /**
  * Task 网格组件 —— Workflow DAG 任务可视化
  *
  * 以卡片网格展示 Workflow 的所有 Task，每个卡片显示：
  * - 工具名称和 nodeKey
  * - 状态标签（颜色自适应）
  * - 执行次数
  * - 错误信息（失败时）
  */
 import { computed } from 'vue'
 import { Wrench, AlertTriangle } from 'lucide-vue-next'
 import StatusBadge from '@/components/StatusBadge.vue'
 import { TASK_STATUS_LABEL } from '@/shared/constants'
 import type { TaskRun } from '@/api/types'

 const props = defineProps<{
   /** Task 列表 */
   tasks: TaskRun[]
   /** 是否加载中 */
   loading?: boolean
 }>()

 /**
  * 工具中文名称映射（部分已知工具）
  * 未知工具显示原始 toolName
  */
 const toolLabelMap: Record<string, string> = {
   'video.probe': '视频信息读取',
   'video.proxy-generate': '生成代理视频',
   'video.shot-detect': '镜头检测',
   'vision.quality-score': '画质评分',
   'vision.vlm-analyze': '语义理解',
   'decision.shot-rank': '镜头排序',
   'planning.story-template': '故事安排',
   'decision.highlight-select': '高光选择',
   'timeline.compose': '时间线编排',
   'audio.bgm-select': '背景音乐选择',
   'audio.source-transcribe': '素材语音转写',
   'subtitle.compose': '字幕编排',
   'video.render': '视频渲染',
 }

 /** 按 nodeKey 排序（保持 DAG 拓扑序） */
 const sortedTasks = computed(() => {
   const order = [
     'video_probe', 'video_proxy_generate', 'video_shot_detect',
     'vision_quality_score', 'vision_vlm_analyze', 'source_transcribe',
     'shot_ranking', 'story_plan', 'highlight_selection',
     'timeline_compose', 'bgm_select', 'subtitle_compose',
     'video_render',
   ]
   return [...props.tasks].sort((a, b) => {
     const ai = order.indexOf(a.nodeKey)
     const bi = order.indexOf(b.nodeKey)
     return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi)
   })
 })

 /** 获取工具中文名 */
 function getToolLabel(toolName: string): string {
   return toolLabelMap[toolName] ?? toolName
 }
</script>

<template>
  <div v-if="loading" class="flex items-center justify-center py-8 text-surface-400 text-sm">
    <Wrench class="w-4 h-4 animate-spin mr-2" />
    加载 Task 状态...
  </div>

  <div v-else-if="tasks.length === 0" class="text-center py-8 text-surface-500 text-sm">
    暂无 Task 数据
  </div>

  <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
    <div
      v-for="task in sortedTasks"
      :key="task.id"
      :class="[
        'card transition-all duration-200',
        task.status === 'RUNNING' ? 'ring-1 ring-accent/40' : '',
        task.status === 'FAILED' ? 'ring-1 ring-danger/30' : '',
      ]"
    >
      <!-- Tool 名称 -->
      <div class="flex items-center gap-2 mb-2">
        <Wrench class="w-3.5 h-3.5 text-surface-500 shrink-0" />
        <span class="text-xs font-medium text-surface-300 truncate">
          {{ getToolLabel(task.toolName) }}
        </span>
      </div>

      <!-- 状态 · 尝试次数 -->
      <div class="flex items-center justify-between">
        <StatusBadge :status="task.status" :label-map="TASK_STATUS_LABEL" />
        <span class="text-xs text-surface-600">
          {{ task.attempt }}
        </span>
      </div>

      <!-- 错误信息 -->
      <div
        v-if="task.status === 'FAILED' && task.errorMessage"
        class="mt-2 flex items-start gap-1.5 text-xs text-danger/80"
      >
        <AlertTriangle class="w-3 h-3 shrink-0 mt-0.5" />
        <span class="line-clamp-2">{{ task.errorMessage }}</span>
      </div>
    </div>
  </div>
</template>
