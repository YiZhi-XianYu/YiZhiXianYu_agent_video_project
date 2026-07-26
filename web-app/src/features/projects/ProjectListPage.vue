 <script setup lang="ts">
 /**
  * 项目列表页
  *
  * 首页：展示历史项目列表，提供创建新项目和选择历史项目的入口。
  * P1 阶段将实现完整功能，P0 仅验证路由和 API 连通性。
  */
 import { onMounted, ref } from 'vue'
 import { useRouter } from 'vue-router'
 import { useProjectStore } from '@/stores/project'
 import { useUiStore } from '@/stores/ui'
 import { Clapperboard, Plus, Loader2 } from 'lucide-vue-next'

 const router = useRouter()
 const projectStore = useProjectStore()
 const uiStore = useUiStore()

 const newProjectName = ref('')
 const creating = ref(false)

 onMounted(async () => {
   await projectStore.fetchProjects()
 })

 /** 创建项目 */
 async function handleCreate(): Promise<void> {
   const name = newProjectName.value.trim()
   if (!name) return
   creating.value = true
   try {
     const project = await projectStore.createProject(name)
     newProjectName.value = ''
     uiStore.showToast(`项目「${project.name}」已创建`, 'success')
     router.push(`/projects/${project.id}`)
   } catch (e: unknown) {
     const msg = e instanceof Error ? e.message : '创建项目失败'
     uiStore.showToast(msg, 'error')
   } finally {
     creating.value = false
   }
 }

 /** 进入项目 */
 function enterProject(id: string): void {
   projectStore.setCurrentProject(id)
   router.push(`/projects/${id}`)
 }
</script>

<template>
  <div class="max-w-4xl mx-auto px-6 py-8">
    <!-- 页头 -->
    <header class="mb-8">
      <p class="section-eyebrow mb-2">AGENT VIDEO PIPELINE</p>
      <h1 class="text-2xl font-bold text-surface-100">
        智能视频制作流水线
      </h1>
      <p class="mt-2 text-sm text-surface-400">
        上传视频素材，启动自动化分析，在关键节点介入审核，生成高质量成片。
      </p>
    </header>

    <!-- 创建项目 -->
    <div class="card mb-6">
      <h2 class="section-heading mb-4 flex items-center gap-2">
        <Plus class="w-5 h-5 text-accent" />
        创建新项目
      </h2>
      <div class="flex gap-3">
        <input
          v-model="newProjectName"
          class="input-field flex-1"
          placeholder="输入项目名称，例如「云南旅行 Day1」"
          maxlength="200"
          @keydown.enter="handleCreate"
        />
        <button
          class="btn-primary"
          :disabled="!newProjectName.trim() || creating"
          @click="handleCreate"
        >
          <Loader2 v-if="creating" class="w-4 h-4 animate-spin" />
          <Plus v-else class="w-4 h-4" />
          创建
        </button>
      </div>
    </div>

    <!-- 项目列表 -->
    <div v-if="projectStore.loading" class="flex items-center justify-center py-12 text-surface-400">
      <Loader2 class="w-5 h-5 animate-spin mr-2" />
      正在加载项目列表...
    </div>

    <div v-else-if="projectStore.error" class="card border-danger/30 text-danger text-sm">
      {{ projectStore.error }}
      <button class="btn-secondary ml-3 text-xs" @click="projectStore.fetchProjects()">重试</button>
    </div>

    <div v-else-if="projectStore.projects.length === 0" class="card text-center py-12">
      <Clapperboard class="w-10 h-10 text-surface-600 mx-auto mb-3" />
      <p class="text-surface-400 text-sm">还没有项目，创建一个开始吧</p>
    </div>

    <div v-else class="grid gap-3 sm:grid-cols-2">
      <button
        v-for="proj in projectStore.projects"
        :key="proj.id"
        class="card text-left hover:border-accent/40 transition-colors cursor-pointer group"
        @click="enterProject(proj.id)"
      >
        <div class="flex items-center gap-3">
          <div class="w-9 h-9 rounded-lg bg-surface-700 flex items-center justify-center
                      group-hover:bg-accent/20 transition-colors">
            <FolderOpen class="w-4 h-4 text-surface-300 group-hover:text-accent" />
          </div>
          <div>
            <p class="text-sm font-medium text-surface-200">{{ proj.name }}</p>
            <p class="text-xs text-surface-500 mt-0.5">
              {{ new Date(proj.createdAt).toLocaleDateString('zh-CN') }}
            </p>
          </div>
        </div>
      </button>
    </div>
  </div>
</template>

