 /**
 * HTTP 客户端封装
 *
 * 基于原生 fetch，提供统一的请求/响应处理、错误格式化和超时控制。
 * 所有 API 模块通过此客户端与 Java 后端通信。
 */

 /** 基础 URL —— 开发时由 Vite proxy 转发，生产时同源 */
 const BASE_URL = ''

 /** 默认请求超时（毫秒） */
const DEFAULT_TIMEOUT_MS = 30_000

export function csrfHeaders(): Record<string, string> {
  const cookie = document.cookie
    .split('; ')
    .find((item) => item.startsWith('avp_csrf='))
  if (!cookie) return {}
  return { 'X-CSRF-Token': decodeURIComponent(cookie.substring('avp_csrf='.length)) }
}

 /** API 错误类型 */
 export class ApiError extends Error {
   constructor(
     message: string,
     public readonly status: number,
     public readonly body: unknown,
   ) {
     super(message)
     this.name = 'ApiError'
   }
 }

 /** 请求选项 */
 interface RequestOptions {
   /** 查询参数 */
   params?: Record<string, string | number | boolean | undefined>
   /** 请求超时（毫秒），默认 30 秒 */
   timeoutMs?: number
   /** 自定义请求头 */
   headers?: Record<string, string>
 }

 /**
 * 拼接查询参数到 URL
 */
 function buildUrl(path: string, params?: Record<string, string | number | boolean | undefined>): string {
   const url = new URL(`${BASE_URL}${path}`, window.location.origin)
   if (params) {
     for (const [key, value] of Object.entries(params)) {
       if (value !== undefined && value !== null) {
         url.searchParams.set(key, String(value))
       }
     }
   }
   return url.toString()
 }

 /**
 * 创建带超时的 fetch 请求
 */
 async function fetchWithTimeout(
   input: RequestInfo,
   init: RequestInit,
   timeoutMs: number,
 ): Promise<Response> {
   const controller = new AbortController()
   const timeoutId = setTimeout(() => controller.abort(), timeoutMs)

   try {
     const response = await fetch(input, {
       ...init,
       signal: controller.signal,
     })
     return response
   } catch (error: unknown) {
     if (error instanceof DOMException && error.name === 'AbortError') {
       throw new ApiError('请求超时', 408, null)
     }
     throw error
   } finally {
     clearTimeout(timeoutId)
   }
 }

 /**
 * 解析响应体
 */
 async function parseResponse<T>(response: Response): Promise<T> {
   // 204 No Content
   if (response.status === 204) {
     return null as T
   }

   const contentType = response.headers.get('content-type') || ''

   if (contentType.includes('application/json')) {
     const body = await response.json()
     if (!response.ok) {
       if (response.status === 401 && !response.url.includes('/api/v1/auth/')) {
         window.dispatchEvent(new Event('avp:unauthorized'))
       }
       const message = body?.message || body?.detail || body?.error || `HTTP ${response.status}`
       throw new ApiError(message, response.status, body)
     }
     return body as T
   }

   // 非 JSON 响应
   if (!response.ok) {
     const text = await response.text()
     throw new ApiError(text || `HTTP ${response.status}`, response.status, text)
   }

   const text = await response.text()
   return text as unknown as T
 }

 /**
 * 发送 GET 请求
 */
 export async function get<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
   const url = buildUrl(path, options.params)
   const response = await fetchWithTimeout(
     url,
     {
       method: 'GET',
       headers: {
         'Accept': 'application/json',
         ...options.headers,
       },
     },
     options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
   )
   return parseResponse<T>(response)
 }

 /**
 * 发送 POST 请求
 */
 export async function post<T = unknown>(
   path: string,
   body?: unknown,
   options: RequestOptions = {},
 ): Promise<T> {
   const url = buildUrl(path, options.params)
   const response = await fetchWithTimeout(
     url,
     {
       method: 'POST',
       headers: {
         'Content-Type': 'application/json',
         'Accept': 'application/json',
         ...csrfHeaders(),
         ...options.headers,
       },
       body: body !== undefined ? JSON.stringify(body) : undefined,
     },
     options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
   )
   return parseResponse<T>(response)
 }

 /**
 * 发送 PUT 请求
 */
 export async function put<T = unknown>(
   path: string,
   body?: unknown,
   options: RequestOptions = {},
 ): Promise<T> {
   const url = buildUrl(path, options.params)
   const response = await fetchWithTimeout(
     url,
     {
       method: 'PUT',
       headers: {
         'Content-Type': 'application/json',
         'Accept': 'application/json',
         ...csrfHeaders(),
         ...options.headers,
       },
       body: body !== undefined ? JSON.stringify(body) : undefined,
     },
     options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
   )
   return parseResponse<T>(response)
 }

 /**
 * 发送 DELETE 请求
 */
 export async function del<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
   const url = buildUrl(path, options.params)
   const response = await fetchWithTimeout(
     url,
     {
       method: 'DELETE',
       headers: {
         'Accept': 'application/json',
         ...csrfHeaders(),
         ...options.headers,
       },
     },
     options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
   )
   return parseResponse<T>(response)
 }

 /**
 * 上传文件（multipart/form-data）
 * 用于素材上传等需要 FormData 的场景
 */
 export async function upload<T = unknown>(
   path: string,
   formData: FormData,
   options: RequestOptions = {},
 ): Promise<T> {
   const url = buildUrl(path, options.params)
   // 不设置 Content-Type，由浏览器自动设置 multipart boundary
   const response = await fetchWithTimeout(
     url,
     {
       method: 'POST',
       headers: {
         'Accept': 'application/json',
         ...csrfHeaders(),
         ...options.headers,
       },
       body: formData,
     },
     // 上传超时设置为 5 分钟
     options.timeoutMs ?? 300_000,
   )
   return parseResponse<T>(response)
 }
