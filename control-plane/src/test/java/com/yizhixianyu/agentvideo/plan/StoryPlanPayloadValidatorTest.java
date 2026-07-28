package com.yizhixianyu.agentvideo.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryPlanPayloadValidatorTest {

    @Test
    void acceptsFiveUniqueBeatsWithBoundedShots() {
        assertThatCode(() -> StoryPlanPayloadValidator.validate(validPlan())).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateShotsAcrossBeats() {
        var plan = validPlan();
        shots(plan, 4).get(0).put("shotId", "shot-1");

        assertThatThrownBy(() -> StoryPlanPayloadValidator.validate(plan))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shotId is duplicated across beats: shot-1");
    }

    @Test
    void rejectsSelectedRangesOutsideTheirSourceShot() {
        var plan = validPlan();
        shots(plan, 2).get(0).put("sourceOutMs", 1200);

        assertThatThrownBy(() -> StoryPlanPayloadValidator.validate(plan))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("selected range exceeds its source Shot");
    }

    @Test
    void rejectsDuplicateOrMissingStoryRoles() {
        var plan = validPlan();
        beats(plan).get(4).put("role", "CLIMAX");
        shots(plan, 4).get(0).put("storyRole", "CLIMAX");

        assertThatThrownBy(() -> StoryPlanPayloadValidator.validate(plan))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("story role is duplicated: CLIMAX")
            .hasMessageContaining("must contain HOOK, INTRO, JOURNEY, CLIMAX and ENDING exactly once");
    }

    private static Map<String, Object> validPlan() {
        var roles = List.of("HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING");
        var beats = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < roles.size(); index++) {
            var role = roles.get(index);
            var shot = new LinkedHashMap<String, Object>();
            shot.put("shotId", "shot-" + (index + 1));
            shot.put("sourceAssetId", "asset-" + (index + 1));
            shot.put("sourceProxyArtifactId", "proxy-" + (index + 1));
            shot.put("startMs", 0);
            shot.put("endMs", 1000);
            shot.put("sourceInMs", 0);
            shot.put("sourceOutMs", 1000);
            shot.put("selectedDurationMs", 1000);
            shot.put("rank", index + 1);
            shot.put("storyRole", role);
            shot.put("selectionReasons", List.of("MANUAL_EDIT"));
            var beat = new LinkedHashMap<String, Object>();
            beat.put("role", role);
            beat.put("targetDurationMs", 1000);
            beat.put("actualDurationMs", 1000);
            beat.put("shots", new ArrayList<>(List.of(shot)));
            beats.add(beat);
        }
        var plan = new LinkedHashMap<String, Object>();
        plan.put("schemaVersion", "1.0");
        plan.put("template", "MANUAL_EDIT");
        plan.put("targetDurationMs", 5000);
        plan.put("maxShots", 5);
        plan.put("beats", beats);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> beats(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("beats");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shots(Map<String, Object> plan, int beatIndex) {
        return (List<Map<String, Object>>) beats(plan).get(beatIndex).get("shots");
    }
}
