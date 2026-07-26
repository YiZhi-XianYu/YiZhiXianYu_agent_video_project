import { ref, computed } from 'vue';
import { Upload, FileVideo, X, Loader2, CheckCircle2 } from 'lucide-vue-next';
import { uploadAssets } from '@/api/assets';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
const props = defineProps();
const emit = defineEmits();
const projectStore = useProjectStore();
const uiStore = useUiStore();
// ===================== State =====================
/** 拖拽悬停状态 */
const isDragging = ref(false);
/** 已选择的待上传文件 */
const pendingFiles = ref([]);
/** 上传中 */
const uploading = ref(false);
/** 上传进度百分比 */
const uploadProgress = ref(0);
/** 上传是否完成 */
const uploadDone = ref(false);
// ===================== Computed =====================
/** 待上传文件总大小（可读格式） */
const totalSize = computed(() => {
    const bytes = pendingFiles.value.reduce((sum, f) => sum + f.size, 0);
    return formatBytes(bytes);
});
/** 文件类型校验 —— 仅允许视频 */
const validFiles = computed(() => pendingFiles.value.every((f) => f.type.startsWith('video/')));
// ===================== Methods =====================
/** 格式化字节为可读字符串 */
function formatBytes(bytes) {
    if (bytes === 0)
        return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    const size = (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0);
    return `${size} ${units[i]}`;
}
/**
 * 处理文件选择（来自 input change 或拖拽 drop）
 */
function handleFiles(fileList) {
    if (!fileList || fileList.length === 0)
        return;
    const files = Array.from(fileList).filter((f) => f.type.startsWith('video/') || f.type === '' || !f.type);
    if (files.length === 0) {
        uiStore.showToast('请选择视频文件', 'warning');
        return;
    }
    pendingFiles.value = [...pendingFiles.value, ...files];
    uploadDone.value = false;
    uploadProgress.value = 0;
}
/** 移除待上传列表中的文件 */
function removeFile(index) {
    pendingFiles.value.splice(index, 1);
}
/** 执行上传 */
async function startUpload() {
    if (pendingFiles.value.length === 0)
        return;
    uploading.value = true;
    uploadDone.value = false;
    uploadProgress.value = 0;
    try {
        const assets = await uploadAssets(props.projectId, pendingFiles.value, (pct) => { uploadProgress.value = pct; });
        uploadDone.value = true;
        uiStore.showToast(`${assets.length} 个素材上传成功`, 'success');
        pendingFiles.value = [];
        // 刷新项目素材列表
        await projectStore.fetchAssets(props.projectId);
        emit('uploaded', assets);
    }
    catch (e) {
        const msg = e instanceof Error ? e.message : '上传失败';
        uiStore.showToast(msg, 'error');
    }
    finally {
        uploading.value = false;
    }
}
// ===================== 拖拽事件处理 =====================
function onDragOver(e) {
    e.preventDefault();
    isDragging.value = true;
}
function onDragLeave(e) {
    e.preventDefault();
    isDragging.value = false;
}
function onDrop(e) {
    e.preventDefault();
    isDragging.value = false;
    handleFiles(e.dataTransfer?.files ?? null);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "card" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
    ...{ class: "section-heading mb-4 flex items-center gap-2" },
});
const __VLS_0 = {}.FileVideo;
/** @type {[typeof __VLS_components.FileVideo, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ class: "w-5 h-5 text-accent" },
}));
const __VLS_2 = __VLS_1({
    ...{ class: "w-5 h-5 text-accent" },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onDragover: (__VLS_ctx.onDragOver) },
    ...{ onDragleave: (__VLS_ctx.onDragLeave) },
    ...{ onDrop: (__VLS_ctx.onDrop) },
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.$refs.fileInput.click();
        } },
    ...{ class: ([
            'border-2 border-dashed rounded-xl p-8 text-center transition-all duration-200 cursor-pointer',
            __VLS_ctx.isDragging
                ? 'border-accent bg-accent/5'
                : 'border-surface-600 hover:border-surface-400 bg-surface-800/50',
        ]) },
});
const __VLS_4 = {}.Upload;
/** @type {[typeof __VLS_components.Upload, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    ...{ class: (['w-10 h-10 mx-auto mb-3 transition-colors', __VLS_ctx.isDragging ? 'text-accent' : 'text-surface-500']) },
}));
const __VLS_6 = __VLS_5({
    ...{ class: (['w-10 h-10 mx-auto mb-3 transition-colors', __VLS_ctx.isDragging ? 'text-accent' : 'text-surface-500']) },
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm text-surface-300 mb-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs text-surface-500" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onChange: (...[$event]) => {
            __VLS_ctx.handleFiles($event.target.files);
        } },
    ref: "fileInput",
    type: "file",
    accept: "video/*",
    multiple: true,
    ...{ class: "hidden" },
});
/** @type {typeof __VLS_ctx.fileInput} */ ;
if (__VLS_ctx.pendingFiles.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-4 space-y-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between text-xs text-surface-400 mb-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.pendingFiles.length);
    (__VLS_ctx.totalSize);
    if (__VLS_ctx.pendingFiles.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.pendingFiles.length > 0))
                        return;
                    if (!(__VLS_ctx.pendingFiles.length > 0))
                        return;
                    __VLS_ctx.pendingFiles = [];
                } },
            ...{ class: "text-surface-500 hover:text-surface-300 text-xs transition-colors" },
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-h-48 overflow-y-auto space-y-1" },
    });
    for (const [file, index] of __VLS_getVForSourceType((__VLS_ctx.pendingFiles))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (file.name + file.size),
            ...{ class: "flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-700/50 text-sm" },
        });
        const __VLS_8 = {}.FileVideo;
        /** @type {[typeof __VLS_components.FileVideo, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            ...{ class: "w-4 h-4 text-surface-500 shrink-0" },
        }));
        const __VLS_10 = __VLS_9({
            ...{ class: "w-4 h-4 text-surface-500 shrink-0" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-surface-200 truncate flex-1" },
        });
        (file.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-surface-500 text-xs shrink-0" },
        });
        (__VLS_ctx.formatBytes(file.size));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.pendingFiles.length > 0))
                        return;
                    __VLS_ctx.removeFile(index);
                } },
            ...{ class: "text-surface-500 hover:text-danger transition-colors p-0.5" },
            disabled: (__VLS_ctx.uploading),
        });
        const __VLS_12 = {}.X;
        /** @type {[typeof __VLS_components.X, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            ...{ class: "w-3.5 h-3.5" },
        }));
        const __VLS_14 = __VLS_13({
            ...{ class: "w-3.5 h-3.5" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    }
    if (__VLS_ctx.uploading || __VLS_ctx.uploadDone) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between text-xs mb-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-surface-400" },
        });
        (__VLS_ctx.uploadDone ? '上传完成' : '上传中...');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-accent" },
        });
        (__VLS_ctx.uploadProgress);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "h-1.5 rounded-full bg-surface-700 overflow-hidden" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
            ...{ class: ([
                    'h-full rounded-full transition-all duration-300',
                    __VLS_ctx.uploadDone ? 'bg-success' : 'bg-accent',
                ]) },
            ...{ style: ({ width: __VLS_ctx.uploadProgress + '%' }) },
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.startUpload) },
        ...{ class: "btn-primary w-full mt-3" },
        disabled: (__VLS_ctx.uploading || !__VLS_ctx.validFiles),
    });
    if (__VLS_ctx.uploading) {
        const __VLS_16 = {}.Loader2;
        /** @type {[typeof __VLS_components.Loader2, ]} */ ;
        // @ts-ignore
        const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
            ...{ class: "w-4 h-4 animate-spin" },
        }));
        const __VLS_18 = __VLS_17({
            ...{ class: "w-4 h-4 animate-spin" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    }
    else if (__VLS_ctx.uploadDone) {
        const __VLS_20 = {}.CheckCircle2;
        /** @type {[typeof __VLS_components.CheckCircle2, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
            ...{ class: "w-4 h-4" },
        }));
        const __VLS_22 = __VLS_21({
            ...{ class: "w-4 h-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    }
    else {
        const __VLS_24 = {}.Upload;
        /** @type {[typeof __VLS_components.Upload, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            ...{ class: "w-4 h-4" },
        }));
        const __VLS_26 = __VLS_25({
            ...{ class: "w-4 h-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    }
    (__VLS_ctx.uploading ? '上传中...' : __VLS_ctx.uploadDone ? '继续上传' : `上传 ${__VLS_ctx.pendingFiles.length} 个文件`);
}
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-accent']} */ ;
/** @type {__VLS_StyleScopedClasses['border-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-dashed']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['p-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['duration-200']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['w-10']} */ ;
/** @type {__VLS_StyleScopedClasses['h-10']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-300']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-surface-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-48']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-surface-700/50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-200']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-danger']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['p-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-3.5']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['text-accent']} */ ;
/** @type {__VLS_StyleScopedClasses['h-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-surface-700']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['duration-300']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            Upload: Upload,
            FileVideo: FileVideo,
            X: X,
            Loader2: Loader2,
            CheckCircle2: CheckCircle2,
            isDragging: isDragging,
            pendingFiles: pendingFiles,
            uploading: uploading,
            uploadProgress: uploadProgress,
            uploadDone: uploadDone,
            totalSize: totalSize,
            validFiles: validFiles,
            formatBytes: formatBytes,
            handleFiles: handleFiles,
            removeFile: removeFile,
            startUpload: startUpload,
            onDragOver: onDragOver,
            onDragLeave: onDragLeave,
            onDrop: onDrop,
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
