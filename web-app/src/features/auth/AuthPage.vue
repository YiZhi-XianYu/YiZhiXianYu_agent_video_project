<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Clapperboard, Loader2, LockKeyhole, Mail, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const mode = ref<'login' | 'register'>('login')
const email = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref<string | null>(null)

const submitLabel = computed(() => mode.value === 'login' ? '登录工作台' : '创建账号')

async function submit(): Promise<void> {
  error.value = null
  if (mode.value === 'register' && password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  try {
    if (mode.value === 'login') {
      await auth.login(email.value, password.value)
    } else {
      await auth.register(email.value, displayName.value, password.value)
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败，请稍后重试'
  }
}

function switchMode(next: 'login' | 'register'): void {
  mode.value = next
  error.value = null
  password.value = ''
  confirmPassword.value = ''
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-intro">
      <div class="brand-mark"><Clapperboard class="h-7 w-7" /></div>
      <p class="section-eyebrow text-blue-200">AGENT-DRIVEN VIDEO PRODUCTION</p>
      <h1>从素材分析到最终成片，<br />让复杂流程保持清晰。</h1>
      <p class="auth-copy">统一管理视频素材、镜头评分、故事安排、时间线、字幕、音乐和渲染结果。</p>
      <div class="auth-points">
        <span>多素材协作</span><span>人工审核 Gate</span><span>完整 Artifact 血缘</span>
      </div>
    </section>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="mb-7">
          <p class="section-eyebrow mb-2">WELCOME</p>
          <h2>{{ mode === 'login' ? '登录你的工作台' : '创建第一个账号' }}</h2>
          <p>{{ mode === 'login' ? '继续管理项目和视频制作流程。' : '注册完成后即可创建独立的项目空间。' }}</p>
        </div>

        <div class="auth-tabs">
          <button :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <form class="space-y-4" @submit.prevent="submit">
          <label v-if="mode === 'register'" class="field-group">
            <span>昵称</span>
            <div class="field-with-icon"><UserRound /><input v-model="displayName" maxlength="80" placeholder="如何称呼你" /></div>
          </label>
          <label class="field-group">
            <span>邮箱</span>
            <div class="field-with-icon"><Mail /><input v-model="email" type="email" maxlength="254" required placeholder="name@example.com" /></div>
          </label>
          <label class="field-group">
            <span>密码</span>
            <div class="field-with-icon"><LockKeyhole /><input v-model="password" type="password" minlength="8" maxlength="72" required placeholder="至少 8 个字符" /></div>
          </label>
          <label v-if="mode === 'register'" class="field-group">
            <span>确认密码</span>
            <div class="field-with-icon"><LockKeyhole /><input v-model="confirmPassword" type="password" minlength="8" maxlength="72" required placeholder="再次输入密码" /></div>
          </label>
          <div v-if="error" class="auth-error">{{ error }}</div>
          <button class="btn-primary h-11 w-full" :disabled="auth.loading">
            <Loader2 v-if="auth.loading" class="h-4 w-4 animate-spin" />
            {{ submitLabel }}
          </button>
        </form>
        <p class="mt-5 text-center text-xs text-surface-500">密码仅以 BCrypt 哈希保存，会话通过安全 Cookie 维持。</p>
      </div>
    </section>
  </main>
</template>
