package com.yizhixianyu.agentvideo.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(
    String definitionKey,
    int definitionVersion,
    List<Node> nodes,
    List<Edge> edges
) {
    public record Node(
        String nodeKey,
        String toolName,
        String toolVersion,
        InputBinding inputBinding,
        Map<String, Object> parameters
    ) {
    }

    public record Edge(String from, String to) {
    }

    public enum InputBinding {
        PROJECT_ASSET,
        UPSTREAM_ARTIFACT
    }
}
