import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { AuthUser } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const initialized = ref(false)
  const loading = ref(false)

  const isAuthenticated = computed(() => user.value !== null)

  async function initialize(): Promise<void> {
    if (initialized.value) return
    try {
      user.value = await authApi.getCurrentUser()
    } catch {
      user.value = null
    } finally {
      initialized.value = true
    }
  }

  async function login(email: string, password: string): Promise<void> {
    loading.value = true
    try {
      user.value = await authApi.login(email, password)
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function register(email: string, displayName: string, password: string): Promise<void> {
    loading.value = true
    try {
      user.value = await authApi.register(email, displayName, password)
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      user.value = null
      initialized.value = true
    }
  }

  function clear(): void {
    user.value = null
    initialized.value = true
  }

  return { user, initialized, loading, isAuthenticated, initialize, login, register, logout, clear }
})
