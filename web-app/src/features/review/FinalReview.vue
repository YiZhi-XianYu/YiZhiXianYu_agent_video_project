<script setup lang="ts">
/**
 * Gate 4：成片预览与字幕配置
 *
 * 预览无字幕成片，配置字幕样式（字号、颜色、位置）。
 * 确认后触发字幕后置渲染流程。
 */
import { ref, computed } from 'vue'
import { Play, Settings, CheckCircle2 } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'

const emit = defineEmits<{
  confirm: [style: import('@/shared/types').SubtitleStyle]
}>()

const review = useReviewStore()
const showSettings = ref(false)

const style = computed(() => review.subtitleStyle)

const fontSizes = [16, 20, 24, 28, 32, 36]
const fontColors = ['#ffffff', '#ffff00', '#00ff00', '#00ffff']
const positions = [
  { value: 'bottom' as const, label: '底部' },
  { value: 'top' as const, label: '顶部' },
]

function update(field: string, value: unknown): void {
  review.updateSubtitleStyle({ [field]: value })
}

function handleConfirm(): void {
  emit('confirm', { ...review.subtitleStyle })
}
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Play class="w-5 h-5 text-warning" />
          成片预览与字幕配置
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          预览第一遍渲染的成片，配置字幕样式后生成带字幕的最终版本。
        </p>
      </div>
      <div class="flex gap-2 shrink-0">
        <button class="btn-secondary" @click="showSettings = !showSettings">
          <Settings class="w-4 h-4" />
          {{ showSettings ? '收起配置' : '字幕设置' }}
        </button>
        <button class="btn-primary" @click="handleConfirm">
          <CheckCircle2 class="w-4 h-4" />
          生成字幕并渲染
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

    <!-- 字幕配置面板 -->
    <div v-if="showSettings" class="grid gap-4 p-4 rounded-lg bg-surface-700/30">
      <div>
        <label class="form-label">字号</label>
        <div class="flex gap-2">
          <button
            v-for="size in fontSizes"
            :key="size"
            :class="['px-3 py-1.5 rounded text-xs transition-colors',
                     style.fontSize === size ? 'bg-accent text-white' : 'bg-surface-700 text-surface-300 hover:bg-surface-600']"
            @click="update('fontSize', size)"
          >
            {{ size }}px
          </button>
        </div>
      </div>
      <div>
        <label class="form-label">字体颜色</label>
        <div class="flex gap-2">
          <button
            v-for="color in fontColors"
            :key="color"
            :class="['w-8 h-8 rounded-full border-2 transition-colors',
                     style.fontColor === color ? 'border-white scale-110' : 'border-transparent']"
            :style="{ backgroundColor: color }"
            @click="update('fontColor', color)"
          />
        </div>
      </div>
      <div>
        <label class="form-label">位置</label>
        <div class="flex gap-2">
          <button
            v-for="pos in positions"
            :key="pos.value"
            :class="['px-3 py-1.5 rounded text-xs transition-colors',
                     style.position === pos.value ? 'bg-accent text-white' : 'bg-surface-700 text-surface-300 hover:bg-surface-600']"
            @click="update('position', pos.value)"
          >
            {{ pos.label }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
