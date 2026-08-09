 <script setup lang="ts">
 /**
  * 骨架屏加载组件
  *
  * 支持多种变体：text（单行文字）、card（卡片）、list（多行列表）、circle（圆形头像）。
  */
 withDefaults(defineProps<{
   /** 变体：text / card / list / circle */
   variant?: 'text' | 'card' | 'list' | 'circle'
   /** 行数（仅 list 变体有效） */
   lines?: number
 }>(), {
   variant: 'text',
   lines: 3,
 })
 </script>

 <template>
   <!-- 单行文字 -->
   <div v-if="variant === 'text'" class="h-4 rounded bg-surface-700 animate-pulse w-full" />

   <!-- 卡片 -->
   <div v-else-if="variant === 'card'" class="card animate-pulse space-y-3">
     <div class="flex items-center gap-3">
       <div class="w-10 h-10 rounded-lg bg-surface-600" />
       <div class="flex-1 space-y-2">
         <div class="h-3 rounded bg-surface-600 w-2/3" />
         <div class="h-3 rounded bg-surface-600 w-1/2" />
       </div>
     </div>
   </div>

   <!-- 多行列表 -->
   <div v-else-if="variant === 'list'" class="space-y-3 animate-pulse">
     <div v-for="i in lines" :key="i" class="flex items-center gap-3">
       <div class="w-8 h-8 rounded-lg bg-surface-600 shrink-0" />
       <div class="flex-1 space-y-2">
         <div class="h-3 rounded bg-surface-600 w-full" />
         <div class="h-3 rounded bg-surface-600 w-2/3" />
       </div>
     </div>
   </div>

   <!-- 圆形 -->
   <div v-else-if="variant === 'circle'" class="animate-pulse">
     <div class="w-12 h-12 rounded-full bg-surface-600" />
   </div>
 </template>
