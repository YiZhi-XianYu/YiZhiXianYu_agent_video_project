<script setup lang="ts">
/**
 * 项目详情页
 *
 * 集成素材上传、素材列表和 Workflow 启动面板。
 * 进入页面时自动加载项目素材，上传完成后自动刷新列表。
 */
import { onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Loader2 } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import { useUiStore } from '@/stores/ui'
import AssetUpload from '@/features/assets/AssetUpload.vue'
import AssetList from '@/features/assets/AssetList.vue'
import WorkflowLauncher from '@/features/workflow/WorkflowLauncher.vue'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const uiStore = useUiStore()

const projectId = route.params.id as string

onMounted(async () => {
  projectStore.setCurrentProject(projectId)
  if (projectStore.projects.length === 0) {
    await projectStore.fetchProjects()
  }
  await projectStore.fetchAssets(projectId)
})

onUnmounted(() => {
  // 保持缓存
})
</script>

<template>
  <div class="max-w-4xl mx-auto px-6 py-8">
    <!-- 页头 -->
    <header class="flex items-center gap-4 mb-8">
      <button
        class="w-9 h-9 rounded-lg flex items-center justify-center
               text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors shrink-0"
        title="返回项目列表"
        @click="router.push('/')"
      >
        <ArrowLeft class="w-5 h-5" />
      </button>
      <div class="min-w-0">
        <p class="section-eyebrow mb-1">项目详情</p>
        <h1 class="text-2xl font-bold text-surface-100 truncate">
          {{ projectStore.currentProject?.name ?? '加载中...' }}
        </h1>
      </div>
      <span class="text-xs text-surface-600 ml-auto shrink-0 font-mono">
        {{ projectId.slice(0, 8) }}&hellip;
      </span>
    </header>

    <!-- 加载中 -->
    <div v-if="!projectStore.currentProject" class="flex items-center justify-center py-20">
      <Loader2 class="w-6 h-6 animate-spin text-surface-500" />
    </div>

    <template v-else>
      <div class="grid gap-6">
        <AssetUpload
          :project-id="projectId"
          @uploaded="() => projectStore.fetchAssets(projectId)"
        />
        <AssetList />

        <!-- Workflow 启动面板 -->
        <WorkflowLauncher
          :project-id="projectId"
          :has-assets="projectStore.assets.length > 0"
        />
      </div>
    </template>

    <!-- Toast -->
    <div class="fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none">
      <transition-group name="fade">
        <div
          v-for="toast in uiStore.toasts"
          :key="toast.id"
          :class="[
            'px-4 py-2.5 rounded-lg text-sm shadow-lg pointer-events-auto border',
            toast.type === 'success' ? 'bg-success/20 text-success border-success/30' :
            toast.type === 'error' ? 'bg-danger/20 text-danger border-danger/30' :
            toast.type === 'warning' ? 'bg-warning/20 text-warning border-warning/30' :
            'bg-surface-800 text-surface-200 border-surface-600',
          ]"
        >
          {{ toast.message }}
        </div>
      </transition-group>
    </div>
  </div>
</template>
