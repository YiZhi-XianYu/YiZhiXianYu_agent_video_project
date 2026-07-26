/**
* 共享常量
*
* 集中管理项目中各处引用的常量值，避免硬编码。
*/
import type { BeatRole } from '@/shared/types';
import type { GateInfo } from '@/api/types';
/** 5 个 Gate 的本地定义（gateKey、label、description 与后端同步） */
export declare const GATE_DEFINITIONS: GateInfo[];
/** 五段式故事角色及其中文名称 */
export declare const BEAT_ROLES: {
    role: BeatRole;
    label: string;
    description: string;
}[];
/** Beat 角色到中文名称的映射 */
export declare const BEAT_LABEL_MAP: Record<BeatRole, string>;
/** 代理视频清晰度选项 */
export declare const PROXY_QUALITY_OPTIONS: ({
    value: "4K";
    label: string;
} | {
    value: "2K";
    label: string;
} | {
    value: "1080P";
    label: string;
} | {
    value: "720P";
    label: string;
})[];
/** RunStatus 中文映射 */
export declare const RUN_STATUS_LABEL: Record<string, string>;
/** TaskStatus 中文映射 */
export declare const TASK_STATUS_LABEL: Record<string, string>;
/** Workflow 状态轮询间隔（毫秒） */
export declare const WORKFLOW_POLL_INTERVAL_MS = 1500;
/** 渲染进度轮询间隔（毫秒） */
export declare const RENDER_POLL_INTERVAL_MS = 3000;
