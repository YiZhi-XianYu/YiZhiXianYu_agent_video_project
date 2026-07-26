<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Clapperboard, FolderOpen, Loader2, Plus, Sparkles } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const projectStore = useProjectStore()
const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()
const newProjectName = ref('')
const creating = ref(false)
const greeting = computed(() => auth.user?.displayName || '创作者')

onMounted(() => projectStore.fetchProjects())

async function createProject(): Promise<void> {
  if (!newProjectName.value.trim()) return
  creating.value = true
  try {
    const project = await projectStore.createProject(newProjectName.value.trim())
    newProjectName.value = ''
    ui.showToast('项目已创建', 'success')
    await router.push(`/projects/${project.id}`)
  } catch (e) {
    ui.showToast(e instanceof Error ? e.message : '创建项目失败', 'error')
  } finally {
    creating.value = false
  }
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', year: 'numeric' })
}
</script>

<template>
  <div class="page-shell">
    <section class="dashboard-hero">
      <div>
        <p class="section-eyebrow mb-3">CREATIVE WORKSPACE</p>
        <h1>{{ greeting }}，开始下一支视频吧。</h1>
        <p>上传素材，交给 Agent 完成分析、故事规划与渲染；你只需要在关键节点做决定。</p>
      </div>
      <div class="hero-stat"><Sparkles /><span><strong>{{ projectStore.projects.length }}</strong><small>个创作项目</small></span></div>
    </section>

    <section class="create-project-panel">
      <div><h2>新建视频项目</h2><p>为一次独立的创作建立素材和 Workflow 空间。</p></div>
      <div class="create-project-form">
        <input v-model="newProjectName" class="input-field" maxlength="200" placeholder="例如：滇西北旅行短片" @keydown.enter="createProject" />
        <button class="btn-primary" :disabled="creating || !newProjectName.trim()" @click="createProject">
          <Loader2 v-if="creating" class="h-4 w-4 animate-spin" /><Plus v-else class="h-4 w-4" />创建项目
        </button>
      </div>
    </section>

    <section class="mt-9">
      <div class="section-title-row"><div><p class="section-eyebrow">PROJECTS</p><h2>最近项目</h2></div><span>{{ projectStore.projects.length }} 个</span></div>
      <div v-if="projectStore.loading" class="loading-panel"><Loader2 class="animate-spin" />正在加载项目...</div>
      <div v-else-if="projectStore.error" class="error-panel">{{ projectStore.error }} <button @click="projectStore.fetchProjects()">重试</button></div>
      <div v-else-if="projectStore.projects.length === 0" class="empty-panel"><Clapperboard /><h3>还没有项目</h3><p>在上方输入名称，建立你的第一个视频制作空间。</p></div>
      <div v-else class="project-grid">
        <RouterLink v-for="project in projectStore.projects" :key="project.id" :to="`/projects/${project.id}`" class="project-card">
          <div class="project-cover"><FolderOpen /><span>ACTIVE</span></div>
          <div class="project-card-body"><p>{{ formatDate(project.createdAt) }}</p><h3>{{ project.name }}</h3><div>进入项目<ArrowRight /></div></div>
        </RouterLink>
      </div>
    </section>
  </div>
</template>
