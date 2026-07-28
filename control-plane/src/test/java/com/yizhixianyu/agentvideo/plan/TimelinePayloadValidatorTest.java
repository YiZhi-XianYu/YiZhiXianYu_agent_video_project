package com.yizhixianyu.agentvideo.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimelinePayloadValidatorTest {

    @Test
    void acceptsContinuousMixedTransitionTimeline() {
        assertThatCode(() -> TimelinePayloadValidator.validate(validTimeline())).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateClipAndShotIds() {
        var timeline = validTimeline();
        var clips = clips(timeline);
        clips.get(1).put("clipId", "clip-1");
        clips.get(1).put("shotId", "shot-1");

        assertThatThrownBy(() -> TimelinePayloadValidator.validate(timeline))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("clips[1].clipId is duplicated")
            .hasMessageContaining("clips[1].shotId is duplicated");
    }

    @Test
    void rejectsTimelineGapsAndIncorrectCrossDissolveOffsets() {
        var timeline = validTimeline();
        var clips = clips(timeline);
        clips.get(1).put("timelineInMs", 1000);
        clips.get(1).put("timelineOutMs", 2000);
        timeline.put("durationMs", 2000);

        assertThatThrownBy(() -> TimelinePayloadValidator.validate(timeline))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("clips[1] has an invalid Timeline position for CROSS_DISSOLVE");
    }

    @Test
    void rejectsCrossDissolveOnTheFirstClip() {
        var timeline = validTimeline();
        clips(timeline).get(0).put("transitionIn", transition("CROSS_DISSOLVE", 200));

        assertThatThrownBy(() -> TimelinePayloadValidator.validate(timeline))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot CROSS_DISSOLVE without a preceding Clip");
    }

    public static Map<String, Object> validTimeline() {
        var first = clip("1", 0, 1000, "CUT", 0);
        var second = clip("2", 800, 1800, "CROSS_DISSOLVE", 200);
        var timeline = new LinkedHashMap<String, Object>();
        timeline.put("timelineId", "tl_manual_test");
        timeline.put("version", 1);
        timeline.put("schemaVersion", "1.1");
        timeline.put("sourceHighlightArtifactId", "manual-timeline-edit");
        timeline.put("canvas", Map.of("width", 1280, "height", 720, "fps", 30));
        timeline.put("durationMs", 1800);
        timeline.put("tracks", List.of(Map.of(
            "type", "VIDEO",
            "clips", new ArrayList<>(List.of(first, second))
        )));
        timeline.put("validation", Map.of("valid", true, "errors", List.of()));
        return timeline;
    }

    private static Map<String, Object> clip(
        String suffix, int timelineIn, int timelineOut, String transitionType, int transitionDuration
    ) {
        var clip = new LinkedHashMap<String, Object>();
        clip.put("clipId", "clip-" + suffix);
        clip.put("shotId", "shot-" + suffix);
        clip.put("assetId", "asset-" + suffix);
        clip.put("sourceProxyArtifactId", "proxy-" + suffix);
        clip.put("sourceInMs", 0);
        clip.put("sourceOutMs", 1000);
        clip.put("sourceShotStartMs", 0);
        clip.put("sourceShotEndMs", 1000);
        clip.put("timelineInMs", timelineIn);
        clip.put("timelineOutMs", timelineOut);
        clip.put("playbackRate", 1.0);
        clip.put("transitionIn", transition(transitionType, transitionDuration));
        clip.put("selectionRank", Integer.parseInt(suffix));
        clip.put("storyRole", "1".equals(suffix) ? "HOOK" : "INTRO");
        clip.put("selectionReasons", List.of("MANUAL_TIMELINE_EDIT"));
        return clip;
    }

    private static Map<String, Object> transition(String type, int durationMs) {
        return Map.of("type", type, "durationMs", durationMs);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> clips(Map<String, Object> timeline) {
        var tracks = (List<Map<String, Object>>) timeline.get("tracks");
        return (List<Map<String, Object>>) tracks.get(0).get("clips");
    }
}
