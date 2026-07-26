<script setup lang="ts">
/** 全局布局壳 —— 桌面侧边栏 + 移动端底部导航 */
import { useRoute } from 'vue-router'
import { Clapperboard, FolderOpen, Activity, ShieldAlert } from 'lucide-vue-next'

const route = useRoute()

interface NavItem {
  label: string
  icon: typeof Clapperboard
  to: string
  routeNames: string[]
}

const navItems: NavItem[] = [
  { label: '项目', icon: FolderOpen, to: '/', routeNames: ['home', 'project-detail'] },
  { label: 'Workflow', icon: Activity, to: '/workflows', routeNames: ['workflow-list', 'workflow-monitor', 'versions'] },
  { label: '审计', icon: ShieldAlert, to: '/audit', routeNames: ['audit', 'project-audit'] },
]

function isActive(item: NavItem): boolean {
  return item.routeNames.includes(String(route.name ?? ''))
}
</script>

<template>
  <div class="flex flex-col md:flex-row h-screen overflow-hidden">
    <!-- 桌面侧边导航 -->
    <nav class="hidden md:flex w-16 flex-col items-center py-4 bg-surface-900 border-r border-surface-700 shrink-0">
      <div class="mb-6"><Clapperboard class="w-6 h-6 text-accent" /></div>
      <RouterLink
        v-for="item in navItems" :key="item.label" :title="item.label"
        :to="item.to"
        :aria-current="isActive(item) ? 'page' : undefined"
        :class="['w-10 h-10 rounded-lg flex items-center justify-center mb-2 transition-colors',
                 isActive(item) ? 'bg-accent/20 text-accent' : 'text-surface-400 hover:text-surface-200 hover:bg-surface-800']">
        <component :is="item.icon" class="w-5 h-5" />
      </RouterLink>
    </nav>

    <!-- 主内容 -->
    <main class="flex-1 overflow-y-auto pb-16 md:pb-0">
      <slot />
    </main>

    <!-- 移动端底部导航 -->
    <nav class="md:hidden fixed bottom-0 inset-x-0 h-14 bg-surface-900 border-t border-surface-700 flex items-center justify-around z-40">
      <RouterLink
        v-for="item in navItems" :key="item.label"
        :to="item.to"
        :aria-current="isActive(item) ? 'page' : undefined"
        :class="['flex flex-col items-center text-xs transition-colors',
                 isActive(item) ? 'text-accent' : 'text-surface-400']">
        <component :is="item.icon" class="w-5 h-5 mb-0.5" />
        {{ item.label }}
      </RouterLink>
    </nav>
  </div>
</template>
