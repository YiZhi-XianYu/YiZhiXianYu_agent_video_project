 <script setup lang="ts">
 /**
  * 素材上传组件
  *
  * 支持拖拽上传和点击选择，批量选取视频文件。
  * 上传过程中显示实时进度，上传完成后自动刷新素材列表。
  */
 import { ref, computed } from 'vue'
 import { Upload, FileVideo, X, Loader2, CheckCircle2 } from 'lucide-vue-next'
 import { uploadAssets } from '@/api/assets'
 import { useProjectStore } from '@/stores/project'
 import { useUiStore } from '@/stores/ui'

 const props = defineProps<{
   /** 当前项目 ID */
   projectId: string
 }>()

 const emit = defineEmits<{
   /** 上传完成后通知父组件刷新 */
   uploaded: [assets: import('@/api/types').Asset[]]
 }>()

 const projectStore = useProjectStore()
 const uiStore = useUiStore()

 // ===================== State =====================

 /** 拖拽悬停状态 */
 const isDragging = ref(false)

 /** 已选择的待上传文件 */
 const pendingFiles = ref<File[]>([])

 /** 上传中 */
 const uploading = ref(false)

 /** 上传进度百分比 */
 const uploadProgress = ref(0)

 /** 上传是否完成 */
 const uploadDone = ref(false)

 // ===================== Computed =====================

 /** 待上传文件总大小（可读格式） */
 const totalSize = computed(() => {
   const bytes = pendingFiles.value.reduce((sum, f) => sum + f.size, 0)
   return formatBytes(bytes)
 })

 /** 文件类型校验 —— 仅允许视频 */
 const validFiles = computed(() =>
   pendingFiles.value.every((f) => f.type.startsWith('video/')),
 )

 // ===================== Methods =====================

 /** 格式化字节为可读字符串 */
 function formatBytes(bytes: number): string {
   if (bytes === 0) return '0 B'
   const units = ['B', 'KB', 'MB', 'GB', 'TB']
   const i = Math.floor(Math.log(bytes) / Math.log(1024))
   const size = (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)
   return `${size} ${units[i]}`
 }

 /**
  * 处理文件选择（来自 input change 或拖拽 drop）
  */
 function handleFiles(fileList: FileList | null): void {
   if (!fileList || fileList.length === 0) return
   const files = Array.from(fileList).filter((f) => f.type.startsWith('video/') || f.type === '' || !f.type)
   if (files.length === 0) {
     uiStore.showToast('请选择视频文件', 'warning')
     return
   }
   pendingFiles.value = [...pendingFiles.value, ...files]
   uploadDone.value = false
   uploadProgress.value = 0
 }

 /** 移除待上传列表中的文件 */
 function removeFile(index: number): void {
   pendingFiles.value.splice(index, 1)
 }

 /** 执行上传 */
 async function startUpload(): Promise<void> {
   if (pendingFiles.value.length === 0) return
   uploading.value = true
   uploadDone.value = false
   uploadProgress.value = 0

   try {
     const assets = await uploadAssets(
       props.projectId,
       pendingFiles.value,
       (pct) => { uploadProgress.value = pct },
     )
     uploadDone.value = true
     uiStore.showToast(`${assets.length} 个素材上传成功`, 'success')
     pendingFiles.value = []
     // 刷新项目素材列表
     await projectStore.fetchAssets(props.projectId)
     emit('uploaded', assets)
   } catch (e: unknown) {
     const msg = e instanceof Error ? e.message : '上传失败'
     uiStore.showToast(msg, 'error')
   } finally {
     uploading.value = false
   }
 }

 // ===================== 拖拽事件处理 =====================

 function onDragOver(e: DragEvent): void {
   e.preventDefault()
   isDragging.value = true
 }

 function onDragLeave(e: DragEvent): void {
   e.preventDefault()
   isDragging.value = false
 }

 function onDrop(e: DragEvent): void {
   e.preventDefault()
   isDragging.value = false
   handleFiles(e.dataTransfer?.files ?? null)
 }
</script>

<template>
  <div class="card">
    <h2 class="section-heading mb-4 flex items-center gap-2">
      <FileVideo class="w-5 h-5 text-accent" />
      上传素材
    </h2>

    <!-- 拖拽上传区域 -->
    <div
      :class="[
        'border-2 border-dashed rounded-xl p-8 text-center transition-all duration-200 cursor-pointer',
        isDragging
          ? 'border-accent bg-accent/5'
          : 'border-surface-600 hover:border-surface-400 bg-surface-800/50',
      ]"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
      @click="($refs.fileInput as HTMLInputElement).click()"
    >
      <Upload
        :class="['w-10 h-10 mx-auto mb-3 transition-colors', isDragging ? 'text-accent' : 'text-surface-500']"
      />
      <p class="text-sm text-surface-300 mb-1">
        拖拽视频文件到此处，或点击选择
      </p>
      <p class="text-xs text-surface-500">
        支持 MP4、MOV、AVI 等常见视频格式
      </p>
      <input
        ref="fileInput"
        type="file"
        accept="video/*"
        multiple
        class="hidden"
        @change="handleFiles(($event.target as HTMLInputElement).files)"
      />
    </div>

    <!-- 已选文件列表 -->
    <div v-if="pendingFiles.length > 0" class="mt-4 space-y-2">
      <div class="flex items-center justify-between text-xs text-surface-400 mb-2">
        <span>已选 {{ pendingFiles.length }} 个文件，共 {{ totalSize }}</span>
        <button
          v-if="pendingFiles.length > 0"
          class="text-surface-500 hover:text-surface-300 text-xs transition-colors"
          @click="pendingFiles = []"
        >
          清空
        </button>
      </div>

      <!-- 文件列表 -->
      <div class="max-h-48 overflow-y-auto space-y-1">
        <div
          v-for="(file, index) in pendingFiles"
          :key="file.name + file.size"
          class="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-700/50 text-sm"
        >
          <FileVideo class="w-4 h-4 text-surface-500 shrink-0" />
          <span class="text-surface-200 truncate flex-1">{{ file.name }}</span>
          <span class="text-surface-500 text-xs shrink-0">{{ formatBytes(file.size) }}</span>
          <button
            class="text-surface-500 hover:text-danger transition-colors p-0.5"
            :disabled="uploading"
            @click.stop="removeFile(index)"
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <!-- 上传进度条 -->
      <div v-if="uploading || uploadDone" class="mt-3">
        <div class="flex items-center justify-between text-xs mb-1">
          <span class="text-surface-400">
            {{ uploadDone ? '上传完成' : '上传中...' }}
          </span>
          <span class="text-accent">{{ uploadProgress }}%</span>
        </div>
        <div class="h-1.5 rounded-full bg-surface-700 overflow-hidden">
          <div
            :class="[
              'h-full rounded-full transition-all duration-300',
              uploadDone ? 'bg-success' : 'bg-accent',
            ]"
            :style="{ width: uploadProgress + '%' }"
          />
        </div>
      </div>

      <!-- 上传按钮 -->
      <button
        class="btn-primary w-full mt-3"
        :disabled="uploading || !validFiles"
        @click="startUpload"
      >
        <Loader2 v-if="uploading" class="w-4 h-4 animate-spin" />
        <CheckCircle2 v-else-if="uploadDone" class="w-4 h-4" />
        <Upload v-else class="w-4 h-4" />
        {{ uploading ? '上传中...' : uploadDone ? '继续上传' : `上传 ${pendingFiles.length} 个文件` }}
      </button>
    </div>
  </div>
</template>
