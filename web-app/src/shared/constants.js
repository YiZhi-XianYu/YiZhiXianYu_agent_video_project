/**
* 共享常量
*
* 集中管理项目中各处引用的常量值，避免硬编码。
*/
// ============================================================
// Gate 定义 —— 与 Java WorkflowDefinition 中的 Gate 保持一致
// ============================================================
/** 5 个 Gate 的本地定义（gateKey、label、description 与后端同步） */
export const GATE_DEFINITIONS = [
    {
        gateKey: 'gate_shot_ranking',
        label: '镜头排序审核',
        description: '请检查系统对镜头的质量评分和排名。可手动调整评分、强制入选或排除指定镜头。',
    },
    {
        gateKey: 'gate_story_edit',
        label: '故事安排编辑',
        description: '请检查五段式故事安排。可替换、排序、锁定、添加或删除各段落中的镜头。',
    },
    {
        gateKey: 'gate_timeline_preview',
        label: '时间线与音乐预览',
        description: '请预览生成的时间线和背景音乐搭配。确认转场效果和整体节奏。',
    },
    {
        gateKey: 'gate_render_review',
        label: '成片预览与字幕配置',
        description: '请预览无字幕成片。可配置字幕样式（字号、颜色、位置），确认后生成字幕并渲染最终成片。',
    },
    {
        gateKey: 'gate_final_download',
        label: '最终成片下载',
        description: '预览带字幕的最终成片，确认无误后下载。',
    },
];
// ============================================================
// 故事段落（Beat）
// ============================================================
/** 五段式故事角色及其中文名称 */
export const BEAT_ROLES = [
    { role: 'HOOK', label: '吸引注意', description: '开场抓人眼球的高光画面' },
    { role: 'INTRO', label: '简单介绍', description: '介绍场景和人物' },
    { role: 'JOURNEY', label: '展开旅程', description: '旅程的主体内容' },
    { role: 'CLIMAX', label: '到达高潮', description: '最精彩或最感人的片段' },
    { role: 'ENDING', label: '收束结尾', description: '收尾画面' },
];
/** Beat 角色到中文名称的映射 */
export const BEAT_LABEL_MAP = Object.fromEntries(BEAT_ROLES.map((b) => [b.role, b.label]));
// ============================================================
// 清晰度选项
// ============================================================
/** 代理视频清晰度选项 */
export const PROXY_QUALITY_OPTIONS = [
    { value: '4K', label: '4K · 3840×2160' },
    { value: '2K', label: '2K · 2560×1440' },
    { value: '1080P', label: '1080p · 高清' },
    { value: '720P', label: '720p · 标清' },
];
// ============================================================
// RunStatus / TaskStatus 显示映射
// ============================================================
/** RunStatus 中文映射 */
export const RUN_STATUS_LABEL = {
    CREATED: '已创建',
    RUNNING: '运行中',
    PAUSED: '等待审核',
    SUCCEEDED: '已完成',
    FAILED: '失败',
};
/** TaskStatus 中文映射 */
export const TASK_STATUS_LABEL = {
    PENDING: '等待中',
    READY: '就绪',
    DISPATCHING: '派发中',
    RUNNING: '执行中',
    RETRY_WAIT: '等待重试',
    SUCCEEDED: '成功',
    FAILED: '失败',
    SKIPPED: '已跳过',
};
// ============================================================
// 轮询间隔
// ============================================================
/** Workflow 状态轮询间隔（毫秒） */
export const WORKFLOW_POLL_INTERVAL_MS = 1500;
/** 渲染进度轮询间隔（毫秒） */
export const RENDER_POLL_INTERVAL_MS = 3000;
