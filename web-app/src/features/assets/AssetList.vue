 <script setup lang="ts">
 /**
  * 素材列表组件
  *
  * 展示项目下的所有素材，含文件图标、名称、大小和上传时间。
  * 空状态和加载状态均有独立展示。
  */
 import { computed } from 'vue'
 import { FileVideo, Film, Loader2 } from 'lucide-vue-next'
 import { useProjectStore } from '@/stores/project'

 const projectStore = useProjectStore()

 // ===================== Computed =====================

 /** 素材列表来源 */
 const assets = computed(() => projectStore.assets)

 /** 是否有素材 */
 const hasAssets = computed(() => assets.value.length > 0)

 // ===================== Helpers =====================

 /** 格式化字节 */
 function formatBytes(bytes: number): string {
   if (bytes === 0) return '0 B'
   const units = ['B', 'KB', 'MB', 'GB', 'TB']
   const i = Math.floor(Math.log(bytes) / Math.log(1024))
   const size = (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)
   return `${size} ${units[i]}`
 }

 /** 格式化日期为本地短格式 */
 function formatDate(dateStr: string): string {
   const d = new Date(dateStr)
   return d.toLocaleDateString('zh-CN', {
     month: 'short',
     day: 'numeric',
     hour: '2-digit',
     minute: '2-digit',
   })
 }
</script>

<template>
  <div class="card">
    <div class="flex items-center justify-between mb-4">
      <h2 class="section-heading flex items-center gap-2">
        <Film class="w-5 h-5 text-accent" />
        素材列表
      </h2>
      <span class="text-xs text-surface-500">
        {{ assets.length }} 个文件
      </span>
    </div>

    <!-- 加载中 -->
    <div v-if="projectStore.loading" class="flex items-center justify-center py-8 text-surface-400">
      <Loader2 class="w-5 h-5 animate-spin mr-2" />
      <span class="text-sm">正在加载素材列表...</span>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!hasAssets" class="flex flex-col items-center py-10 text-surface-500">
      <FileVideo class="w-10 h-10 mb-3" />
      <p class="text-sm">暂无素材</p>
      <p class="text-xs mt-1">上传视频文件开始</p>
    </div>

    <!-- 素材表格 -->
    <table v-else class="w-full text-sm">
      <thead>
        <tr class="text-left text-xs text-surface-500 uppercase tracking-wider border-b border-surface-700">
          <th class="pb-2 font-medium">文件名</th>
          <th class="pb-2 font-medium w-20 text-right">大小</th>
          <th class="pb-2 font-medium w-36 text-right">上传时间</th>
        </tr>
      </thead>
      <tbody class="divide-y divide-surface-700/50">
        <tr
          v-for="asset in assets"
          :key="asset.id"
          class="hover:bg-surface-700/30 transition-colors group"
        >
          <td class="py-2.5 pr-2">
            <div class="flex items-center gap-2.5">
              <div class="w-8 h-8 rounded-lg bg-surface-700 flex items-center justify-center
                          group-hover:bg-surface-600 transition-colors shrink-0">
                <FileVideo class="w-4 h-4 text-surface-400" />
              </div>
              <span class="text-surface-200 truncate max-w-[320px]" :title="asset.fileName">
                {{ asset.fileName }}
              </span>
            </div>
          </td>
          <td class="py-2.5 text-right text-surface-400">
            {{ formatBytes(asset.sizeBytes) }}
          </td>
          <td class="py-2.5 text-right text-surface-500 text-xs">
            {{ formatDate(asset.createdAt) }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
