<script setup lang="ts">
/**
 * 版本列表组件
 *
 * 展示 Story Plan 的已保存版本，支持加载、对比、恢复和删除。
 */
import { ref, onMounted } from 'vue'
import { GitBranch, Loader2, RotateCcw, Trash2, Diff } from 'lucide-vue-next'
import { listVersions, restoreVersion, deleteVersion } from '@/api/plans'
import { useUiStore } from '@/stores/ui'
import type { VersionListItem } from '@/api/types'

const props = defineProps<{
  runId: string
}>()

const emit = defineEmits<{
  /** 请求对比：{ planId, versionName } */
  diff: [planId: string, versionName: string]
  /** 版本已加载 */
  loaded: [planId: string]
}>()

const uiStore = useUiStore()
const versions = ref<VersionListItem[]>([])
const loading = ref(false)
const restoring = ref<string | null>(null)

async function fetch(): Promise<void> {
  loading.value = true
  try {
    versions.value = await listVersions(props.runId)
  } catch {
    uiStore.showToast('获取版本列表失败', 'error')
  } finally {
    loading.value = false
  }
}

async function handleRestore(planId: string): Promise<void> {
  restoring.value = planId
  try {
    await restoreVersion(props.runId, planId)
    uiStore.showToast('版本已恢复', 'success')
    emit('loaded', planId)
  } catch {
    uiStore.showToast('恢复版本失败', 'error')
  } finally {
    restoring.value = null
  }
}

async function handleDelete(planId: string): Promise<void> {
  try {
    await deleteVersion(props.runId, planId)
    uiStore.showToast('版本已删除', 'success')
    await fetch()
  } catch {
    uiStore.showToast('删除版本失败', 'error')
  }
}

onMounted(() => fetch())

function formatDate(d: string): string {
  return new Date(d).toLocaleString('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="card">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
        <GitBranch class="w-5 h-5 text-accent" />
        已保存版本
      </h3>
      <span class="text-xs text-surface-500">{{ versions.length }} 个版本</span>
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <Loader2 class="w-5 h-5 animate-spin text-surface-500" />
    </div>

    <div v-else-if="versions.length === 0" class="text-center py-8 text-surface-500 text-sm">
      暂无已保存版本，在故事编辑器中保存后在此查看
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="v in versions"
        :key="v.id"
        class="flex items-center gap-3 px-3 py-2.5 rounded-lg bg-surface-700/30 hover:bg-surface-700/50 transition-colors"
      >
        <GitBranch class="w-4 h-4 text-surface-500 shrink-0" />
        <div class="flex-1 min-w-0">
          <p class="text-sm text-surface-200 truncate">{{ v.versionName || '未命名版本' }}</p>
          <p class="text-xs text-surface-500">{{ formatDate(v.createdAt) }}</p>
        </div>
        <div class="flex gap-1 shrink-0">
          <button class="w-7 h-7 rounded flex items-center justify-center text-surface-500 hover:text-accent hover:bg-surface-600 transition-colors"
                  title="对比当前" @click="emit('diff', v.id, v.versionName)">
            <Diff class="w-3.5 h-3.5" />
          </button>
          <button class="w-7 h-7 rounded flex items-center justify-center text-surface-500 hover:text-accent hover:bg-surface-600 transition-colors"
                  :disabled="restoring === v.id" title="恢复此版本" @click="handleRestore(v.id)">
            <Loader2 v-if="restoring === v.id" class="w-3.5 h-3.5 animate-spin" />
            <RotateCcw v-else class="w-3.5 h-3.5" />
          </button>
          <button class="w-7 h-7 rounded flex items-center justify-center text-surface-500 hover:text-danger hover:bg-surface-600 transition-colors"
                  title="删除" @click="handleDelete(v.id)">
            <Trash2 class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
