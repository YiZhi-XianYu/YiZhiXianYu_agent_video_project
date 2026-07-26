import { ref, watch } from 'vue';
import { Diff, X, ArrowRight, Plus, Minus, Edit3, Circle } from 'lucide-vue-next';
import { getVersion, getCurrentPlan } from '@/api/plans';
import { useUiStore } from '@/stores/ui';
const props = defineProps();
const emit = defineEmits();
const uiStore = useUiStore();
const diffs = ref([]);
const loading = ref(false);
const summary = ref({ added: 0, removed: 0, modified: 0, unchanged: 0 });
watch(() => props.planId, (id) => {
    if (id)
        computeDiff();
});
async function computeDiff() {
    if (!props.planId)
        return;
    loading.value = true;
    try {
        const [version, current] = await Promise.all([
            getVersion(props.runId, props.planId),
            getCurrentPlan(props.runId),
        ]);
        diffs.value = comparePlans(version.planData?.beats ?? [], current.planData?.beats ?? []);
        summary.value = {
            added: diffs.value.filter((d) => d.type === 'added').length,
            removed: diffs.value.filter((d) => d.type === 'removed').length,
            modified: diffs.value.filter((d) => d.type === 'modified').length,
            unchanged: diffs.value.filter((d) => d.type === 'unchanged').length,
        };
    }
    catch {
        uiStore.showToast('获取 Diff 数据失败', 'error');
    }
    finally {
        loading.value = false;
    }
}
/** 对比两个 beat 列表，生成 DiffEntry 数组 */
function comparePlans(oldBeats, newBeats) {
    const result = [];
    const oldMap = new Map(oldBeats.map((b) => [b.role, b]));
    const newMap = new Map(newBeats.map((b) => [b.role, b]));
    const allRoles = new Set([...oldMap.keys(), ...newMap.keys()]);
    for (const role of allRoles) {
        const oldShotIds = oldMap.get(role)?.shotIds ?? [];
        const newShotIds = newMap.get(role)?.shotIds ?? [];
        for (const sid of newShotIds) {
            if (!oldShotIds.includes(sid)) {
                result.push({ beatRole: role, type: 'added', shotId: sid, oldPosition: null, newPosition: newShotIds.indexOf(sid) });
            }
            else {
                const oldIdx = oldShotIds.indexOf(sid);
                const newIdx = newShotIds.indexOf(sid);
                result.push({
                    beatRole: role,
                    type: oldIdx === newIdx ? 'unchanged' : 'modified',
                    shotId: sid,
                    oldPosition: oldIdx,
                    newPosition: newIdx,
                });
            }
        }
        for (const sid of oldShotIds) {
            if (!newShotIds.includes(sid)) {
                result.push({ beatRole: role, type: 'removed', shotId: sid, oldPosition: oldShotIds.indexOf(sid), newPosition: null });
            }
        }
    }
    return result;
}
const typeIcons = {
    added: Plus, removed: Minus, modified: Edit3, unchanged: Circle,
};
const typeColors = {
    added: 'text-success', removed: 'text-danger', modified: 'text-warning', unchanged: 'text-surface-500',
};
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (props.planId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card ring-1 ring-accent/30" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between mb-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-sm font-semibold text-surface-200 flex items-center gap-2" },
    });
    const __VLS_0 = {}.Diff;
    /** @type {[typeof __VLS_components.Diff, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ class: "w-4 h-4 text-accent" },
    }));
    const __VLS_2 = __VLS_1({
        ...{ class: "w-4 h-4 text-accent" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-accent" },
    });
    (props.versionName);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(props.planId))
                    return;
                __VLS_ctx.emit('close');
            } },
        ...{ class: "text-surface-500 hover:text-surface-300 transition-colors" },
    });
    const __VLS_4 = {}.X;
    /** @type {[typeof __VLS_components.X, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        ...{ class: "w-4 h-4" },
    }));
    const __VLS_6 = __VLS_5({
        ...{ class: "w-4 h-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    if (__VLS_ctx.diffs.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-4 mb-3 text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success" },
        });
        (__VLS_ctx.summary.added);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-danger" },
        });
        (__VLS_ctx.summary.removed);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-warning" },
        });
        (__VLS_ctx.summary.modified);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-surface-500" },
        });
        (__VLS_ctx.summary.unchanged);
    }
    if (__VLS_ctx.loading) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-center py-6 text-surface-500 text-sm" },
        });
    }
    else if (__VLS_ctx.diffs.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-center py-6 text-surface-500 text-sm" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "space-y-1 max-h-80 overflow-y-auto" },
        });
        for (const [d, i] of __VLS_getVForSourceType((__VLS_ctx.diffs))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                key: (i),
                ...{ class: (['flex items-center gap-2 px-2 py-1.5 rounded text-xs',
                        d.type === 'added' ? 'bg-success/5' :
                            d.type === 'removed' ? 'bg-danger/5' :
                                d.type === 'modified' ? 'bg-warning/5' : '']) },
            });
            const __VLS_8 = ((__VLS_ctx.typeIcons[d.type]));
            // @ts-ignore
            const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
                ...{ class: (['w-3 h-3 shrink-0', __VLS_ctx.typeColors[d.type]]) },
            }));
            const __VLS_10 = __VLS_9({
                ...{ class: (['w-3 h-3 shrink-0', __VLS_ctx.typeColors[d.type]]) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_9));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "w-12 text-surface-500 shrink-0" },
            });
            (d.beatRole);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (['font-mono truncate', __VLS_ctx.typeColors[d.type]]) },
            });
            (d.shotId.slice(0, 16));
            if (d.type === 'modified') {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-surface-600 ml-auto" },
                });
                ((d.oldPosition ?? 0) + 1);
                const __VLS_12 = {}.ArrowRight;
                /** @type {[typeof __VLS_components.ArrowRight, ]} */ ;
                // @ts-ignore
                const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
                    ...{ class: "w-3 h-3 inline mx-0.5" },
                }));
                const __VLS_14 = __VLS_13({
                    ...{ class: "w-3 h-3 inline mx-0.5" },
                }, ...__VLS_functionalComponentArgsRest(__VLS_13));
                ((d.newPosition ?? 0) + 1);
            }
        }
    }
}
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-1']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-accent/30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-200']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-accent']} */ ;
/** @type {__VLS_StyleScopedClasses['text-accent']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-surface-300']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-80']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3']} */ ;
/** @type {__VLS_StyleScopedClasses['h-3']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['w-12']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-600']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3']} */ ;
/** @type {__VLS_StyleScopedClasses['h-3']} */ ;
/** @type {__VLS_StyleScopedClasses['inline']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-0.5']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            Diff: Diff,
            X: X,
            ArrowRight: ArrowRight,
            emit: emit,
            diffs: diffs,
            loading: loading,
            summary: summary,
            typeIcons: typeIcons,
            typeColors: typeColors,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
