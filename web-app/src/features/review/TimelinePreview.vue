<script setup lang="ts">
/**
 * Gate 3：时间线预览
 *
 * 可视化 Timeline 轨道，展示片段顺序、转场效果和成片时长。
 */
import { computed } from 'vue'
import { Clock, CheckCircle2 } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'
import type { TimelineClip } from '@/shared/types'

const emit = defineEmits<{ confirm: [] }>()
const review = useReviewStore()

const clips = computed<TimelineClip[]>(() => review.timeline?.clips ?? [])
const totalDuration = computed(() => review.timeline?.totalDurationMs ?? 0)

function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}

const transLabel: Record<string, string> = { CUT: '硬切', FADE: '淡入淡出', CROSS_DISSOLVE: '交叉溶解' }
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Clock class="w-5 h-5 text-warning" />
          时间线预览
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          {{ clips.length }} 个片段 · 总时长 {{ formatMs(totalDuration) }}
        </p>
      </div>
      <button class="btn-primary shrink-0" @click="emit('confirm')">
        <CheckCircle2 class="w-4 h-4" />
        确认时间线
      </button>
    </div>

    <!-- 无数据 -->
    <div v-if="clips.length === 0" class="py-10 text-center text-surface-500 text-sm">
      加载时间线数据...
    </div>

    <!-- 时间线轨道 -->
    <div v-else class="relative">
      <div class="flex items-center gap-1 overflow-x-auto pb-3">
        <template v-for="clip in clips" :key="clip.shotId + clip.sourceInMs">
          <div
            class="shrink-0 flex flex-col items-center"
            :style="{ width: Math.max(32, (clip.durationMs / totalDuration) * 600) + 'px' }"
          >
            <div class="w-full h-8 rounded bg-accent/30 border border-accent/40 flex items-center justify-center text-xs text-surface-200 font-mono truncate px-1">
              {{ clip.shotId.slice(0, 6) }}
            </div>
            <span class="text-xs text-surface-500 mt-1">{{ formatMs(clip.durationMs) }}</span>
          </div>
          <!-- 转场 -->
          <div v-if="clip.transition !== 'CUT'"
               class="shrink-0 w-8 h-6 flex items-center justify-center
                      bg-warning/20 border border-warning/30 rounded text-xs text-warning">
            {{ transLabel[clip.transition] ?? clip.transition }}
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
