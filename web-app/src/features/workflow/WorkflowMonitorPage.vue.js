import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, Loader2, CheckCircle2, Download, Film, XCircle, PauseCircle } from 'lucide-vue-next';
import { useWorkflowStore } from '@/stores/workflow';
import { useReviewStore } from '@/stores/review';
import { useUiStore } from '@/stores/ui';
import { usePolling } from '@/shared/composables/usePolling';
import { ApiError } from '@/api/client';
import { getGateDraft, saveGateDraft } from '@/api/workflows';
import { WORKFLOW_POLL_INTERVAL_MS, RUN_STATUS_LABEL } from '@/shared/constants';
import ProgressBar from '@/components/ProgressBar.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import TaskGrid from '@/components/TaskGrid.vue';
import ShotGalleryView from '@/features/review/ShotGalleryView.vue';
import ShotRankingReview from '@/features/review/ShotRankingReview.vue';
import StoryEditor from '@/features/review/StoryEditor.vue';
import TimelinePreview from '@/features/review/TimelinePreview.vue';
import BgmSelectionReview from '@/features/review/BgmSelectionReview.vue';
import FinalReview from '@/features/review/FinalReview.vue';
const props = defineProps();
const router = useRouter();
const workflowStore = useWorkflowStore();
const reviewStore = useReviewStore();
const uiStore = useUiStore();
const gateDraftStatus = ref('idle');
let gateDraftTimer = null;
let hydratingGateDraft = false;
/** Gate 1 视图模式：true = 画廊视图, false = 列表视图 */
const showGalleryView = ref(true);
const renderedVideo = computed(() => workflowStore.tasks
    .flatMap((task) => task.artifacts)
    .find((artifact) => artifact.type === 'RENDERED_VIDEO') ?? null);
const renderedVideoDownloadUrl = computed(() => {
    if (!renderedVideo.value)
        return '';
    return `${renderedVideo.value.contentUrl}?download=true`;
});
const proxyUrls = computed(() => Object.fromEntries(workflowStore.tasks
    .flatMap((task) => task.artifacts)
    .filter((artifact) => artifact.type === 'VIDEO_PROXY')
    .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl])));
const bgmCandidates = computed(() => workflowStore.tasks
    .find((task) => task.nodeKey === 'bgm_select')
    ?.artifacts.filter((artifact) => artifact.type === 'BGM_CANDIDATE') ?? []);
const latestBgmCandidates = computed(() => {
    const candidates = bgmCandidates.value;
    if (!candidates.length)
        return [];
    let latestSetId = '';
    try {
        latestSetId = String(JSON.parse(candidates[0]?.metadataJson || '{}').candidateSetId ?? '');
    }
    catch {
        return candidates;
    }
    if (!latestSetId)
        return candidates;
    return candidates.filter((artifact) => {
        try {
            return String(JSON.parse(artifact.metadataJson || '{}').candidateSetId ?? '') === latestSetId;
        }
        catch {
            return false;
        }
    });
});
const timelineDurationMs = computed(() => {
    const artifact = workflowStore.tasks
        .find((task) => task.nodeKey === 'timeline_compose')
        ?.artifacts.find((item) => item.type === 'TIMELINE');
    if (!artifact)
        return 0;
    try {
        const metadata = JSON.parse(artifact.metadataJson || '{}');
        return Number(metadata.durationMs ?? 0);
    }
    catch {
        return 0;
    }
});
const bgmProviderFailed = computed(() => workflowStore.tasks
    .some((task) => task.nodeKey === 'bgm_select' && task.status === 'FAILED'));
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
watch(() => workflowStore.run?.currentGateKey, () => {
    syncGate();
});
watch(() => ({
    gate: reviewStore.currentGate?.gateKey,
    shotScores: reviewStore.shotScores,
    excludedShotIds: [...reviewStore.excludedShotIds],
    forcedShotIds: [...reviewStore.forcedShotIds],
    storyPlan: reviewStore.storyPlan,
    lockedShotIds: [...reviewStore.lockedShotIds],
    timeline: reviewStore.timeline,
}), () => {
    if (!hydratingGateDraft && workflowStore.currentGate && workflowStore.isPaused)
        scheduleGateDraftSave();
}, { deep: true });
onUnmounted(() => {
    stopPolling();
    workflowStore.clear();
    reviewStore.resetAll();
});
// ===================== Gate 同步 =====================
async function syncGate() {
    const gate = workflowStore.currentGate;
    reviewStore.activateGate(gate);
    if (!gate)
        return;
    hydratingGateDraft = true;
    gateDraftStatus.value = 'idle';
    try {
        if (gate.gateKey === 'gate_shot_ranking') {
            const payload = await loadArtifactJson('shot_ranking', 'SHOT_RANKING');
            const scores = mapShotScores(payload);
            reviewStore.setShotScores(await enrichShotScores(scores));
        }
        else if (gate.gateKey === 'gate_story_edit') {
            const payload = await loadArtifactJson('story_plan', 'STORY_PLAN');
            reviewStore.setStoryPlan(mapStoryPlan(payload));
            const ranking = await loadArtifactJson('shot_ranking', 'SHOT_RANKING');
            reviewStore.setShotScores(await enrichShotScores(mapShotScores(ranking)));
        }
        else if (gate.gateKey === 'gate_timeline_preview') {
            const payload = await loadArtifactJson('timeline_compose', 'TIMELINE');
            reviewStore.setTimeline(mapTimeline(payload));
        }
        else if (gate.gateKey === 'gate_bgm_review') {
            // Candidate metadata and audio URLs are already included in the Workflow snapshot.
        }
        else if (gate.gateKey === 'gate_render_review') {
            const artifact = renderedVideo.value;
            if (!artifact)
                throw new Error('缺少 RENDERED_VIDEO Artifact，无法打开当前审核页');
            reviewStore.setRenderedVideo(artifact.contentUrl);
        }
        await restoreGateDraft(gate.gateKey);
    }
    catch (error) {
        uiStore.showToast(error instanceof Error ? error.message : '审核数据加载失败', 'error');
    }
    finally {
        hydratingGateDraft = false;
    }
}
function gateDraftPayload() {
    return { version: 1, gateKey: reviewStore.currentGate?.gateKey, shotScores: reviewStore.shotScores, excludedShotIds: [...reviewStore.excludedShotIds], forcedShotIds: [...reviewStore.forcedShotIds], storyPlan: reviewStore.storyPlan, lockedShotIds: [...reviewStore.lockedShotIds], timeline: reviewStore.timeline };
}
function scheduleGateDraftSave() {
    const gateKey = workflowStore.currentGate?.gateKey;
    if (!gateKey || !workflowStore.isPaused)
        return;
    gateDraftStatus.value = 'saving';
    if (gateDraftTimer)
        clearTimeout(gateDraftTimer);
    gateDraftTimer = setTimeout(async () => {
        try {
            await saveGateDraft(props.runId, gateKey, gateDraftPayload());
            gateDraftStatus.value = 'saved';
        }
        catch {
            gateDraftStatus.value = 'error';
        }
    }, 700);
}
async function restoreGateDraft(gateKey) {
    try {
        const draft = await getGateDraft(props.runId, gateKey);
        if (!draft || draft.version !== 1 || draft.gateKey !== gateKey)
            return;
        if (Array.isArray(draft.shotScores) && draft.shotScores.length)
            reviewStore.setShotScores(draft.shotScores);
        if (Array.isArray(draft.excludedShotIds))
            reviewStore.setExcludedShotIds(draft.excludedShotIds);
        if (Array.isArray(draft.forcedShotIds))
            reviewStore.setForcedShotIds(draft.forcedShotIds);
        if (draft.storyPlan)
            reviewStore.setStoryPlan(draft.storyPlan);
        if (Array.isArray(draft.lockedShotIds))
            reviewStore.setLockedShotIds(draft.lockedShotIds);
        if (draft.timeline)
            reviewStore.setTimeline(draft.timeline);
        gateDraftStatus.value = 'saved';
    }
    catch (error) {
        if (!(error instanceof ApiError && error.status === 404))
            gateDraftStatus.value = 'error';
    }
}
function findArtifact(nodeKey, type) {
    const artifact = workflowStore.tasks
        .find((task) => task.nodeKey === nodeKey)
        ?.artifacts.find((item) => item.type === type);
    if (!artifact)
        throw new Error(`缺少 ${type} Artifact，无法打开当前审核页`);
    return artifact;
}
/**
 * Return every artifact produced by a node key.  Asset-scoped nodes are
 * instantiated once per input asset, so using `find()` here silently drops
 * all but the first asset's output.
 */
function findArtifacts(nodeKey, type) {
    return workflowStore.tasks
        .filter((task) => task.nodeKey === nodeKey)
        .flatMap((task) => task.artifacts.filter((artifact) => artifact.type === type));
}
async function loadArtifactJson(nodeKey, type) {
    const artifact = findArtifact(nodeKey, type);
    return await fetch(artifact.contentUrl).then(async (response) => {
        if (!response.ok)
            throw new Error(`${type} Artifact 加载失败：HTTP ${response.status}`);
        return await response.json();
    });
}
function mapShotScores(payload) {
    return (payload.shots ?? []).map((shot) => ({
        shotId: String(shot.shotId),
        quality: {
            sharpness: Number(shot.clarity ?? 0) * 100,
            exposure: Number(shot.exposure ?? 0) * 100,
            stability: Number(shot.stability ?? 0) * 100,
            composition: Number(shot.composition ?? 0) * 100,
            motionInterest: Number(shot.motionInterest ?? 0) * 100,
            overall: Number(shot.qualityScore ?? 0) * 100,
        },
        labels: { scene: [], object: [], person: [] },
        rankScore: Number(shot.finalScore ?? shot.qualityScore ?? 0) * 100,
        penalties: (shot.rejectionReasons ?? []).map(String),
        selected: Boolean(shot.eligible),
        sourceAssetId: shot.sourceAssetId ? String(shot.sourceAssetId) : undefined,
        sourceProxyArtifactId: shot.sourceProxyArtifactId ? String(shot.sourceProxyArtifactId) : undefined,
        startMs: Number(shot.startMs ?? 0),
        endMs: Number(shot.endMs ?? shot.durationMs ?? 0),
    }));
}
async function loadArtifactJsonList(nodeKey, type) {
    const artifacts = findArtifacts(nodeKey, type);
    if (!artifacts.length)
        throw new Error(`缺少 ${type} Artifact，无法加载当前审核页`);
    return await Promise.all(artifacts.map(async (artifact) => {
        const response = await fetch(artifact.contentUrl);
        if (!response.ok)
            throw new Error(`${type} Artifact 加载失败：HTTP ${response.status}`);
        return await response.json();
    }));
}
function mapStoryPlan(payload) {
    return {
        workflowRunId: props.runId,
        beats: (payload.beats ?? []).map((beat) => ({
            role: beat.role,
            shotIds: (beat.shots ?? []).map((shot) => String(shot.shotId)),
            shots: (beat.shots ?? []).map((shot) => ({
                ...shot,
                shotId: String(shot.shotId),
                sourceAssetId: String(shot.sourceAssetId ?? ''),
                sourceProxyArtifactId: String(shot.sourceProxyArtifactId ?? ''),
                startMs: Number(shot.startMs ?? 0), endMs: Number(shot.endMs ?? 0),
                sourceInMs: Number(shot.sourceInMs ?? shot.startMs ?? 0),
                sourceOutMs: Number(shot.sourceOutMs ?? shot.endMs ?? 0),
                selectedDurationMs: Number(shot.selectedDurationMs ?? 0),
            })),
            targetDurationMs: Number(beat.targetDurationMs ?? beat.actualDurationMs ?? 0),
            actualDurationMs: Number(beat.actualDurationMs ?? beat.targetDurationMs ?? 0),
        })),
        totalDurationMs: Number(payload.targetDurationMs ?? 0),
    };
}
function mapTimeline(payload) {
    const videoTrack = (payload.tracks ?? []).find((track) => track.type === 'VIDEO');
    return {
        clips: (videoTrack?.clips ?? []).map((clip) => ({
            shotId: String(clip.shotId),
            sourceInMs: Number(clip.sourceInMs),
            sourceOutMs: Number(clip.sourceOutMs),
            durationMs: Number(clip.timelineOutMs) - Number(clip.timelineInMs),
            transition: (clip.transitionIn?.type ?? 'CUT'),
            transitionDurationMs: Number(clip.transitionIn?.durationMs ?? 0),
            clipId: String(clip.clipId ?? `clip_${clip.shotId}`),
            assetId: String(clip.assetId ?? ''), sourceProxyArtifactId: String(clip.sourceProxyArtifactId ?? ''),
            sourceShotStartMs: clip.sourceShotStartMs == null ? undefined : Number(clip.sourceShotStartMs),
            sourceShotEndMs: clip.sourceShotEndMs == null ? undefined : Number(clip.sourceShotEndMs),
            timelineInMs: Number(clip.timelineInMs ?? 0), timelineOutMs: Number(clip.timelineOutMs ?? 0),
            playbackRate: Number(clip.playbackRate ?? 1), storyRole: clip.storyRole,
            selectionRank: Number(clip.selectionRank ?? 1), selectionReasons: clip.selectionReasons ?? [],
        })),
        totalDurationMs: Number(payload.durationMs ?? 0),
        bgmName: null,
        timelineId: payload.timelineId ? String(payload.timelineId) : undefined,
        canvas: {
            width: Number(payload.canvas?.width ?? 1920),
            height: Number(payload.canvas?.height ?? 1080),
            fps: Number(payload.canvas?.fps ?? 30),
        },
    };
}
/** 从视频检测任务中提取每个镜头的关键帧 URL 和代理视频地址，合并到 ShotScore */
async function enrichShotScores(scores) {
    try {
        /* 1. 加载所有素材级 SHOT_LIST（含 keyframeArtifactId 和时间） */
        const shotListPayloads = await loadArtifactJsonList('video_shot_detect', 'SHOT_LIST');
        const shotMetaMap = new Map(shotListPayloads.flatMap((payload) => (payload.shots ?? [])).map((s) => [
            String(s.shotId),
            {
                keyframeArtifactId: String(s.keyframeArtifactId),
                startMs: Number(s.startMs),
                endMs: Number(s.endMs),
                sourceAssetId: String(s.sourceAssetId ?? ''),
                sourceProxyArtifactId: String(s.sourceProxyArtifactId ?? ''),
            },
        ]));
        /* 2. 获取每个代理 Artifact 的 URL（按 externalArtifactId 匹配镜头来源） */
        const proxyUrlMap = new Map(workflowStore.tasks
            .filter((task) => task.nodeKey === 'video_proxy_generate')
            .flatMap((task) => task.artifacts.filter((artifact) => artifact.type === 'VIDEO_PROXY'))
            .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl]));
        /* 3. 从所有 video_shot_detect 任务的 artifacts 中构建 externalArtifactId → contentUrl 映射 */
        const artifactUrlMap = new Map(workflowStore.tasks
            .filter((task) => task.nodeKey === 'video_shot_detect')
            .flatMap((task) => task.artifacts)
            .map((artifact) => [artifact.externalArtifactId, artifact.contentUrl]));
        /* 4. 合并到 scores */
        return scores.map((shot) => {
            const meta = shotMetaMap.get(shot.shotId);
            return {
                ...shot,
                keyframeUrl: meta ? artifactUrlMap.get(meta.keyframeArtifactId) : undefined,
                proxyVideoUrl: meta ? proxyUrlMap.get(meta.sourceProxyArtifactId) : undefined,
                startMs: meta?.startMs,
                endMs: meta?.endMs,
                sourceAssetId: meta?.sourceAssetId || shot.sourceAssetId,
                sourceProxyArtifactId: meta?.sourceProxyArtifactId || shot.sourceProxyArtifactId,
            };
        });
    }
    catch {
        /* 预加载失败不阻塞主流程，返回原始 scores */
        return scores;
    }
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
async function handleRenderConfirm() {
    await handleContinue();
}
async function handleStoryPlanApplied() {
    await workflowStore.fetchRun(props.runId);
    startPolling();
}
async function handleTimelineApplied() {
    await workflowStore.fetchRun(props.runId);
    startPolling();
}
async function handleBgmApplied() {
    await workflowStore.fetchRun(props.runId);
    startPolling();
}
function goBack() {
    router.push(`/projects/${props.projectId}`);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "page-shell workflow-page" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "flex items-center gap-4 mb-8" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.goBack) },
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
        ...{ class: "workflow-overview mb-8" },
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
        ...{ class: "mb-8" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "section-title-row mb-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "section-eyebrow" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    /** @type {[typeof TaskGrid, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(TaskGrid, new TaskGrid({
        tasks: (__VLS_ctx.workflowStore.tasks),
    }));
    const __VLS_19 = __VLS_18({
        tasks: (__VLS_ctx.workflowStore.tasks),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mb-3 text-right text-[11px]" },
        });
        if (__VLS_ctx.gateDraftStatus === 'saving') {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-surface-500" },
            });
        }
        else if (__VLS_ctx.gateDraftStatus === 'saved') {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-emerald-400" },
            });
        }
        else if (__VLS_ctx.gateDraftStatus === 'error') {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-warning" },
            });
        }
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_shot_ranking') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-1 mb-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.workflowStore.run && !__VLS_ctx.workflowStore.error))
                        return;
                    if (!(__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_shot_ranking'))
                        return;
                    __VLS_ctx.showGalleryView = true;
                } },
            ...{ class: ([
                    'px-3 py-1.5 rounded text-xs font-medium transition-colors',
                    __VLS_ctx.showGalleryView
                        ? 'bg-accent/20 text-accent border border-accent/30'
                        : 'text-surface-400 hover:text-surface-200 hover:bg-surface-700',
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.workflowStore.run && !__VLS_ctx.workflowStore.error))
                        return;
                    if (!(__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_shot_ranking'))
                        return;
                    __VLS_ctx.showGalleryView = false;
                } },
            ...{ class: ([
                    'px-3 py-1.5 rounded text-xs font-medium transition-colors',
                    !__VLS_ctx.showGalleryView
                        ? 'bg-accent/20 text-accent border border-accent/30'
                        : 'text-surface-400 hover:text-surface-200 hover:bg-surface-700',
                ]) },
        });
        if (__VLS_ctx.showGalleryView) {
            /** @type {[typeof ShotGalleryView, ]} */ ;
            // @ts-ignore
            const __VLS_21 = __VLS_asFunctionalComponent(ShotGalleryView, new ShotGalleryView({
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
        else {
            /** @type {[typeof ShotRankingReview, ]} */ ;
            // @ts-ignore
            const __VLS_28 = __VLS_asFunctionalComponent(ShotRankingReview, new ShotRankingReview({
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
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_story_edit') {
        /** @type {[typeof StoryEditor, ]} */ ;
        // @ts-ignore
        const __VLS_35 = __VLS_asFunctionalComponent(StoryEditor, new StoryEditor({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
        }));
        const __VLS_36 = __VLS_35({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_35));
        let __VLS_38;
        let __VLS_39;
        let __VLS_40;
        const __VLS_41 = {
            onConfirm: (__VLS_ctx.handleStoryPlanApplied)
        };
        var __VLS_37;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_timeline_preview') {
        /** @type {[typeof TimelinePreview, ]} */ ;
        // @ts-ignore
        const __VLS_42 = __VLS_asFunctionalComponent(TimelinePreview, new TimelinePreview({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
            proxyUrls: (__VLS_ctx.proxyUrls),
        }));
        const __VLS_43 = __VLS_42({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
            proxyUrls: (__VLS_ctx.proxyUrls),
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        let __VLS_45;
        let __VLS_46;
        let __VLS_47;
        const __VLS_48 = {
            onConfirm: (__VLS_ctx.handleTimelineApplied)
        };
        var __VLS_44;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_bgm_review') {
        /** @type {[typeof BgmSelectionReview, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(BgmSelectionReview, new BgmSelectionReview({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
            candidates: (__VLS_ctx.latestBgmCandidates),
            timelineDurationMs: (__VLS_ctx.timelineDurationMs),
            providerFailed: (__VLS_ctx.bgmProviderFailed),
        }));
        const __VLS_50 = __VLS_49({
            ...{ 'onConfirm': {} },
            runId: (__VLS_ctx.runId),
            candidates: (__VLS_ctx.latestBgmCandidates),
            timelineDurationMs: (__VLS_ctx.timelineDurationMs),
            providerFailed: (__VLS_ctx.bgmProviderFailed),
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
        let __VLS_52;
        let __VLS_53;
        let __VLS_54;
        const __VLS_55 = {
            onConfirm: (__VLS_ctx.handleBgmApplied)
        };
        var __VLS_51;
    }
    if (__VLS_ctx.workflowStore.isPaused && __VLS_ctx.workflowStore.currentGate?.gateKey === 'gate_render_review') {
        /** @type {[typeof FinalReview, ]} */ ;
        // @ts-ignore
        const __VLS_56 = __VLS_asFunctionalComponent(FinalReview, new FinalReview({
            ...{ 'onConfirm': {} },
        }));
        const __VLS_57 = __VLS_56({
            ...{ 'onConfirm': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_56));
        let __VLS_59;
        let __VLS_60;
        let __VLS_61;
        const __VLS_62 = {
            onConfirm: (__VLS_ctx.handleRenderConfirm)
        };
        var __VLS_58;
    }
    if (__VLS_ctx.workflowStore.isPaused && !__VLS_ctx.workflowStore.currentGate) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card mb-6 ring-1 ring-warning/40" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-4" },
        });
        const __VLS_63 = {}.PauseCircle;
        /** @type {[typeof __VLS_components.PauseCircle, ]} */ ;
        // @ts-ignore
        const __VLS_64 = __VLS_asFunctionalComponent(__VLS_63, new __VLS_63({
            ...{ class: "w-6 h-6 text-warning shrink-0" },
        }));
        const __VLS_65 = __VLS_64({
            ...{ class: "w-6 h-6 text-warning shrink-0" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_64));
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
        const __VLS_67 = {}.CheckCircle2;
        /** @type {[typeof __VLS_components.CheckCircle2, ]} */ ;
        // @ts-ignore
        const __VLS_68 = __VLS_asFunctionalComponent(__VLS_67, new __VLS_67({
            ...{ class: "w-4 h-4" },
        }));
        const __VLS_69 = __VLS_68({
            ...{ class: "w-4 h-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_68));
    }
    if (__VLS_ctx.workflowStore.status === 'SUCCEEDED' && __VLS_ctx.renderedVideo) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
            ...{ class: "card mb-6 overflow-hidden" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mb-5 flex flex-col justify-between gap-4 sm:flex-row sm:items-start" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "section-eyebrow mb-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
            ...{ class: "flex items-center gap-2 text-lg font-semibold text-surface-100" },
        });
        const __VLS_71 = {}.Film;
        /** @type {[typeof __VLS_components.Film, ]} */ ;
        // @ts-ignore
        const __VLS_72 = __VLS_asFunctionalComponent(__VLS_71, new __VLS_71({
            ...{ class: "h-5 w-5 text-accent-light" },
        }));
        const __VLS_73 = __VLS_72({
            ...{ class: "h-5 w-5 text-accent-light" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_72));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "mt-2 text-sm text-surface-400" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            href: (__VLS_ctx.renderedVideoDownloadUrl),
            ...{ class: "btn-primary shrink-0" },
        });
        const __VLS_75 = {}.Download;
        /** @type {[typeof __VLS_components.Download, ]} */ ;
        // @ts-ignore
        const __VLS_76 = __VLS_asFunctionalComponent(__VLS_75, new __VLS_75({
            ...{ class: "h-4 w-4" },
        }));
        const __VLS_77 = __VLS_76({
            ...{ class: "h-4 w-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_76));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "overflow-hidden rounded-xl border border-surface-700 bg-black" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.video)({
            src: (__VLS_ctx.renderedVideo.contentUrl),
            controls: true,
            preload: "metadata",
            ...{ class: "aspect-video max-h-[70vh] w-full bg-black" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 flex flex-wrap items-center justify-between gap-2 text-[11px] text-surface-500" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.renderedVideo.mediaType);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.renderedVideo.externalArtifactId);
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
            const __VLS_79 = {}.CheckCircle2;
            /** @type {[typeof __VLS_components.CheckCircle2, ]} */ ;
            // @ts-ignore
            const __VLS_80 = __VLS_asFunctionalComponent(__VLS_79, new __VLS_79({
                ...{ class: "w-8 h-8 text-success" },
            }));
            const __VLS_81 = __VLS_80({
                ...{ class: "w-8 h-8 text-success" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_80));
        }
        else {
            const __VLS_83 = {}.XCircle;
            /** @type {[typeof __VLS_components.XCircle, ]} */ ;
            // @ts-ignore
            const __VLS_84 = __VLS_asFunctionalComponent(__VLS_83, new __VLS_83({
                ...{ class: "w-8 h-8 text-danger" },
            }));
            const __VLS_85 = __VLS_84({
                ...{ class: "w-8 h-8 text-danger" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_84));
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
const __VLS_87 = {}.TransitionGroup;
/** @type {[typeof __VLS_components.TransitionGroup, typeof __VLS_components.transitionGroup, typeof __VLS_components.TransitionGroup, typeof __VLS_components.transitionGroup, ]} */ ;
// @ts-ignore
const __VLS_88 = __VLS_asFunctionalComponent(__VLS_87, new __VLS_87({
    name: "fade",
}));
const __VLS_89 = __VLS_88({
    name: "fade",
}, ...__VLS_functionalComponentArgsRest(__VLS_88));
__VLS_90.slots.default;
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
var __VLS_90;
/** @type {__VLS_StyleScopedClasses['page-shell']} */ ;
/** @type {__VLS_StyleScopedClasses['workflow-page']} */ ;
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
/** @type {__VLS_StyleScopedClasses['workflow-overview']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-8']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-8']} */ ;
/** @type {__VLS_StyleScopedClasses['section-title-row']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['section-eyebrow']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
/** @type {__VLS_StyleScopedClasses['text-emerald-400']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
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
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['sm:flex-row']} */ ;
/** @type {__VLS_StyleScopedClasses['sm:items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['section-eyebrow']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-100']} */ ;
/** @type {__VLS_StyleScopedClasses['h-5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-accent-light']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-400']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['h-4']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-surface-700']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-black']} */ ;
/** @type {__VLS_StyleScopedClasses['aspect-video']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-[70vh]']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-black']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-surface-500']} */ ;
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
            Download: Download,
            Film: Film,
            XCircle: XCircle,
            PauseCircle: PauseCircle,
            RUN_STATUS_LABEL: RUN_STATUS_LABEL,
            ProgressBar: ProgressBar,
            StatusBadge: StatusBadge,
            TaskGrid: TaskGrid,
            ShotGalleryView: ShotGalleryView,
            ShotRankingReview: ShotRankingReview,
            StoryEditor: StoryEditor,
            TimelinePreview: TimelinePreview,
            BgmSelectionReview: BgmSelectionReview,
            FinalReview: FinalReview,
            workflowStore: workflowStore,
            uiStore: uiStore,
            gateDraftStatus: gateDraftStatus,
            showGalleryView: showGalleryView,
            renderedVideo: renderedVideo,
            renderedVideoDownloadUrl: renderedVideoDownloadUrl,
            proxyUrls: proxyUrls,
            latestBgmCandidates: latestBgmCandidates,
            timelineDurationMs: timelineDurationMs,
            bgmProviderFailed: bgmProviderFailed,
            handleContinue: handleContinue,
            handleRenderConfirm: handleRenderConfirm,
            handleStoryPlanApplied: handleStoryPlanApplied,
            handleTimelineApplied: handleTimelineApplied,
            handleBgmApplied: handleBgmApplied,
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
