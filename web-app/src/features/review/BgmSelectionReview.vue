<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { CheckCircle2, Headphones, Loader2, Music2, RefreshCw, Repeat2, UploadCloud, VolumeX } from 'lucide-vue-next'
import type { ArtifactSnapshot } from '@/api/types'
import * as bgmApi from '@/api/bgm'
import { useUiStore } from '@/stores/ui'

const props = defineProps<{
  runId: string
  candidates: ArtifactSnapshot[]
  timelineDurationMs: number
  providerFailed: boolean
}>()

const emit = defineEmits<{ confirm: [] }>()
const uiStore = useUiStore()
const selectedId = ref<string | null>(null)
const submitting = ref(false)
const localFile = ref<File | null>(null)
const localAudioUrl = ref('')
const localDurationMs = ref(0)
const localPlaybackMode = ref<'ONCE' | 'LOOP' | null>(null)
const refreshing = ref(false)

const localIsShorter = computed(() => localDurationMs.value > 0
  && props.timelineDurationMs > 0
  && localDurationMs.value < props.timelineDurationMs)

const canUploadLocal = computed(() => Boolean(localFile.value)
  && (!localIsShorter.value || localPlaybackMode.value !== null))

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
  profileTags: string[]
  reasons: string[]
  batch: number
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
    profileTags: Array.isArray(metadata.musicProfileTags)
      ? metadata.musicProfileTags.map((tag) => String(tag))
      : [],
    reasons: Array.isArray(metadata.recommendationReasons)
      ? metadata.recommendationReasons.map((reason) => String(reason))
      : [],
    batch: Number(metadata.recommendationBatch ?? 0),
  }
}).sort((left, right) => left.rank - right.rank))

async function refreshCandidates(): Promise<void> {
  if (submitting.value || refreshing.value) return
  refreshing.value = true
  selectedId.value = null
  try {
    await bgmApi.refreshBgm(props.runId)
    uiStore.showToast('正在结合故事情绪搜索另一批音乐', 'success')
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '刷新音乐候选失败', 'error')
  } finally {
    refreshing.value = false
  }
}

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

function chooseLocalFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  if (localAudioUrl.value) URL.revokeObjectURL(localAudioUrl.value)
  localFile.value = file
  localAudioUrl.value = file ? URL.createObjectURL(file) : ''
  localDurationMs.value = 0
  localPlaybackMode.value = null
  if (file && file.size > 100 * 1024 * 1024) {
    uiStore.showToast('本地 BGM 文件不能超过 100 MB', 'error')
    localFile.value = null
    URL.revokeObjectURL(localAudioUrl.value)
    localAudioUrl.value = ''
    input.value = ''
  }
}

function readLocalDuration(event: Event): void {
  const audio = event.currentTarget as HTMLAudioElement
  localDurationMs.value = Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : 0
  if (!localIsShorter.value) localPlaybackMode.value = 'ONCE'
}

async function applyLocalBgm(): Promise<void> {
  if (!localFile.value || !canUploadLocal.value || submitting.value) return
  submitting.value = true
  try {
    await bgmApi.uploadBgm(
      props.runId,
      localFile.value,
      localPlaybackMode.value ?? 'ONCE',
      localDurationMs.value,
    )
    uiStore.showToast(
      localPlaybackMode.value === 'LOOP' ? '本地 BGM 将循环播放至视频结束' : '本地 BGM 将播放一次',
      'success',
    )
    emit('confirm')
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '本地 BGM 上传失败', 'error')
  } finally {
    submitting.value = false
  }
}

onUnmounted(() => {
  if (localAudioUrl.value) URL.revokeObjectURL(localAudioUrl.value)
})
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
      <div class="flex shrink-0 flex-wrap gap-2">
        <button class="btn-secondary" :disabled="submitting || refreshing" @click="refreshCandidates">
          <Loader2 v-if="refreshing" class="h-4 w-4 animate-spin" />
          <RefreshCw v-else class="h-4 w-4" />
          换一批
        </button>
        <button class="btn-secondary" :disabled="submitting || refreshing" @click="continueWithoutBgm">
          <VolumeX class="h-4 w-4" />
          无 BGM 继续
        </button>
      </div>
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
                <span v-for="tag in candidate.profileTags" :key="tag">{{ tag }}</span>
                <span>{{ candidate.provider }}</span>
                <span v-if="candidate.licenseName">{{ candidate.licenseName }}</span>
              </span>
              <span v-if="candidate.reasons.length" class="mt-2 block text-[11px] text-surface-400">
                {{ candidate.reasons.join(' · ') }}
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
      <p class="text-sm text-surface-300">
        {{ providerFailed ? '在线音乐服务请求失败' : '当前没有可用音乐候选' }}
      </p>
      <p class="mt-1 text-xs text-surface-500">
        {{ providerFailed ? '仍可上传本地音乐，或选择无 BGM 继续。' : '可以上传本地音乐，或安全选择无 BGM 继续。' }}
      </p>
    </div>

    <div class="mt-5 rounded-xl border border-accent/25 bg-accent/5 p-4">
      <div class="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
        <div>
          <h4 class="flex items-center gap-2 text-sm font-semibold text-surface-100">
            <UploadCloud class="h-4 w-4 text-accent-light" />
            上传本地背景音乐
          </h4>
          <p class="mt-1 text-xs text-surface-400">
            无论在线候选是否可用，都可以上传 MP3、WAV、M4A、AAC、OGG 或 FLAC，最大 100 MB。
          </p>
        </div>
        <label class="btn-secondary cursor-pointer" :class="{ 'pointer-events-none opacity-50': submitting }">
          <UploadCloud class="h-4 w-4" />
          选择音频
          <input
            class="sr-only"
            type="file"
            accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac"
            :disabled="submitting"
            @change="chooseLocalFile"
          />
        </label>
      </div>

      <div v-if="localFile" class="mt-4 rounded-lg border border-surface-700 bg-surface-850 p-3">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-center">
          <div class="min-w-0 flex-1">
            <strong class="block truncate text-sm text-surface-100">{{ localFile.name }}</strong>
            <span class="mt-1 block text-xs text-surface-500">
              音频 {{ formatDuration(localDurationMs) }} · 视频 {{ formatDuration(timelineDurationMs) }}
            </span>
          </div>
          <audio
            :src="localAudioUrl"
            controls
            preload="metadata"
            class="w-full lg:w-72"
            @loadedmetadata="readLocalDuration"
          />
        </div>

        <div v-if="localIsShorter" class="mt-4 rounded-lg border border-warning/30 bg-warning/5 p-3">
          <p class="text-sm font-medium text-warning">这段音乐比视频短，是否重复播放？</p>
          <p class="mt-1 text-xs text-surface-400">循环模式会重复音乐直到视频结束；单次模式播放完后只保留视频原声。</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              :class="localPlaybackMode === 'LOOP' ? 'btn-primary' : 'btn-secondary'"
              :disabled="submitting"
              @click="localPlaybackMode = 'LOOP'"
            >
              <Repeat2 class="h-4 w-4" />
              是，循环至视频结束
            </button>
            <button
              type="button"
              :class="localPlaybackMode === 'ONCE' ? 'btn-primary' : 'btn-secondary'"
              :disabled="submitting"
              @click="localPlaybackMode = 'ONCE'"
            >
              否，只播放一次
            </button>
          </div>
        </div>

        <div class="mt-4 flex justify-end">
          <button class="btn-primary" :disabled="!canUploadLocal || submitting" @click="applyLocalBgm">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
            <UploadCloud v-else class="h-4 w-4" />
            使用本地音乐并继续
          </button>
        </div>
      </div>
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
