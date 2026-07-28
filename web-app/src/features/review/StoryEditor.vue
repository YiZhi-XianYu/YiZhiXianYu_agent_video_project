<script setup lang="ts">
/**
 * Gate 2：故事安排编辑器
 *
 * 展示五段式 Story Plan，每段可替换/排序/锁定/增删 shot。
 * 支持版本保存（通过 Custom Story Plan API）。
 * 确认后 emit 通知父组件继续 Workflow。
 */
import { computed, onMounted, ref } from 'vue'
import {
  Lock, LockOpen, ArrowUp, ArrowDown, Trash2, Plus, Save, CheckCircle2,
  X, Play, ImageOff, GitBranch, Loader2, Download,
} from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'
import { BEAT_LABEL_MAP } from '@/shared/constants'
import type { BeatRole, ShotScore, StoryBeat, StoryPlan } from '@/shared/types'
import type { VersionListItem } from '@/api/types'
import ShotPreviewPanel from '@/components/ShotPreviewPanel.vue'
import { savePlan, applyPlan, getVersion, listVersions } from '@/api/plans'
import { useUiStore } from '@/stores/ui'

const emit = defineEmits<{
  confirm: []
}>()

const props = defineProps<{ runId: string }>()

const review = useReviewStore()
const ui = useUiStore()
const saving = ref(false)
const versionName = ref('')
const candidateRole = ref<StoryBeat['role'] | null>(null)
const selectedCandidateId = ref<string | null>(null)
const versions = ref<VersionListItem[]>([])
const selectedVersionId = ref('')
const loadingVersions = ref(false)
const loadingVersion = ref(false)

/** 当前预览的镜头数据（null = 关闭） */
const previewShot = ref<ShotScore | null>(null)
/** 预览锚点 DOM 元素 */
const previewAnchorEl = ref<HTMLElement | null>(null)

/** 点击 shot ID：从 reviewStore 查找对应 ShotScore 并打开预览 */
function handleShotPreview(shotId: string, event: MouseEvent): void {
  if (previewShot.value?.shotId === shotId) {
    previewShot.value = null
    previewAnchorEl.value = null
    return
  }
  const found = review.shotScores.find((s) => s.shotId === shotId)
  if (found) {
    previewShot.value = found
    previewAnchorEl.value = event.currentTarget as HTMLElement
  }
}

// ===================== Computed =====================

const beats = computed<StoryBeat[]>(() => review.storyPlan?.beats ?? [])

const availableCandidates = computed<ShotScore[]>(() => {
  const used = new Set(beats.value.flatMap((beat) => beat.shotIds))
  return review.shotScores
    .filter((shot) => shot.selected
      && !review.excludedShotIds.has(shot.shotId)
      && !used.has(shot.shotId)
      && Boolean(shot.sourceAssetId)
      && Boolean(shot.sourceProxyArtifactId)
      && shot.startMs != null
      && shot.endMs != null
      && shot.endMs > shot.startMs)
    .sort((a, b) => (b.rankScore ?? 0) - (a.rankScore ?? 0))
})

const candidateBeatLabel = computed(() => candidateRole.value
  ? (BEAT_LABEL_MAP[candidateRole.value] ?? candidateRole.value)
  : '')

/** 计算成片总时长 */
const totalDuration = computed(() => {
  if (!review.storyPlan) return 0
  return review.storyPlan.totalDurationMs
})

/** 格式化毫秒为 mm:ss */
function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}

function defaultVersionName(): string {
  return `故事方案 ${new Date().toLocaleString()}`
}

function mapVersionPlan(payload: unknown): StoryPlan {
  const data = (payload && typeof payload === 'object' ? payload : {}) as Record<string, any>
  const mappedBeats = (Array.isArray(data.beats) ? data.beats : []).map((beat: Record<string, any>) => ({
    role: beat.role as BeatRole,
    shotIds: (Array.isArray(beat.shots) ? beat.shots : []).map((shot: Record<string, any>) => String(shot.shotId)),
    shots: (Array.isArray(beat.shots) ? beat.shots : []).map((shot: Record<string, any>) => ({
      ...shot,
      shotId: String(shot.shotId),
      sourceAssetId: String(shot.sourceAssetId ?? ''),
      sourceProxyArtifactId: String(shot.sourceProxyArtifactId ?? ''),
      startMs: Number(shot.startMs ?? 0),
      endMs: Number(shot.endMs ?? 0),
      sourceInMs: Number(shot.sourceInMs ?? shot.startMs ?? 0),
      sourceOutMs: Number(shot.sourceOutMs ?? shot.endMs ?? 0),
      selectedDurationMs: Number(shot.selectedDurationMs ?? 0),
      storyRole: beat.role as BeatRole,
    })),
    targetDurationMs: Number(beat.targetDurationMs ?? 0),
  }))
  const calculatedDuration = mappedBeats.reduce((total, beat) => total + (beat.shots ?? []).reduce(
    (sum, shot) => sum + Math.max(0, shot.selectedDurationMs || shot.sourceOutMs - shot.sourceInMs), 0,
  ), 0)
  return {
    workflowRunId: props.runId,
    beats: mappedBeats,
    totalDurationMs: Number(data.targetDurationMs ?? calculatedDuration),
  }
}

async function loadVersions(): Promise<void> {
  loadingVersions.value = true
  try {
    versions.value = await listVersions(props.runId)
  } catch (error) {
    ui.showToast(error instanceof Error ? error.message : '加载故事方案版本失败', 'error')
  } finally {
    loadingVersions.value = false
  }
}

async function loadSelectedVersion(): Promise<void> {
  if (!selectedVersionId.value) return
  loadingVersion.value = true
  try {
    const version = await getVersion(props.runId, selectedVersionId.value)
    review.setStoryPlan(mapVersionPlan(version.planData))
    review.markDirty()
    versionName.value = version.versionName ? `${version.versionName} - 编辑副本` : '历史版本编辑副本'
    ui.showToast(`已载入“${version.versionName || '未命名版本'}”，保存后才会成为新的方案版本`, 'success')
  } catch (error) {
    ui.showToast(error instanceof Error ? error.message : '载入故事方案版本失败', 'error')
  } finally {
    loadingVersion.value = false
  }
}

function openCandidateSelector(role: StoryBeat['role']): void {
  candidateRole.value = role
  selectedCandidateId.value = null
}

function closeCandidateSelector(): void {
  candidateRole.value = null
  selectedCandidateId.value = null
}

function addSelectedCandidate(): void {
  if (!candidateRole.value || !selectedCandidateId.value) return
  if (!review.addStoryShot(candidateRole.value, selectedCandidateId.value)) {
    ui.showToast('该镜头已不可添加，请刷新候选列表后重试', 'warning')
    return
  }
  ui.showToast(`镜头已添加到“${candidateBeatLabel.value}”`, 'success')
  closeCandidateSelector()
}

function previewCandidate(shot: ShotScore, event: MouseEvent): void {
  previewShot.value = shot
  previewAnchorEl.value = event.currentTarget as HTMLElement
}

function planPayload(): Record<string, unknown> {
  return {
    schemaVersion: '1.0',
    template: 'MANUAL_EDIT',
    targetDurationMs: totalDuration.value,
    maxShots: beats.value.reduce((sum, beat) => sum + beat.shotIds.length, 0),
    beats: beats.value.map((beat) => ({
      role: beat.role,
      targetDurationMs: beat.targetDurationMs,
      shots: (beat.shots ?? beat.shotIds.map((shotId) => {
        const score = review.shotScores.find((item) => item.shotId === shotId)
        return {
          shotId,
          sourceAssetId: score?.sourceAssetId ?? '',
          sourceProxyArtifactId: score?.sourceProxyArtifactId ?? '',
          startMs: score?.startMs ?? 0,
          endMs: score?.endMs ?? 0,
          sourceInMs: score?.startMs ?? 0,
          sourceOutMs: score?.endMs ?? 0,
          selectedDurationMs: Math.max(600, (score?.endMs ?? 0) - (score?.startMs ?? 0)),
          rank: Math.max(1, Math.round(score?.rankScore ?? 0)),
          storyRole: beat.role,
        }
      })).map((shot) => ({ ...shot, storyRole: beat.role })),
    })),
  }
}

async function saveVersion(): Promise<void> {
  saving.value = true
  try {
    const name = versionName.value.trim() || defaultVersionName()
    const saved = await savePlan(props.runId, planPayload(), name)
    review.markSaved()
    versionName.value = ''
    await loadVersions()
    selectedVersionId.value = saved.id
    ui.showToast(`故事方案版本“${name}”已保存`, 'success')
  } catch (error) {
    ui.showToast(error instanceof Error ? error.message : '保存故事安排失败', 'error')
  } finally {
    saving.value = false
  }
}

async function confirmPlan(): Promise<void> {
  saving.value = true
  try {
    const name = versionName.value.trim() || defaultVersionName()
    await savePlan(props.runId, planPayload(), name)
    review.markSaved()
    await applyPlan(props.runId)
    emit('confirm')
  } catch (error) {
    ui.showToast(error instanceof Error ? error.message : '应用故事安排失败', 'error')
  } finally {
    saving.value = false
  }
}

onMounted(loadVersions)
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex flex-col gap-4 mb-4 xl:flex-row xl:items-start xl:justify-between">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Lock class="w-5 h-5 text-warning" />
          故事安排编辑
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          五段式故事结构：检查各段的镜头分配。可替换、排序、锁定、添加或删除镜头。
        </p>
        <p class="text-xs text-surface-500 mt-1">
          总时长：{{ formatMs(totalDuration) }}
          <span v-if="review.dirty" class="ml-2 text-warning">有未保存修改</span>
          <span v-else class="ml-2 text-success">当前方案已保存</span>
        </p>
        <p class="mt-2 max-w-3xl text-xs leading-5 text-surface-500">
          保存版本只记录当前五段故事结构、镜头分配与裁剪信息，不会复制素材、任务、时间线或成片。
          应用方案后，系统会在当前工作流中重新执行高光选择、时间线、配乐、字幕和渲染任务。
        </p>
      </div>
      <div class="flex w-full flex-col gap-2 shrink-0 xl:w-[440px]">
        <div class="grid gap-2 sm:grid-cols-[1fr_auto]">
          <div class="relative">
            <GitBranch class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-500" />
            <select
              v-model="selectedVersionId"
              class="select-field h-10 bg-surface-800/90 pl-9"
              :disabled="loadingVersions || loadingVersion || versions.length === 0"
              title="选择一个已保存的故事方案版本"
            >
              <option value="">{{ loadingVersions ? '正在加载版本...' : versions.length ? '选择已保存版本' : '暂无已保存版本' }}</option>
              <option v-for="item in versions" :key="item.id" :value="item.id">
                {{ item.versionName || '未命名版本' }} · {{ new Date(item.createdAt).toLocaleString() }}
              </option>
            </select>
          </div>
          <button
            class="btn-secondary h-10"
            :disabled="!selectedVersionId || loadingVersion"
            title="把所选历史版本载入当前编辑器，不会立即创建工作流"
            @click="loadSelectedVersion"
          >
            <Loader2 v-if="loadingVersion" class="h-4 w-4 animate-spin" />
            <Download v-else class="h-4 w-4" />
            载入版本
          </button>
        </div>
        <input
          v-model="versionName"
          class="input-field h-10 bg-surface-800/90"
          maxlength="80"
          placeholder="新版本名称（可选，例如：节奏加快版）"
          :disabled="saving"
        />
        <p class="text-[11px] text-surface-500">
          载入历史版本只替换当前编辑内容；保存后生成新的故事方案版本，应用后才会继续当前工作流。
        </p>
        <div class="flex flex-wrap gap-2 xl:justify-end">
        <button class="btn-secondary" title="保存当前故事方案快照，不会启动后续任务" :disabled="saving" @click="saveVersion">
          <Save class="w-4 h-4" />
          保存故事方案版本
        </button>
        <button class="btn-primary" title="保存当前方案并在当前工作流中继续执行后续任务" :disabled="saving" @click="confirmPlan">
          <CheckCircle2 class="w-4 h-4" />
          应用方案并继续执行
        </button>
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="beats.length === 0" class="flex items-center justify-center py-10 text-surface-500 text-sm">
      加载故事安排数据...
    </div>

    <!-- 五段式 Beat 列表 -->
    <div v-else class="grid gap-4">
      <div
        v-for="beat in beats"
        :key="beat.role"
        class="rounded-lg bg-surface-700/30 border border-surface-600/50 overflow-hidden"
      >
        <!-- Beat 头 -->
        <div class="flex items-center gap-2 px-4 py-2.5 bg-surface-700/50">
          <span class="text-xs font-semibold text-accent uppercase tracking-wider">
            {{ beat.role }}
          </span>
          <span class="text-sm font-medium text-surface-200">
            {{ BEAT_LABEL_MAP[beat.role] ?? beat.role }}
          </span>
          <span class="text-xs text-surface-500 ml-auto">
            {{ formatMs(beat.targetDurationMs) }}
          </span>
        </div>

        <!-- Shot 列表 -->
        <div class="px-3 py-2 space-y-1">
          <div
            v-for="(shotId, idx) in beat.shotIds"
            :key="shotId"
            :class="[
              'flex items-center gap-2 px-2 py-2 rounded text-xs transition-colors',
              review.lockedShotIds.has(shotId)
                ? 'bg-accent/5 border border-accent/20'
                : 'hover:bg-surface-600/30',
            ]"
          >
            <!-- 序号 -->
            <span class="w-5 text-surface-500 text-right shrink-0">{{ idx + 1 }}</span>

            <!-- Shot ID -->
            <span class="flex-1 text-surface-200 font-mono truncate cursor-pointer hover:text-accent hover:underline transition-colors" @click="handleShotPreview(shotId, $event)">
              {{ shotId.slice(0, 16) }}...
            </span>

            <!-- 锁定按钮 -->
            <button
              :class="review.lockedShotIds.has(shotId) ? 'text-accent' : 'text-surface-500 hover:text-surface-300'"
              title="锁定/解锁"
              @click="review.toggleLockShot(shotId)"
            >
              <Lock v-if="review.lockedShotIds.has(shotId)" class="w-3.5 h-3.5" />
              <LockOpen v-else class="w-3.5 h-3.5" />
            </button>

            <!-- 排序按钮 -->
            <button
              class="text-surface-500 hover:text-surface-300 p-0.5 disabled:opacity-30"
              :disabled="!review.canMoveStoryShot(beat.role, idx, -1)"
              title="上移"
              @click.stop="review.moveStoryShot(beat.role, idx, -1)"
            >
              <ArrowUp class="w-3.5 h-3.5" />
            </button>
            <button
              class="text-surface-500 hover:text-surface-300 p-0.5 disabled:opacity-30"
              :disabled="!review.canMoveStoryShot(beat.role, idx, 1)"
              title="下移"
              @click.stop="review.moveStoryShot(beat.role, idx, 1)"
            >
              <ArrowDown class="w-3.5 h-3.5" />
            </button>

            <!-- 删除 -->
            <button
              class="text-surface-500 hover:text-danger p-0.5 disabled:opacity-30"
              :disabled="beat.shotIds.length <= 1 || review.lockedShotIds.has(shotId)"
              title="移除"
              @click.stop="review.removeStoryShot(beat.role, idx)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>

          <!-- 空 Beat -->
          <div
            v-if="beat.shotIds.length === 0"
            class="px-2 py-3 text-xs text-surface-500 text-center"
          >
            此段暂无镜头
          </div>

          <!-- 添加镜头按钮 -->
          <button
            class="w-full flex items-center justify-center gap-1 py-1.5 rounded text-xs
                   text-surface-500 hover:text-surface-300 hover:bg-surface-600/20 transition-colors"
            @click="openCandidateSelector(beat.role)"
          >
            <Plus class="w-3.5 h-3.5" />
            从候选镜头中添加
          </button>
        </div>
      </div>
    </div>
  </div>

    <Teleport to="body">
      <div v-if="candidateRole" class="modal-backdrop z-[90]" @click.self="closeCandidateSelector">
        <section class="w-full max-w-5xl overflow-hidden rounded-2xl border border-surface-700 bg-surface-900 shadow-2xl">
          <header class="flex items-start justify-between gap-4 border-b border-surface-700 px-5 py-4">
            <div>
              <h4 class="text-base font-semibold text-surface-100">为“{{ candidateBeatLabel }}”选择镜头</h4>
              <p class="mt-1 text-xs text-surface-500">
                仅显示已入选、未排除、尚未使用且素材来源完整的镜头。选择后再确认添加。
              </p>
            </div>
            <button class="rounded-lg p-2 text-surface-400 hover:bg-white/5 hover:text-surface-100" title="关闭" @click="closeCandidateSelector">
              <X class="h-5 w-5" />
            </button>
          </header>

          <div v-if="availableCandidates.length" class="grid max-h-[65vh] gap-3 overflow-y-auto p-5 sm:grid-cols-2 lg:grid-cols-3">
            <article
              v-for="shot in availableCandidates"
              :key="shot.shotId"
              :class="[
                'cursor-pointer overflow-hidden rounded-xl border bg-surface-800 transition',
                selectedCandidateId === shot.shotId
                  ? 'border-accent ring-2 ring-accent/25'
                  : 'border-surface-700 hover:border-surface-500',
              ]"
              @click="selectedCandidateId = shot.shotId"
            >
              <div class="relative flex aspect-video items-center justify-center bg-black/50">
                <img v-if="shot.keyframeUrl" :src="shot.keyframeUrl" :alt="shot.shotId" class="h-full w-full object-cover" />
                <ImageOff v-else class="h-8 w-8 text-surface-600" />
                <button
                  class="absolute bottom-2 right-2 flex items-center gap-1 rounded-lg bg-black/70 px-2 py-1 text-[11px] text-white hover:bg-black"
                  title="预览镜头片段"
                  @click.stop="previewCandidate(shot, $event)"
                >
                  <Play class="h-3 w-3" />
                  预览
                </button>
              </div>
              <div class="space-y-2 p-3">
                <div class="flex items-center justify-between gap-2">
                  <span class="truncate font-mono text-xs text-surface-200" :title="shot.shotId">{{ shot.shotId }}</span>
                  <span class="rounded-md bg-accent/10 px-1.5 py-0.5 text-xs font-semibold text-accent">
                    {{ (shot.rankScore ?? 0).toFixed(0) }} 分
                  </span>
                </div>
                <div class="flex justify-between gap-3 text-[11px] text-surface-500">
                  <span>时长 {{ formatMs((shot.endMs ?? 0) - (shot.startMs ?? 0)) }}</span>
                  <span class="truncate" :title="shot.sourceAssetId">素材 {{ shot.sourceAssetId?.slice(0, 10) }}...</span>
                </div>
              </div>
            </article>
          </div>

          <div v-else class="flex min-h-56 flex-col items-center justify-center px-6 text-center text-surface-500">
            <ImageOff class="mb-3 h-9 w-9" />
            <p class="text-sm text-surface-300">暂无可添加的候选镜头</p>
            <p class="mt-1 text-xs">镜头可能已被使用、已排除，或缺少素材来源信息。</p>
          </div>

          <footer class="flex items-center justify-between gap-4 border-t border-surface-700 px-5 py-4">
            <span class="text-xs text-surface-500">
              {{ selectedCandidateId ? '已选择 1 个镜头' : '请选择一个候选镜头' }}
            </span>
            <div class="flex gap-2">
              <button class="btn-secondary" @click="closeCandidateSelector">取消</button>
              <button class="btn-primary" :disabled="!selectedCandidateId" @click="addSelectedCandidate">
                <Plus class="h-4 w-4" />
                添加到此段
              </button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>

    <!-- 镜头预览浮层 -->
    <ShotPreviewPanel
      :shot-id="previewShot?.shotId ?? ''"
      :keyframe-url="previewShot?.keyframeUrl ?? null"
      :video-url="previewShot?.proxyVideoUrl ?? null"
      :start-ms="previewShot?.startMs ?? 0"
      :end-ms="previewShot?.endMs ?? 0"
      :anchor-el="previewAnchorEl"
      :visible="previewShot !== null"
      @close="previewShot = null; previewAnchorEl = null"
    />

</template>
