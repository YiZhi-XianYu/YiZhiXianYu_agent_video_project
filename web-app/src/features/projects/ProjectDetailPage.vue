<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Film, Loader2, UploadCloud, WandSparkles } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import AssetUpload from '@/features/assets/AssetUpload.vue'
import AssetList from '@/features/assets/AssetList.vue'
import WorkflowLauncher from '@/features/workflow/WorkflowLauncher.vue'

const route = useRoute()
const projectStore = useProjectStore()
const projectId = route.params.id as string

onMounted(async () => {
  projectStore.setCurrentProject(projectId)
  if (projectStore.projects.length === 0) await projectStore.fetchProjects()
  await projectStore.fetchAssets(projectId)
})
</script>

<template>
  <div class="page-shell">
    <header class="project-header">
      <div><p class="section-eyebrow mb-2">VIDEO PROJECT</p><h1>{{ projectStore.currentProject?.name || '加载中...' }}</h1><p>管理原始素材，设置制作参数并跟踪每一个 Agent 子任务。</p></div>
      <div class="project-summary"><Film /><span><strong>{{ projectStore.assetCount }}</strong><small>个视频素材</small></span></div>
    </header>
    <div v-if="!projectStore.currentProject" class="loading-panel"><Loader2 class="animate-spin" />正在加载项目...</div>
    <template v-else>
      <div class="step-heading"><span>01</span><div><h2><UploadCloud />准备素材</h2><p>上传并预览原始视频，确认参与本次制作的素材。</p></div></div>
      <div class="project-workspace-grid"><AssetUpload :project-id="projectId" /><AssetList :project-id="projectId" /></div>
      <div class="step-heading mt-10"><span>02</span><div><h2><WandSparkles />配置并启动</h2><p>选择代理清晰度、成片时长和人工审核方式。</p></div></div>
      <WorkflowLauncher :project-id="projectId" :has-assets="projectStore.assets.length > 0" />
    </template>
  </div>
</template>
