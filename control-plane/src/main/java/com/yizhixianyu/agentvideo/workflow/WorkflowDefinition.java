package com.yizhixianyu.agentvideo.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(
    String definitionKey,
    int definitionVersion,
    List<Node> nodes,
    List<Edge> edges,
    List<Gate> gates
) {
    public WorkflowDefinition(
        String definitionKey,
        int definitionVersion,
        List<Node> nodes,
        List<Edge> edges
    ) {
        this(definitionKey, definitionVersion, nodes, edges, List.of());
    }

    public record Node(
        String nodeKey,
        String toolName,
        String toolVersion,
        NodeScope scope,
        InputBinding inputBinding,
        Map<String, Object> parameters
    ) {
        public Node(
            String nodeKey,
            String toolName,
            String toolVersion,
            InputBinding inputBinding,
            Map<String, Object> parameters
        ) {
            this(nodeKey, toolName, toolVersion, NodeScope.ASSET, inputBinding, parameters);
        }
    }

    public record Edge(String from, String to, DependencyType dependencyType) {
        public Edge(String from, String to) {
            this(from, to, DependencyType.REQUIRED);
        }
    }


    /** 人在回路关卡。关联到某个 Node.nodeKey，该 Node 完成后触发暂停 */
    public record Gate(
        String gateKey,
        String afterNodeKey,
        String label,
        String description
    ) {}

    public enum InputBinding {
        PROJECT_ASSET,
        UPSTREAM_ARTIFACT
    }

    public enum NodeScope {
        ASSET,
        WORKFLOW
    }

    public enum DependencyType {
        REQUIRED,
        OPTIONAL
    }
}
