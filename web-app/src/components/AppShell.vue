<script setup lang="ts">
/** 全局布局壳 —— 桌面侧边栏 + 移动端底部导航 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Clapperboard, FolderOpen, Activity, ShieldAlert } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const currentPath = computed(() => route.path)

interface NavItem {
  label: string
  icon: typeof Clapperboard
  path: string
  exact: boolean
}

const navItems: NavItem[] = [
  { label: '项目', icon: FolderOpen, path: '/', exact: true },
  { label: 'Workflow', icon: Activity, path: '/projects/', exact: false },
  { label: '审计', icon: ShieldAlert, path: '/audit', exact: false },
]

function isActive(item: NavItem): boolean {
  if (item.exact) return currentPath.value === item.path
  return currentPath.value.startsWith(item.path)
}
</script>

<template>
  <div class="flex flex-col md:flex-row h-screen overflow-hidden">
    <!-- 桌面侧边导航 -->
    <nav class="hidden md:flex w-16 flex-col items-center py-4 bg-surface-900 border-r border-surface-700 shrink-0">
      <div class="mb-6"><Clapperboard class="w-6 h-6 text-accent" /></div>
      <button
        v-for="item in navItems" :key="item.label" :title="item.label"
        :class="['w-10 h-10 rounded-lg flex items-center justify-center mb-2 transition-colors',
                 isActive(item) ? 'bg-accent/20 text-accent' : 'text-surface-400 hover:text-surface-200 hover:bg-surface-800']"
        @click="router.push(item.path)">
        <component :is="item.icon" class="w-5 h-5" />
      </button>
    </nav>

    <!-- 主内容 -->
    <main class="flex-1 overflow-y-auto pb-16 md:pb-0">
      <router-view />
    </main>

    <!-- 移动端底部导航 -->
    <nav class="md:hidden fixed bottom-0 inset-x-0 h-14 bg-surface-900 border-t border-surface-700 flex items-center justify-around z-40">
      <button
        v-for="item in navItems" :key="item.label"
        :class="['flex flex-col items-center text-xs transition-colors',
                 isActive(item) ? 'text-accent' : 'text-surface-400']"
        @click="router.push(item.path)">
        <component :is="item.icon" class="w-5 h-5 mb-0.5" />
        {{ item.label }}
      </button>
    </nav>
  </div>
</template>
