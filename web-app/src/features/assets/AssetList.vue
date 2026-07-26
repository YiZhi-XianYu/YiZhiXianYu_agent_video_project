<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileVideo, Film, Play, X } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import type { Asset } from '@/api/types'

const projectStore = useProjectStore()
const assets = computed(() => projectStore.assets)
const preview = ref<Asset | null>(null)

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
</script>

<template>
  <section class="workspace-card asset-library">
    <div class="card-heading"><div><Film /><span><h2>素材库</h2><p>{{ assets.length }} 个视频文件</p></span></div></div>
    <div v-if="assets.length === 0" class="compact-empty"><FileVideo /><p>上传后的视频会显示在这里</p></div>
    <div v-else class="asset-list">
      <button v-for="asset in assets" :key="asset.id" class="asset-row" @click="preview = asset">
        <span class="asset-thumb"><FileVideo /><i><Play /></i></span>
        <span class="asset-meta"><strong>{{ asset.fileName }}</strong><small>{{ formatBytes(asset.sizeBytes) }} · 可预览</small></span>
        <Play class="row-action" />
      </button>
    </div>
  </section>

  <div v-if="preview" class="modal-backdrop" @click.self="preview = null">
    <div class="video-modal">
      <div><span><strong>{{ preview.fileName }}</strong><small>原始素材预览</small></span><button @click="preview = null"><X /></button></div>
      <video :src="preview.contentUrl" controls autoplay preload="metadata" />
    </div>
  </div>
</template>
