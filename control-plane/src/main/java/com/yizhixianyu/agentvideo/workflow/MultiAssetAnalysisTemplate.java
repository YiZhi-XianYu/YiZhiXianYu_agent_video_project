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
            9,
            // === Nodes ===
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
                    "source_transcribe", "audio.source-transcribe", "1.0.0",
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
                    "subtitle_compose", "subtitle.compose", "1.0.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                ),
                new WorkflowDefinition.Node(
                    "video_render", "video.render", "1.1.0",
                    WorkflowDefinition.NodeScope.WORKFLOW,
                    WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT, Map.of()
                )
            ),
            // === Edges ===
            List.of(
                new WorkflowDefinition.Edge("video_probe", "video_proxy_generate"),
                new WorkflowDefinition.Edge("video_proxy_generate", "video_shot_detect"),
                new WorkflowDefinition.Edge("video_proxy_generate", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_quality_score"),
                new WorkflowDefinition.Edge("video_shot_detect", "vision_vlm_analyze"),
                new WorkflowDefinition.Edge("video_proxy_generate", "source_transcribe"),
                new WorkflowDefinition.Edge("vision_quality_score", "shot_ranking"),
                new WorkflowDefinition.Edge("shot_ranking", "story_plan"),
                new WorkflowDefinition.Edge("vision_vlm_analyze", "story_plan"),
                new WorkflowDefinition.Edge("story_plan", "highlight_selection"),
                new WorkflowDefinition.Edge("shot_ranking", "highlight_selection"),
                new WorkflowDefinition.Edge("highlight_selection", "timeline_compose"),
                new WorkflowDefinition.Edge("story_plan", "bgm_select"),
                new WorkflowDefinition.Edge("timeline_compose", "bgm_select"),
                new WorkflowDefinition.Edge("timeline_compose", "subtitle_compose"),
                new WorkflowDefinition.Edge(
                    "source_transcribe", "subtitle_compose", WorkflowDefinition.DependencyType.OPTIONAL
                ),
                new WorkflowDefinition.Edge("timeline_compose", "video_render"),
                new WorkflowDefinition.Edge(
                    "bgm_select", "video_render", WorkflowDefinition.DependencyType.OPTIONAL
                ),
                new WorkflowDefinition.Edge(
                    "subtitle_compose", "video_render", WorkflowDefinition.DependencyType.OPTIONAL
                )
            ),
            // === Gates（人在回路关卡） ===
            List.of(
                new WorkflowDefinition.Gate("gate_shot_ranking", "shot_ranking", "镜头排序审核", "请检查系统对镜头的质量评分和排名。可手动调整评分、强制入选或排除指定镜头。"),
                new WorkflowDefinition.Gate("gate_story_edit", "story_plan", "故事安排编辑", "请检查五段式故事安排。可替换、排序、锁定、添加或删除各段落中的镜头。"),
                new WorkflowDefinition.Gate("gate_timeline_preview", "timeline_compose", "时间线预览", "请预览生成的时间线，确认镜头顺序、转场效果和整体节奏。"),
                new WorkflowDefinition.Gate("gate_render_review", "video_render", "最终成片预览", "请预览最终成片；BGM 或字幕不可用时，系统会保留可播放的降级版本。")
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
