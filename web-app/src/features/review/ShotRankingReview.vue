 <script setup lang="ts">
 /**
  * Gate 1：镜头排序审核
  *
  * 展示系统对镜头的评分排名，用户可强制入选或排除指定镜头。
  * 点击确认后通过 emit 通知父组件继续 Workflow。
  */
 import { computed } from 'vue'
 import { Star, EyeOff, Eye, CheckCircle2, Loader2 } from 'lucide-vue-next'
 import { useReviewStore } from '@/stores/review'
 import type { ShotScore } from '@/shared/types'

 const emit = defineEmits<{
   confirm: []
 }>()

 const review = useReviewStore()

 // ===================== Computed =====================

 /** 按 rankScore 降序排列 */
 const sortedShots = computed<ShotScore[]>(() =>
   [...review.shotScores].sort((a, b) => (b.rankScore ?? 0) - (a.rankScore ?? 0)),
 )

 /** 统计 */
 const stats = computed(() => {
   const total = sortedShots.value.length
   const selected = sortedShots.value.filter((s) => s.selected).length
   const forced = review.forcedShotIds.size
   const excluded = review.excludedShotIds.size
   return { total, selected, forced, excluded }
 })
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Star class="w-5 h-5 text-warning" />
          镜头排序审核
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          检查镜头质量评分和排名。可手动调整：<strong>强制入选</strong>或将低分镜头<strong>排除</strong>。
        </p>
      </div>
      <button class="btn-primary shrink-0" @click="emit('confirm')">
        <CheckCircle2 class="w-4 h-4" />
        确认排名
      </button>
    </div>

    <!-- 统计栏 -->
    <div class="flex gap-4 mb-4 text-xs">
      <span class="text-surface-400">共 <strong class="text-surface-200">{{ stats.total }}</strong> 个镜头</span>
      <span class="text-success">入选 <strong>{{ stats.selected }}</strong></span>
      <span v-if="stats.forced" class="text-accent">强制入选 <strong>{{ stats.forced }}</strong></span>
      <span v-if="stats.excluded" class="text-danger">已排除 <strong>{{ stats.excluded }}</strong></span>
    </div>

    <!-- 空态 -->
    <div v-if="sortedShots.length === 0" class="flex items-center justify-center py-10 text-surface-500">
      <Loader2 class="w-5 h-5 animate-spin mr-2" />
      <span class="text-sm">加载镜头数据...</span>
    </div>

    <!-- 镜头列表 -->
    <div v-else class="space-y-2 max-h-96 overflow-y-auto">
      <div
        v-for="(shot, idx) in sortedShots"
        :key="shot.shotId"
        :class="[
          'flex items-center gap-3 px-3 py-2.5 rounded-lg transition-colors text-sm',
          review.excludedShotIds.has(shot.shotId)
            ? 'bg-danger/5 border border-danger/20 opacity-60'
            : review.forcedShotIds.has(shot.shotId)
              ? 'bg-accent/10 border border-accent/30'
              : shot.selected
                ? 'bg-surface-700/30 border border-transparent'
                : 'bg-surface-700/10 border border-transparent opacity-50',
        ]"
      >
        <!-- 排名 -->
        <span class="w-7 text-xs font-mono text-surface-500 text-right shrink-0">
          #{{ idx + 1 }}
        </span>

        <!-- 分数 -->
        <span :class="['w-14 text-xs font-mono text-right shrink-0',
                       (shot.rankScore ?? 0) >= 80 ? 'text-success' :
                       (shot.rankScore ?? 0) >= 50 ? 'text-warning' :
                       'text-danger']">
          {{ (shot.rankScore ?? 0).toFixed(0) }}
        </span>

        <!-- 维度评分 -->
        <div class="flex-1 min-w-0">
          <p class="text-surface-200 truncate text-xs">{{ shot.shotId.slice(0, 12) }}...</p>
          <div class="flex gap-2 mt-0.5 text-xs text-surface-500">
            <span>清晰:{{ shot.quality.sharpness.toFixed(0) }}</span>
            <span>曝光:{{ shot.quality.exposure.toFixed(0) }}</span>
            <span>稳定:{{ shot.quality.stability.toFixed(0) }}</span>
            <span>构图:{{ shot.quality.composition.toFixed(0) }}</span>
          </div>
          <!-- 扣分项 -->
          <p v-if="shot.penalties.length > 0" class="text-xs text-danger/70 mt-0.5 truncate">
            {{ shot.penalties.join('；') }}
          </p>
        </div>

        <!-- 操作按钮 -->
        <div class="flex gap-1 shrink-0">
          <button
            :class="[
              'w-7 h-7 rounded flex items-center justify-center transition-colors',
              review.forcedShotIds.has(shot.shotId)
                ? 'bg-accent/20 text-accent'
                : 'text-surface-500 hover:text-accent hover:bg-surface-700',
            ]"
            title="强制入选"
            @click="review.toggleForced(shot.shotId)"
          >
            <Star class="w-3.5 h-3.5" />
          </button>
          <button
            :class="[
              'w-7 h-7 rounded flex items-center justify-center transition-colors',
              review.excludedShotIds.has(shot.shotId)
                ? 'bg-danger/20 text-danger'
                : 'text-surface-500 hover:text-danger hover:bg-surface-700',
            ]"
            title="排除此镜头"
            @click="review.toggleExcluded(shot.shotId)"
          >
            <EyeOff v-if="review.excludedShotIds.has(shot.shotId)" class="w-3.5 h-3.5" />
            <Eye v-else class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
