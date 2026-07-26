<script setup lang="ts">
/**
 * 版本 Diff 对比组件
 *
 * 对比指定版本与当前 Story Plan 的差异，按 Beat 分类展示 Added/Removed/Modified/Unchanged。
 */
import { ref, watch } from 'vue'
import { Diff, X, ArrowRight, Plus, Minus, Edit3, Circle } from 'lucide-vue-next'
import { getVersion, getCurrentPlan } from '@/api/plans'
import { useUiStore } from '@/stores/ui'
import type { DiffEntry, StoryPlan } from '@/shared/types'

const props = defineProps<{
  runId: string
  planId: string | null
  versionName: string
}>()

const emit = defineEmits<{ close: [] }>()

const uiStore = useUiStore()
const diffs = ref<DiffEntry[]>([])
const loading = ref(false)
const summary = ref({ added: 0, removed: 0, modified: 0, unchanged: 0 })

watch(() => props.planId, (id) => {
  if (id) computeDiff()
})

async function computeDiff(): Promise<void> {
  if (!props.planId) return
  loading.value = true
  try {
    const [version, current] = await Promise.all([
      getVersion(props.runId, props.planId),
      getCurrentPlan(props.runId),
    ])
    diffs.value = comparePlans(
      (version.planData as { beats?: StoryPlan['beats'] })?.beats ?? [],
      (current.planData as { beats?: StoryPlan['beats'] })?.beats ?? [],
    )
    summary.value = {
      added: diffs.value.filter((d) => d.type === 'added').length,
      removed: diffs.value.filter((d) => d.type === 'removed').length,
      modified: diffs.value.filter((d) => d.type === 'modified').length,
      unchanged: diffs.value.filter((d) => d.type === 'unchanged').length,
    }
  } catch {
    uiStore.showToast('获取 Diff 数据失败', 'error')
  } finally {
    loading.value = false
  }
}

/** 对比两个 beat 列表，生成 DiffEntry 数组 */
function comparePlans(
  oldBeats: StoryPlan['beats'],
  newBeats: StoryPlan['beats'],
): DiffEntry[] {
  const result: DiffEntry[] = []
  const oldMap = new Map(oldBeats.map((b) => [b.role, b]))
  const newMap = new Map(newBeats.map((b) => [b.role, b]))
  const allRoles = new Set([...oldMap.keys(), ...newMap.keys()])

  for (const role of allRoles) {
    const oldShotIds = oldMap.get(role)?.shotIds ?? []
    const newShotIds = newMap.get(role)?.shotIds ?? []

    for (const sid of newShotIds) {
      if (!oldShotIds.includes(sid)) {
        result.push({ beatRole: role, type: 'added', shotId: sid, oldPosition: null, newPosition: newShotIds.indexOf(sid) })
      } else {
        const oldIdx = oldShotIds.indexOf(sid)
        const newIdx = newShotIds.indexOf(sid)
        result.push({
          beatRole: role,
          type: oldIdx === newIdx ? 'unchanged' : 'modified',
          shotId: sid,
          oldPosition: oldIdx,
          newPosition: newIdx,
        })
      }
    }
    for (const sid of oldShotIds) {
      if (!newShotIds.includes(sid)) {
        result.push({ beatRole: role, type: 'removed', shotId: sid, oldPosition: oldShotIds.indexOf(sid), newPosition: null })
      }
    }
  }
  return result
}

const typeIcons: Record<string, typeof Plus> = {
  added: Plus, removed: Minus, modified: Edit3, unchanged: Circle,
}
const typeColors: Record<string, string> = {
  added: 'text-success', removed: 'text-danger', modified: 'text-warning', unchanged: 'text-surface-500',
}
</script>

<template>
  <div v-if="props.planId" class="card ring-1 ring-accent/30">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-sm font-semibold text-surface-200 flex items-center gap-2">
        <Diff class="w-4 h-4 text-accent" />
        版本对比：<span class="text-accent">{{ props.versionName }}</span>
      </h3>
      <button class="text-surface-500 hover:text-surface-300 transition-colors" @click="emit('close')">
        <X class="w-4 h-4" />
      </button>
    </div>

    <!-- 统计 -->
    <div v-if="diffs.length > 0" class="flex gap-4 mb-3 text-xs">
      <span class="text-success">+{{ summary.added }}</span>
      <span class="text-danger">-{{ summary.removed }}</span>
      <span class="text-warning">~{{ summary.modified }}</span>
      <span class="text-surface-500">={{ summary.unchanged }}</span>
    </div>

    <div v-if="loading" class="text-center py-6 text-surface-500 text-sm">加载差异...</div>
    <div v-else-if="diffs.length === 0" class="text-center py-6 text-surface-500 text-sm">无差异</div>

    <div v-else class="space-y-1 max-h-80 overflow-y-auto">
      <div
        v-for="(d, i) in diffs"
        :key="i"
        :class="['flex items-center gap-2 px-2 py-1.5 rounded text-xs',
                 d.type === 'added' ? 'bg-success/5' :
                 d.type === 'removed' ? 'bg-danger/5' :
                 d.type === 'modified' ? 'bg-warning/5' : '']"
      >
        <component :is="typeIcons[d.type]" :class="['w-3 h-3 shrink-0', typeColors[d.type]]" />
        <span class="w-12 text-surface-500 shrink-0">{{ d.beatRole }}</span>
        <span :class="['font-mono truncate', typeColors[d.type]]">{{ d.shotId.slice(0, 16) }}...</span>
        <span v-if="d.type === 'modified'" class="text-surface-600 ml-auto">
          #{{ (d.oldPosition ?? 0) + 1 }} <ArrowRight class="w-3 h-3 inline mx-0.5" /> #{{ (d.newPosition ?? 0) + 1 }}
        </span>
      </div>
    </div>
  </div>
</template>
