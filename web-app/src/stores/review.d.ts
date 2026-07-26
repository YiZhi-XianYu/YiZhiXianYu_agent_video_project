import type { ShotScore, StoryPlan, Timeline, SubtitleStyle } from '@/shared/types';
import type { GateInfo } from '@/api/types';
export declare const useReviewStore: import("pinia").StoreDefinition<"review", Pick<{
    currentGate: import("vue").Ref<{
        gateKey: string;
        label: string;
        description: string;
    } | null, GateInfo | {
        gateKey: string;
        label: string;
        description: string;
    } | null>;
    shotScores: import("vue").Ref<{
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[], ShotScore[] | {
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[]>;
    excludedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    forcedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    storyPlan: import("vue").Ref<{
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null, StoryPlan | {
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null>;
    lockedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    timeline: import("vue").Ref<{
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null, Timeline | {
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null>;
    renderedVideoUrl: import("vue").Ref<string | null, string | null>;
    subtitleStyle: import("vue").Ref<{
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }, SubtitleStyle | {
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }>;
    finalVideoUrl: import("vue").Ref<string | null, string | null>;
    dirty: import("vue").Ref<boolean, boolean>;
    activateGate: (gate: GateInfo | null) => void;
    resetAll: () => void;
    setShotScores: (scores: ShotScore[]) => void;
    setStoryPlan: (plan: StoryPlan) => void;
    setTimeline: (tl: Timeline) => void;
    setRenderedVideo: (url: string) => void;
    setFinalVideo: (url: string) => void;
    toggleForced: (shotId: string) => void;
    toggleExcluded: (shotId: string) => void;
    toggleLockShot: (shotId: string) => void;
    updateSubtitleStyle: (style: Partial<SubtitleStyle>) => void;
}, "currentGate" | "shotScores" | "excludedShotIds" | "forcedShotIds" | "storyPlan" | "lockedShotIds" | "timeline" | "renderedVideoUrl" | "subtitleStyle" | "finalVideoUrl" | "dirty">, Pick<{
    currentGate: import("vue").Ref<{
        gateKey: string;
        label: string;
        description: string;
    } | null, GateInfo | {
        gateKey: string;
        label: string;
        description: string;
    } | null>;
    shotScores: import("vue").Ref<{
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[], ShotScore[] | {
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[]>;
    excludedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    forcedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    storyPlan: import("vue").Ref<{
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null, StoryPlan | {
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null>;
    lockedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    timeline: import("vue").Ref<{
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null, Timeline | {
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null>;
    renderedVideoUrl: import("vue").Ref<string | null, string | null>;
    subtitleStyle: import("vue").Ref<{
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }, SubtitleStyle | {
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }>;
    finalVideoUrl: import("vue").Ref<string | null, string | null>;
    dirty: import("vue").Ref<boolean, boolean>;
    activateGate: (gate: GateInfo | null) => void;
    resetAll: () => void;
    setShotScores: (scores: ShotScore[]) => void;
    setStoryPlan: (plan: StoryPlan) => void;
    setTimeline: (tl: Timeline) => void;
    setRenderedVideo: (url: string) => void;
    setFinalVideo: (url: string) => void;
    toggleForced: (shotId: string) => void;
    toggleExcluded: (shotId: string) => void;
    toggleLockShot: (shotId: string) => void;
    updateSubtitleStyle: (style: Partial<SubtitleStyle>) => void;
}, never>, Pick<{
    currentGate: import("vue").Ref<{
        gateKey: string;
        label: string;
        description: string;
    } | null, GateInfo | {
        gateKey: string;
        label: string;
        description: string;
    } | null>;
    shotScores: import("vue").Ref<{
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[], ShotScore[] | {
        shotId: string;
        quality: {
            sharpness: number;
            exposure: number;
            stability: number;
            composition: number;
            motionInterest: number;
            overall: number;
        };
        labels: {
            scene: string[];
            object: string[];
            person: string[];
        };
        rankScore: number | null;
        penalties: string[];
        selected: boolean;
    }[]>;
    excludedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    forcedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    storyPlan: import("vue").Ref<{
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null, StoryPlan | {
        workflowRunId: string;
        beats: {
            role: import("@/shared/types").BeatRole;
            shotIds: string[];
            targetDurationMs: number;
        }[];
        totalDurationMs: number;
    } | null>;
    lockedShotIds: import("vue").Ref<Set<string> & Omit<Set<string>, keyof Set<any>>, Set<string> | (Set<string> & Omit<Set<string>, keyof Set<any>>)>;
    timeline: import("vue").Ref<{
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null, Timeline | {
        clips: {
            shotId: string;
            sourceInMs: number;
            sourceOutMs: number;
            durationMs: number;
            transition: import("@/shared/types").TransitionType;
            transitionDurationMs: number;
        }[];
        totalDurationMs: number;
        bgmName: string | null;
    } | null>;
    renderedVideoUrl: import("vue").Ref<string | null, string | null>;
    subtitleStyle: import("vue").Ref<{
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }, SubtitleStyle | {
        fontSize: number;
        fontColor: string;
        position: "bottom" | "top";
        outlineColor: string;
    }>;
    finalVideoUrl: import("vue").Ref<string | null, string | null>;
    dirty: import("vue").Ref<boolean, boolean>;
    activateGate: (gate: GateInfo | null) => void;
    resetAll: () => void;
    setShotScores: (scores: ShotScore[]) => void;
    setStoryPlan: (plan: StoryPlan) => void;
    setTimeline: (tl: Timeline) => void;
    setRenderedVideo: (url: string) => void;
    setFinalVideo: (url: string) => void;
    toggleForced: (shotId: string) => void;
    toggleExcluded: (shotId: string) => void;
    toggleLockShot: (shotId: string) => void;
    updateSubtitleStyle: (style: Partial<SubtitleStyle>) => void;
}, "activateGate" | "resetAll" | "setShotScores" | "setStoryPlan" | "setTimeline" | "setRenderedVideo" | "setFinalVideo" | "toggleForced" | "toggleExcluded" | "toggleLockShot" | "updateSubtitleStyle">>;
