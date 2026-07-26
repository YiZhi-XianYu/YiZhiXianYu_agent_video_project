/**
* HTTP 客户端封装
*
* 基于原生 fetch，提供统一的请求/响应处理、错误格式化和超时控制。
* 所有 API 模块通过此客户端与 Java 后端通信。
*/
/** 基础 URL —— 开发时由 Vite proxy 转发，生产时同源 */
const BASE_URL = '';
/** 默认请求超时（毫秒） */
const DEFAULT_TIMEOUT_MS = 30000;
/** API 错误类型 */
export class ApiError extends Error {
    constructor(message, status, body) {
        super(message);
        Object.defineProperty(this, "status", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: status
        });
        Object.defineProperty(this, "body", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: body
        });
        this.name = 'ApiError';
    }
}
/**
* 拼接查询参数到 URL
*/
function buildUrl(path, params) {
    const url = new URL(`${BASE_URL}${path}`, window.location.origin);
    if (params) {
        for (const [key, value] of Object.entries(params)) {
            if (value !== undefined && value !== null) {
                url.searchParams.set(key, String(value));
            }
        }
    }
    return url.toString();
}
/**
* 创建带超时的 fetch 请求
*/
async function fetchWithTimeout(input, init, timeoutMs) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const response = await fetch(input, {
            ...init,
            signal: controller.signal,
        });
        return response;
    }
    catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
            throw new ApiError('请求超时', 408, null);
        }
        throw error;
    }
    finally {
        clearTimeout(timeoutId);
    }
}
/**
* 解析响应体
*/
async function parseResponse(response) {
    // 204 No Content
    if (response.status === 204) {
        return null;
    }
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
        const body = await response.json();
        if (!response.ok) {
            const message = body?.message || body?.detail || body?.error || `HTTP ${response.status}`;
            throw new ApiError(message, response.status, body);
        }
        return body;
    }
    // 非 JSON 响应
    if (!response.ok) {
        const text = await response.text();
        throw new ApiError(text || `HTTP ${response.status}`, response.status, text);
    }
    const text = await response.text();
    return text;
}
/**
* 发送 GET 请求
*/
export async function get(path, options = {}) {
    const url = buildUrl(path, options.params);
    const response = await fetchWithTimeout(url, {
        method: 'GET',
        headers: {
            'Accept': 'application/json',
            ...options.headers,
        },
    }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    return parseResponse(response);
}
/**
* 发送 POST 请求
*/
export async function post(path, body, options = {}) {
    const url = buildUrl(path, options.params);
    const response = await fetchWithTimeout(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            ...options.headers,
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
    }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    return parseResponse(response);
}
/**
* 发送 PUT 请求
*/
export async function put(path, body, options = {}) {
    const url = buildUrl(path, options.params);
    const response = await fetchWithTimeout(url, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            ...options.headers,
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
    }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    return parseResponse(response);
}
/**
* 发送 DELETE 请求
*/
export async function del(path, options = {}) {
    const url = buildUrl(path, options.params);
    const response = await fetchWithTimeout(url, {
        method: 'DELETE',
        headers: {
            'Accept': 'application/json',
            ...options.headers,
        },
    }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    return parseResponse(response);
}
/**
* 上传文件（multipart/form-data）
* 用于素材上传等需要 FormData 的场景
*/
export async function upload(path, formData, options = {}) {
    const url = buildUrl(path, options.params);
    // 不设置 Content-Type，由浏览器自动设置 multipart boundary
    const response = await fetchWithTimeout(url, {
        method: 'POST',
        headers: {
            'Accept': 'application/json',
            ...options.headers,
        },
        body: formData,
    }, 
    // 上传超时设置为 5 分钟
    options.timeoutMs ?? 300000);
    return parseResponse(response);
}
