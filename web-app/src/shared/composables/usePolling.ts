/**
 * 通用轮询 composable
 *
 * 在组件 mounted 时开始轮询，unmounted 时自动清理。
 * 支持动态调整轮询间隔和手动启停。
 */
import { ref, unref, onUnmounted, type Ref } from 'vue'

export interface UsePollingReturn {
  isPolling: Ref<boolean>
  start: () => void
  stop: () => void
}

/**
 * 创建轮询实例
 *
 * @param fn - 每次轮询执行的异步函数
 * @param intervalMs - 轮询间隔（毫秒），支持传入普通数值或 Ref
 */
export function usePolling(
  fn: () => Promise<void>,
  intervalMs: number | Ref<number>,
): UsePollingReturn {
  const isPolling = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  const getInterval = (): number => unref(intervalMs)

  const stop = (): void => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    isPolling.value = false
  }

  const start = (): void => {
    stop()
    isPolling.value = true
    fn().catch(() => { /* 静默处理 */ })
    timer = setInterval(() => {
      fn().catch(() => { /* 静默处理 */ })
    }, getInterval())
  }

  onUnmounted(stop)
  return { isPolling, start, stop }
}
