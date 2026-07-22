package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MultiAssetAnalysisTemplate {

    public WorkflowDefinition create(ProxyQuality quality) {
        return new WorkflowDefinition(
            "MULTI_ASSET_ANALYSIS",
            4,
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
                    "vision_scene_classify", "vision.scene-classify", "1.0.0",
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "vision_object_detect", "vision.object-detect", "1.0.0",
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "vision_person_detect", "vision.person-detect", "1.0.0",
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
                    Map.of("targetDurationMs", 30000, "maxShots", 12)
                ),
                new WorkflowDefinition.Node(
                    "highlight_selection", "decision.highlight-select", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "timeline_compose", "timeline.compose", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT,
                    timelineParameters(quality)
                )
            ),
            List.of(
                new WorkflowDefinition.Edge("video_probe", "video_proxy_generate"),
                new WorkflowDefinition.Edge("video_proxy_generate", "video_shot_detect"),
                new WorkflowDefinition.Edge("video_proxy_generate", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_scene_classify"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_object_detect"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_person_detect"),
                new WorkflowDefinition.Edge("vision_quality_score", "shot_ranking"),
                new WorkflowDefinition.Edge("shot_ranking", "story_plan"),
                new WorkflowDefinition.Edge("vision_scene_classify", "story_plan"),
                new WorkflowDefinition.Edge("vision_object_detect", "story_plan"),
                new WorkflowDefinition.Edge("vision_person_detect", "story_plan"),
                new WorkflowDefinition.Edge("story_plan", "highlight_selection"),
                new WorkflowDefinition.Edge("highlight_selection", "timeline_compose")
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
