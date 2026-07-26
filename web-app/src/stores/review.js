/**
 * 人在回路审核状态管理
 *
 * 管理当前 Gate 的审核数据和用户编辑状态。
 * 每个 Gate 有独立的编辑数据，切换 Gate 时自动重置。
 */
import { ref } from 'vue';
import { defineStore } from 'pinia';
export const useReviewStore = defineStore('review', () => {
    // ===================== State =====================
    const currentGate = ref(null);
    // Gate 1：镜头排序
    const shotScores = ref([]);
    const excludedShotIds = ref(new Set());
    const forcedShotIds = ref(new Set());
    // Gate 2：故事编辑
    const storyPlan = ref(null);
    const lockedShotIds = ref(new Set());
    // Gate 3：时间线
    const timeline = ref(null);
    // Gate 4：成片预览
    const renderedVideoUrl = ref(null);
    const subtitleStyle = ref({
        fontSize: 24,
        fontColor: '#ffffff',
        position: 'bottom',
        outlineColor: '#000000',
    });
    // Gate 5：最终下载
    const finalVideoUrl = ref(null);
    const dirty = ref(false);
    // ===================== Actions =====================
    function activateGate(gate) {
        currentGate.value = gate;
        dirty.value = false;
        if (!gate)
            resetAll();
    }
    function resetAll() {
        shotScores.value = [];
        excludedShotIds.value = new Set();
        forcedShotIds.value = new Set();
        storyPlan.value = null;
        lockedShotIds.value = new Set();
        timeline.value = null;
        renderedVideoUrl.value = null;
        finalVideoUrl.value = null;
        dirty.value = false;
    }
    function setShotScores(scores) { shotScores.value = scores; }
    function setStoryPlan(plan) { storyPlan.value = plan; }
    function setTimeline(tl) { timeline.value = tl; }
    function setRenderedVideo(url) { renderedVideoUrl.value = url; }
    function setFinalVideo(url) { finalVideoUrl.value = url; }
    function toggleForced(shotId) {
        const s = new Set(forcedShotIds.value);
        if (s.has(shotId)) {
            s.delete(shotId);
        }
        else {
            s.add(shotId);
        }
        forcedShotIds.value = s;
        dirty.value = true;
    }
    function toggleExcluded(shotId) {
        const s = new Set(excludedShotIds.value);
        if (s.has(shotId)) {
            s.delete(shotId);
        }
        else {
            s.add(shotId);
        }
        excludedShotIds.value = s;
        dirty.value = true;
    }
    function toggleLockShot(shotId) {
        const s = new Set(lockedShotIds.value);
        if (s.has(shotId)) {
            s.delete(shotId);
        }
        else {
            s.add(shotId);
        }
        lockedShotIds.value = s;
        dirty.value = true;
    }
    function updateSubtitleStyle(style) {
        subtitleStyle.value = { ...subtitleStyle.value, ...style };
        dirty.value = true;
    }
    return {
        currentGate, shotScores, excludedShotIds, forcedShotIds,
        storyPlan, lockedShotIds, timeline,
        renderedVideoUrl, subtitleStyle, finalVideoUrl, dirty,
        activateGate, resetAll,
        setShotScores, setStoryPlan, setTimeline,
        setRenderedVideo, setFinalVideo,
        toggleForced, toggleExcluded, toggleLockShot,
        updateSubtitleStyle,
    };
});
