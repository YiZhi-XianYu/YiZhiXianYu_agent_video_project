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
import { WORKFLOW_POLL_INTERVAL_MS, RUN_STATUS_LABEL } from '@/shared/constants'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TaskGrid from '@/components/TaskGrid.vue'
import ShotGalleryView from '@/features/review/ShotGalleryView.vue'
import ShotRankingReview from '@/features/review/ShotRankingReview.vue'
import StoryEditor from '@/features/review/StoryEditor.vue'
import TimelinePreview from '@/features/review/TimelinePreview.vue'
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

/** Gate 1 视图模式：true = 画廊视图, false = 列表视图 */
const showGalleryView = ref(true)

const renderedVideo = computed(() => workflowStore.tasks
  .flatMap((task) => task.artifacts)
  .slice()
  .reverse()
  .find((artifact) => artifact.type === 'RENDERED_VIDEO') ?? null)

const renderedVideoDownloadUrl = computed(() => {
  if (!renderedVideo.value) return ''
  return `${renderedVideo.value.contentUrl}?download=true`
})

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
})

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

  try {
    if (gate.gateKey === 'gate_shot_ranking') {
      const payload = await loadArtifactJson('shot_ranking', 'SHOT_RANKING')
      const scores = mapShotScores(payload)
      reviewStore.setShotScores(await enrichShotScores(scores))
    } else if (gate.gateKey === 'gate_story_edit') {
      const payload = await loadArtifactJson('story_plan', 'STORY_PLAN')
      reviewStore.setStoryPlan(mapStoryPlan(payload))
    } else if (gate.gateKey === 'gate_timeline_preview') {
      const payload = await loadArtifactJson('timeline_compose', 'TIMELINE')
      reviewStore.setTimeline(mapTimeline(payload))
    } else if (gate.gateKey === 'gate_render_review') {
      const artifact = renderedVideo.value
      if (!artifact) throw new Error('缺少 RENDERED_VIDEO Artifact，无法打开当前审核页')
      reviewStore.setRenderedVideo(artifact.contentUrl)
    }
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '审核数据加载失败', 'error')
  }
}

function findArtifact(nodeKey: string, type: string): ArtifactSnapshot {
  const artifact = workflowStore.tasks
    .find((task) => task.nodeKey === nodeKey)
    ?.artifacts.find((item) => item.type === type)
  if (!artifact) throw new Error(`缺少 ${type} Artifact，无法打开当前审核页`)
  return artifact
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
  }))
}

function mapStoryPlan(payload: Record<string, any>): StoryPlan {
  return {
    workflowRunId: props.runId,
    beats: (payload.beats ?? []).map((beat: Record<string, any>) => ({
      role: beat.role as BeatRole,
      shotIds: (beat.shots ?? []).map((shot: Record<string, any>) => String(shot.shotId)),
      targetDurationMs: Number(beat.targetDurationMs ?? beat.actualDurationMs ?? 0),
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
    })),
    totalDurationMs: Number(payload.durationMs ?? 0),
    bgmName: null,
  }
}

/** 从视频检测任务中提取每个镜头的关键帧 URL 和代理视频地址，合并到 ShotScore */
async function enrichShotScores(scores: ShotScore[]): Promise<ShotScore[]> {
  try {
    /* 1. 加载 SHOT_LIST（含 keyframeArtifactId 和时间） */
    const shotListPayload = await loadArtifactJson('video_shot_detect', 'SHOT_LIST')
    const shotMetaMap = new Map<string, { keyframeArtifactId: string; startMs: number; endMs: number }>(
      (shotListPayload.shots ?? []).map((s: Record<string, any>) => [
        String(s.shotId),
        {
          keyframeArtifactId: String(s.keyframeArtifactId),
          startMs: Number(s.startMs),
          endMs: Number(s.endMs),
        },
      ])
    )

    /* 2. 获取代理视频 URL */
    const proxyTask = workflowStore.tasks.find((t) => t.nodeKey === 'video_proxy_generate')
    const proxyUrl = proxyTask?.artifacts.find((a) => a.type === 'VIDEO_PROXY')?.contentUrl ?? null

    /* 3. 从 video_shot_detect 任务的 artifacts 中构建 externalArtifactId → contentUrl 映射 */
    const shotDetectTask = workflowStore.tasks.find((t) => t.nodeKey === 'video_shot_detect')
    const artifactUrlMap = new Map<string, string>(
      (shotDetectTask?.artifacts ?? []).map((a) => [a.externalArtifactId, a.contentUrl])
    )

    /* 4. 合并到 scores */
    return scores.map((shot) => {
      const meta = shotMetaMap.get(shot.shotId)
      return {
        ...shot,
        keyframeUrl: meta ? artifactUrlMap.get(meta.keyframeArtifactId) : undefined,
        proxyVideoUrl: proxyUrl ?? undefined,
        startMs: meta?.startMs,
        endMs: meta?.endMs,
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
        @confirm="handleContinue"
      />
      <TimelinePreview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_timeline_preview'"
        @confirm="handleContinue"
      />
      <FinalReview
        v-if="workflowStore.isPaused && workflowStore.currentGate?.gateKey === 'gate_render_review'"
        @confirm="handleRenderConfirm"
      />

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
