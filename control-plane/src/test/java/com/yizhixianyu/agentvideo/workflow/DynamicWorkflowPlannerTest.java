package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

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

    @Test
    void planProposalKeepsHumanGatesInAutoMode() {
        var preview = planner.preview(
            ProxyQuality.FHD_1080P, "30 seconds", true,
            DynamicWorkflowPlanner.WorkflowCapabilities.defaults(), true,
            "制作旅行短片", List.of("asset-1", "asset-2")
        );

        assertThat(preview.automationMode()).isEqualTo("AUTO");
        assertThat(preview.requiredGates()).isEmpty();
        assertThat(preview.definition().nodes()).extracting(WorkflowDefinition.Node::nodeKey)
            .containsExactly("video_probe", "video_proxy_generate", "video_shot_detect", "vision_quality_score",
                "vision_vlm_analyze", "source_transcribe", "shot_ranking", "story_plan", "highlight_selection",
                "timeline_compose", "bgm_select", "subtitle_compose", "video_render");
    }

    @Test
    void disablingBgmRemovesNodeAndItsGate() {
        var preview = planner.preview(ProxyQuality.HD_720P, "30 seconds", false,
            new DynamicWorkflowPlanner.WorkflowCapabilities(true, true, true, false), false,
            "不要音乐的旅行短片", List.of("asset-1"));
        assertThat(preview.definition().nodes()).extracting(WorkflowDefinition.Node::nodeKey).doesNotContain("bgm_select");
        assertThat(preview.definition().gates()).extracting(WorkflowDefinition.Gate::gateKey).doesNotContain("gate_bgm_review");
    }

    @Test
    void requestedReviewGatesAreTheOnlyCollaborativeGates() {
        var preview = planner.preview(ProxyQuality.HD_720P, "30 seconds", true,
            DynamicWorkflowPlanner.WorkflowCapabilities.defaults(), true,
            "旅行短片", List.of("asset-1"), Set.of("gate_bgm_review"));
        assertThat(preview.definition().gates()).extracting(WorkflowDefinition.Gate::gateKey)
            .containsExactly("gate_bgm_review");
    }

    @Test
    void middleReviewRequestCreatesTimelineGateOnly() {
        var preview = planner.preview(ProxyQuality.FHD_1080P, "15 seconds", false,
            DynamicWorkflowPlanner.WorkflowCapabilities.defaults(), false,
            "用当前素材制作 15 秒视频，中间让我审核一下", List.of("asset-1"),
            Set.of("gate_timeline_preview"));

        assertThat(preview.definition().gates()).extracting(WorkflowDefinition.Gate::gateKey)
            .containsExactly("gate_timeline_preview");
        assertThat(preview.definition().gates().getFirst().afterNodeKey())
            .isEqualTo("timeline_compose");
    }
}
