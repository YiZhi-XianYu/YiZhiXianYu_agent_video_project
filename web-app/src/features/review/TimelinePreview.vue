<script setup lang="ts">
/**
 * Gate 3：时间线预览
 *
 * 可视化 Timeline 轨道，展示片段顺序、转场效果和成片时长。
 */
import { computed, ref } from 'vue'
import { Clock, CheckCircle2, Trash2, GripVertical, Play } from 'lucide-vue-next'
import { useReviewStore } from '@/stores/review'
import type { Timeline, TimelineClip } from '@/shared/types'
import { startCustomTimelineRender } from '@/api/timeline'
import { useUiStore } from '@/stores/ui'
import ShotPreviewPanel from '@/components/ShotPreviewPanel.vue'

const props = defineProps<{
  runId: string
  proxyUrls: Record<string, string>
}>()
const emit = defineEmits<{ confirm: [] }>()
const review = useReviewStore()
const ui = useUiStore()
const saving = ref(false)
const draggingIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)
const previewClip = ref<TimelineClip | null>(null)
const previewAnchorEl = ref<HTMLElement | null>(null)

const clips = computed<TimelineClip[]>(() => review.timeline?.clips ?? [])
const totalDuration = computed(() => review.timeline?.totalDurationMs ?? 0)

function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}

const transLabel: Record<string, string> = { CUT: '硬切', FADE: '淡入淡出', CROSS_DISSOLVE: '交叉溶解' }

function setTransition(index: number, event: Event): void {
  const type = (event.target as HTMLSelectElement).value as TimelineClip['transition']
  review.updateTimelineClip(index, {
    transition: type,
    transitionDurationMs: type === 'CUT' ? 0 : type === 'CROSS_DISSOLVE' ? 500 : 300,
  })
}

function startDrag(index: number, event: DragEvent): void {
  draggingIndex.value = index
  dragOverIndex.value = index
  previewClip.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(index))
  }
}

function dragOver(index: number, event: DragEvent): void {
  event.preventDefault()
  dragOverIndex.value = index
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

function dropClip(index: number, event: DragEvent): void {
  event.preventDefault()
  const fromIndex = draggingIndex.value ?? Number(event.dataTransfer?.getData('text/plain'))
  if (Number.isInteger(fromIndex)) review.reorderTimelineClip(fromIndex, index)
  endDrag()
}

function endDrag(): void {
  draggingIndex.value = null
  dragOverIndex.value = null
}

function openPreview(clip: TimelineClip, event: MouseEvent): void {
  previewClip.value = clip
  previewAnchorEl.value = event.currentTarget as HTMLElement
}

function previewUrl(clip: TimelineClip | null): string | null {
  if (!clip?.sourceProxyArtifactId) return null
  return props.proxyUrls[clip.sourceProxyArtifactId] ?? null
}

async function confirmTimeline(): Promise<void> {
  if (!review.timeline) return
  saving.value = true
  try {
    await startCustomTimelineRender(props.runId, toPayload(review.timeline))
    emit('confirm')
  } catch (error) {
    ui.showToast(error instanceof Error ? error.message : '应用时间线失败', 'error')
  } finally {
    saving.value = false
  }
}

function toPayload(timeline: Timeline): unknown {
  return {
    timelineId: timeline.timelineId ?? `tl_manual_${Date.now()}`,
    version: 1,
    schemaVersion: '1.1',
    sourceHighlightArtifactId: 'manual-timeline-edit',
    canvas: timeline.canvas ?? { width: 1920, height: 1080, fps: 30 },
    durationMs: timeline.totalDurationMs,
    tracks: [{
      type: 'VIDEO',
      clips: timeline.clips.map((clip, index) => ({
        clipId: clip.clipId ?? `clip_${clip.shotId}`,
        shotId: clip.shotId,
        assetId: clip.assetId ?? '',
        sourceProxyArtifactId: clip.sourceProxyArtifactId ?? '',
        sourceInMs: clip.sourceInMs,
        sourceOutMs: clip.sourceOutMs,
        sourceShotStartMs: clip.sourceShotStartMs ?? clip.sourceInMs,
        sourceShotEndMs: clip.sourceShotEndMs ?? clip.sourceOutMs,
        timelineInMs: clip.timelineInMs ?? 0,
        timelineOutMs: clip.timelineOutMs ?? clip.durationMs,
        playbackRate: 1.0,
        transitionIn: {
          type: index === 0 && clip.transition === 'CROSS_DISSOLVE' ? 'CUT' : clip.transition,
          durationMs: index === 0 && clip.transition === 'CROSS_DISSOLVE' ? 0 : clip.transitionDurationMs,
        },
        selectionRank: Math.max(1, clip.selectionRank ?? index + 1),
        storyRole: clip.storyRole ?? 'JOURNEY',
        selectionReasons: clip.selectionReasons ?? ['MANUAL_TIMELINE_EDIT'],
      })),
    }],
    validation: { valid: true, errors: [] },
  }
}
</script>

<template>
  <div class="card ring-1 ring-warning/30">
    <div class="flex items-start justify-between mb-4">
      <div>
        <h3 class="text-lg font-semibold text-surface-100 flex items-center gap-2">
          <Clock class="w-5 h-5 text-warning" />
          时间线预览
        </h3>
        <p class="text-sm text-surface-400 mt-1">
          {{ clips.length }} 个片段 · 总时长 {{ formatMs(totalDuration) }}
        </p>
        <p class="mt-1 text-xs text-surface-500">
          拖动手柄可调整顺序；点击“预览”可播放当前切片并听取原声。
        </p>
      </div>
      <button class="btn-primary shrink-0" :disabled="saving" @click="confirmTimeline">
        <CheckCircle2 class="w-4 h-4" />
        应用时间线并继续执行
      </button>
    </div>

    <!-- 无数据 -->
    <div v-if="clips.length === 0" class="py-10 text-center text-surface-500 text-sm">
      加载时间线数据...
    </div>

    <!-- 时间线轨道 -->
    <div v-else class="relative">
      <div class="flex items-center gap-1 overflow-x-auto pb-3">
        <template v-for="(clip, index) in clips" :key="clip.clipId ?? (clip.shotId + clip.sourceInMs)">
          <div
            v-if="index > 0"
            class="flex w-28 shrink-0 flex-col items-center justify-center gap-1 rounded-lg border border-warning/30 bg-warning/10 px-2 py-2"
          >
            <span class="text-[10px] text-warning/80">片段间转场</span>
            <select
              :value="clip.transition"
              class="w-full rounded border border-warning/40 bg-surface-900 px-1 py-1 text-xs text-surface-200"
              title="设置进入当前片段前的转场"
              @change="setTransition(index, $event)"
            >
              <option value="CUT">硬切</option>
              <option value="FADE">淡入</option>
              <option value="CROSS_DISSOLVE">交叉溶解</option>
            </select>
            <span class="text-[10px] text-surface-500">{{ transLabel[clip.transition] }}</span>
          </div>
          <div
            :class="[
              'shrink-0 flex flex-col items-center rounded-lg border border-transparent p-1 transition',
              dragOverIndex === index && draggingIndex !== index ? 'border-accent bg-accent/10' : '',
              draggingIndex === index ? 'opacity-40' : '',
            ]"
            :style="{ width: Math.max(210, (clip.durationMs / totalDuration) * 900) + 'px' }"
            @dragover="dragOver(index, $event)"
            @drop="dropClip(index, $event)"
          >
            <div class="flex w-full items-center gap-1">
              <button
                class="flex h-8 w-6 shrink-0 cursor-grab items-center justify-center rounded border border-surface-600 bg-surface-800 text-surface-400 active:cursor-grabbing"
                draggable="true"
                title="拖动调整切片顺序"
                @dragstart="startDrag(index, $event)"
                @dragend="endDrag"
              >
                <GripVertical class="h-3.5 w-3.5" />
              </button>
              <button
                class="flex h-8 min-w-0 flex-1 items-center justify-center gap-1 rounded border border-accent/40 bg-accent/30 px-1 font-mono text-xs text-surface-200 hover:bg-accent/40"
                title="预览这个切片"
                @click="openPreview(clip, $event)"
              >
                <Play class="h-3 w-3 shrink-0" />
                <span class="truncate">{{ clip.shotId.slice(0, 6) }}</span>
              </button>
            </div>
            <span class="text-xs text-surface-500 mt-1">{{ formatMs(clip.durationMs) }}</span>
            <div class="mt-1 flex items-center gap-2">
              <select
                v-if="index === 0"
                :value="clip.transition"
                class="w-24 rounded border border-surface-600 bg-surface-800 text-[10px] text-surface-300"
                title="设置片头效果"
                @change="setTransition(index, $event)"
              >
                <option value="CUT">片头：直接开始</option>
                <option value="FADE">片头：淡入</option>
              </select>
              <button class="flex items-center gap-1 rounded px-2 py-1 text-xs text-danger/80 hover:bg-danger/10 hover:text-danger" :disabled="clips.length <= 1" title="移除切片" @click="review.removeTimelineClip(index)">
                <Trash2 class="h-3.5 w-3.5" />
                移除
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <ShotPreviewPanel
      :shot-id="previewClip?.shotId ?? ''"
      :keyframe-url="null"
      :video-url="previewUrl(previewClip)"
      :start-ms="previewClip?.sourceInMs ?? 0"
      :end-ms="previewClip?.sourceOutMs ?? 0"
      :anchor-el="previewAnchorEl"
      :visible="previewClip !== null"
      @close="previewClip = null"
    />
  </div>
</template>
