package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class WorkflowDefinitionValidator {

    private static final Pattern NODE_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,99}$");
    private static final Set<String> KNOWN_TOOLS = Set.of(
        "video.probe@1.0.0",
        "video.proxy-generate@1.0.0",
        "video.shot-detect@1.0.0"
    );

    public void validate(WorkflowDefinition definition) {
        if (definition == null || definition.nodes() == null || definition.nodes().isEmpty()) {
            throw new IllegalArgumentException("WorkflowDefinition must contain at least one node");
        }
        if (definition.definitionKey() == null || definition.definitionKey().isBlank()) {
            throw new IllegalArgumentException("WorkflowDefinition key is required");
        }
        if (definition.definitionVersion() < 1) {
            throw new IllegalArgumentException("WorkflowDefinition version must be positive");
        }

        var nodes = new HashMap<String, WorkflowDefinition.Node>();
        for (var node : definition.nodes()) {
            if (node.nodeKey() == null || !NODE_KEY.matcher(node.nodeKey()).matches()) {
                throw new IllegalArgumentException("Invalid workflow node key: " + node.nodeKey());
            }
            if (nodes.putIfAbsent(node.nodeKey(), node) != null) {
                throw new IllegalArgumentException("Duplicate workflow node: " + node.nodeKey());
            }
            var toolKey = node.toolName() + "@" + node.toolVersion();
            if (!KNOWN_TOOLS.contains(toolKey)) {
                throw new IllegalArgumentException("Unknown or disabled Tool: " + toolKey);
            }
            validateParameters(node);
        }

        var adjacency = new HashMap<String, Set<String>>();
        var indegree = new HashMap<String, Integer>();
        nodes.keySet().forEach(key -> {
            adjacency.put(key, new HashSet<>());
            indegree.put(key, 0);
        });
        for (var edge : definition.edges() == null ? java.util.List.<WorkflowDefinition.Edge>of() : definition.edges()) {
            if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                throw new IllegalArgumentException("Workflow edge references an unknown node: " + edge);
            }
            if (edge.from().equals(edge.to())) {
                throw new IllegalArgumentException("Workflow self-loop is not allowed: " + edge.from());
            }
            if (!adjacency.get(edge.from()).add(edge.to())) {
                throw new IllegalArgumentException("Duplicate workflow edge: " + edge.from() + " -> " + edge.to());
            }
            indegree.compute(edge.to(), (key, value) -> value + 1);
        }

        var queue = new ArrayDeque<String>();
        indegree.forEach((key, value) -> {
            if (value == 0) {
                queue.add(key);
            }
        });
        if (queue.isEmpty()) {
            throw new IllegalArgumentException("WorkflowDefinition must contain a root node");
        }
        var originalIndegree = new HashMap<>(indegree);
        var visited = 0;
        while (!queue.isEmpty()) {
            var node = queue.removeFirst();
            visited += 1;
            for (var successor : adjacency.get(node)) {
                var remaining = indegree.compute(successor, (key, value) -> value - 1);
                if (remaining == 0) {
                    queue.add(successor);
                }
            }
        }
        if (visited != nodes.size()) {
            throw new IllegalArgumentException("WorkflowDefinition contains a cycle");
        }

        for (var node : definition.nodes()) {
            if (node.inputBinding() == WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT
                && originalIndegree.get(node.nodeKey()) == 0) {
                throw new IllegalArgumentException(
                    "Node " + node.nodeKey() + " requires an upstream Artifact but has no dependency"
                );
            }
        }
    }

    private void validateParameters(WorkflowDefinition.Node node) {
        var parameters = node.parameters() == null ? Map.<String, Object>of() : node.parameters();
        if ("video.proxy-generate".equals(node.toolName())) {
            var quality = String.valueOf(parameters.getOrDefault("quality", "1080P"));
            ProxyQuality.fromValue(quality);
            if (parameters.size() > 1 || (!parameters.isEmpty() && !parameters.containsKey("quality"))) {
                throw new IllegalArgumentException("video.proxy-generate only accepts the quality parameter");
            }
        } else if ("video.shot-detect".equals(node.toolName())) {
            var allowed = Set.of("sceneThreshold", "minShotDurationMs");
            if (!allowed.containsAll(parameters.keySet())) {
                throw new IllegalArgumentException("video.shot-detect contains unsupported parameters");
            }
        } else if (!parameters.isEmpty()) {
            throw new IllegalArgumentException(node.toolName() + " does not accept parameters");
        }
    }
}
