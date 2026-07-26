import { computed } from 'vue';
const props = defineProps();
const colorSchemes = {
    gray: 'bg-surface-700 text-surface-300',
    blue: 'bg-accent/20 text-accent-light',
    green: 'bg-success/20 text-success',
    yellow: 'bg-warning/20 text-warning',
    red: 'bg-danger/20 text-danger',
};
/** 根据状态确定颜色 */
const scheme = computed(() => {
    const s = props.status;
    if (s === 'RUNNING' || s === 'DISPATCHING')
        return 'blue';
    if (s === 'SUCCEEDED')
        return 'green';
    if (s === 'PAUSED')
        return 'yellow';
    if (s === 'FAILED')
        return 'red';
    return 'gray';
});
const label = computed(() => {
    if (props.labelMap && props.status in props.labelMap) {
        return props.labelMap[props.status];
    }
    return props.status;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: (['inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', __VLS_ctx.colorSchemes[__VLS_ctx.scheme]]) },
});
(__VLS_ctx.label);
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            colorSchemes: colorSchemes,
            scheme: scheme,
            label: label,
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
