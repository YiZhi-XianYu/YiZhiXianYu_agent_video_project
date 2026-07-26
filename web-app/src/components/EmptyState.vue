 <script setup lang="ts">
 /**
  * 空状态占位组件
  *
  * 当列表无数据时展示引导图标和文案，统一全局空状态视觉。
  */
 import { Clapperboard } from 'lucide-vue-next'

 withDefaults(defineProps<{
   icon?: string
   title?: string
   description?: string
 }>(), {
   title: '暂无数据',
   description: '',
 })
 </script>

 <template>
   <div class="flex flex-col items-center justify-center py-12 text-center">
     <div class="w-12 h-12 rounded-xl bg-surface-700 flex items-center justify-center mb-4">
       <slot name="icon">
         <Clapperboard class="w-6 h-6 text-surface-500" />
       </slot>
     </div>
     <p class="text-sm text-surface-400">{{ title }}</p>
     <p v-if="description" class="text-xs text-surface-600 mt-1">{{ description }}</p>
     <slot name="action" />
   </div>
 </template>
