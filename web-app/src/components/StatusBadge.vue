 <script setup lang="ts">
 /**
  * 状态标签组件
  *
  * 根据 RunStatus 或 TaskStatus 显示不同颜色的状态标签。
  * 颜色方案：
  *   运行中 → 蓝色；成功/完成 → 绿色；暂停 → 黄色；失败 → 红色；其他 → 灰色。
  */
 import { computed } from 'vue'

 const props = defineProps<{
   /** 状态值 */
   status: string
   /** 状态中文映射表（可选，不传则直接显示 status） */
   labelMap?: Record<string, string>
 }>()

 /** 状态颜色方案 */
 type ColorScheme = 'gray' | 'blue' | 'green' | 'yellow' | 'red'

 const colorSchemes: Record<ColorScheme, string> = {
   gray: 'bg-surface-700 text-surface-300',
   blue: 'bg-accent/20 text-accent-light',
   green: 'bg-success/20 text-success',
   yellow: 'bg-warning/20 text-warning',
   red: 'bg-danger/20 text-danger',
 }

 /** 根据状态确定颜色 */
 const scheme = computed<ColorScheme>(() => {
   const s = props.status
   if (s === 'RUNNING' || s === 'DISPATCHING') return 'blue'
   if (s === 'SUCCEEDED') return 'green'
   if (s === 'PAUSED') return 'yellow'
   if (s === 'FAILED') return 'red'
   return 'gray'
 })

 const label = computed(() => {
   if (props.labelMap && props.status in props.labelMap) {
     return props.labelMap[props.status]!
   }
   return props.status
 })
</script>

<template>
  <span :class="['inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', colorSchemes[scheme]]">
    {{ label }}
  </span>
</template>
