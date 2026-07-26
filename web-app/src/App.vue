 <script setup lang="ts">
 // 根组件：全局布局壳
 import AppShell from '@/components/AppShell.vue'
 </script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
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
