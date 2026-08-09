<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useChuxueStore } from '@/stores/chuxue'
import ChuxueChatCard from '@/components/ChuxueChatCard.vue'

interface SpinePlayerInstance {
  setAnimation: (animationName: string, loop: boolean) => void
  dispose?: () => void
}

interface SpinePlayerOptions {
  skelUrl: string
  atlasUrl: string
  showControls: boolean
  alpha: boolean
  backgroundColor: string
  fullScreenBackgroundColor: string
  defaultMix: number
  success: () => void
  error: (_player: unknown, reason: string) => void
}

declare global {
  interface Window {
    spine?: {
      SpinePlayer: new (containerId: string, options: SpinePlayerOptions) => SpinePlayerInstance
    }
  }
}

const PLAYER_CONTAINER_ID = 'chuxue-spine-player'
const RUNTIME_SCRIPT_ID = 'chuxue-spine-runtime'
const RUNTIME_STYLESHEET_ID = 'chuxue-spine-styles'
const baseUrl = import.meta.env.BASE_URL.endsWith('/')
  ? import.meta.env.BASE_URL
  : `${import.meta.env.BASE_URL}/`
const assetRoot = `${baseUrl}characters/chuxue/`

let runtimePromise: Promise<void> | null = null

function ensureRuntimeStyles(): void {
  if (document.getElementById(RUNTIME_STYLESHEET_ID)) return
  const link = document.createElement('link')
  link.id = RUNTIME_STYLESHEET_ID
  link.rel = 'stylesheet'
  link.href = `${baseUrl}vendor/spine-3.8/spine-player.css`
  document.head.appendChild(link)
}

function loadSpineRuntime(): Promise<void> {
  if (window.spine?.SpinePlayer) return Promise.resolve()
  if (runtimePromise) return runtimePromise

  runtimePromise = new Promise<void>((resolve, reject) => {
    const existing = document.getElementById(RUNTIME_SCRIPT_ID) as HTMLScriptElement | null
    const script = existing ?? document.createElement('script')

    const cleanup = (): void => {
      script.removeEventListener('load', handleLoad)
      script.removeEventListener('error', handleError)
    }
    const handleLoad = (): void => {
      cleanup()
      if (window.spine?.SpinePlayer) resolve()
      else reject(new Error('Spine 3.8 runtime 未正确初始化'))
    }
    const handleError = (): void => {
      cleanup()
      reject(new Error('Spine 3.8 runtime 加载失败'))
    }

    script.addEventListener('load', handleLoad, { once: true })
    script.addEventListener('error', handleError, { once: true })
    if (!existing) {
      script.id = RUNTIME_SCRIPT_ID
      script.src = `${baseUrl}vendor/spine-3.8/spine-player.js`
      script.async = true
      document.head.appendChild(script)
    }
  }).catch((error) => {
    runtimePromise = null
    document.getElementById(RUNTIME_SCRIPT_ID)?.remove()
    throw error
  })

  return runtimePromise
}

const chuxue = useChuxueStore()
const ready = ref(false)
const loadError = ref(false)
const stateLabel = computed(() => chuxue.isSleeping ? '正在睡觉，点击唤醒' : '待机中')

let player: SpinePlayerInstance | null = null
let disposed = false

function playCurrentState(): void {
  if (!ready.value || !player) return
  player.setAnimation(chuxue.isSleeping ? 'Sleep' : 'Relax', true)
}

function handleInteraction(): void {
  if (chuxue.chatOpen) return
  if (chuxue.isSleeping) chuxue.wake()
  else chuxue.openChat()
}

watch(() => chuxue.state, playCurrentState)

onMounted(async () => {
  chuxue.start()
  ensureRuntimeStyles()

  try {
    await loadSpineRuntime()
    if (disposed || !window.spine?.SpinePlayer) return

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    player = new window.spine.SpinePlayer(PLAYER_CONTAINER_ID, {
      skelUrl: `${assetRoot}build_char_1046_sbell2.skel`,
      atlasUrl: `${assetRoot}build_char_1046_sbell2.atlas`,
      showControls: false,
      alpha: true,
      backgroundColor: '#00000000',
      fullScreenBackgroundColor: '#00000000',
      defaultMix: reduceMotion ? 0 : 0.28,
      success: () => {
        if (disposed) return
        ready.value = true
        playCurrentState()
      },
      error: () => {
        if (!disposed) loadError.value = true
      },
    })
  } catch {
    if (!disposed) loadError.value = true
  }
})

onUnmounted(() => {
  disposed = true
  player?.dispose?.()
  player = null
  chuxue.stop()
})
</script>

<template>
  <section class="chuxue-pet-slot" aria-label="动态助手初雪">
    <ChuxueChatCard v-if="chuxue.chatOpen" />
    <div
      class="chuxue-stage"
      :class="{ sleeping: chuxue.isSleeping }"
        role="button"
        tabindex="0"
        :aria-label="stateLabel"
        :title="stateLabel"
        @click="handleInteraction"
        @keydown.enter.prevent="handleInteraction"
        @keydown.space.prevent="handleInteraction"
      >
        <div :id="PLAYER_CONTAINER_ID" class="chuxue-player" aria-hidden="true" />
        <div v-if="!ready && !loadError" class="chuxue-loading">初雪正在赶来…</div>
        <div v-else-if="loadError" class="chuxue-loading error">初雪暂时离开了</div>
        <span class="chuxue-nameplate">初雪 · {{ chuxue.isSleeping ? '睡眠' : '待机' }}</span>
    </div>
  </section>
</template>

<style scoped>
.chuxue-pet-slot {
  position: relative;
  display: flex;
  min-height: 170px;
  flex: 1 1 auto;
  align-items: flex-end;
  justify-content: center;
  margin-top: 12px;
  pointer-events: none;
}

.chuxue-stage {
  position: relative;
  z-index: 1;
  width: 232px;
  height: clamp(170px, 27vh, 260px);
  overflow: hidden;
  border-radius: 22px;
  outline: none;
  pointer-events: auto;
  cursor: default;
  transform: translateY(0);
  transition: transform 280ms ease, filter 280ms ease;
}

.chuxue-stage.sleeping {
  cursor: pointer;
  transform: translateY(3px);
  filter: saturate(0.92) brightness(0.94);
}

.chuxue-stage:focus-visible {
  box-shadow: 0 0 0 2px rgba(95, 158, 255, 0.6);
}

.chuxue-player {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.chuxue-player :deep(.spine-player),
.chuxue-player :deep(canvas) {
  width: 100% !important;
  height: 100% !important;
  background: transparent !important;
}

.chuxue-player :deep(.spine-player-controls) {
  display: none !important;
}

.chuxue-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgb(100 116 139);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.chuxue-loading.error {
  color: rgb(148 163 184);
}

.chuxue-nameplate {
  position: absolute;
  left: 50%;
  bottom: 7px;
  z-index: 2;
  transform: translateX(-50%);
  padding: 4px 9px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.64);
  color: rgb(148 163 184);
  font-size: 9px;
  letter-spacing: 0.08em;
  white-space: nowrap;
  backdrop-filter: blur(8px);
}

@media (max-height: 720px) {
  .chuxue-nameplate {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chuxue-stage {
    transition: none;
  }
}
</style>
