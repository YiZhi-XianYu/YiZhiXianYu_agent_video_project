import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import VersionList from '@/features/versions/VersionList.vue';
import VersionDiff from '@/features/versions/VersionDiff.vue';
const __VLS_props = defineProps();
const router = useRouter();
const uiStore = useUiStore();
const diffPlanId = ref(null);
const diffVersionName = ref('');
function handleDiff(planId, versionName) {
    diffPlanId.value = planId;
    diffVersionName.value = versionName;
}
function closeDiff() {
    diffPlanId.value = null;
    diffVersionName.value = '';
}
function handleVersionLoaded(_planId) {
    uiStore.showToast('版本已恢复', 'success');
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-4xl mx-auto px-6 py-8" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "flex items-center gap-4 mb-8" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.router.push(`/projects/${__VLS_ctx.projectId}/runs/${__VLS_ctx.runId}`);
        } },
    ...{ class: "\u0077\u002d\u0039\u0020\u0068\u002d\u0039\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u006c\u0067\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u000d\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0065\u0078\u0074\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0034\u0030\u0030\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0074\u0065\u0078\u0074\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0032\u0030\u0030\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0073\u0075\u0072\u0066\u0061\u0063\u0065\u002d\u0038\u0030\u0030\u0020\u0074\u0072\u0061\u006e\u0073\u0069\u0074\u0069\u006f\u006e\u002d\u0063\u006f\u006c\u006f\u0072\u0073\u0020\u0073\u0068\u0072\u0069\u006e\u006b\u002d\u0030" },
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
    ...{ class: "min-w-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "section-eyebrow mb-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
    ...{ class: "text-xl font-bold text-surface-100" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid gap-6" },
});
/** @type {[typeof VersionList, ]} */ ;
// @ts-ignore
const __VLS_4 = __VLS_asFunctionalComponent(VersionList, new VersionList({
    ...{ 'onDiff': {} },
    ...{ 'onLoaded': {} },
    runId: (__VLS_ctx.runId),
}));
const __VLS_5 = __VLS_4({
    ...{ 'onDiff': {} },
    ...{ 'onLoaded': {} },
    runId: (__VLS_ctx.runId),
}, ...__VLS_functionalComponentArgsRest(__VLS_4));
let __VLS_7;
let __VLS_8;
let __VLS_9;
const __VLS_10 = {
    onDiff: (__VLS_ctx.handleDiff)
};
const __VLS_11 = {
    onLoaded: (__VLS_ctx.handleVersionLoaded)
};
var __VLS_6;
/** @type {[typeof VersionDiff, ]} */ ;
// @ts-ignore
const __VLS_12 = __VLS_asFunctionalComponent(VersionDiff, new VersionDiff({
    ...{ 'onClose': {} },
    runId: (__VLS_ctx.runId),
    planId: (__VLS_ctx.diffPlanId),
    versionName: (__VLS_ctx.diffVersionName),
}));
const __VLS_13 = __VLS_12({
    ...{ 'onClose': {} },
    runId: (__VLS_ctx.runId),
    planId: (__VLS_ctx.diffPlanId),
    versionName: (__VLS_ctx.diffVersionName),
}, ...__VLS_functionalComponentArgsRest(__VLS_12));
let __VLS_15;
let __VLS_16;
let __VLS_17;
const __VLS_18 = {
    onClose: (__VLS_ctx.closeDiff)
};
var __VLS_14;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
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
/** @type {__VLS_StyleScopedClasses['section-eyebrow']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-100']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-6']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            ArrowLeft: ArrowLeft,
            VersionList: VersionList,
            VersionDiff: VersionDiff,
            router: router,
            diffPlanId: diffPlanId,
            diffVersionName: diffVersionName,
            handleDiff: handleDiff,
            closeDiff: closeDiff,
            handleVersionLoaded: handleVersionLoaded,
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
