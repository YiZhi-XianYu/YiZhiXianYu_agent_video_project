import type { CustomStoryPlan, VersionListItem } from '@/api/types';
/** 获取版本列表 */
export declare function listVersions(runId: string): Promise<VersionListItem[]>;
/** 获取指定版本详情 */
export declare function getVersion(runId: string, planId: string): Promise<CustomStoryPlan>;
/** 获取当前 Story Plan */
export declare function getCurrentPlan(runId: string): Promise<CustomStoryPlan>;
/** 保存 Story Plan（含版本名） */
export declare function savePlan(runId: string, planData: unknown, versionName?: string): Promise<CustomStoryPlan>;
/** 恢复指定版本 */
export declare function restoreVersion(runId: string, planId: string): Promise<CustomStoryPlan>;
/** 删除指定版本 */
export declare function deleteVersion(runId: string, planId: string): Promise<void>;
