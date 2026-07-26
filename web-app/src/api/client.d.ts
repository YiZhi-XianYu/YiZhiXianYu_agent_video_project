/**
* HTTP 客户端封装
*
* 基于原生 fetch，提供统一的请求/响应处理、错误格式化和超时控制。
* 所有 API 模块通过此客户端与 Java 后端通信。
*/
/** API 错误类型 */
export declare class ApiError extends Error {
    readonly status: number;
    readonly body: unknown;
    constructor(message: string, status: number, body: unknown);
}
/** 请求选项 */
interface RequestOptions {
    /** 查询参数 */
    params?: Record<string, string | number | boolean | undefined>;
    /** 请求超时（毫秒），默认 30 秒 */
    timeoutMs?: number;
    /** 自定义请求头 */
    headers?: Record<string, string>;
}
/**
* 发送 GET 请求
*/
export declare function get<T = unknown>(path: string, options?: RequestOptions): Promise<T>;
/**
* 发送 POST 请求
*/
export declare function post<T = unknown>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
/**
* 发送 PUT 请求
*/
export declare function put<T = unknown>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
/**
* 发送 DELETE 请求
*/
export declare function del<T = unknown>(path: string, options?: RequestOptions): Promise<T>;
/**
* 上传文件（multipart/form-data）
* 用于素材上传等需要 FormData 的场景
*/
export declare function upload<T = unknown>(path: string, formData: FormData, options?: RequestOptions): Promise<T>;
export {};
