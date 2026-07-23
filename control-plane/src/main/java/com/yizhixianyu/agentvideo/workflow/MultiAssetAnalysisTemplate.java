package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MultiAssetAnalysisTemplate {

    public WorkflowDefinition create(ProxyQuality quality) {
        return create(quality, null);
    }

    public WorkflowDefinition create(ProxyQuality quality, String durationPrompt) {
        var storyParams = new java.util.LinkedHashMap<String, Object>();
        storyParams.put("targetDurationMs", 30000);
        storyParams.put("maxShots", 18);
        if (durationPrompt != null && !durationPrompt.isBlank()) {
            storyParams.put("durationPrompt", durationPrompt.strip());
        }
        return new WorkflowDefinition(
            "MULTI_ASSET_ANALYSIS",
            7,
            List.of(
                new WorkflowDefinition.Node(
                    "video_probe", "video.probe", "1.0.0",
                    WorkflowDefinition.InputBinding.PROJECT_ASSET, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "video_proxy_generate", "video.proxy-generate", "1.0.0",
                    WorkflowDefinition.InputBinding.PROJECT_ASSET, Map.of("quality", quality.value())
                ),
                new WorkflowDefinition.Node(
                    "video_shot_detect", "video.shot-detect", "1.0.0",
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT,
                    Map.of("sceneThreshold", 0.30, "minShotDurationMs", 600)
                ),
                new WorkflowDefinition.Node(
                    "vision_quality_score", "vision.quality-score", "1.0.0",
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of("sampleFrames", 3)
                ),
                new WorkflowDefinition.Node(
                    "vision_vlm_analyze", "vision.vlm-analyze", "1.0.0",
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "shot_ranking", "decision.shot-rank", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "story_plan", "planning.story-template", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT,
                    storyParams
                ),
                new WorkflowDefinition.Node(
                    "highlight_selection", "decision.highlight-select", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "timeline_compose", "timeline.compose", "1.1.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT,
                    timelineParameters(quality)
                ),
                new WorkflowDefinition.Node(
                    "bgm_select", "audio.bgm-select", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "speech_transcribe", "audio.speech-transcribe", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "video_render", "video.render", "1.1.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                )
            ),
            List.of(
                new WorkflowDefinition.Edge("video_probe", "video_proxy_generate"),
                new WorkflowDefinition.Edge("video_proxy_generate", "video_shot_detect"),
                new WorkflowDefinition.Edge("video_proxy_generate", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_vlm_analyze"),
                new WorkflowDefinition.Edge("vision_quality_score", "shot_ranking"),
                new WorkflowDefinition.Edge("shot_ranking", "story_plan"),
                new WorkflowDefinition.Edge("vision_vlm_analyze", "story_plan"),
                new WorkflowDefinition.Edge("story_plan", "highlight_selection"),
                new WorkflowDefinition.Edge("shot_ranking", "highlight_selection"),
                new WorkflowDefinition.Edge("highlight_selection", "timeline_compose"),
                new WorkflowDefinition.Edge("timeline_compose", "bgm_select"),
                new WorkflowDefinition.Edge("timeline_compose", "speech_transcribe"),
                new WorkflowDefinition.Edge("bgm_select", "video_render"),
                new WorkflowDefinition.Edge("speech_transcribe", "video_render"),
                new WorkflowDefinition.Edge("timeline_compose", "video_render")
            )
        );
    }

    private Map<String, Object> timelineParameters(ProxyQuality quality) {
        return switch (quality) {
            case UHD_4K -> Map.of("width", 3840, "height", 2160, "fps", 30);
            case QHD_2K -> Map.of("width", 2560, "height", 1440, "fps", 30);
            case FHD_1080P -> Map.of("width", 1920, "height", 1080, "fps", 30);
            case HD_720P -> Map.of("width", 1280, "height", 720, "fps", 30);
        };
    }
}
