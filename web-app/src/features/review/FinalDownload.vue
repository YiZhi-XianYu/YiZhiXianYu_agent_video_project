<script setup lang="ts">
/**
 * Gate 5：最终成片下载
 *
 * 预览带字幕的最终成片，提供下载按钮。
 */
import { Download, Play, CheckCircle2 } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'

const emit = defineEmits<{ confirm: [] }>()
const review = useReviewStore()
</script>

<template>
  <div class="card ring-1 ring-success/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Play class="w-5 h-5 text-success" />
          最终成片预览
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          预览带字幕的最终成片，确认无误后下载。
        </p>
      </div>
      <div class="flex gap-2 shrink-0">
        <a
          v-if="review.finalVideoUrl"
          :href="review.finalVideoUrl"
          download
          class="btn-primary"
        >
          <Download class="w-4 h-4" />
          下载 MP4
        </a>
        <button class="btn-secondary" @click="emit('confirm')">
          <CheckCircle2 class="w-4 h-4" />
          完成
        </button>
      </div>
    </div>

    <div class="rounded-lg bg-surface-900 border border-surface-700 overflow-hidden">
      <video
        v-if="review.finalVideoUrl"
        :src="review.finalVideoUrl"
        controls
        class="w-full max-h-96"
        preload="metadata"
      />
      <div v-else class="flex items-center justify-center py-16 text-surface-500 text-sm">
        等待最终成片渲染完成...
      </div>
    </div>
  </div>
</template>
