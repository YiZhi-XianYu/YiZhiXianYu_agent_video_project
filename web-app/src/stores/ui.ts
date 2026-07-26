/**
 * 全局 UI 状态管理
 *
 * 管理 Auto 模式开关、全局加载态、通知等跨组件 UI 状态。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

/** Toast 通知数据结构（导出以便其他模块引用类型） */
export interface Toast {
  id: number
  message: string
  type: 'info' | 'success' | 'warning' | 'error'
}

export const useUiStore = defineStore('ui', () => {
  // ===================== State =====================

  /** 是否开启全自动模式（跳过所有 Gate） */
  const autoMode = ref(false)

  /** 全局加载提示 */
  const globalLoading = ref(false)
  const globalLoadingText = ref('')

  /** Toast 通知列表 */
  const toasts = ref<Toast[]>([])

  // ==================== Actions ====================

  let toastId = 0

  /** 显示 Toast 通知，3 秒后自动消失 */
  function showToast(message: string, type: Toast['type'] = 'info'): void {
    const id = ++toastId
    toasts.value.push({ id, message, type })
    setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, 3_000)
  }

  /** 设置全局加载状态 */
  function setGlobalLoading(loading: boolean, text = ''): void {
    globalLoading.value = loading
    globalLoadingText.value = text
  }

  /** 切换 Auto 模式开关 */
  function toggleAutoMode(): void {
    autoMode.value = !autoMode.value
  }

  return {
    autoMode,
    globalLoading,
    globalLoadingText,
    toasts,
    showToast,
    setGlobalLoading,
    toggleAutoMode,
  }
})
