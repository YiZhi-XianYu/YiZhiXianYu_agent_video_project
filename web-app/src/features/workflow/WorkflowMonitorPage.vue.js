import { onMounted, onUnmounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, Loader2, CheckCircle2, XCircle, PauseCircle } from 'lucide-vue-next';
import { useWorkflowStore } from '@/stores/workflow';
import { useReviewStore } from '@/stores/review';
import { useUiStore } from '@/stores/ui';
import { usePolling } from '@/shared/composables/usePolling';
import { WORKFLOW_POLL_INTERVAL_MS, RUN_STATUS_LABEL } from '@/shared/constants';
import ProgressBar from '@/components/ProgressBar.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import TaskGrid from '@/components/TaskGrid.vue';
import ShotRankingReview from '@/features/review/ShotRankingReview.vue';
import StoryEditor from '@/features/review/StoryEditor.vue';
import TimelinePreview from '@/features/review/TimelinePreview.vue';
import FinalReview from '@/features/review/FinalReview.vue';
import FinalDownload from '@/features/review/FinalDownload.vue';
const props = defineProps();
const router = useRouter();
const workflowStore = useWorkflowStore();
const reviewStore = useReviewStore();
const uiStore = useUiStore();
// ===================== 分层轮询 =====================
const { start: startPolling, stop: stopPolling } = usePolling(() => workflowStore.fetchRun(props.runId), WORKFLOW_POLL_INTERVAL_MS);
onMounted(async () => {
    workflowStore.clear();
    await workflowStore.fetchRun(props.runId);
    syncGate();
    if (!workflowStore.isTerminal)
        startPolling();
});
watch(() => workflowStore.isTerminal, (terminal) => {
    if (terminal)
        stopPolling();
});
// Gate 变化时同步到 review store
watch(() => workflowStore.currentGate, () => {
    syncGate();
});
onUnmounted(() => {
    stopPolling();
    workflowStore.clear();
    reviewStore.resetAll();
});
// ===================== Gate 同步 =====================
function syncGate() {
    reviewStore.activateGate(workflowStore.currentGate);
}
// ===================== Actions =====================
async function handleContinue() {
    try {
        await workflowStore.continueWorkflow(props.runId);
        startPolling();
    }
    catch {
        // 错误已在 Store 中处理
    }
}
function goBack() {
    router.push(`/projects/${props.projectId}`);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-5xl mx-auto px-6 py-8" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "flex items-center gap-4 mb-8" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.goBack) },
    ...{ class: "\u0077\u002d\u0039\u0020\u0068\u002d\u0039\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u006c\u0067\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0065\u0078\u0074\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0034\u0030\u0030\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0074\u0065\u0078\u0074\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0032\u0030\u0030\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0038\u0030\u0030\u0020\u0074\u0072\u0061\u006e\u0073\u0069\u0074\u0069\u006f\u006e\u002d\u0063\u006f\u006c\u006f\u0072\u0073\u0020\u0073\u0068\u0072\u0069\u006e\u006b\u002d\u0030" },
});
const __VLS_0 = {}.ArrowLeft;
/** @type {[typeof __VLS_components.ArrowLeft, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ class: "w-5 h-5" },
}));
const __VLS_2 = __VLS_1({
    ...{ class: "w-5 h-5" },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "min-w-0 flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "section-eyebrow mb-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
    ...{ class: "text-xl font-bold text-surface-100" },
});
if (__VLS_ctx.workflowStore.status) {
    /** @type {[typeof StatusBadge, ]} */ ;
    // @ts-ignore
    const __VLS_4 = __VLS_asFunctionalComponent(StatusBadge, new StatusBadge({
        status: (__VLS_ctx.workflowStore.status),
        labelMap: (__VLS_ctx.RUN_STATUS_LABEL),
    }));
    const __VLS_5 = __VLS_4({
        status: (__VLS_ctx.workflowStore.status),
        labelMap: (__VLS_ctx.RUN_STATUS_LABEL),
    }, ...__VLS_functionalComponentArgsRest(__VLS_4));
}
if (__VLS_ctx.workflowStore.error) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card border-danger/30 mb-6" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-start gap-3" },
    });
    const __VLS_7 = {}.XCircle;
    /** @type {[typeof __VLS_components.XCircle, ]} */ ;
    // @ts-ignore
    const __VLS_8 = __VLS_asFunctionalComponent(__VLS_7, new __VLS_7({
        ...{ class: "w-5 h-5 text-danger shrink-0 mt-0.5" },
    }));
    const __VLS_9 = __VLS_8({
        ...{ class: "w-5 h-5 text-danger shrink-0 mt-0.5" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_8));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-sm text-danger" },
    });
    (__VLS_ctx.workflowStore.error);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.workflowStore.error))
                    return;
                __VLS_ctx.workflowStore.fetchRun(__VLS_ctx.runId);
            } },
        ...{ class: "btn-secondary mt-2 text-xs" },
    });
}
if (!__VLS_ctx.workflowStore.run && !__VLS_ctx.workflowStore.error) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-center py-16" },
    });
    const __VLS_11 = {}.Loader2;
    /** @type {[typeof __VLS_components.Loader2, ]} */ ;
    // @ts-ignore
    const __VLS_12 = __VLS_asFunctionalComponent(__VLS_11, new __VLS_11({
        ...{ class: "w-6 h-6 animate-spin text-surface-500" },
    }));
    const __VLS_13 = __VLS_12({
        ...{ class: "w-6 h-6 animate-spin text-surface-500" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_12));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "ml-3 text-sm text-surface-400" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card mb-6" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "section-heading" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm text-surface-400" },
    });
    (__VLS_ctx.workflowStore.completedTaskCount);
    (__VLS_ctx.workflowStore.totalTaskCount);
    /** @type {[typeof ProgressBar, ]} */ ;
    // @ts-ignore
    const __VLS_15 = __VLS_asFunctionalComponent(ProgressBar, new ProgressBar({
        percent: (__VLS_ctx.workflowStore.progressPercent),
        variant: (__VLS_ctx.workflowStore.isTerminal ? (__VLS_ctx.workflowStore.status === 'SUCCEEDED' ? 'success' : 'warning') : 'accent'),
    }));
    const __VLS_16 = __VLS_15({
        percent: (__VLS_ctx.workflowStore.progressPercent),
        variant: (__VLS_ctx.workflowStore.isTerminal ? (__VLS_ctx.workflowStore.status === 'SUCCEEDED' ? 'success' : 'warning') : 'accent'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_15));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mb-6" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "section-heading mb-4" },
    });
    /** @type {[typeof TaskGrid, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(TaskGrid, new TaskGrid({
        tasks: (__VLS_ctx.workflowStore.tasks),
    }));
    const __VLS_19 = __VLS_18({
        tasks: (__VLS_ctx.workflowStore.tasks),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_shot_ranking') {
        /** @type {[typeof ShotRankingReview, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(ShotRankingReview, new ShotRankingReview({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_22 = __VLS_21({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        let __VLS_24;
        let __VLS_25;
        let __VLS_26;
        const __VLS_27 = {
            onConfirm: (__VLS_ctx.handleContinue)
        };
        var __VLS_23;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_story_edit') {
        /** @type {[typeof StoryEditor, ]} */ ;
        // @ts-ignore
        const __VLS_28 = __VLS_asFunctionalComponent(StoryEditor, new StoryEditor({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_29 = __VLS_28({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_28));
        let __VLS_31;
        let __VLS_32;
        let __VLS_33;
        const __VLS_34 = {
            onConfirm: (__VLS_ctx.handleContinue)
        };
        var __VLS_30;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_timeline_preview') {
        /** @type {[typeof TimelinePreview, ]} */ ;
        // @ts-ignore
        const __VLS_35 = __VLS_asFunctionalComponent(TimelinePreview, new TimelinePreview({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_36 = __VLS_35({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_35));
        let __VLS_38;
        let __VLS_39;
        let __VLS_40;
        const __VLS_41 = {
            onConfirm: (__VLS_ctx.handleContinue)
        };
        var __VLS_37;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_render_review') {
        /** @type {[typeof FinalReview, ]} */ ;
        // @ts-ignore
        const __VLS_42 = __VLS_asFunctionalComponent(FinalReview, new FinalReview({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_43 = __VLS_42({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        let __VLS_45;
        let __VLS_46;
        let __VLS_47;
        const __VLS_48 = {
            onConfirm: (__VLS_ctx.handleContinue)
        };
        var __VLS_44;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_final_download') {
        /** @type {[typeof FinalDownload, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(FinalDownload, new FinalDownload({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_50 = __VLS_49({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
        let __VLS_52;
        let __VLS_53;
        let __VLS_54;
        const __VLS_55 = {
            onConfirm: (__VLS_ctx.handleContinue)
        };
        var __VLS_51;
    }
    if (__VLS_ctx.workflowStore.isPaused && !__VLS_ctx.workflowStore.currentGate) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card mb-6 ring-1 ring-warning/40" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-4" },
        });
        const __VLS_56 = {}.PauseCircle;
        /** @type {[typeof __VLS_components.PauseCircle, ]} */ ;
        // @ts-ignore
        const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
            ...{ class: "w-6 h-6 text-warning shrink-0" },
        }));
        const __VLS_58 = __VLS_57({
            ...{ class: "w-6 h-6 text-warning shrink-0" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_57));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-sm font-semibold text-warning" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm text-surface-400 mt-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.handleContinue) },
            ...{ class: "btn-primary" },
        });
        const __VLS_60 = {}.CheckCircle2;
        /** @type {[typeof __VLS_components.CheckCircle2, ]} */ ;
        // @ts-ignore
        const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
            ...{ class: "w-4 h-4" },
        }));
        const __VLS_62 = __VLS_61({
            ...{ class: "w-4 h-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_61));
    }
    if (__VLS_ctx.workflowStore.isTerminal) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card mb-6" },
            ...{ class: (__VLS_ctx.workflowStore.status === 'SUCCEEDED' ? 'ring-1 ring-success/30' : 'ring-1 ring-danger/30') },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-4" },
        });
        if (__VLS_ctx.workflowStore.status === 'SUCCEEDED') {
            const __VLS_64 = {}.CheckCircle2;
            /** @type {[typeof __VLS_components.CheckCircle2, ]} */ ;
            // @ts-ignore
            const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
                ...{ class: "w-8 h-8 text-success" },
            }));
            const __VLS_66 = __VLS_65({
                ...{ class: "w-8 h-8 text-success" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_65));
        }
        else {
            const __VLS_68 = {}.XCircle;
            /** @type {[typeof __VLS_components.XCircle, ]} */ ;
            // @ts-ignore
            const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
                ...{ class: "w-8 h-8 text-danger" },
            }));
            const __VLS_70 = __VLS_69({
                ...{ class: "w-8 h-8 text-danger" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_69));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-sm font-semibold text-surface-200" },
        });
        (__VLS_ctx.workflowStore.status === 'SUCCEEDED' ? 'Workflow 执行完成' : 'Workflow 执行失败');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm text-surface-400 mt-1" },
        });
        (__VLS_ctx.workflowStore.status === 'SUCCEEDED'
            ? '所有任务已成功完成，可在项目详情页查看结果。'
            : '部分任务失败，请检查错误信息。');
    }
    if (__VLS_ctx.workflowStore.error) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card border-danger/30 mb-6" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm text-danger" },
        });
        (__VLS_ctx.workflowStore.error);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none" },
});
const __VLS_72 = {}.TransitionGroup;
/** @type {[typeof __VLS_components.TransitionGroup, typeof __VLS_components.transitionGroup, typeof __VLS_components.TransitionGroup, typeof __VLS_components.transitionGroup, ]} */ ;
// @ts-ignore
const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
    name: "fade",
}));
const __VLS_74 = __VLS_73({
    name: "fade",
}, ...__VLS_functionalComponentArgsRest(__VLS_73));
__VLS_75.slots.default;
for (const [toast] of __VLS_getVForSourceType((__VLS_ctx.uiStore.toasts))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        key: (toast.id),
        ...{ class: (['px-4 py-2.5 rounded-lg text-sm shadow-lg pointer-events-auto border',
                toast.type === 'success' ? 'bg-success/20 text-success border-success/30' :
                    toast.type === 'error' ? 'bg-danger/20 text-danger border-danger/30' :
                        toast.type === 'warning' ? 'bg-warning/20 text-warning border-warning/30' :
                            'bg-surface-800 text-surface-200 border-surface-600']) },
    });
    (toast.message);
}
var __VLS_75;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-8']} */ ;
/** @type {__VLS_StyleScopedClasses['w-9']} */ ;
/** @type {__VLS_StyleScopedClasses['h-9']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-surface-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-surface-800']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['w-5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-5']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['section-eyebrow']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-100']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['border-danger/30']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['w-5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-secondary']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-16']} */ ;
/** @type {__VLS_StyleScopedClasses['w-6']} */ ;
/** @type {__VLS_StyleScopedClasses['h-6']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-1']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-warning/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['w-6']} */ ;
/** @type {__VLS_StyleScopedClasses['h-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['w-8']} */ ;
/** @type {__VLS_StyleScopedClasses['h-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['w-8']} */ ;
/** @type {__VLS_StyleScopedClasses['h-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['border-danger/30']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['fixed']} */ ;
/** @type {__VLS_StyleScopedClasses['bottom-6']} */ ;
/** @type {__VLS_StyleScopedClasses['right-6']} */ ;
/** @type {__VLS_StyleScopedClasses['z-50']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pointer-events-none']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['pointer-events-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            ArrowLeft: ArrowLeft,
            Loader2: Loader2,
            CheckCircle2: CheckCircle2,
            XCircle: XCircle,
            PauseCircle: PauseCircle,
            RUN_STATUS_LABEL: RUN_STATUS_LABEL,
            ProgressBar: ProgressBar,
            StatusBadge: StatusBadge,
            TaskGrid: TaskGrid,
            ShotRankingReview: ShotRankingReview,
            StoryEditor: StoryEditor,
            TimelinePreview: TimelinePreview,
            FinalReview: FinalReview,
            FinalDownload: FinalDownload,
            workflowStore: workflowStore,
            uiStore: uiStore,
            handleContinue: handleContinue,
            goBack: goBack,
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
