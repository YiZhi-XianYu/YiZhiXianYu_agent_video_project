<script setup lang="ts">
/**
 * Gate 4：最终成片预览
 *
 * 预览最终成片。字幕不可用时，主 Workflow 会安全降级为无字幕成片。
 */
import { Play, CheckCircle2 } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'

const emit = defineEmits<{
  confirm: []
}>()

const review = useReviewStore()
function handleConfirm(): void {
  emit('confirm')
}
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Play class="w-5 h-5 text-warning" />
          最终成片预览
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          预览已完成的成片。BGM 或字幕不可用时，系统会保留可播放的降级版本。
        </p>
      </div>
      <div class="flex gap-2 shrink-0">
        <button class="btn-primary" @click="handleConfirm">
          <CheckCircle2 class="w-4 h-4" />
          确认完成
        </button>
      </div>
    </div>

    <!-- 视频预览区 -->
    <div class="rounded-lg bg-surface-900 border border-surface-700 overflow-hidden mb-4">
      <video
        v-if="review.renderedVideoUrl"
        :src="review.renderedVideoUrl"
        controls
        class="w-full max-h-80"
        preload="metadata"
      />
      <div v-else class="flex items-center justify-center py-16 text-surface-500 text-sm">
        等待成片渲染完成...
      </div>
    </div>

  </div>
</template>
