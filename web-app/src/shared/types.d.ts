/**
* 共享领域类型
*
* 定义视频制作领域的核心数据结构，跨多个 Feature 使用。
* 与 Python Tool Service 输出的 JSON 结构保持一致。
*/
/** 视频基本信息（video.probe 输出） */
export interface VideoMetadata {
    durationMs: number;
    width: number;
    height: number;
    fps: number;
    videoCodec: string;
    audioCodec: string;
    sizeBytes: number;
}
/** 镜头（video.shot-detect 输出） */
export interface Shot {
    shotId: string;
    assetId: string;
    assetFileName: string;
    startMs: number;
    endMs: number;
    durationMs: number;
    /** 关键帧图片 URL */
    keyframeUrl: string | null;
}
/** 质量评分维度（vision.quality-score 输出） */
export interface QualityScores {
    sharpness: number;
    exposure: number;
    stability: number;
    composition: number;
    motionInterest: number;
    overall: number;
}
/** 语义标签（CLIP 输出） */
export interface SemanticLabels {
    scene: string[];
    object: string[];
    person: string[];
}
/** 镜头评分汇总 */
export interface ShotScore {
    shotId: string;
    quality: QualityScores;
    labels: SemanticLabels;
    /** 排名分数（decision.shot-rank 输出） */
    rankScore: number | null;
    /** 扣分原因 */
    penalties: string[];
    /** 是否入选 */
    selected: boolean;
}
/** 故事段落角色 */
export type BeatRole = 'HOOK' | 'INTRO' | 'JOURNEY' | 'CLIMAX' | 'ENDING';
/** 故事段落 */
export interface StoryBeat {
    role: BeatRole;
    /** 段落内的 shot ID 列表（按顺序排列） */
    shotIds: string[];
    /** 段落目标时长（毫秒） */
    targetDurationMs: number;
}
/** 完整 Story Plan */
export interface StoryPlan {
    workflowRunId: string;
    beats: StoryBeat[];
    totalDurationMs: number;
}
/** 转场类型 */
export type TransitionType = 'CUT' | 'FADE' | 'CROSS_DISSOLVE';
/** Timeline 片段 */
export interface TimelineClip {
    shotId: string;
    sourceInMs: number;
    sourceOutMs: number;
    durationMs: number;
    transition: TransitionType;
    transitionDurationMs: number;
}
/** Timeline */
export interface Timeline {
    clips: TimelineClip[];
    totalDurationMs: number;
    bgmName: string | null;
}
/** Diff 条目 */
export interface DiffEntry {
    beatRole: BeatRole;
    type: 'added' | 'removed' | 'modified' | 'unchanged';
    shotId: string;
    oldPosition: number | null;
    newPosition: number | null;
}
/** 字幕样式配置（Gate 4 配置使用） */
export interface SubtitleStyle {
    fontSize: number;
    fontColor: string;
    position: 'bottom' | 'top';
    outlineColor: string;
}
