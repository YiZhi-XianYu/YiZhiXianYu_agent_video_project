<script setup lang="ts">
/**
 * ShotPreviewPanel — 镜头关键帧/视频片段浮层预览组件
 *
 * 点击镜头行或 shot 标签时，在锚点元素旁边弹出浮层展示关键帧或代理视频。
 * 使用 Teleport 渲染到 body，避免被父容器 overflow:hidden 裁剪。
 *
 * Props:
 *   shotId         - 镜头唯一标识
 *   videoUrl       - 代理视频 URL（优先展示视频片段）
 *   keyframeUrl    - 关键帧图片 URL（视频不可用时回退）
 *   startMs/endMs  - 视频片段起止时间（毫秒），用于 seek 和循环播放
 *   anchorEl       - 锚点 DOM 元素，用于计算浮层位置
 *   visible        - 是否显示
 *
 * Events:
 *   close          - 用户请求关闭（点击外部 / Escape / 点击关闭按钮）
 */
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { X, ImageOff } from 'lucide-vue-next'

const props = defineProps<{
  shotId: string
  keyframeUrl: string | null
  videoUrl: string | null
  startMs: number
  endMs: number
  anchorEl: HTMLElement | null
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

/* =========================== 状态 =========================== */

const panelRef = ref<HTMLElement | null>(null)
const videoRef = ref<HTMLVideoElement | null>(null)
const position = ref({ top: 0, left: 0 })
const imageError = ref(false)
const panelWidth = 360
const panelHeight = props.videoUrl ? 280 : 240
const gap = 12

/* =========================== 计算 =========================== */

/** 是否有可展示的关键帧 */
const hasKeyframe = computed(() => props.keyframeUrl !== null && props.keyframeUrl !== '')

/** 当前展示模式 */
const mode = computed<'video' | 'keyframe' | 'empty'>(() => {
  if (props.videoUrl) return 'video'
  if (hasKeyframe.value && !imageError.value) return 'keyframe'
  return 'empty'
})

/** 格式化毫秒为 mm:ss */
function formatMs(ms: number): string {
  const sec = Math.round(ms / 1000)
  const min = Math.floor(sec / 60)
  const s = sec % 60
  return `${min}:${s.toString().padStart(2, '0')}`
}

/* =========================== 定位计算 =========================== */

function recalculate(): void {
  if (!props.anchorEl || !props.visible) return

  const rect = props.anchorEl.getBoundingClientRect()
  let left = rect.right + gap
  const top = Math.max(8, Math.min(
    rect.top + rect.height / 2 - panelHeight / 2,
    window.innerHeight - panelHeight - 8,
  ))

  /* 右侧空间不足则放左侧 */
  if (left + panelWidth > window.innerWidth - 16) {
    left = rect.left - gap - panelWidth
  }

  /* 左侧也不够则贴边 */
  if (left < 8) left = 8

  position.value = { top, left }
}

/* =========================== 生命周期 =========================== */

onMounted(() => {
  window.addEventListener('resize', recalculate)
  window.addEventListener('scroll', recalculate, true)
})

onUnmounted(() => {
  window.removeEventListener('resize', recalculate)
  window.removeEventListener('scroll', recalculate, true)
})

watch(() => props.visible, async (v) => {
  if (v) {
    imageError.value = false
    await nextTick()
    recalculate()
  }
})

watch(() => props.anchorEl, () => {
  if (props.visible) recalculate()
})

/* =========================== 视频控制 =========================== */

watch(() => props.visible, (v) => {
  if (!v && videoRef.value) {
    videoRef.value.pause()
  }
})

watch(videoRef, (el, _previous, onCleanup) => {
  if (!el) return
  el.muted = false
  el.volume = 1

  const startPlayback = () => {
    el.currentTime = props.startMs / 1000
    el.play().catch(() => { /* 有声自动播放被限制时，保留原生播放控件供用户启动 */ })
  }

  if (el.readyState >= 1) startPlayback()
  else el.addEventListener('loadedmetadata', startPlayback, { once: true })

  /* 循环播放片段 */
  const onTimeUpdate = () => {
    if (el.currentTime * 1000 >= props.endMs) {
      el.currentTime = props.startMs / 1000
      el.play().catch(() => {})
    }
  }
  el.addEventListener('timeupdate', onTimeUpdate)

  onCleanup(() => {
    el.removeEventListener('loadedmetadata', startPlayback)
    el.removeEventListener('timeupdate', onTimeUpdate)
  })
})

/* =========================== 关闭处理 =========================== */

function handleBackdropClick(e: MouseEvent): void {
  /* 点击浮层内部不关闭 */
  if (panelRef.value?.contains(e.target as Node)) return
  emit('close')
}

function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    emit('close')
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    document.addEventListener('keydown', handleKeydown)
  } else {
    document.removeEventListener('keydown', handleKeydown)
  }
})
</script>

<template>
  <Teleport to="body">
    <!-- 透明遮罩层（捕获点击外部） -->
    <div
      v-if="visible"
      class="fixed inset-0 z-[100]"
      @click="handleBackdropClick"
    />

    <!-- 浮层 -->
    <div
      v-if="visible"
      ref="panelRef"
      class="fixed z-[101] rounded-lg border border-surface-600 bg-surface-800 shadow-2xl overflow-hidden"
      :style="{
        top: position.top + 'px',
        left: position.left + 'px',
        width: panelWidth + 'px',
        minHeight: '180px',
      }"
    >
      <!-- 头部 -->
      <div class="flex items-center justify-between px-3 py-2 border-b border-surface-700 bg-surface-750">
        <span class="text-xs font-mono text-surface-300 truncate" :title="shotId">
          {{ shotId.slice(0, 20) }}{{ shotId.length > 20 ? '...' : '' }}
        </span>
        <div class="flex items-center gap-2">
          <span class="text-xs text-surface-500 font-mono">
            {{ formatMs(startMs) }} – {{ formatMs(endMs) }}
          </span>
          <span v-if="mode === 'video'" class="text-[10px] text-success">声音已开启</span>
          <button
            class="w-6 h-6 rounded flex items-center justify-center text-surface-400 hover:text-surface-200 hover:bg-surface-600 transition-colors"
            @click="emit('close')"
            title="关闭预览"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- 内容区 -->
      <div class="relative bg-black">
        <!-- 视频片段模式（优先） -->
        <template v-if="mode === 'video'">
          <div class="relative">
            <video
              ref="videoRef"
              :src="videoUrl!"
              class="w-full object-contain"
              style="max-height: 220px"
              controls
              preload="metadata"
              playsinline
            />
          </div>
        </template>

        <!-- 关键帧模式（fallback） -->
        <template v-else-if="mode === 'keyframe'">
          <img
            :src="keyframeUrl!"
            :alt="'Shot ' + shotId"
            class="w-full object-contain"
            style="max-height: 220px"
            loading="lazy"
            @error="imageError = true"
          />
        </template>

        <!-- 空态 -->
        <template v-else>
          <div class="flex flex-col items-center justify-center py-12 text-surface-500">
            <ImageOff class="w-8 h-8 mb-2" />
            <span class="text-xs">无预览内容可用</span>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>
