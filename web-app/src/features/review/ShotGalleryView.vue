<script setup lang="ts">
/**
 * ShotGalleryView — 镜头画廊总览视图
 *
 * 以网格形式展示所有镜头关键帧缩略图，支持：
 * - 快速视觉扫描（响应式：5 列 → 3 列 → 2 列）
 * - 镜头状态颜色编码（入选/强制/排除/低分）
 * - 强制入选 / 排除操作（悬停按钮）
 * - 点击卡片 → 弹出 ShotPreviewPanel 浮层预览
 * - 筛选和排序
 *
 * Emits: confirm — 用户确认排名后继续 Workflow
 */
import { computed, ref } from 'vue'
import { Star, EyeOff, CheckCircle2, LayoutGrid, Filter } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'
import type { ShotScore } from '@/shared/types'
import ShotPreviewPanel from '@/components/ShotPreviewPanel.vue'

/* =========================== Props & Emits =========================== */

const emit = defineEmits<{
  confirm: []
}>()

const review = useReviewStore()

/* =========================== 状态 =========================== */

/** 当前预览镜头 */
const previewShot = ref<ShotScore | null>(null)
const previewAnchorEl = ref<HTMLElement | null>(null)

/** 筛选模式 */
const filterMode = ref<'all' | 'selected' | 'excluded' | 'low'>('all')

/** 排序键 */
type SortKey = 'rank' | 'index' | 'duration'
const sortKey = ref<SortKey>('rank')

/* =========================== Computed =========================== */

/** 按筛选和排序后的镜头列表 */
const displayedShots = computed<ShotScore[]>(() => {
  let shots = [...review.shotScores]

  /* 筛选 */
  switch (filterMode.value) {
    case 'selected':
      shots = shots.filter((s) => s.selected && !review.excludedShotIds.has(s.shotId))
      break
    case 'excluded':
      shots = shots.filter((s) => review.excludedShotIds.has(s.shotId))
      break
    case 'low':
      shots = shots.filter((s) => (s.rankScore ?? 0) < 50)
      break
  }

  /* 排序 */
  switch (sortKey.value) {
    case 'rank':
      shots.sort((a, b) => (b.rankScore ?? 0) - (a.rankScore ?? 0))
      break
    case 'index':
      break
    case 'duration':
      shots.sort((a, b) => ((b.endMs ?? 0) - (b.startMs ?? 0)) - ((a.endMs ?? 0) - (a.startMs ?? 0)))
      break
  }

  return shots
})

/** 统计 */
const stats = computed(() => ({
  total: review.shotScores.length,
  showing: displayedShots.value.length,
  forced: review.forcedShotIds.size,
  excluded: review.excludedShotIds.size,
}))

/* =========================== 方法 =========================== */

function handleCardClick(shot: ShotScore, event: MouseEvent): void {
  if (previewShot.value?.shotId === shot.shotId) {
    previewShot.value = null
    previewAnchorEl.value = null
  } else {
    previewShot.value = shot
    previewAnchorEl.value = event.currentTarget as HTMLElement
  }
}

function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}

function filterLabel(mode: string): string {
  const map: Record<string, string> = { all: '全部镜头', selected: '已入选', excluded: '已排除', low: '低分 (< 50)' }
  return map[mode] ?? mode
}

function sortLabel(key: string): string {
  const map: Record<string, string> = { rank: '评分降序', index: '镜头序号', duration: '时长' }
  return map[key] ?? key
}
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <!-- 头部 -->
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <LayoutGrid class="w-5 h-5 text-warning" />
          镜头画廊总览
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          快速扫视所有镜头关键帧。标记<strong>强制入选</strong>或<strong>排除</strong>镜头。
        </p>
      </div>
      <button class="btn-primary shrink-0" @click="emit('confirm')">
        <CheckCircle2 class="w-4 h-4" />
        确认排名
      </button>
    </div>

    <!-- 统计栏 + 筛选 + 排序 -->
    <div class="flex flex-wrap items-center gap-3 mb-4">
      <span class="text-xs text-surface-400">
        共 <strong class="text-surface-200">{{ stats.total }}</strong> 个镜头
        <template v-if="stats.showing !== stats.total">
          · 显示 <strong class="text-surface-200">{{ stats.showing }}</strong>
        </template>
      </span>
      <span v-if="stats.forced" class="text-xs text-accent">强制入选 {{ stats.forced }}</span>
      <span v-if="stats.excluded" class="text-xs text-danger">已排除 {{ stats.excluded }}</span>

      <div class="flex items-center gap-1.5 ml-auto">
        <Filter class="w-3.5 h-3.5 text-surface-500" />
        <select
          v-model="filterMode"
          class="bg-surface-800 border border-surface-600 rounded px-2 py-1 text-xs text-surface-300"
        >
          <option value="all">全部镜头</option>
          <option value="selected">已入选</option>
          <option value="excluded">已排除</option>
          <option value="low">低分 (&lt; 50)</option>
        </select>
        <select
          v-model="sortKey"
          class="bg-surface-800 border border-surface-600 rounded px-2 py-1 text-xs text-surface-300"
        >
          <option value="rank">评分降序</option>
          <option value="index">镜头序号</option>
          <option value="duration">时长</option>
        </select>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="displayedShots.length === 0" class="flex items-center justify-center py-16 text-surface-500 text-sm">
      没有符合条件的镜头
    </div>

    <!-- 网格 -->
    <div
      v-else
      class="grid gap-3 overflow-y-auto"
      style="max-height: 70vh; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));"
    >
      <div
        v-for="shot in displayedShots"
        :key="shot.shotId"
        :class="[
          'group relative rounded-lg overflow-hidden border transition-all cursor-pointer',
          review.excludedShotIds.has(shot.shotId)
            ? 'border-danger/30 opacity-60'
            : review.forcedShotIds.has(shot.shotId)
              ? 'border-accent ring-1 ring-accent/50 bg-accent/5'
              : (shot.rankScore ?? 0) < 50
                ? 'border-warning/30'
                : 'border-surface-600 hover:border-surface-400',
        ]"
        @click="handleCardClick(shot, $event)"
      >
        <!-- 图片区 -->
        <div class="relative bg-black aspect-video flex items-center justify-center overflow-hidden">
          <img
            v-if="shot.keyframeUrl"
            :src="shot.keyframeUrl"
            :alt="'Shot ' + shot.shotId"
            class="w-full h-full object-cover transition-transform group-hover:scale-105"
            loading="lazy"
          />
          <div v-else class="flex flex-col items-center text-surface-600">
            <LayoutGrid class="w-8 h-8 mb-1" />
            <span class="text-xs">无关键帧</span>
          </div>

          <!-- 角标：强制入选 -->
          <div
            v-if="review.forcedShotIds.has(shot.shotId)"
            class="absolute top-1.5 left-1.5 w-5 h-5 rounded-full bg-accent flex items-center justify-center"
          >
            <Star class="w-3 h-3 text-white" />
          </div>

          <!-- 角标：已排除 -->
          <div
            v-if="review.excludedShotIds.has(shot.shotId)"
            class="absolute inset-0 bg-black/40 flex items-center justify-center"
          >
            <span class="text-xs text-white font-semibold bg-danger/80 px-2 py-0.5 rounded">已排除</span>
          </div>

          <!-- 角标：低分 -->
          <div
            v-if="(shot.rankScore ?? 0) < 50 && !review.excludedShotIds.has(shot.shotId)"
            class="absolute bottom-1.5 right-1.5 bg-warning/80 text-black text-xs font-bold px-1.5 py-0.5 rounded"
          >
            ⚠ {{ (shot.rankScore ?? 0).toFixed(0) }}
          </div>
        </div>

        <!-- 信息栏 -->
        <div class="px-2 py-1.5 flex items-center justify-between bg-surface-750">
          <div class="min-w-0 flex-1">
            <p class="text-xs font-mono text-surface-300 truncate" :title="shot.shotId">
              {{ shot.shotId.slice(0, 10) }}...
            </p>
            <p class="text-xs text-surface-500">
              {{ formatMs(shot.startMs ?? 0) }} – {{ formatMs(shot.endMs ?? 0) }}
            </p>
          </div>
          <span
            :class="[
              'text-xs font-bold font-mono shrink-0 ml-2',
              (shot.rankScore ?? 0) >= 80 ? 'text-success' :
              (shot.rankScore ?? 0) >= 50 ? 'text-warning' : 'text-danger',
            ]"
          >
            {{ (shot.rankScore ?? 0).toFixed(0) }}
          </span>
        </div>

        <!-- 悬停操作栏 -->
        <div class="absolute top-1.5 right-1.5 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            :class="[
              'w-6 h-6 rounded flex items-center justify-center transition-colors',
              review.forcedShotIds.has(shot.shotId)
                ? 'bg-accent/80 text-white'
                : 'bg-black/60 text-surface-300 hover:bg-accent/50 hover:text-white',
            ]"
            title="强制入选"
            @click.stop="review.toggleForced(shot.shotId)"
          >
            <Star class="w-3.5 h-3.5" />
          </button>
          <button
            :class="[
              'w-6 h-6 rounded flex items-center justify-center transition-colors',
              review.excludedShotIds.has(shot.shotId)
                ? 'bg-danger/80 text-white'
                : 'bg-black/60 text-surface-300 hover:bg-danger/50 hover:text-white',
            ]"
            title="排除此镜头"
            @click.stop="review.toggleExcluded(shot.shotId)"
          >
            <EyeOff class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>

    <!-- 浮层预览 -->
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
  </div>
</template>
