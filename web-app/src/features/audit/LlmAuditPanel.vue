<script setup lang="ts">
/**
 * LLM 审计面板
 *
 * 展示 Workflow 中 LLM 相关的审计记录。当前为展示骨架，
 * 后续接入 LlmAuditRecord API 后替换数据源。
 */
import { ref, onMounted } from 'vue'
import { ShieldAlert, Cpu, Zap, Clock, AlertTriangle, CheckCircle2 } from 'lucide-vue-next'
'vue'

defineProps<{
  projectId: string
}>()



// 审计记录骨架（后续替换为 API 数据）
interface AuditRecord {
  id: string
  taskName: string
  provider: string
  model: string
  latencyMs: number
  result: 'ai' | 'fallback'
  errors: string[]
  createdAt: string
}

const records = ref<AuditRecord[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    // TODO: 接入实际 LLM 审计 API
    // records.value = await getAuditRecords(props.projectId)
  } catch {
    // 静默
  } finally {
    loading.value = false
  }
})

function formatLatency(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

function formatDate(d: string): string {
  return new Date(d).toLocaleString('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="max-w-4xl mx-auto px-6 py-8">
    <header class="mb-8">
      <p class="section-eyebrow mb-2">LLM AUDIT</p>
      <h1 class="text-2xl font-bold text-surface-100 flex items-center gap-2">
        <ShieldAlert class="w-6 h-6 text-accent" />
        LLM 审计面板
      </h1>
      <p class="text-sm text-surface-400 mt-2">
        追踪每次 LLM 调用的 Provider、模型、延迟、校验结果与 fallback 情况。
      </p>
    </header>

    <!-- 统计卡片 -->
    <div class="grid grid-cols-4 gap-4 mb-6">
      <div class="card text-center">
        <p class="text-2xl font-bold text-surface-100">{{ records.length }}</p>
        <p class="text-xs text-surface-500 mt-1">总调用次数</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-success">{{ records.filter(r => r.result === 'ai').length }}</p>
        <p class="text-xs text-surface-500 mt-1">AI 采纳</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-warning">{{ records.filter(r => r.result === 'fallback').length }}</p>
        <p class="text-xs text-surface-500 mt-1">Fallback</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-surface-300">{{ records.length > 0 ? Math.round(records.filter(r => r.result === 'ai').length / records.length * 100) : 0 }}%</p>
        <p class="text-xs text-surface-500 mt-1">采纳率</p>
      </div>
    </div>

    <!-- 记录列表 -->
    <div v-if="loading" class="text-center py-8 text-surface-500 text-sm">加载审计记录...</div>

    <div v-else-if="records.length === 0" class="card text-center py-12">
      <ShieldAlert class="w-10 h-10 text-surface-600 mx-auto mb-3" />
      <p class="text-surface-400 text-sm">暂无 LLM 审计记录</p>
      <p class="text-surface-600 text-xs mt-1">运行 Workflow 且接入 LLM 后将在此显示审计信息</p>
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="r in records"
        :key="r.id"
        class="card hover:bg-surface-700/30 transition-colors"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-surface-200">{{ r.taskName }}</span>
          <span :class="['flex items-center gap-1 text-xs', r.result === 'ai' ? 'text-success' : 'text-warning']">
            <CheckCircle2 v-if="r.result === 'ai'" class="w-3.5 h-3.5" />
            <AlertTriangle v-else class="w-3.5 h-3.5" />
            {{ r.result === 'ai' ? 'AI 采纳' : 'Fallback' }}
          </span>
        </div>
        <div class="flex gap-4 text-xs text-surface-500">
          <span class="flex items-center gap-1"><Cpu class="w-3 h-3" />{{ r.provider }}</span>
          <span class="flex items-center gap-1"><Zap class="w-3 h-3" />{{ r.model }}</span>
          <span class="flex items-center gap-1"><Clock class="w-3 h-3" />{{ formatLatency(r.latencyMs) }}</span>
          <span>{{ formatDate(r.createdAt) }}</span>
        </div>
        <div v-if="r.errors.length > 0" class="mt-2 text-xs text-danger/80">
          <span v-for="(e, i) in r.errors" :key="i" class="block">{{ e }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
