 /**
  * 键盘快捷键 composable
  *
  * 在组件 mounted 时注册全局键盘事件，unmounted 时自动解绑。
  * 支持 Space 播放/暂停、Enter 确认、Escape 关闭等常用快捷键。
  */
 import { onMounted, onUnmounted } from 'vue'

 /** 快捷键绑定 */
 export interface KeyBinding {
   /** 按键名（如 'Space', 'Enter', 'Escape', 'ArrowLeft', 'ArrowRight'） */
   key: string
   /** 是否按 Ctrl/Cmd */
   ctrl?: boolean
   /** 回调 */
   handler: () => void
   /** 描述（文档用） */
   description?: string
 }

 /**
  * 创建键盘快捷键管理器。
  * 自动在组件卸载时解绑所有快捷键。
  */
 export function useKeyboard(bindings: KeyBinding[]): void {
   function onKeyDown(e: KeyboardEvent): void {
     // 不在输入框中触发
     const target = e.target as HTMLElement
     if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') {
       return
     }

     for (const binding of bindings) {
       const matchKey = e.code === binding.key || e.key === binding.key
       const matchCtrl = binding.ctrl ? (e.ctrlKey || e.metaKey) : true
       if (matchKey && matchCtrl) {
         e.preventDefault()
         binding.handler()
         return
       }
     }
   }

   onMounted(() => window.addEventListener('keydown', onKeyDown))
   onUnmounted(() => window.removeEventListener('keydown', onKeyDown))
 }

 /** 常用快捷键常量（供模板中展示） */
 export const SHORTCUTS = {
   PLAY_PAUSE: { key: 'Space', description: '播放 / 暂停' },
   CONFIRM: { key: 'Enter', description: '确认操作' },
   CLOSE: { key: 'Escape', description: '关闭 / 返回' },
 }
