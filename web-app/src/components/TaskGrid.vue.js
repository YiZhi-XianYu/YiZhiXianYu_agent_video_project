import { computed } from 'vue';
import { Wrench, AlertTriangle } from 'lucide-vue-next';
import StatusBadge from '@/components/StatusBadge.vue';
import { TASK_STATUS_LABEL } from '@/shared/constants';
const props = defineProps();
/**
 * 工具中文名称映射（部分已知工具）
 * 未知工具显示原始 toolName
 */
const toolLabelMap = {
    'video.probe': '视频信息读取',
    'video.proxy-generate': '生成代理视频',
    'video.shot-detect': '镜头检测',
    'vision.quality-score': '画质评分',
    'vision.vlm-analyze': '语义理解',
    'decision.shot-rank': '镜头排序',
    'planning.story-template': '故事安排',
    'decision.highlight-select': '高光选择',
    'timeline.compose': '时间线编排',
    'audio.bgm-select': '背景音乐选择',
    'video.render': '视频渲染',
    'audio.transcribe-final': '字幕转写',
    'video.render-subtitles': '字幕烧录',
};
/** 按 nodeKey 排序（保持 DAG 拓扑序） */
const sortedTasks = computed(() => {
    const order = [
        'video_probe', 'video_proxy_generate', 'video_shot_detect',
        'vision_quality_score', 'vision_vlm_analyze',
        'shot_ranking', 'story_plan', 'highlight_selection',
        'timeline_compose', 'bgm_select',
        'video_render', 'speech_transcribe',
    ];
    return [...props.tasks].sort((a, b) => {
        const ai = order.indexOf(a.nodeKey);
        const bi = order.indexOf(b.nodeKey);
        return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi);
    });
});
/** 获取工具中文名 */
function getToolLabel(toolName) {
    return toolLabelMap[toolName] ?? toolName;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-center py-8 text-surface-400 text-sm" },
    });
    const __VLS_0 = {}.Wrench;
    /** @type {[typeof __VLS_components.Wrench, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ class: "w-4 h-4 animate-spin mr-2" },
    }));
    const __VLS_2 = __VLS_1({
        ...{ class: "w-4 h-4 animate-spin mr-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
else if (__VLS_ctx.tasks.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-center py-8 text-surface-500 text-sm" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3" },
    });
    for (const [task] of __VLS_getVForSourceType((__VLS_ctx.sortedTasks))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (task.id),
            ...{ class: ([
                    'card transition-all duration-200',
                    task.status === 'RUNNING' ? 'ring-1 ring-accent/40' : '',
                    task.status === 'FAILED' ? 'ring-1 ring-danger/30' : '',
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 mb-2" },
        });
        const __VLS_4 = {}.Wrench;
        /** @type {[typeof __VLS_components.Wrench, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            ...{ class: "w-3.5 h-3.5 text-surface-500 shrink-0" },
        }));
        const __VLS_6 = __VLS_5({
            ...{ class: "w-3.5 h-3.5 text-surface-500 shrink-0" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs font-medium text-surface-300 truncate" },
        });
        (__VLS_ctx.getToolLabel(task.toolName));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between" },
        });
        /** @type {[typeof StatusBadge, ]} */ ;
        // @ts-ignore
        const __VLS_8 = __VLS_asFunctionalComponent(StatusBadge, new StatusBadge({
            status: (task.status),
            labelMap: (__VLS_ctx.TASK_STATUS_LABEL),
        }));
        const __VLS_9 = __VLS_8({
            status: (task.status),
            labelMap: (__VLS_ctx.TASK_STATUS_LABEL),
        }, ...__VLS_functionalComponentArgsRest(__VLS_8));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs text-surface-600" },
        });
        (task.attempt);
        if (task.status === 'FAILED' && task.errorMessage) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "mt-2 flex items-start gap-1.5 text-xs text-danger/80" },
            });
            const __VLS_11 = {}.AlertTriangle;
            /** @type {[typeof __VLS_components.AlertTriangle, ]} */ ;
            // @ts-ignore
            const __VLS_12 = __VLS_asFunctionalComponent(__VLS_11, new __VLS_11({
                ...{ class: "w-3 h-3 shrink-0 mt-0.5" },
            }));
            const __VLS_13 = __VLS_12({
                ...{ class: "w-3 h-3 shrink-0 mt-0.5" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_12));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "line-clamp-2" },
            });
            (task.errorMessage);
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['sm:grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['lg:grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['duration-200']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-3.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-300']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-600']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger/80']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3']} */ ;
/** @type {__VLS_StyleScopedClasses['h-3']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            Wrench: Wrench,
            AlertTriangle: AlertTriangle,
            StatusBadge: StatusBadge,
            TASK_STATUS_LABEL: TASK_STATUS_LABEL,
            sortedTasks: sortedTasks,
            getToolLabel: getToolLabel,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
