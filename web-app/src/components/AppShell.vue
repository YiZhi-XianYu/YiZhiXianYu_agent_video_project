<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, Clapperboard, FolderOpen, LogOut, ShieldCheck } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import { useWorkflowStore } from '@/stores/workflow'
import { useWorkflowCompletionWatcher } from '@/shared/composables/useWorkflowCompletionWatcher'
import ChuxuePet from '@/components/ChuxuePet.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projects = useProjectStore()
const workflow = useWorkflowStore()

useWorkflowCompletionWatcher()

const navItems = [
  { label: '项目工作台', caption: '素材与制作入口', icon: FolderOpen, to: '/', names: ['home', 'project-detail'] },
  { label: 'Workflow', caption: '运行与审核记录', icon: Activity, to: '/workflows', names: ['workflow-list', 'workflow-monitor', 'versions'] },
  { label: 'LLM 审计', caption: '模型调用与回退', icon: ShieldCheck, to: '/audit', names: ['audit', 'project-audit'] },
]

const pageTitle = computed(() => String(route.meta.title ?? '视频制作工作台'))

function active(names: string[]): boolean {
  return names.includes(String(route.name ?? ''))
}

async function logout(): Promise<void> {
  await auth.logout()
  projects.reset()
  workflow.clear()
  await router.replace('/auth')
}
</script>

<template>
  <div class="app-layout">
    <aside class="app-sidebar">
      <RouterLink to="/" class="app-brand">
        <span class="brand-icon"><Clapperboard /></span>
        <span><strong>FramePilot</strong><small>Agent Video Studio</small></span>
      </RouterLink>

      <nav class="app-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          :class="{ active: active(item.names) }"
        >
          <component :is="item.icon" />
          <span><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></span>
        </RouterLink>
      </nav>

      <ChuxuePet />

      <div class="account-card">
        <div class="account-avatar">{{ auth.user?.displayName?.slice(0, 1).toUpperCase() || 'U' }}</div>
        <div class="min-w-0 flex-1"><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.email }}</small></div>
        <button title="退出登录" @click="logout"><LogOut /></button>
      </div>
    </aside>

    <div class="app-content">
      <header class="mobile-header">
        <RouterLink to="/" class="flex items-center gap-2 font-semibold"><Clapperboard class="h-5 w-5 text-accent" />FramePilot</RouterLink>
        <span>{{ pageTitle }}</span>
      </header>
      <main class="app-main"><slot /></main>
      <nav class="mobile-nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" :class="{ active: active(item.names) }">
          <component :is="item.icon" /><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
    </div>
  </div>
</template>
