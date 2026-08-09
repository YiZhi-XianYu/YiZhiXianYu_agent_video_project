<script setup lang="ts">
/**
 * 版本管理页
 *
 * 集成版本列表和 Diff 对比，支持加载、恢复、删除和对比操作。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { useUiStore } from '@/stores/ui'
import VersionList from '@/features/versions/VersionList.vue'
import VersionDiff from '@/features/versions/VersionDiff.vue'

defineProps<{
  projectId: string
  runId: string
}>()

const router = useRouter()
const uiStore = useUiStore()

const diffPlanId = ref<string | null>(null)
const diffVersionName = ref('')

function handleDiff(planId: string, versionName: string): void {
  diffPlanId.value = planId
  diffVersionName.value = versionName
}

function closeDiff(): void {
  diffPlanId.value = null
  diffVersionName.value = ''
}

function handleVersionLoaded(_planId: string): void {
  uiStore.showToast('版本已恢复', 'success')
}
</script>

<template>
  <div class="max-w-4xl mx-auto px-6 py-8">
    <header class="flex items-center gap-4 mb-8">
      <button class="w-9 h-9 rounded-lg flex items-center justify-center
                     text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors shrink-0"
              @click="router.push(`/projects/${projectId}/runs/${runId}`)">
        <ArrowLeft class="w-5 h-5" />
      </button>
      <div class="min-w-0">
        <p class="section-eyebrow mb-1">VERSION MANAGEMENT</p>
        <h1 class="text-xl font-bold text-surface-100">版本管理</h1>
      </div>
    </header>

    <div class="grid gap-6">
      <VersionList :run-id="runId" @diff="handleDiff" @loaded="handleVersionLoaded" />
      <VersionDiff
        :run-id="runId"
        :plan-id="diffPlanId"
        :version-name="diffVersionName"
        @close="closeDiff"
      />
    </div>
  </div>
</template>
