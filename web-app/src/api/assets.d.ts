import type { Asset } from '@/api/types';
/**
 * 获取项目的素材列表
 */
export declare function listAssets(projectId: string): Promise<Asset[]>;
/**
 * 批量上传素材
 *
 * @param projectId - 项目 ID
 * @param files - 文件列表
 * @param onProgress - 上传进度回调（0-100）
 */
export declare function uploadAssets(projectId: string, files: File[], onProgress?: (percent: number) => void): Promise<Asset[]>;
