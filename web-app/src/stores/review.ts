/**
 * 人在回路审核状态管理
 *
 * 管理当前 Gate 的审核数据和用户编辑状态。
 * 每个 Gate 有独立的编辑数据，切换 Gate 时自动重置。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { BeatRole, ShotScore, StoryPlan, Timeline } from '@/shared/types'
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
    dirty.value = false
  }

  function setShotScores(scores: ShotScore[]): void { shotScores.value = scores }
  function setExcludedShotIds(ids: string[]): void { excludedShotIds.value = new Set(ids) }
  function setForcedShotIds(ids: string[]): void { forcedShotIds.value = new Set(ids) }
  function setLockedShotIds(ids: string[]): void { lockedShotIds.value = new Set(ids) }
  function setStoryPlan(plan: StoryPlan): void { storyPlan.value = plan }
  function setTimeline(tl: Timeline): void { timeline.value = tl }
  function setRenderedVideo(url: string): void { renderedVideoUrl.value = url }

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
  }

  function markSaved(): void { dirty.value = false }
  function markDirty(): void { dirty.value = true }

  function updateStoryPlan(mutator: (plan: StoryPlan) => StoryPlan): void {
    if (!storyPlan.value) return
    const next = mutator(JSON.parse(JSON.stringify(storyPlan.value)) as StoryPlan)
    next.beats.forEach((beat) => {
      beat.shotIds = [...new Set(beat.shotIds.filter(Boolean))]
      if (beat.shots) {
        const shotsById = new Map(beat.shots.map((shot) => [shot.shotId, shot]))
        beat.shots = beat.shotIds.flatMap((shotId) => {
          const shot = shotsById.get(shotId)
          return shot ? [shot] : []
        })
        const actualDurationMs = beat.shots.reduce(
          (sum, shot) => sum + Math.max(0, shot.sourceOutMs - shot.sourceInMs), 0,
        )
        beat.actualDurationMs = actualDurationMs
        beat.targetDurationMs = actualDurationMs
      }
    })
    next.totalDurationMs = next.beats.reduce(
      (total, beat) => total + (beat.actualDurationMs ?? 0), 0,
    )
    storyPlan.value = next
    dirty.value = true
  }

  function moveStoryShot(role: BeatRole, index: number, delta: -1 | 1): void {
    updateStoryPlan((plan) => {
      const beat = plan.beats.find((item) => item.role === role)
      if (!beat || index < 0 || index >= beat.shotIds.length) return plan
      const target = index + delta
      if (target < 0 || target >= beat.shotIds.length) return plan
      const currentShotId = beat.shotIds[index]
      const targetShotId = beat.shotIds[target]
      if (currentShotId === undefined || targetShotId === undefined) return plan
      if (lockedShotIds.value.has(currentShotId) || lockedShotIds.value.has(targetShotId)) return plan
      beat.shotIds[index] = targetShotId
      beat.shotIds[target] = currentShotId
      if (beat.shots) {
        const currentShot = beat.shots[index]
        const targetShot = beat.shots[target]
        if (currentShot === undefined || targetShot === undefined) return plan
        beat.shots[index] = targetShot
        beat.shots[target] = currentShot
      }
      return plan
    })
  }

  function canMoveStoryShot(role: BeatRole, index: number, delta: -1 | 1): boolean {
    const beat = storyPlan.value?.beats.find((item) => item.role === role)
    const target = index + delta
    if (!beat || index < 0 || target < 0 || target >= beat.shotIds.length) return false
    const currentShotId = beat.shotIds[index]
    const targetShotId = beat.shotIds[target]
    return Boolean(currentShotId && targetShotId
      && !lockedShotIds.value.has(currentShotId)
      && !lockedShotIds.value.has(targetShotId))
  }

  function removeStoryShot(role: BeatRole, index: number): void {
    updateStoryPlan((plan) => {
      const beat = plan.beats.find((item) => item.role === role)
      const shotId = beat?.shotIds[index]
      const totalShotCount = plan.beats.reduce((total, item) => total + item.shotIds.length, 0)
      if (!beat || totalShotCount <= 1 || shotId === undefined || lockedShotIds.value.has(shotId)) return plan
      beat.shotIds.splice(index, 1)
      beat.shots?.splice(index, 1)
      return plan
    })
  }

  function addStoryShot(role: BeatRole, shotId: string): boolean {
    const candidate = shotScores.value.find((shot) => shot.shotId === shotId)
    const alreadyUsed = storyPlan.value?.beats.some((beat) => beat.shotIds.includes(shotId)) ?? false
    const sourceDuration = (candidate?.endMs ?? 0) - (candidate?.startMs ?? 0)
    if (!candidate?.selected || excludedShotIds.value.has(shotId) || alreadyUsed
      || !candidate.sourceAssetId || !candidate.sourceProxyArtifactId
      || candidate.startMs == null || candidate.endMs == null || sourceDuration < 600) {
      return false
    }
    updateStoryPlan((plan) => {
      const beat = plan.beats.find((item) => item.role === role)
      if (!beat) return plan
      const duration = Math.min(sourceDuration, Math.max(600, beat.targetDurationMs))
      beat.shotIds.push(candidate.shotId)
      beat.shots ??= []
      beat.shots.push({
        shotId: candidate.shotId,
        sourceAssetId: candidate.sourceAssetId!,
        sourceProxyArtifactId: candidate.sourceProxyArtifactId!,
        startMs: candidate.startMs!, endMs: candidate.endMs!,
        sourceInMs: candidate.startMs!, sourceOutMs: candidate.startMs! + duration,
        selectedDurationMs: duration,
        rank: Math.max(1, Math.round(candidate.rankScore ?? 0)),
        selectionReasons: ['MANUAL_STORY_EDIT'],
        storyRole: role,
      })
      return plan
    })
    return true
  }

  function updateTimeline(mutator: (timeline: Timeline) => Timeline): void {
    if (!timeline.value) return
    timeline.value = mutator(JSON.parse(JSON.stringify(timeline.value)) as Timeline)
    dirty.value = true
  }

  function recalculateTimeline(tl: Timeline): void {
    let cursor = 0
    tl.clips.forEach((clip, index) => {
      const sourceStart = clip.sourceShotStartMs ?? clip.sourceInMs
      const sourceEnd = clip.sourceShotEndMs ?? clip.sourceOutMs
      clip.sourceInMs = Math.max(sourceStart, Math.min(clip.sourceInMs, sourceEnd - 200))
      clip.sourceOutMs = Math.min(sourceEnd, Math.max(clip.sourceOutMs, clip.sourceInMs + 200))
      clip.transitionDurationMs = Math.max(0, Math.min(2000, clip.transitionDurationMs || 0))
      if (index === 0 && clip.transition === 'CROSS_DISSOLVE') {
        clip.transition = 'CUT'
        clip.transitionDurationMs = 0
      }
      if (clip.transition === 'CUT') clip.transitionDurationMs = 0
      clip.durationMs = Math.max(200, clip.sourceOutMs - clip.sourceInMs)
      if (clip.transition !== 'CUT') {
        clip.transitionDurationMs = Math.min(clip.transitionDurationMs || 300, Math.max(0, clip.durationMs - 1))
      }
      const overlap = clip.transition === 'CROSS_DISSOLVE' && index > 0 ? clip.transitionDurationMs : 0
      clip.timelineInMs = cursor - overlap
      clip.timelineOutMs = clip.timelineInMs + clip.durationMs
      cursor = clip.timelineOutMs
      clip.clipId ??= `clip_${clip.shotId}_${index}`
    })
    tl.totalDurationMs = cursor
  }

  function moveTimelineClip(index: number, delta: -1 | 1): void {
    updateTimeline((tl) => {
      const target = index + delta
      if (index < 0 || target < 0 || target >= tl.clips.length) return tl
      const currentClip = tl.clips[index]
      const targetClip = tl.clips[target]
      if (currentClip === undefined || targetClip === undefined) return tl
      tl.clips[index] = targetClip
      tl.clips[target] = currentClip
      recalculateTimeline(tl)
      return tl
    })
  }

  function reorderTimelineClip(fromIndex: number, toIndex: number): void {
    updateTimeline((tl) => {
      if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0
        || fromIndex >= tl.clips.length || toIndex >= tl.clips.length) return tl
      const [clip] = tl.clips.splice(fromIndex, 1)
      if (!clip) return tl
      tl.clips.splice(toIndex, 0, clip)
      recalculateTimeline(tl)
      return tl
    })
  }

  function removeTimelineClip(index: number): void {
    updateTimeline((tl) => {
      if (tl.clips.length <= 1) return tl
      tl.clips.splice(index, 1)
      recalculateTimeline(tl)
      return tl
    })
  }

  function updateTimelineClip(index: number, patch: Partial<Timeline['clips'][number]>): void {
    updateTimeline((tl) => {
      if (!tl.clips[index]) return tl
      Object.assign(tl.clips[index], patch)
      recalculateTimeline(tl)
      return tl
    })
  }

  return {
    currentGate, shotScores, excludedShotIds, forcedShotIds,
    storyPlan, lockedShotIds, timeline,
    renderedVideoUrl, dirty,
    activateGate, resetAll,
    setShotScores, setExcludedShotIds, setForcedShotIds, setLockedShotIds, setStoryPlan, setTimeline,
    setRenderedVideo,
    toggleForced, toggleExcluded, toggleLockShot, markSaved, markDirty,
    updateStoryPlan, moveStoryShot, canMoveStoryShot, removeStoryShot, addStoryShot,
    updateTimeline, moveTimelineClip, reorderTimelineClip, removeTimelineClip, updateTimelineClip,
  }
})
