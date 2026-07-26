 /**
  * 素材 API 模块
  *
  * 封装素材上传和列表查询的 HTTP 请求。
  */
 import { get } from '@/api/client'
 import type { Asset } from '@/api/types'

 /**
  * 获取项目的素材列表
  */
 export async function listAssets(projectId: string): Promise<Asset[]> {
   return get<Asset[]>(`/api/v1/projects/${projectId}/assets`)
 }

 /**
  * 批量上传素材
  *
  * @param projectId - 项目 ID
  * @param files - 文件列表
  * @param onProgress - 上传进度回调（0-100）
  */
 export async function uploadAssets(
   projectId: string,
   files: File[],
   onProgress?: (percent: number) => void,
 ): Promise<Asset[]> {
   const formData = new FormData()
   // 多文件追加到同一个 FormData（Java 端按 "files" 字段接收）
   for (const file of files) {
     formData.append('files', file)
   }

   // 使用 XMLHttpRequest 实现进度监听
   return new Promise((resolve, reject) => {
     const xhr = new XMLHttpRequest()

     xhr.upload.addEventListener('progress', (e) => {
       if (e.lengthComputable && onProgress) {
         onProgress(Math.round((e.loaded / e.total) * 100))
       }
     })

     xhr.addEventListener('load', () => {
       if (xhr.status >= 200 && xhr.status < 300) {
         try {
           resolve(JSON.parse(xhr.responseText))
         } catch {
           reject(new Error('解析上传响应失败'))
         }
       } else {
         reject(new Error(`上传失败: HTTP ${xhr.status}`))
       }
     })

     xhr.addEventListener('error', () => reject(new Error('网络错误，上传中断')))
     xhr.addEventListener('abort', () => reject(new Error('上传已取消')))

     xhr.open('POST', `/api/v1/projects/${projectId}/assets/batch`)
     xhr.send(formData)
   })
 }
