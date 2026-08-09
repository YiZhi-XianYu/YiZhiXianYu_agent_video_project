<script setup lang="ts">
/**
 * Workflow 启动面板
 *
 * 提供清晰度选择、自然语言时长输入和 Auto 模式开关。
 * 点击启动后调用后端创建 Workflow，成功后跳转到监控页。
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Play, Zap, Loader2, Info } from 'lucide-vue-next'
import { createAnalysisRun } from '@/api/workflows'
import { useUiStore } from '@/stores/ui'
import { useProjectStore } from '@/stores/project'
import { PROXY_QUALITY_OPTIONS } from '@/shared/constants'
import type { CreateAnalysisRunRequest } from '@/api/types'

const props = defineProps<{
  projectId: string
  hasAssets: boolean
}>()

const router = useRouter()
const uiStore = useUiStore()
const projectStore = useProjectStore()

// ===================== State =====================

const proxyQuality = ref<CreateAnalysisRunRequest['quality']>('1080P')
const durationPrompt = ref('')
const launching = ref(false)

// ===================== Computed =====================


const canLaunch = computed(() => props.hasAssets && !launching.value)

// ===================== Methods =====================

async function launch(): Promise<void> {
  if (!canLaunch.value) return
  launching.value = true
  try {
    const request: CreateAnalysisRunRequest = {
      assetIds: projectStore.assets.map(a => a.id),
      quality: proxyQuality.value,
      autoMode: uiStore.autoMode,
    }
    if (durationPrompt.value.trim()) {
      request.durationPrompt = durationPrompt.value.trim()
    }
    const run = await createAnalysisRun(props.projectId, request)
    uiStore.showToast('Workflow 已启动', 'success')
    router.push(`/projects/${props.projectId}/runs/${run.workflowRunId}`)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '启动 Workflow 失败'
    uiStore.showToast(msg, 'error')
  } finally {
    launching.value = false
  }
}
</script>

<template>
  <div class="card">
    <h2 class="section-heading mb-4 flex items-center gap-2">
      <Play class="w-5 h-5 text-accent" />
      启动 Workflow
    </h2>

    <div
      v-if="!hasAssets"
      class="flex items-center gap-2 px-3 py-2.5 rounded-lg bg-warning/10 border border-warning/20 text-sm text-warning mb-4"
    >
      <Info class="w-4 h-4 shrink-0" />
      请先上传视频素材，再启动分析流程
    </div>

    <div class="grid gap-4">
      <div>
        <label class="form-label">输出清晰度</label>
        <select v-model="proxyQuality" class="select-field">
          <option v-for="opt in PROXY_QUALITY_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>

      <div>
        <label class="form-label">成片时长（可选）</label>
        <input
          v-model="durationPrompt"
          class="input-field"
          type="text"
          maxlength="100"
          placeholder="例如：快节奏15秒、1分钟慢旅行（留空默认30秒）"
        />
        <p class="text-xs text-surface-500 mt-1">支持自然语言，由 LLM 解析为目标时长</p>
      </div>

      <div class="flex items-center justify-between py-2">
        <div>
          <span class="text-sm text-surface-200">全自动模式</span>
          <p class="text-xs text-surface-500 mt-0.5">
            {{ uiStore.autoMode ? '跳过所有审核环节，直达成片' : '在关键节点暂停，等你审核后再继续' }}
          </p>
        </div>
        <button
          type="button"
          role="switch"
          :aria-checked="uiStore.autoMode"
          :class="[
            'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200',
            uiStore.autoMode ? 'bg-accent' : 'bg-surface-600',
          ]"
          @click="uiStore.toggleAutoMode()"
        >
          <span
            :class="[
              'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200',
              uiStore.autoMode ? 'translate-x-5' : 'translate-x-0',
            ]"
          />
        </button>
      </div>

      <button
        class="btn-primary w-full"
        :disabled="!canLaunch"
        @click="launch"
      >
        <Loader2 v-if="launching" class="w-4 h-4 animate-spin" />
        <Zap v-else class="w-4 h-4" />
        {{ launching ? '启动中...' : '启动分析流程' }}
      </button>
    </div>
  </div>
</template>
