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
            1,
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
                )
            ),
            List.of(
                new WorkflowDefinition.Edge("video_probe", "video_proxy_generate"),
                new WorkflowDefinition.Edge("video_proxy_generate", "video_shot_detect")
            )
        );
    }
}
