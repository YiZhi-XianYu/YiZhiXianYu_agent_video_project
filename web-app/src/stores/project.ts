 /**
 * 项目状态管理
 *
 * 管理项目列表、当前选中项目。组件卸载后状态保留在 Store 中，
 * 避免页面切换时重复请求。
 */
 import { ref, computed } from 'vue'
 import { defineStore } from 'pinia'
 import * as api from '@/api/client'
 import type { Project, Asset } from '@/api/types'

 export const useProjectStore = defineStore('project', () => {
   // ============================================================
   // State
   // ============================================================

   /** 项目列表 */
   const projects = ref<Project[]>([])

   /** 当前选中的项目 ID */
   const currentProjectId = ref<string | null>(null)

   /** 当前项目的素材列表 */
   const assets = ref<Asset[]>([])

   /** 列表加载状态 */
   const loading = ref(false)

   /** 错误信息 */
   const error = ref<string | null>(null)

   // ============================================================
   // Getters
   // ============================================================

   /** 当前项目 */
   const currentProject = computed<Project | null>(() => {
     if (!currentProjectId.value) return null
     return projects.value.find((p) => p.id === currentProjectId.value) ?? null
   })

   /** 素材数量 */
   const assetCount = computed(() => assets.value.length)

   // ============================================================
   // Actions
   // ============================================================

   /** 加载项目列表 */
   async function fetchProjects(): Promise<void> {
     loading.value = true
     error.value = null
     try {
       projects.value = await api.get<Project[]>('/api/v1/projects')
     } catch (e: unknown) {
       error.value = e instanceof Error ? e.message : '获取项目列表失败'
     } finally {
       loading.value = false
     }
   }

   /** 创建项目 */
   async function createProject(name: string): Promise<Project> {
     error.value = null
     const project = await api.post<Project>('/api/v1/projects', { name })
     projects.value.unshift(project)
     return project
   }

   /** 加载项目素材 */
   async function fetchAssets(projectId: string): Promise<void> {
     error.value = null
     try {
       assets.value = await api.get<Asset[]>(`/api/v1/projects/${projectId}/assets`)
     } catch (e: unknown) {
       error.value = e instanceof Error ? e.message : '获取素材列表失败'
     }
   }

   /** 设置当前项目 */
   function setCurrentProject(projectId: string | null): void {
     currentProjectId.value = projectId
     if (!projectId) {
       assets.value = []
     }
   }

   /** 清除错误 */
   function clearError(): void {
     error.value = null
   }

   return {
     // state
     projects,
     currentProjectId,
     assets,
     loading,
     error,
     // getters
     currentProject,
     assetCount,
     // actions
     fetchProjects,
     createProject,
     fetchAssets,
     setCurrentProject,
     clearError,
   }
 })
