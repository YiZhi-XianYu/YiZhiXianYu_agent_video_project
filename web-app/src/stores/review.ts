/**
 * 人在回路审核状态管理
 *
 * 管理当前 Gate 的审核数据和用户编辑状态。
 * 每个 Gate 有独立的编辑数据，切换 Gate 时自动重置。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { ShotScore, StoryPlan, Timeline, SubtitleStyle } from '@/shared/types'
import type { GateInfo } from '@/api/types'

export const useReviewStore = defineStore('review', () => {
  // ===================== State =====================

  const currentGate = ref<GateInfo | null>(null)

  // Gate 1：镜头排序
  const shotScores = ref<ShotScore[]>([])
  const excludedShotIds = ref<Set<string>>(new Set())
  const forcedShotIds = ref<Set<string>>(new Set())

  // Gate 2：故事编辑
  const storyPlan = ref<StoryPlan | null>(null)
  const lockedShotIds = ref<Set<string>>(new Set())

  // Gate 3：时间线
  const timeline = ref<Timeline | null>(null)

  // Gate 4：成片预览
  const renderedVideoUrl = ref<string | null>(null)
  const subtitleStyle = ref<SubtitleStyle>({
    fontSize: 24,
    fontColor: '#ffffff',
    position: 'bottom',
    outlineColor: '#000000',
  })

  // Gate 5：最终下载
  const finalVideoUrl = ref<string | null>(null)

  const dirty = ref(false)

  // ===================== Actions =====================

  function activateGate(gate: GateInfo | null): void {
    currentGate.value = gate
    dirty.value = false
    if (!gate) resetAll()
  }

  function resetAll(): void {
    shotScores.value = []
    excludedShotIds.value = new Set()
    forcedShotIds.value = new Set()
    storyPlan.value = null
    lockedShotIds.value = new Set()
    timeline.value = null
    renderedVideoUrl.value = null
    finalVideoUrl.value = null
    dirty.value = false
  }

  function setShotScores(scores: ShotScore[]): void { shotScores.value = scores }
  function setStoryPlan(plan: StoryPlan): void { storyPlan.value = plan }
  function setTimeline(tl: Timeline): void { timeline.value = tl }
  function setRenderedVideo(url: string): void { renderedVideoUrl.value = url }
  function setFinalVideo(url: string): void { finalVideoUrl.value = url }

  function toggleForced(shotId: string): void {
    const s = new Set(forcedShotIds.value)
    if (s.has(shotId)) { s.delete(shotId) } else { s.add(shotId) }
    forcedShotIds.value = s
    dirty.value = true
  }

  function toggleExcluded(shotId: string): void {
    const s = new Set(excludedShotIds.value)
    if (s.has(shotId)) { s.delete(shotId) } else { s.add(shotId) }
    excludedShotIds.value = s
    dirty.value = true
  }

  function toggleLockShot(shotId: string): void {
    const s = new Set(lockedShotIds.value)
    if (s.has(shotId)) { s.delete(shotId) } else { s.add(shotId) }
    lockedShotIds.value = s
    dirty.value = true
  }

  function updateSubtitleStyle(style: Partial<SubtitleStyle>): void {
    subtitleStyle.value = { ...subtitleStyle.value, ...style }
    dirty.value = true
  }

  return {
    currentGate, shotScores, excludedShotIds, forcedShotIds,
    storyPlan, lockedShotIds, timeline,
    renderedVideoUrl, subtitleStyle, finalVideoUrl, dirty,
    activateGate, resetAll,
    setShotScores, setStoryPlan, setTimeline,
    setRenderedVideo, setFinalVideo,
    toggleForced, toggleExcluded, toggleLockShot,
    updateSubtitleStyle,
  }
})
