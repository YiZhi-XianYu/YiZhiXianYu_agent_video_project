 <script setup lang="ts">
 // 根组件：全局布局壳
 import { onMounted, onUnmounted } from 'vue'
 import { useRoute, useRouter } from 'vue-router'
 import AppShell from '@/components/AppShell.vue'
 import { useAuthStore } from '@/stores/auth'

 const route = useRoute()
 const router = useRouter()
 const auth = useAuthStore()

 function handleUnauthorized(): void {
   auth.clear()
   router.push({ path: '/auth', query: { redirect: route.fullPath } })
 }

 onMounted(() => window.addEventListener('avp:unauthorized', handleUnauthorized))
 onUnmounted(() => window.removeEventListener('avp:unauthorized', handleUnauthorized))
 </script>

<template>
  <RouterView v-if="route.meta.public" />
  <AppShell v-else><RouterView /></AppShell>
</template>
 
 <style>
 /* 全局过渡动画 */
 .fade-enter-active,
 .fade-leave-active {
   transition: opacity 0.15s ease;
 }
 .fade-enter-from,
 .fade-leave-to {
   opacity: 0;
 }
 </style>
