 <script setup lang="ts">
 /**
  * 进度条组件
  *
  * 支持百分比和不确定（indeterminate）两种模式。
  * 在 Workflow 进度、上传进度、渲染进度等场景复用。
  */
 import { computed } from 'vue'

 const props = withDefaults(defineProps<{
   /** 进度百分比（0-100），不传或 -1 时为不确定模式 */
   percent?: number
   /** 颜色方案 */
   variant?: 'accent' | 'success' | 'warning'
   /** 高度（Tailwind 尺寸类） */
   size?: 'sm' | 'md'
 }>(), {
   percent: -1,
   variant: 'accent',
   size: 'md',
 })

 const isIndeterminate = computed(() => props.percent < 0)
 const clampedPercent = computed(() => Math.max(0, Math.min(100, props.percent ?? 0)))

 const variantColors: Record<string, string> = {
   accent: 'bg-accent',
   success: 'bg-success',
   warning: 'bg-warning',
 }

 const sizes: Record<string, string> = {
   sm: 'h-1',
   md: 'h-1.5',
 }
</script>

<template>
  <div :class="['w-full rounded-full bg-surface-700 overflow-hidden', sizes[size]]">
    <div
      v-if="!isIndeterminate"
      :class="['h-full rounded-full transition-all duration-500 ease-out', variantColors[variant]]"
      :style="{ width: clampedPercent + '%' }"
    />
    <div
      v-else
      :class="['h-full rounded-full animate-pulse-slow w-1/3', variantColors[variant]]"
    />
  </div>
</template>
