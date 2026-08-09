<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Activity, AlertCircle, Loader2 } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import { listWorkflowRuns } from '@/api/workflows'
import type { Project, WorkflowHistoryItem } from '@/api/types'
import StatusBadge from '@/components/StatusBadge.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import { RUN_STATUS_LABEL } from '@/shared/constants'

interface ProjectRun extends WorkflowHistoryItem {
  project: Project
}

const projectStore = useProjectStore()
const runs = ref<ProjectRun[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(loadRuns)

async function loadRuns(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await projectStore.fetchProjects()
    const groups = await Promise.all(projectStore.projects.map(async (project) => {
      const items = await listWorkflowRuns(project.id)
      return items.map((run) => ({ ...run, project }))
    }))
    runs.value = groups.flat().sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Workflow 历史加载失败'
  } finally {
    loading.value = false
  }
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="page-shell">
    <header class="mb-8">
      <p class="section-eyebrow mb-2">WORKFLOW HISTORY</p>
      <h1 class="text-2xl font-bold text-surface-100 flex items-center gap-2">
        <Activity class="w-6 h-6 text-accent" /> Workflow 历史
      </h1>
      <p class="text-sm text-surface-400 mt-2">查看全部项目的执行记录，并进入监控或审核页面。</p>
    </header>

    <div v-if="loading" class="flex justify-center py-16 text-surface-400">
      <Loader2 class="w-5 h-5 animate-spin mr-2" /> 正在加载 Workflow 历史...
    </div>
    <div v-else-if="error" class="card border-danger/30 text-danger text-sm">
      <AlertCircle class="inline w-4 h-4 mr-2" />{{ error }}
      <button class="btn-secondary ml-3 text-xs" @click="loadRuns">重试</button>
    </div>
    <div v-else-if="runs.length === 0" class="card text-center py-12 text-surface-400">暂无 Workflow 记录</div>
    <div v-else class="space-y-3">
      <RouterLink
        v-for="run in runs"
        :key="run.id"
        :to="`/projects/${run.project.id}/runs/${run.id}`"
      class="card block hover:border-accent/40 transition-colors"
      >
        <div class="flex flex-wrap items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium text-surface-100 truncate">{{ run.project.name }}</p>
            <p class="text-xs text-surface-500 mt-1">{{ formatDate(run.createdAt) }} · {{ run.assetCount }} 个素材 · {{ run.taskCount }} 个任务</p>
          </div>
          <span class="text-xs text-surface-500">{{ run.proxyQuality }}</span>
          <span class="text-xs text-surface-400">{{ run.progress }}%</span>
          <StatusBadge :status="run.status" :label-map="RUN_STATUS_LABEL" />
        </div>
        <ProgressBar class="mt-3" :percent="run.progress" size="sm" :variant="run.status === 'SUCCEEDED' ? 'success' : 'accent'" />
        <p v-if="run.errorMessage" class="text-xs text-danger/80 mt-3 line-clamp-2">{{ run.errorMessage }}</p>
      </RouterLink>
    </div>
  </div>
</template>
