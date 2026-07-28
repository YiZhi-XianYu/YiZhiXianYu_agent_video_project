import { onMounted, onUnmounted } from 'vue'
import { listWorkflowRuns } from '@/api/workflows'
import { useChuxueStore } from '@/stores/chuxue'
import { useProjectStore } from '@/stores/project'
import type { RunStatus, WorkflowHistoryItem } from '@/api/types'

const COMPLETION_POLL_INTERVAL_MS = 5_000

export function useWorkflowCompletionWatcher(): void {
  const projects = useProjectStore()
  const chuxue = useChuxueStore()
  const seenStatuses = new Map<string, RunStatus>()
  const notifiedRunIds = new Set<string>()
  const watcherStartedAt = Date.now()

  let initialized = false
  let scanning = false
  let timer: number | null = null

  function completedAfterWatcherStarted(run: WorkflowHistoryItem): boolean {
    if (!run.completedAt) return false
    const completedAt = Date.parse(run.completedAt)
    return Number.isFinite(completedAt) && completedAt >= watcherStartedAt
  }

  function shouldNotify(run: WorkflowHistoryItem, previous: RunStatus | undefined): boolean {
    if (run.workflowType !== 'MULTI_ASSET_ANALYSIS' || run.status !== 'SUCCEEDED') return false
    if (notifiedRunIds.has(run.id)) return false
    if (previous && previous !== 'SUCCEEDED') return true
    return previous === undefined && completedAfterWatcherStarted(run)
  }

  async function scan(): Promise<void> {
    if (scanning) return
    scanning = true

    try {
      if (projects.projects.length === 0) {
        await projects.fetchProjects()
        if (projects.error) return
      }

      const results = await Promise.allSettled(
        projects.projects.map((project) => listWorkflowRuns(project.id)),
      )
      const successfulResults = results.filter(
        (result): result is PromiseFulfilledResult<WorkflowHistoryItem[]> => result.status === 'fulfilled',
      )
      if (projects.projects.length > 0 && successfulResults.length === 0) return

      const runs = successfulResults.flatMap((result) => result.value)
      for (const run of runs) {
        const previous = seenStatuses.get(run.id)
        if (initialized && shouldNotify(run, previous)) {
          notifiedRunIds.add(run.id)
          chuxue.notifyVideoCompleted()
        }
        seenStatuses.set(run.id, run.status)
      }
      initialized = true
    } finally {
      scanning = false
    }
  }

  function handleVisibilityChange(): void {
    if (document.visibilityState === 'visible') void scan()
  }

  onMounted(async () => {
    await scan()
    timer = window.setInterval(() => void scan(), COMPLETION_POLL_INTERVAL_MS)
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onUnmounted(() => {
    if (timer) window.clearInterval(timer)
    timer = null
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })
}
