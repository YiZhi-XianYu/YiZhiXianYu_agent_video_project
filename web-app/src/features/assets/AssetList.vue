<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileVideo, Film, Loader2, Play, Trash2, X } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import { useUiStore } from '@/stores/ui'
import { deleteAsset } from '@/api/assets'
import type { Asset } from '@/api/types'

const props = defineProps<{ projectId: string }>()
const projectStore = useProjectStore()
const uiStore = useUiStore()
const assets = computed(() => projectStore.assets)
const preview = ref<Asset | null>(null)
const deletingAssetId = ref<string | null>(null)

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}

async function removeAsset(asset: Asset): Promise<void> {
  if (deletingAssetId.value) return
  const confirmed = window.confirm(
    `确定从素材库移除“${asset.fileName}”吗？\n\n已开始和历史 Workflow 仍可继续使用该素材。`,
  )
  if (!confirmed) return
  deletingAssetId.value = asset.id
  try {
    await deleteAsset(props.projectId, asset.id)
    if (preview.value?.id === asset.id) preview.value = null
    await projectStore.fetchAssets(props.projectId)
    uiStore.showToast('素材已从项目素材库移除', 'success')
  } catch (error) {
    uiStore.showToast(error instanceof Error ? error.message : '删除素材失败', 'error')
  } finally {
    deletingAssetId.value = null
  }
}
</script>

<template>
  <section class="workspace-card asset-library">
    <div class="card-heading"><div><Film /><span><h2>素材库</h2><p>{{ assets.length }} 个视频文件</p></span></div></div>
    <div v-if="assets.length === 0" class="compact-empty"><FileVideo /><p>上传后的视频会显示在这里</p></div>
    <div v-else class="asset-list">
      <div v-for="asset in assets" :key="asset.id" class="asset-row">
        <button class="asset-row-main" type="button" @click="preview = asset">
          <span class="asset-thumb"><FileVideo /><i><Play /></i></span>
          <span class="asset-meta"><strong>{{ asset.fileName }}</strong><small>{{ formatBytes(asset.sizeBytes) }} · 可预览</small></span>
          <Play class="row-action" />
        </button>
        <button
          class="asset-delete"
          type="button"
          :disabled="deletingAssetId !== null"
          :aria-label="`删除素材 ${asset.fileName}`"
          title="从素材库移除"
          @click="removeAsset(asset)"
        >
          <Loader2 v-if="deletingAssetId === asset.id" class="animate-spin" />
          <Trash2 v-else />
        </button>
      </div>
    </div>
  </section>

  <div v-if="preview" class="modal-backdrop" @click.self="preview = null">
    <div class="video-modal">
      <div><span><strong>{{ preview.fileName }}</strong><small>原始素材预览</small></span><button @click="preview = null"><X /></button></div>
      <video :src="preview.contentUrl" controls autoplay preload="metadata" />
    </div>
  </div>
</template>
