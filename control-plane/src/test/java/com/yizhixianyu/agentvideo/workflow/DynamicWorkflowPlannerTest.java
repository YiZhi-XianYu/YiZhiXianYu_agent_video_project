package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DynamicWorkflowPlannerTest {

    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();
    private final DynamicWorkflowPlanner planner = new DynamicWorkflowPlanner(
        new MultiAssetAnalysisTemplate(), validator
    );

    @Test
    void defaultPlanKeepsAllProtectedNodesAndGates() {
        var preview = planner.preview(
            ProxyQuality.FHD_1080P, null, false,
            DynamicWorkflowPlanner.WorkflowCapabilities.defaults(), true
        );

        assertThat(preview.defaultSelected()).isTrue();
        assertThat(preview.definition().nodes()).hasSize(13);
        assertThat(preview.definition().edges()).hasSize(19);
        assertThat(preview.definition().gates()).hasSize(5);
        assertThatCode(() -> validator.validate(preview.definition())).doesNotThrowAnyException();
    }

    @Test
    void optionalCapabilitiesCanBeDisabledWithoutRemovingRender() {
        var capabilities = new DynamicWorkflowPlanner.WorkflowCapabilities(true, false, false, false);
        var preview = planner.preview(ProxyQuality.HD_720P, "15秒", false, capabilities, false);

        assertThat(preview.defaultSelected()).isFalse();
        assertThat(preview.definition().nodes()).extracting(WorkflowDefinition.Node::nodeKey)
            .contains("video_render", "timeline_compose")
            .doesNotContain("source_transcribe", "subtitle_compose", "bgm_select");
        assertThat(preview.definition().gates()).extracting(WorkflowDefinition.Gate::gateKey)
            .containsExactly("gate_shot_ranking", "gate_story_edit", "gate_timeline_preview", "gate_render_review");
        assertThatCode(() -> validator.validate(preview.definition())).doesNotThrowAnyException();
    }

    @Test
    void vlmCannotBeDisabledBecauseStoryPlanningRequiresIt() {
        var capabilities = new DynamicWorkflowPlanner.WorkflowCapabilities(false, true, true, true);
        var preview = planner.preview(ProxyQuality.FHD_1080P, null, false, capabilities, false);

        assertThat(preview.definition().nodes()).extracting(WorkflowDefinition.Node::nodeKey)
            .contains("vision_vlm_analyze");
    }
}
