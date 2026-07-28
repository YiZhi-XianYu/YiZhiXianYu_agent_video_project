<script setup lang="ts">
import { computed, ref } from 'vue'
import { CheckCircle2, Headphones, Loader2, Music2, VolumeX } from 'lucide-vue-next'
import type { ArtifactSnapshot } from '@/api/types'
import * as bgmApi from '@/api/bgm'
import { useUiStore } from '@/stores/ui'

const props = defineProps<{
  runId: string
  candidates: ArtifactSnapshot[]
}>()

const emit = defineEmits<{ confirm: [] }>()
const uiStore = useUiStore()
const selectedId = ref<string | null>(null)
const submitting = ref(false)

interface CandidateView {
  artifact: ArtifactSnapshot
  title: string
  artist: string
  rank: number
  score: number
  mood: string
  durationMs: number
  provider: string
  sourceUrl: string
  licenseName: string
}

const rankedCandidates = computed<CandidateView[]>(() => props.candidates.map((artifact) => {
  let metadata: Record<string, unknown> = {}
  try {
    metadata = JSON.parse(artifact.metadataJson || '{}') as Record<string, unknown>
  } catch {
    metadata = {}
  }
  return {
    artifact,
    title: String(metadata.title ?? '未命名音乐'),
    artist: String(metadata.artist ?? '未知作者'),
    rank: Number(metadata.rank ?? 999),
    score: Number(metadata.score ?? 0),
    mood: String(metadata.selectedMood ?? ''),
    durationMs: Number(metadata.bgmDurationMs ?? 0),
    provider: String(metadata.provider ?? 'unknown'),
    sourceUrl: String(metadata.sourceUrl ?? ''),
    licenseName: String(metadata.licenseName ?? ''),
  }
}).sort((left, right) => left.rank - right.rank))

function formatDuration(durationMs: number): string {
  if (!durationMs) return '时长未知'
  const minutes = Math.floor(durationMs / 60_000)
  const seconds = Math.round((durationMs % 60_000) / 1000)
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

async function applySelection(): Promise<void> {
  if (!selectedId.value || submitting.value) return
  submitting.value = true
  try {
    await bgmApi.selectBgm(props.runId, selectedId.value)
    uiStore.showToast('背景音乐已选择，继续渲染', 'success')
    emit('confirm')
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '背景音乐选择失败', 'error')
  } finally {
    submitting.value = false
  }
}

async function continueWithoutBgm(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  try {
    await bgmApi.skipBgm(props.runId)
    uiStore.showToast('已选择无 BGM 模式，继续渲染', 'success')
    emit('confirm')
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '继续 Workflow 失败', 'error')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="card mb-6 ring-1 ring-warning/30">
    <div class="mb-5 flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
      <div>
        <p class="section-eyebrow mb-2">BGM REVIEW</p>
        <h3 class="flex items-center gap-2 text-lg font-semibold text-surface-100">
          <Headphones class="h-5 w-5 text-warning" />
          试听并选择背景音乐
        </h3>
        <p class="mt-2 text-sm text-surface-400">
          候选按情绪、时长和匹配度排序。选择只会创建新的 BGM_AUDIO Artifact，不会修改候选文件。
        </p>
      </div>
      <button class="btn-secondary shrink-0" :disabled="submitting" @click="continueWithoutBgm">
        <VolumeX class="h-4 w-4" />
        无 BGM 继续
      </button>
    </div>

    <div v-if="rankedCandidates.length" class="grid gap-3">
      <article
        v-for="candidate in rankedCandidates"
        :key="candidate.artifact.externalArtifactId"
        :class="[
          'rounded-xl border p-4 transition-colors',
          selectedId === candidate.artifact.externalArtifactId
            ? 'border-accent/70 bg-accent/10'
            : 'border-surface-700 bg-surface-850 hover:border-surface-600',
        ]"
      >
        <div class="flex flex-col gap-4 lg:flex-row lg:items-center">
          <button
            class="flex min-w-0 flex-1 items-start gap-3 text-left"
            @click="selectedId = candidate.artifact.externalArtifactId"
          >
            <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-700 text-sm font-bold text-accent-light">
              {{ candidate.rank }}
            </span>
            <span class="min-w-0">
              <strong class="block truncate text-sm text-surface-100">{{ candidate.title }}</strong>
              <span class="mt-1 block text-xs text-surface-400">{{ candidate.artist }}</span>
              <span class="mt-2 flex flex-wrap gap-2 text-[11px] text-surface-500">
                <span>{{ formatDuration(candidate.durationMs) }}</span>
                <span>匹配分 {{ candidate.score.toFixed(1) }}</span>
                <span>{{ candidate.mood || '综合情绪' }}</span>
                <span>{{ candidate.provider }}</span>
                <span v-if="candidate.licenseName">{{ candidate.licenseName }}</span>
              </span>
            </span>
          </button>

          <audio :src="candidate.artifact.contentUrl" controls preload="none" class="w-full lg:w-72" />

          <a
            v-if="candidate.sourceUrl"
            :href="candidate.sourceUrl"
            target="_blank"
            rel="noreferrer"
            class="text-xs text-accent-light hover:underline"
          >来源页面</a>
        </div>
      </article>
    </div>

    <div v-else class="rounded-xl border border-dashed border-surface-700 py-10 text-center">
      <Music2 class="mx-auto mb-3 h-7 w-7 text-surface-500" />
      <p class="text-sm text-surface-300">当前没有可用音乐候选</p>
      <p class="mt-1 text-xs text-surface-500">Provider 不可用时可以安全选择无 BGM 继续。</p>
    </div>

    <div class="mt-5 flex justify-end">
      <button class="btn-primary" :disabled="!selectedId || submitting" @click="applySelection">
        <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
        <CheckCircle2 v-else class="h-4 w-4" />
        使用所选音乐并继续
      </button>
    </div>
  </section>
</template>
