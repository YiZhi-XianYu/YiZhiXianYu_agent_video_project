<script setup lang="ts">
/**
 * Workflow 运行监控页
 *
 * 实时展示 Workflow DAG 中 Task 状态、整体进度和 Gate 人在回路审核界面。
 * 分层轮询：1.5s 刷新 Workflow 状态，进入 PAUSED 后展示对应审核视图。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Loader2, CheckCircle2, Download, Film, XCircle, PauseCircle } from 'lucide-vue-next'
import { useWorkflowStore } from '@/stores/workflow'
import { useReviewStore } from '@/stores/review'
import { useUiStore } from '@/stores/ui'
import { usePolling } from '@/shared/composables/usePolling'
import { ApiError } from '@/api/client'
import { getGateDraft, saveGateDraft, submitGateFeedback } from '@/api/workflows'
import { WORKFLOW_POLL_INTERVAL_MS, RUN_STATUS_LABEL } from '@/shared/constants'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TaskGrid from '@/components/TaskGrid.vue'
import ShotGalleryView from '@/features/review/ShotGalleryView.vue'
import ShotRankingReview from '@/features/review/ShotRankingReview.vue'
import StoryEditor from '@/features/review/StoryEditor.vue'
import TimelinePreview from '@/features/review/TimelinePreview.vue'
import BgmSelectionReview from '@/features/review/BgmSelectionReview.vue'
import FinalReview from '@/features/review/FinalReview.vue'
import type { ArtifactSnapshot } from '@/api/types'
import type { BeatRole, ShotScore, StoryPlan, Timeline, TransitionType } from '@/shared/types'

const props = defineProps<{
  projectId: string
  runId: string
}>()

const router = useRouter()
const workflowStore = useWorkflowStore()
const reviewStore = useReviewStore()
const uiStore = useUiStore()
const gateDraftStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
let gateDraftTimer: ReturnType<typeof setTimeout> | null = null
let hydratingGateDraft = false

/** Gate 1 视图模式：true = 画廊视图, false = 列表视图 */
const showGalleryView = ref(true)
const gateFeedbackScore = ref(0)
const gateFeedbackReasons = ref<string[]>([])
const gateFeedbackComment = ref('')
const gateFeedbackStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const feedbackReasonOptions = ['镜头不合适', '节奏不对', '素材重复', '风格不符']

const renderedVideo = computed(() => workflowStore.tasks
  .flatMap((task) => task.artifacts)
  .find((artifact) => artifact.type === 'RENDERED_VIDEO') ?? null)

const renderedVideoDownloadUrl = computed(() => {
  if (!renderedVideo.value) return ''
  return `${renderedVideo.value.contentUrl}?download=true`
})

const proxyUrls = computed<Record<string, string>>(() => Object.fromEntries(
  workflowStore.tasks
    .flatMap((task) => task.artifacts)
    .filter((artifact) => artifact.type === 'VIDEO_PROXY')
    .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl]),
))

const bgmCandidates = computed(() => workflowStore.tasks
  .find((task) => task.nodeKey === 'bgm_select')
  ?.artifacts.filter((artifact) => artifact.type === 'BGM_CANDIDATE') ?? [])

const latestBgmCandidates = computed(() => {
  const candidates = bgmCandidates.value
  if (!candidates.length) return []
  let latestSetId = ''
  try {
    latestSetId = String(JSON.parse(candidates[0]?.metadataJson || '{}').candidateSetId ?? '')
  } catch {
    return candidates
  }
  if (!latestSetId) return candidates
  return candidates.filter((artifact) => {
    try {
      return String(JSON.parse(artifact.metadataJson || '{}').candidateSetId ?? '') === latestSetId
    } catch {
      return false
    }
  })
})

const timelineDurationMs = computed(() => {
  const artifact = workflowStore.tasks
    .find((task) => task.nodeKey === 'timeline_compose')
    ?.artifacts.find((item) => item.type === 'TIMELINE')
  if (!artifact) return 0
  try {
    const metadata = JSON.parse(artifact.metadataJson || '{}') as Record<string, unknown>
    return Number(metadata.durationMs ?? 0)
  } catch {
    return 0
  }
})

const bgmProviderFailed = computed(() => workflowStore.tasks
  .some((task) => task.nodeKey === 'bgm_select' && task.status === 'FAILED'))

// ===================== 分层轮询 =====================

const { start: startPolling, stop: stopPolling } = usePolling(
  () => workflowStore.fetchRun(props.runId),
  WORKFLOW_POLL_INTERVAL_MS,
)

onMounted(async () => {
  workflowStore.clear()
  await workflowStore.fetchRun(props.runId)
  syncGate()
  if (!workflowStore.isTerminal) startPolling()
})

watch(() => workflowStore.isTerminal, (terminal) => {
  if (terminal) stopPolling()
})

// Gate 变化时同步到 review store
watch(() => workflowStore.run?.currentGateKey, () => {
  syncGate()
  gateFeedbackScore.value = 0
  gateFeedbackReasons.value = []
  gateFeedbackComment.value = ''
  gateFeedbackStatus.value = 'idle'
})

watch(() => ({
  gate: reviewStore.currentGate?.gateKey,
  shotScores: reviewStore.shotScores,
  excludedShotIds: [...reviewStore.excludedShotIds],
  forcedShotIds: [...reviewStore.forcedShotIds],
  storyPlan: reviewStore.storyPlan,
  lockedShotIds: [...reviewStore.lockedShotIds],
  timeline: reviewStore.timeline,
}), () => {
  if (!hydratingGateDraft && workflowStore.currentGate && workflowStore.isPaused) scheduleGateDraftSave()
}, { deep: true })

onUnmounted(() => {
  stopPolling()
  workflowStore.clear()
  reviewStore.resetAll()
})

// ===================== Gate 同步 =====================

async function syncGate(): Promise<void> {
  const gate = workflowStore.currentGate
  reviewStore.activateGate(gate)
  if (!gate) return

  hydratingGateDraft = true
  gateDraftStatus.value = 'idle'
  try {
    if (gate.gateKey === 'gate_shot_ranking') {
      const payload = await loadArtifactJson('shot_ranking', 'SHOT_RANKING')
      const scores = mapShotScores(payload)
      reviewStore.setShotScores(await enrichShotScores(scores))
    } else if (gate.gateKey === 'gate_story_edit') {
      const payload = await loadArtifactJson('story_plan', 'STORY_PLAN')
      reviewStore.setStoryPlan(mapStoryPlan(payload))
      const ranking = await loadArtifactJson('shot_ranking', 'SHOT_RANKING')
      reviewStore.setShotScores(await enrichShotScores(mapShotScores(ranking)))
    } else if (gate.gateKey === 'gate_timeline_preview') {
      const payload = await loadArtifactJson('timeline_compose', 'TIMELINE')
      reviewStore.setTimeline(mapTimeline(payload))
    } else if (gate.gateKey === 'gate_bgm_review') {
      // Candidate metadata and audio URLs are already included in the Workflow snapshot.
    } else if (gate.gateKey === 'gate_render_review') {
      const artifact = renderedVideo.value
      if (!artifact) throw new Error('缺少 RENDERED_VIDEO Artifact，无法打开当前审核页')
      reviewStore.setRenderedVideo(artifact.contentUrl)
    }
    await restoreGateDraft(gate.gateKey)
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '审核数据加载失败', 'error')
  } finally {
    hydratingGateDraft = false
  }
}

function gateDraftPayload(): Record<string, unknown> {
  return { version: 1, gateKey: reviewStore.currentGate?.gateKey, shotScores: reviewStore.shotScores, excludedShotIds: [...reviewStore.excludedShotIds], forcedShotIds: [...reviewStore.forcedShotIds], storyPlan: reviewStore.storyPlan, lockedShotIds: [...reviewStore.lockedShotIds], timeline: reviewStore.timeline }
}

function scheduleGateDraftSave(): void {
  const gateKey = workflowStore.currentGate?.gateKey
  if (!gateKey || !workflowStore.isPaused) return
  gateDraftStatus.value = 'saving'
  if (gateDraftTimer) clearTimeout(gateDraftTimer)
  gateDraftTimer = setTimeout(async () => {
    try { await saveGateDraft(props.runId, gateKey, gateDraftPayload()); gateDraftStatus.value = 'saved' }
    catch { gateDraftStatus.value = 'error' }
  }, 700)
}

async function restoreGateDraft(gateKey: string): Promise<void> {
  try {
    const draft = await getGateDraft<Record<string, any>>(props.runId, gateKey)
    if (!draft || draft.version !== 1 || draft.gateKey !== gateKey) return
    if (Array.isArray(draft.shotScores) && draft.shotScores.length) reviewStore.setShotScores(draft.shotScores)
    if (Array.isArray(draft.excludedShotIds)) reviewStore.setExcludedShotIds(draft.excludedShotIds)
    if (Array.isArray(draft.forcedShotIds)) reviewStore.setForcedShotIds(draft.forcedShotIds)
    if (draft.storyPlan) reviewStore.setStoryPlan(draft.storyPlan)
    if (Array.isArray(draft.lockedShotIds)) reviewStore.setLockedShotIds(draft.lockedShotIds)
    if (draft.timeline) reviewStore.setTimeline(draft.timeline)
    gateDraftStatus.value = 'saved'
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) gateDraftStatus.value = 'error'
  }
}

function findArtifact(nodeKey: string, type: string): ArtifactSnapshot {
  const artifact = workflowStore.tasks
    .find((task) => task.nodeKey === nodeKey)
    ?.artifacts.find((item) => item.type === type)
  if (!artifact) throw new Error(`缺少 ${type} Artifact，无法打开当前审核页`)
  return artifact
}

/**
 * Return every artifact produced by a node key.  Asset-scoped nodes are
 * instantiated once per input asset, so using `find()` here silently drops
 * all but the first asset's output.
 */
function findArtifacts(nodeKey: string, type: string): ArtifactSnapshot[] {
  return workflowStore.tasks
    .filter((task) => task.nodeKey === nodeKey)
    .flatMap((task) => task.artifacts.filter((artifact) => artifact.type === type))
}

async function loadArtifactJson(nodeKey: string, type: string): Promise<Record<string, any>> {
  const artifact = findArtifact(nodeKey, type)
  return await fetch(artifact.contentUrl).then(async (response) => {
    if (!response.ok) throw new Error(`${type} Artifact 加载失败：HTTP ${response.status}`)
    return await response.json() as Record<string, any>
  })
}

function mapShotScores(payload: Record<string, any>): ShotScore[] {
  return (payload.shots ?? []).map((shot: Record<string, any>) => ({
    shotId: String(shot.shotId),
    quality: {
      sharpness: Number(shot.clarity ?? 0) * 100,
      exposure: Number(shot.exposure ?? 0) * 100,
      stability: Number(shot.stability ?? 0) * 100,
      composition: Number(shot.composition ?? 0) * 100,
      motionInterest: Number(shot.motionInterest ?? 0) * 100,
      overall: Number(shot.qualityScore ?? 0) * 100,
    },
    labels: { scene: [], object: [], person: [] },
    rankScore: Number(shot.finalScore ?? shot.qualityScore ?? 0) * 100,
    penalties: (shot.rejectionReasons ?? []).map(String),
    selected: Boolean(shot.eligible),
    sourceAssetId: shot.sourceAssetId ? String(shot.sourceAssetId) : undefined,
    sourceProxyArtifactId: shot.sourceProxyArtifactId ? String(shot.sourceProxyArtifactId) : undefined,
    startMs: Number(shot.startMs ?? 0),
    endMs: Number(shot.endMs ?? shot.durationMs ?? 0),
  }))
}

async function loadArtifactJsonList(nodeKey: string, type: string): Promise<Record<string, any>[]> {
  const artifacts = findArtifacts(nodeKey, type)
  if (!artifacts.length) throw new Error(`缺少 ${type} Artifact，无法加载当前审核页`)
  return await Promise.all(artifacts.map(async (artifact) => {
    const response = await fetch(artifact.contentUrl)
    if (!response.ok) throw new Error(`${type} Artifact 加载失败：HTTP ${response.status}`)
    return await response.json() as Record<string, any>
  }))
}

function mapStoryPlan(payload: Record<string, any>): StoryPlan {
  return {
    workflowRunId: props.runId,
    beats: (payload.beats ?? []).map((beat: Record<string, any>) => ({
      role: beat.role as BeatRole,
      shotIds: (beat.shots ?? []).map((shot: Record<string, any>) => String(shot.shotId)),
      shots: (beat.shots ?? []).map((shot: Record<string, any>) => ({
        ...shot,
        shotId: String(shot.shotId),
        sourceAssetId: String(shot.sourceAssetId ?? ''),
        sourceProxyArtifactId: String(shot.sourceProxyArtifactId ?? ''),
        startMs: Number(shot.startMs ?? 0), endMs: Number(shot.endMs ?? 0),
        sourceInMs: Number(shot.sourceInMs ?? shot.startMs ?? 0),
        sourceOutMs: Number(shot.sourceOutMs ?? shot.endMs ?? 0),
        selectedDurationMs: Number(shot.selectedDurationMs ?? 0),
      })),
      targetDurationMs: Number(beat.targetDurationMs ?? beat.actualDurationMs ?? 0),
      actualDurationMs: Number(beat.actualDurationMs ?? beat.targetDurationMs ?? 0),
    })),
    totalDurationMs: Number(payload.targetDurationMs ?? 0),
  }
}

function mapTimeline(payload: Record<string, any>): Timeline {
  const videoTrack = (payload.tracks ?? []).find((track: Record<string, any>) => track.type === 'VIDEO')
  return {
    clips: (videoTrack?.clips ?? []).map((clip: Record<string, any>) => ({
      shotId: String(clip.shotId),
      sourceInMs: Number(clip.sourceInMs),
      sourceOutMs: Number(clip.sourceOutMs),
      durationMs: Number(clip.timelineOutMs) - Number(clip.timelineInMs),
      transition: (clip.transitionIn?.type ?? 'CUT') as TransitionType,
      transitionDurationMs: Number(clip.transitionIn?.durationMs ?? 0),
      clipId: String(clip.clipId ?? `clip_${clip.shotId}`),
      assetId: String(clip.assetId ?? ''), sourceProxyArtifactId: String(clip.sourceProxyArtifactId ?? ''),
      sourceShotStartMs: clip.sourceShotStartMs == null ? undefined : Number(clip.sourceShotStartMs),
      sourceShotEndMs: clip.sourceShotEndMs == null ? undefined : Number(clip.sourceShotEndMs),
      timelineInMs: Number(clip.timelineInMs ?? 0), timelineOutMs: Number(clip.timelineOutMs ?? 0),
      playbackRate: Number(clip.playbackRate ?? 1), storyRole: clip.storyRole as BeatRole,
      selectionRank: Number(clip.selectionRank ?? 1), selectionReasons: clip.selectionReasons ?? [],
    })),
    totalDurationMs: Number(payload.durationMs ?? 0),
    bgmName: null,
    timelineId: payload.timelineId ? String(payload.timelineId) : undefined,
    canvas: {
      width: Number(payload.canvas?.width ?? 1920),
      height: Number(payload.canvas?.height ?? 1080),
      fps: Number(payload.canvas?.fps ?? 30),
    },
  }
}

/** 从视频检测任务中提取每个镜头的关键帧 URL 和代理视频地址，合并到 ShotScore */
async function enrichShotScores(scores: ShotScore[]): Promise<ShotScore[]> {
  try {
    /* 1. 加载所有素材级 SHOT_LIST（含 keyframeArtifactId 和时间） */
    const shotListPayloads = await loadArtifactJsonList('video_shot_detect', 'SHOT_LIST')
    const shotMetaMap = new Map<string, { keyframeArtifactId: string; startMs: number; endMs: number; sourceAssetId: string; sourceProxyArtifactId: string }>(
      shotListPayloads.flatMap((payload) => (payload.shots ?? [])).map((s: Record<string, any>) => [
        String(s.shotId),
        {
          keyframeArtifactId: String(s.keyframeArtifactId),
          startMs: Number(s.startMs),
          endMs: Number(s.endMs),
          sourceAssetId: String(s.sourceAssetId ?? ''),
          sourceProxyArtifactId: String(s.sourceProxyArtifactId ?? ''),
        },
      ])
    )

    /* 2. 获取每个代理 Artifact 的 URL（按 externalArtifactId 匹配镜头来源） */
    const proxyUrlMap = new Map<string, string>(
      workflowStore.tasks
        .filter((task) => task.nodeKey === 'video_proxy_generate')
        .flatMap((task) => task.artifacts.filter((artifact) => artifact.type === 'VIDEO_PROXY'))
        .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl]),
    )

    /* 3. 从所有 video_shot_detect 任务的 artifacts 中构建 externalArtifactId → contentUrl 映射 */
    const artifactUrlMap = new Map<string, string>(
      workflowStore.tasks
        .filter((task) => task.nodeKey === 'video_shot_detect')
        .flatMap((task) => task.artifacts)
        .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl]),
    )

    /* 4. 合并到 scores */
    return scores.map((shot) => {
      const meta = shotMetaMap.get(shot.shotId)
      return {
        ...shot,
        keyframeUrl: meta ? artifactUrlMap.get(meta.keyframeArtifactId) : undefined,
        proxyVideoUrl: meta ? proxyUrlMap.get(meta.sourceProxyArtifactId) : undefined,
        startMs: meta?.startMs,
        endMs: meta?.endMs,
        sourceAssetId: meta?.sourceAssetId || shot.sourceAssetId,
        sourceProxyArtifactId: meta?.sourceProxyArtifactId || shot.sourceProxyArtifactId,
      }
    })
  } catch {
    /* 预加载失败不阻塞主流程，返回原始 scores */
    return scores
  }
}


// ===================== Actions =====================

async function handleContinue(): Promise<void> {
  try {
    await workflowStore.continueWorkflow(props.runId)
    startPolling()
  } catch {
    // 错误已在 Store 中处理
  }
}

async function handleRenderConfirm(): Promise<void> {
  await handleContinue()
}

async function handleStoryPlanApplied(): Promise<void> {
  await workflowStore.fetchRun(props.runId)
  startPolling()
}

async function handleTimelineApplied(): Promise<void> {
  await workflowStore.fetchRun(props.runId)
  startPolling()
}

async function handleBgmApplied(): Promise<void> {
  await workflowStore.fetchRun(props.runId)
  startPolling()
}

async function sendGateFeedback(): Promise<void> {
  const gate = workflowStore.currentGate
  if (!gate || !gateFeedbackScore.value || gateFeedbackStatus.value === 'saving') return
  gateFeedbackStatus.value = 'saving'
  try {
    await submitGateFeedback(props.runId, {
      gateKey: gate.gateKey,
      score: gateFeedbackScore.value,
      reasonCodes: gateFeedbackReasons.value,
      comment: gateFeedbackComment.value.trim() || undefined,
      action: undefined,
      artifactIds: workflowStore.tasks.flatMap(task => task.artifacts).map(artifact => artifact.externalArtifactId),
    })
    gateFeedbackStatus.value = 'saved'
  } catch {
    gateFeedbackStatus.value = 'error'
  }
}

function goBack(): void {
  router.push(`/projects/${props.projectId}`)
}
</script>

<template>
  <div class="page-shell workflow-page">
    <!-- 页头 -->
    <header class="flex items-center gap-4 mb-8">
      <button class="w-9 h-9 rounded-lg flex items-center justify-center
                     text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors shrink-0"
              @click="goBack">
        <ArrowLeft class="w-5 h-5" />
      </button>
      <div class="min-w-0 flex-1">
        <p class="section-eyebrow mb-1">WORKFLOW MONITOR</p>
        <h1 class="text-xl font-bold text-surface-100">Workflow 运行监控</h1>
      </div>
      <StatusBadge v-if="workflowStore.status" :status="workflowStore.status" :label-map="RUN_STATUS_LABEL" />
    </header>

    <!-- 错误信息（无论 run 是否为 null 都显示） -->
    <div v-if="workflowStore.error" class="card border-danger/30 mb-6">
      <div class="flex items-start gap-3">
        <XCircle class="w-5 h-5 text-danger shrink-0 mt-0.5" />
        <div class="flex-1">
          <p class="text-sm text-danger">{{ workflowStore.error }}</p>
          <button class="btn-secondary mt-2 text-xs" @click="workflowStore.fetchRun(runId)">重试</button>
        </div>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="!workflowStore.run && !workflowStore.error" class="flex items-center justify-center py-16">
      <Loader2 class="w-6 h-6 animate-spin text-surface-500" />
      <span class="ml-3 text-sm text-surface-400">正在加载 Workflow 状态...</span>
    </div>

    <template v-else>
      <!-- 进度 -->
      <div class="workflow-overview mb-8">
        <div class="flex items-center justify-between mb-3">
          <h2 class="section-heading">执行进度</h2>
          <span class="text-sm text-surface-400">
            {{ workflowStore.completedTaskCount }} / {{ workflowStore.totalTaskCount }}
          </span>
        </div>
        <ProgressBar
          :percent="workflowStore.progressPercent"
          :variant="workflowStore.isTerminal ? (workflowStore.status === 'SUCCEEDED' ? 'success' : 'warning') : 'accent'"
        />
      </div>

      <!-- Task 网格 -->
      <div class="mb-8">
        <div class="section-title-row mb-4"><div><p class="section-eyebrow">PROCESS</p><h2>子进程执行进度</h2></div><span>实时刷新</span></div>
        <TaskGrid :tasks="workflowStore.tasks" />
      </div>

      <!-- ===== Gate 人在回路审核区 ===== -->
      <div v-if="workflowStore.isPaused && workflowStore.currentGate" class="mb-3 text-right text-[11px]">
        <span v-if="gateDraftStatus === 'saving'" class="text-surface-500">正在自动保存当前审核草稿…</span>
        <span v-else-if="gateDraftStatus === 'saved'" class="text-emerald-400">当前审核草稿已保存</span>
        <span v-else-if="gateDraftStatus === 'error'" class="text-warning">草稿保存失败，当前页面内容仍保留</span>
      </div>

      <!-- Gate 1: 镜头排序（画廊 / 列表 双视图切换） -->
      <template v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_shot_ranking'">
        <!-- 视图切换 tabs -->
        <div class="flex gap-1 mb-3">
          <button
            :class="[
              'px-3 py-1.5 rounded text-xs font-medium transition-colors',
              showGalleryView
                ? 'bg-accent/20 text-accent border border-accent/30'
                : 'text-surface-400 hover:text-surface-200 hover:bg-surface-700',
            ]"
            @click="showGalleryView = true"
          >
            &#x1F4F7; 画廊视图
          </button>
          <button
            :class="[
              'px-3 py-1.5 rounded text-xs font-medium transition-colors',
              !showGalleryView
                ? 'bg-accent/20 text-accent border border-accent/30'
                : 'text-surface-400 hover:text-surface-200 hover:bg-surface-700',
            ]"
            @click="showGalleryView = false"
          >
            &#x1F4CB; 列表视图
          </button>
        </div>

        <ShotGalleryView
          v-if="showGalleryView"
          @confirm="handleContinue"
        />
        <ShotRankingReview
          v-else
          @confirm="handleContinue"
        />
      </template>
      <StoryEditor
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_story_edit'"
        :run-id="runId"
        @confirm="handleStoryPlanApplied"
      />
      <TimelinePreview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_timeline_preview'"
        :run-id="runId"
        :proxy-urls="proxyUrls"
        @confirm="handleTimelineApplied"
      />
      <BgmSelectionReview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_bgm_review'"
        :run-id="runId"
        :candidates="latestBgmCandidates"
        :timeline-duration-ms="timelineDurationMs"
        :provider-failed="bgmProviderFailed"
        @confirm="handleBgmApplied"
      />
      <FinalReview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_render_review'"
        @confirm="handleRenderConfirm"
      />

      <section v-if="workflowStore.isPaused && workflowStore.currentGate" class="card mb-6 border border-surface-700/80 bg-surface-800/60">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p class="section-eyebrow">OPTIONAL FEEDBACK</p>
            <h3 class="mt-1 text-sm font-semibold text-surface-100">这个 Gate 的结果符合你的预期吗？</h3>
            <p class="mt-1 text-xs text-surface-500">评分不会阻塞 Workflow，也可以直接跳过。</p>
          </div>
          <span v-if="gateFeedbackStatus === 'saved'" class="text-xs text-emerald-400">反馈已记录</span>
          <span v-else-if="gateFeedbackStatus === 'error'" class="text-xs text-warning">反馈保存失败，可稍后重试</span>
        </div>
        <div class="mt-3 flex flex-wrap items-center gap-1">
          <button
            v-for="score in 5"
            :key="score"
            type="button"
            :aria-label="`${score} 星`"
            :class="score <= gateFeedbackScore ? 'text-amber-300' : 'text-surface-600'"
            class="text-2xl leading-none transition-colors hover:text-amber-200"
            @click="gateFeedbackScore = score"
          >★</button>
          <span v-if="gateFeedbackScore" class="ml-2 text-xs text-surface-400">{{ gateFeedbackScore }}/5</span>
        </div>
        <div v-if="gateFeedbackScore" class="mt-3 flex flex-wrap gap-2">
          <button
            v-for="reason in feedbackReasonOptions"
            :key="reason"
            type="button"
            :class="gateFeedbackReasons.includes(reason) ? 'border-accent/50 bg-accent/15 text-accent-light' : 'border-surface-700 text-surface-400'"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            @click="gateFeedbackReasons = gateFeedbackReasons.includes(reason) ? gateFeedbackReasons.filter(item => item !== reason) : [...gateFeedbackReasons, reason]"
          >{{ reason }}</button>
        </div>
        <div v-if="gateFeedbackScore" class="mt-3 flex flex-col gap-2 sm:flex-row">
          <input v-model="gateFeedbackComment" class="input-field flex-1 text-xs" maxlength="2000" placeholder="可以补充一句具体感受（可选）" />
          <button type="button" class="btn-secondary shrink-0 text-xs" :disabled="gateFeedbackStatus === 'saving'" @click="sendGateFeedback">{{ gateFeedbackStatus === 'saving' ? '保存中…' : '提交反馈' }}</button>
        </div>
      </section>

      <!-- 未知 Gate 兜底 -->
      <div v-if="workflowStore.isPaused && !workflowStore.currentGate" class="card mb-6 ring-1 ring-warning/40">
        <div class="flex items-center gap-4">
          <PauseCircle class="w-6 h-6 text-warning shrink-0" />
          <div class="flex-1">
            <h3 class="text-sm font-semibold text-warning">Workflow 已暂停</h3>
            <p class="text-sm text-surface-400 mt-1">等待用户审核中...</p>
          </div>
          <button class="btn-primary" @click="handleContinue">
            <CheckCircle2 class="w-4 h-4" />
            确认并继续
          </button>
        </div>
      </div>

      <!-- 完成状态 -->
      <section v-if="workflowStore.status === 'SUCCEEDED' && renderedVideo" class="card mb-6 overflow-hidden">
        <div class="mb-5 flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
          <div>
            <p class="section-eyebrow mb-2">FINAL OUTPUT</p>
            <h2 class="flex items-center gap-2 text-lg font-semibold text-surface-100">
              <Film class="h-5 w-5 text-accent-light" />
              最终成片
            </h2>
            <p class="mt-2 text-sm text-surface-400">直接预览本次 Workflow 生成的不可变成片 Artifact，或下载原始输出文件。</p>
          </div>
          <a :href="renderedVideoDownloadUrl" class="btn-primary shrink-0">
            <Download class="h-4 w-4" />
            下载成片
          </a>
        </div>
        <div class="overflow-hidden rounded-xl border border-surface-700 bg-black">
          <video
            :src="renderedVideo.contentUrl"
            controls
            preload="metadata"
            class="aspect-video max-h-[70vh] w-full bg-black"
          />
        </div>
        <div class="mt-3 flex flex-wrap items-center justify-between gap-2 text-[11px] text-surface-500">
          <span>{{ renderedVideo.mediaType }}</span>
          <span>Artifact {{ renderedVideo.externalArtifactId }}</span>
        </div>
      </section>

      <div v-if="workflowStore.isTerminal" class="card mb-6"
           :class="workflowStore.status === 'SUCCEEDED' ? 'ring-1 ring-success/30' : 'ring-1 ring-danger/30'">
        <div class="flex items-center gap-4">
          <CheckCircle2 v-if="workflowStore.status === 'SUCCEEDED'" class="w-8 h-8 text-success" />
          <XCircle v-else class="w-8 h-8 text-danger" />
          <div>
            <h3 class="text-sm font-semibold text-surface-200">
              {{ workflowStore.status === 'SUCCEEDED' ? 'Workflow 执行完成' : 'Workflow 执行失败' }}
            </h3>
            <p class="text-sm text-surface-400 mt-1">
              {{ workflowStore.status === 'SUCCEEDED'
                ? '所有任务已成功完成，可在项目详情页查看结果。'
                : '部分任务失败，请检查错误信息。' }}
            </p>
          </div>
        </div>
      </div>

      <!-- 错误 -->
      <div v-if="workflowStore.error" class="card border-danger/30 mb-6">
        <p class="text-sm text-danger">{{ workflowStore.error }}</p>
      </div>
    </template>

    <!-- Toast -->
    <div class="fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none">
      <transition-group name="fade">
        <div v-for="toast in uiStore.toasts" :key="toast.id"
             :class="['px-4 py-2.5 rounded-lg text-sm shadow-lg pointer-events-auto border',
                      toast.type === 'success' ? 'bg-success/20 text-success border-success/30' :
                      toast.type === 'error' ? 'bg-danger/20 text-danger border-danger/30' :
                      toast.type === 'warning' ? 'bg-warning/20 text-warning border-warning/30' :
                      'bg-surface-800 text-surface-200 border-surface-600']">
          {{ toast.message }}
        </div>
      </transition-group>
    </div>
  </div>
</template>
