<script setup lang="ts">
/**
 * Gate 2：故事安排编辑器
 *
 * 展示五段式 Story Plan，每段可替换/排序/锁定/增删 shot。
 * 支持版本保存（通过 Custom Story Plan API）。
 * 确认后 emit 通知父组件继续 Workflow。
 */
import { computed, ref } from 'vue'
import { Lock, LockOpen, ArrowUp, ArrowDown, Trash2, Plus, Save, CheckCircle2 } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'
import { BEAT_LABEL_MAP } from '@/shared/constants'
import type { ShotScore, StoryBeat } from '@/shared/types'
import ShotPreviewPanel from '@/components/ShotPreviewPanel.vue'

const emit = defineEmits<{
  confirm: []
}>()

const review = useReviewStore()

/** 当前预览的镜头数据（null = 关闭） */
const previewShot = ref<ShotScore | null>(null)
/** 预览锚点 DOM 元素 */
const previewAnchorEl = ref<HTMLElement | null>(null)

/** 点击 shot ID：从 reviewStore 查找对应 ShotScore 并打开预览 */
function handleShotPreview(shotId: string, event: MouseEvent): void {
  if (previewShot.value?.shotId === shotId) {
    previewShot.value = null
    previewAnchorEl.value = null
    return
  }
  const found = review.shotScores.find((s) => s.shotId === shotId)
  if (found) {
    previewShot.value = found
    previewAnchorEl.value = event.currentTarget as HTMLElement
  }
}

// ===================== Computed =====================

const beats = computed<StoryBeat[]>(() => review.storyPlan?.beats ?? [])

/** 计算成片总时长 */
const totalDuration = computed(() => {
  if (!review.storyPlan) return 0
  return review.storyPlan.totalDurationMs
})

/** 格式化毫秒为 mm:ss */
function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Lock class="w-5 h-5 text-warning" />
          故事安排编辑
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          五段式故事结构：检查各段的镜头分配。可替换、排序、锁定、添加或删除镜头。
        </p>
        <p class="text-xs text-surface-500 mt-1">
          总时长：{{ formatMs(totalDuration) }}
        </p>
      </div>
      <div class="flex gap-2 shrink-0">
        <button class="btn-secondary" title="保存版本">
          <Save class="w-4 h-4" />
          保存版本
        </button>
        <button class="btn-primary" @click="emit('confirm')">
          <CheckCircle2 class="w-4 h-4" />
          确认故事安排
        </button>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="beats.length === 0" class="flex items-center justify-center py-10 text-surface-500 text-sm">
      加载故事安排数据...
    </div>

    <!-- 五段式 Beat 列表 -->
    <div v-else class="grid gap-4">
      <div
        v-for="beat in beats"
        :key="beat.role"
        class="rounded-lg bg-surface-700/30 border border-surface-600/50 overflow-hidden"
      >
        <!-- Beat 头 -->
        <div class="flex items-center gap-2 px-4 py-2.5 bg-surface-700/50">
          <span class="text-xs font-semibold text-accent uppercase tracking-wider">
            {{ beat.role }}
          </span>
          <span class="text-sm font-medium text-surface-200">
            {{ BEAT_LABEL_MAP[beat.role] ?? beat.role }}
          </span>
          <span class="text-xs text-surface-500 ml-auto">
            {{ formatMs(beat.targetDurationMs) }}
          </span>
        </div>

        <!-- Shot 列表 -->
        <div class="px-3 py-2 space-y-1">
          <div
            v-for="(shotId, idx) in beat.shotIds"
            :key="shotId"
            :class="[
              'flex items-center gap-2 px-2 py-2 rounded text-xs transition-colors',
              review.lockedShotIds.has(shotId)
                ? 'bg-accent/5 border border-accent/20'
                : 'hover:bg-surface-600/30',
            ]"
          >
            <!-- 序号 -->
            <span class="w-5 text-surface-500 text-right shrink-0">{{ idx + 1 }}</span>

            <!-- Shot ID -->
            <span class="flex-1 text-surface-200 font-mono truncate cursor-pointer hover:text-accent hover:underline transition-colors" @click="handleShotPreview(shotId, $event)">
              {{ shotId.slice(0, 16) }}...
            </span>

            <!-- 锁定按钮 -->
            <button
              :class="review.lockedShotIds.has(shotId) ? 'text-accent' : 'text-surface-500 hover:text-surface-300'"
              title="锁定/解锁"
              @click="review.toggleLockShot(shotId)"
            >
              <Lock v-if="review.lockedShotIds.has(shotId)" class="w-3.5 h-3.5" />
              <LockOpen v-else class="w-3.5 h-3.5" />
            </button>

            <!-- 排序按钮 -->
            <button
              class="text-surface-500 hover:text-surface-300 p-0.5 disabled:opacity-30"
              :disabled="idx === 0 || review.lockedShotIds.has(shotId)"
              title="上移"
            >
              <ArrowUp class="w-3.5 h-3.5" />
            </button>
            <button
              class="text-surface-500 hover:text-surface-300 p-0.5 disabled:opacity-30"
              :disabled="idx === beat.shotIds.length - 1 || review.lockedShotIds.has(shotId)"
              title="下移"
            >
              <ArrowDown class="w-3.5 h-3.5" />
            </button>

            <!-- 删除 -->
            <button
              class="text-surface-500 hover:text-danger p-0.5 disabled:opacity-30"
              :disabled="review.lockedShotIds.has(shotId)"
              title="移除"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>

          <!-- 空 Beat -->
          <div
            v-if="beat.shotIds.length === 0"
            class="px-2 py-3 text-xs text-surface-500 text-center"
          >
            此段暂无镜头
          </div>

          <!-- 添加镜头按钮 -->
          <button
            class="w-full flex items-center justify-center gap-1 py-1.5 rounded text-xs
                   text-surface-500 hover:text-surface-300 hover:bg-surface-600/20 transition-colors"
          >
            <Plus class="w-3.5 h-3.5" />
            添加镜头到此段
          </button>
        </div>
      </div>
    </div>
  </div>
    <!-- 镜头预览浮层 -->
    <ShotPreviewPanel
      :shot-id="previewShot?.shotId ?? ''"
      :keyframe-url="previewShot?.keyframeUrl ?? null"
      :video-url="previewShot?.proxyVideoUrl ?? null"
      :start-ms="previewShot?.startMs ?? 0"
      :end-ms="previewShot?.endMs ?? 0"
      :anchor-el="previewAnchorEl"
      :visible="previewShot !== null"
      @close="previewShot = null; previewAnchorEl = null"
    />

</template>
