<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, CheckCircle2, Clock, Cpu, Loader2, ShieldAlert, Zap } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import { get } from '@/api/client'

const props = defineProps<{
  projectId?: string
}>()

interface AuditRecord {
  id: string
  projectId: string
  projectName: string
  runId: string
  provider: string
  model: string
  latencyMs: number
  result: 'ai' | 'fallback'
  errors: string[]
  createdAt: string
}

const router = useRouter()
const projectStore = useProjectStore()
const records = ref<AuditRecord[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(loadRecords)

async function loadRecords(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await projectStore.fetchProjects()
    const response = await get<{ items: AuditRecord[] }>(`/api/v1/llm-audits`, {
      params: { projectId: props.projectId, page: 0, size: 100 },
    })
    records.value = response.items || []
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'LLM 审计记录加载失败'
  } finally {
    loading.value = false
  }
}

function formatLatency(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN')
}

function openRun(record: AuditRecord): void {
  router.push(`/projects/${record.projectId}/runs/${record.runId}`)
}
</script>

<template>
  <div class="max-w-5xl mx-auto px-6 py-8">
    <header class="mb-8">
      <p class="section-eyebrow mb-2">LLM AUDIT</p>
      <h1 class="text-2xl font-bold text-surface-100 flex items-center gap-2">
        <ShieldAlert class="w-6 h-6 text-accent" /> LLM 审计
      </h1>
      <p class="text-sm text-surface-400 mt-2">从不可变 Story Plan Artifact 汇总 Provider、模型、校验结果和安全回退记录。</p>
    </header>

    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
      <div class="card text-center"><p class="text-2xl font-bold">{{ records.length }}</p><p class="text-xs text-surface-500 mt-1">总记录</p></div>
      <div class="card text-center"><p class="text-2xl font-bold text-success">{{ records.filter(r => r.result === 'ai').length }}</p><p class="text-xs text-surface-500 mt-1">LLM 采纳</p></div>
      <div class="card text-center"><p class="text-2xl font-bold text-warning">{{ records.filter(r => r.result === 'fallback').length }}</p><p class="text-xs text-surface-500 mt-1">安全回退</p></div>
      <div class="card text-center"><p class="text-2xl font-bold text-surface-300">{{ records.length ? Math.round(records.filter(r => r.result === 'ai').length / records.length * 100) : 0 }}%</p><p class="text-xs text-surface-500 mt-1">采纳率</p></div>
    </div>

    <div v-if="loading" class="flex justify-center py-16 text-surface-400"><Loader2 class="w-5 h-5 animate-spin mr-2" />正在加载审计记录...</div>
    <div v-else-if="error" class="card border-danger/30 text-danger text-sm">{{ error }}<button class="btn-secondary ml-3 text-xs" @click="loadRecords">重试</button></div>
    <div v-else-if="records.length === 0" class="card text-center py-12 text-surface-400">暂无 Story Plan LLM 审计记录</div>
    <div v-else class="space-y-3">
      <button v-for="record in records" :key="record.id" class="card w-full text-left hover:border-accent/40 transition-colors" @click="openRun(record)">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-2">
          <div><p class="text-sm font-medium text-surface-100">{{ record.projectName }}</p><p class="text-xs text-surface-500 mt-1">{{ formatDate(record.createdAt) }}</p></div>
          <span :class="['flex items-center gap-1 text-xs', record.result === 'ai' ? 'text-success' : 'text-warning']">
            <CheckCircle2 v-if="record.result === 'ai'" class="w-3.5 h-3.5" /><AlertTriangle v-else class="w-3.5 h-3.5" />
            {{ record.result === 'ai' ? 'LLM 采纳' : '安全回退' }}
          </span>
        </div>
        <div class="flex flex-wrap gap-4 text-xs text-surface-500">
          <span class="flex items-center gap-1"><Cpu class="w-3 h-3" />{{ record.provider }}</span>
          <span class="flex items-center gap-1"><Zap class="w-3 h-3" />{{ record.model }}</span>
          <span class="flex items-center gap-1"><Clock class="w-3 h-3" />{{ formatLatency(record.latencyMs) }}</span>
        </div>
        <p v-for="(item, index) in record.errors.slice(0, 2)" :key="index" class="text-xs text-danger/80 mt-2">{{ item }}</p>
      </button>
    </div>
  </div>
</template>
