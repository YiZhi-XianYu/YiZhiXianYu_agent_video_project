import { computed } from 'vue';
const props = withDefaults(defineProps(), {
    percent: -1,
    variant: 'accent',
    size: 'md',
});
const isIndeterminate = computed(() => props.percent < 0);
const clampedPercent = computed(() => Math.max(0, Math.min(100, props.percent ?? 0)));
const variantColors = {
    accent: 'bg-accent',
    success: 'bg-success',
    warning: 'bg-warning',
};
const sizes = {
    sm: 'h-1',
    md: 'h-1.5',
};
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    percent: -1,
    variant: 'accent',
    size: 'md',
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['w-full rounded-full bg-surface-700 overflow-hidden', __VLS_ctx.sizes[__VLS_ctx.size]]) },
});
if (!__VLS_ctx.isIndeterminate) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: (['h-full rounded-full transition-all duration-500 ease-out', __VLS_ctx.variantColors[__VLS_ctx.variant]]) },
        ...{ style: ({ width: __VLS_ctx.clampedPercent + '%' }) },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: (['h-full rounded-full animate-pulse-slow w-1/3', __VLS_ctx.variantColors[__VLS_ctx.variant]]) },
    });
}
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-surface-700']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['duration-500']} */ ;
/** @type {__VLS_StyleScopedClasses['ease-out']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse-slow']} */ ;
/** @type {__VLS_StyleScopedClasses['w-1/3']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            isIndeterminate: isIndeterminate,
            clampedPercent: clampedPercent,
            variantColors: variantColors,
            sizes: sizes,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
