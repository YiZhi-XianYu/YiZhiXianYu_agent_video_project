import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type ChuxueState = 'idle' | 'sleeping'
export type ChuxueChatMessage = { id: string; role: 'user' | 'assistant' | 'system'; content: string; planId?: string | null }

const NEXT_SLEEP_MIN_MS = 45_000
const NEXT_SLEEP_MAX_MS = 90_000
const SLEEP_MIN_MS = 22_000
const SLEEP_MAX_MS = 45_000
const BUBBLE_DURATION_MS = 5_000

function randomDuration(min: number, max: number): number {
  return Math.round(min + Math.random() * (max - min))
}

export const useChuxueStore = defineStore('chuxue', () => {
  const state = ref<ChuxueState>('idle')
  const bubbleVisible = ref(false)
  const completionNotice = ref<{ workflowRunId: string; projectId: string } | null>(null)
  const active = ref(false)
  const chatOpen = ref(false)
  const chatMessages = ref<ChuxueChatMessage[]>([])
  const chatSessionId = ref<string | null>(null)

  let nextSleepTimer: number | null = null
  let naturalWakeTimer: number | null = null
  let bubbleTimer: number | null = null

  const isSleeping = computed(() => state.value === 'sleeping')

  function clearTimer(timer: number | null): void {
    if (timer) window.clearTimeout(timer)
  }

  function scheduleNextSleep(): void {
    clearTimer(nextSleepTimer)
    nextSleepTimer = null
    if (!active.value || state.value !== 'idle') return

    nextSleepTimer = window.setTimeout(() => {
      nextSleepTimer = null
      sleep()
    }, randomDuration(NEXT_SLEEP_MIN_MS, NEXT_SLEEP_MAX_MS))
  }

  function scheduleNaturalWake(): void {
    clearTimer(naturalWakeTimer)
    naturalWakeTimer = window.setTimeout(() => {
      naturalWakeTimer = null
      wake()
    }, randomDuration(SLEEP_MIN_MS, SLEEP_MAX_MS))
  }

  function sleep(): void {
    if (!active.value || state.value === 'sleeping') return
    clearTimer(nextSleepTimer)
    nextSleepTimer = null
    state.value = 'sleeping'
    scheduleNaturalWake()
  }

  function wake(): void {
    clearTimer(naturalWakeTimer)
    naturalWakeTimer = null
    if (state.value === 'sleeping') state.value = 'idle'
    scheduleNextSleep()
  }

  function openChat(): void { if (state.value === 'idle') chatOpen.value = true }
  function closeChat(): void { chatOpen.value = false }
  function ensureSession(sessionId: string): void { chatSessionId.value = sessionId }
  function addChatMessage(message: ChuxueChatMessage): void { chatMessages.value.push(message) }
  function resetChat(sessionId: string | null = null): void {
    chatSessionId.value = sessionId
    chatMessages.value = []
  }

  function notifyVideoCompleted(workflowRunId: string, projectId: string): void {
    if (state.value === 'sleeping') wake()

    bubbleVisible.value = true
    completionNotice.value = { workflowRunId, projectId }
    clearTimer(bubbleTimer)
    bubbleTimer = window.setTimeout(() => {
      bubbleVisible.value = false
      bubbleTimer = null
    }, BUBBLE_DURATION_MS)
  }

  function start(): void {
    if (active.value) return
    active.value = true
    scheduleNextSleep()
  }

  function stop(): void {
    active.value = false
    clearTimer(nextSleepTimer)
    clearTimer(naturalWakeTimer)
    clearTimer(bubbleTimer)
    nextSleepTimer = null
    naturalWakeTimer = null
    bubbleTimer = null
    state.value = 'idle'
    bubbleVisible.value = false
    completionNotice.value = null
    chatOpen.value = false
    chatMessages.value = []
    chatSessionId.value = null
  }

  return {
    state,
    bubbleVisible,
    completionNotice,
    isSleeping,
    start,
    stop,
    sleep,
    wake,
    chatOpen,
    chatMessages,
    chatSessionId,
    openChat,
    closeChat,
    ensureSession,
    addChatMessage,
    resetChat,
    notifyVideoCompleted,
  }
})
