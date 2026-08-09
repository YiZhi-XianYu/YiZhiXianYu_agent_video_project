package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import com.yizhixianyu.agentvideo.agent.BlackboardService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DynamicWorkflowPlanner {
    private static final Set<String> OPTIONAL_NODE_KEYS = Set.of("source_transcribe", "subtitle_compose", "bgm_select");
    private final MultiAssetAnalysisTemplate template;
    private final WorkflowDefinitionValidator validator;
    private final ToolServiceClient toolClient;
    private final BlackboardService blackboard;

    @Autowired
    public DynamicWorkflowPlanner(MultiAssetAnalysisTemplate template, WorkflowDefinitionValidator validator, ToolServiceClient toolClient, BlackboardService blackboard) {
        this.template = template;
        this.validator = validator;
        this.toolClient = toolClient;
        this.blackboard = blackboard;
    }

    public DynamicWorkflowPlanner(MultiAssetAnalysisTemplate template, WorkflowDefinitionValidator validator) {
        this(template, validator, null, null);
    }

    public WorkflowPlanPreview preview(ProxyQuality quality, String durationPrompt, boolean autoMode,
                                       WorkflowCapabilities requested, boolean useDefault) {
        return preview(quality, durationPrompt, autoMode, requested, useDefault, "", List.of("asset-1"), null);
    }

    public WorkflowPlanPreview preview(ProxyQuality quality, String durationPrompt, boolean autoMode,
                                       WorkflowCapabilities requested, boolean useDefault, String goal, List<String> assetIds) {
        return preview(quality, durationPrompt, autoMode, requested, useDefault, goal, assetIds, null);
    }

    public WorkflowPlanPreview preview(ProxyQuality quality, String durationPrompt, boolean autoMode,
                                       WorkflowCapabilities requested, boolean useDefault, String goal, List<String> assetIds,
                                       Set<String> reviewGateKeys) {
        validateReviewGates(reviewGateKeys);
        var defaults = WorkflowCapabilities.defaults();
        var llmIntent = useDefault || requested != null ? null : requestIntent(goal, durationPrompt, assetIds == null ? 1 : assetIds.size());
        var capabilities = useDefault ? defaults : requested != null ? requested.normalized() : capabilitiesFromIntent(llmIntent);
        // An explicit duration is user intent and must not be silently changed by the model.
        var targetDurationMs = durationPrompt != null && !durationPrompt.isBlank()
            ? parseDurationMs(durationPrompt)
            : llmIntent == null ? parseDurationMs(goal) : llmIntent.targetDurationMs();
        var defaultDefinition = template.create(quality, goal, autoMode, targetDurationMs);
        var effectiveReviewGates = reviewGateKeys == null && autoMode ? Set.<String>of() : reviewGateKeys;
        var candidate = buildDefinition(defaultDefinition, capabilities, autoMode, effectiveReviewGates);
        validator.validate(candidate);
        var intent = new WorkflowIntentView("TRAVEL_HIGHLIGHT", String.valueOf(targetDurationMs),
            llmIntent == null ? "已按用户选择生成受控候选流程图" : llmIntent.explanation(), capabilities);
        var explanations = candidate.nodes().stream().map(node -> {
            var policy = ToolGovernanceCatalog.policy(node.toolName());
            return new NodeExplanation(node.nodeKey(), chineseLabel(node.nodeKey()), chineseReason(node.nodeKey()), node.toolName() + "@" + node.toolVersion(), OPTIONAL_NODE_KEYS.contains(node.nodeKey()), policy.automationPolicy(), policy.requiresUserConfirmation(), policy.maxAttempts(), policy.resourceGroup());
        }).toList();
        var governanceWarnings = explanations.stream().filter(NodeExplanation::requiresUserConfirmation)
            .map(item -> item.tool() + " requires user confirmation before execution").toList();
        var riskGates = candidate.gates().stream().filter(g -> g.gateKey().startsWith("gate_governance_")
            || "gate_bgm_review".equals(g.gateKey()) || "gate_render_review".equals(g.gateKey())).map(WorkflowDefinition.Gate::gateKey).toList();
        var requiredGates = new ArrayList<>(riskGates);
        return new WorkflowPlanPreview(intent, candidate, defaultDefinition, explanations, capabilities.equals(defaults), expandCanvas(candidate, assetIds), llmIntent != null && llmIntent.llmUsed(), governanceWarnings, !requiredGates.isEmpty(), requiredGates, autoMode ? "AUTO" : "COLLABORATIVE");
    }

    private void validateReviewGates(Set<String> reviewGateKeys) {
        if (reviewGateKeys == null) return;
        var allowed = Set.of("gate_shot_ranking", "gate_story_edit", "gate_timeline_preview", "gate_bgm_review", "gate_render_review");
        if (!allowed.containsAll(reviewGateKeys)) {
            throw new IllegalArgumentException("Unsupported review Gate requested: " + reviewGateKeys);
        }
    }

    public WorkflowPlanPreview previewWithBlackboard(String userId, String sessionId, ProxyQuality quality,
                                                     String durationPrompt, boolean autoMode,
                                                     WorkflowCapabilities requested, boolean useDefault,
                                                     String goal, List<String> assetIds) {
        return previewWithBlackboard(userId, sessionId, quality, durationPrompt, autoMode, requested, useDefault, goal, assetIds, null);
    }

    public WorkflowPlanPreview previewWithBlackboard(String userId, String sessionId, ProxyQuality quality,
                                                     String durationPrompt, boolean autoMode,
                                                     WorkflowCapabilities requested, boolean useDefault,
                                                     String goal, List<String> assetIds, Set<String> reviewGateKeys) {
        var board = blackboard == null ? null : blackboard.get(userId, sessionId);
        var effectiveGoal = goal == null || goal.isBlank() ? board == null ? "" : board.goal() : goal;
        return preview(quality, durationPrompt, autoMode, requested, useDefault, effectiveGoal, assetIds, reviewGateKeys);
    }

    private ToolServiceClient.WorkflowIntentResponse requestIntent(String goal, String durationPrompt, int assetCount) {
        if (toolClient == null) return null;
        try {
            Integer durationMs = durationPrompt == null || durationPrompt.isBlank() ? null : parseDurationMs(durationPrompt);
            return toolClient.requestWorkflowIntent(new ToolServiceClient.WorkflowIntentRequest(
                goal == null ? "" : goal, durationPrompt, assetCount,
                List.of("vlmAnalysis", "sourceTranscription", "subtitles", "bgm"), durationMs));
        } catch (RuntimeException ignored) { return null; }
    }

    private int parseDurationMs(String goal) {
        if (goal == null) return 30000;
        var matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(秒|秒钟|seconds?|secs?)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(goal);
        if (matcher.find()) return Math.max(5000, Math.min(300000, (int) (Double.parseDouble(matcher.group(1)) * 1000)));
        matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(分钟|分|minutes?|mins?)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(goal);
        if (matcher.find()) return Math.max(5000, Math.min(300000, (int) (Double.parseDouble(matcher.group(1)) * 60000)));
        return 30000;
    }

    private WorkflowCapabilities capabilitiesFromIntent(ToolServiceClient.WorkflowIntentResponse intent) {
        if (intent == null || intent.capabilities() == null) return WorkflowCapabilities.defaults();
        var c = intent.capabilities();
        var source = !"DISABLED".equals(c.get("sourceTranscription"));
        var subtitles = source && !"DISABLED".equals(c.get("subtitles"));
        return new WorkflowCapabilities(true, source, subtitles, !"DISABLED".equals(c.get("bgm")));
    }

    public WorkflowDefinition buildDefinition(WorkflowDefinition defaultDefinition, WorkflowCapabilities capabilities) {
        return buildDefinition(defaultDefinition, capabilities, false, null);
    }

    public WorkflowDefinition buildDefinition(WorkflowDefinition defaultDefinition, WorkflowCapabilities capabilities, boolean autoMode) {
        return buildDefinition(defaultDefinition, capabilities, autoMode, null);
    }

    public WorkflowDefinition buildDefinition(WorkflowDefinition defaultDefinition, WorkflowCapabilities capabilities,
                                               boolean autoMode, Set<String> reviewGateKeys) {
        var selected = capabilities == null ? WorkflowCapabilities.defaults() : capabilities.normalized();
        var nodes = defaultDefinition.nodes().stream()
            .filter(node -> selected.sourceTranscription() || !"source_transcribe".equals(node.nodeKey()))
            .filter(node -> selected.subtitles() && selected.sourceTranscription() || !"subtitle_compose".equals(node.nodeKey()))
            .filter(node -> selected.bgm() || !"bgm_select".equals(node.nodeKey())).toList();
        var keys = nodes.stream().map(WorkflowDefinition.Node::nodeKey).collect(java.util.stream.Collectors.toSet());
        var edges = defaultDefinition.edges().stream().filter(edge -> keys.contains(edge.from()) && keys.contains(edge.to())).toList();
        var gates = new ArrayList<>(defaultDefinition.gates().stream()
            .filter(gate -> keys.contains(gate.afterNodeKey()))
            .filter(gate -> reviewGateKeys == null || reviewGateKeys.contains(gate.gateKey()))
            .toList());
        return new WorkflowDefinition(defaultDefinition.definitionKey(), defaultDefinition.definitionVersion(), nodes, edges, gates);
    }

    public WorkflowDefinition applyCanvasEdits(WorkflowDefinition definition, List<String> removedNodeIds, List<String> removedEdgeIds, List<CanvasEdge> addedEdges) {
        var removedNodes = removedNodeIds == null ? Set.<String>of() : removedNodeIds.stream().map(this::logicalKey).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var nodes = definition.nodes().stream().filter(node -> !removedNodes.contains(node.nodeKey())).toList();
        var nodeKeys = nodes.stream().map(WorkflowDefinition.Node::nodeKey).collect(java.util.stream.Collectors.toSet());
        var removed = removedEdgeIds == null ? Set.<String>of() : Set.copyOf(removedEdgeIds);
        var edges = new ArrayList<WorkflowDefinition.Edge>();
        for (var edge : definition.edges()) {
            var blocked = removed.stream().anyMatch(id -> edgeMatches(id, edge));
            if (!blocked && nodeKeys.contains(edge.from()) && nodeKeys.contains(edge.to())) edges.add(edge);
        }
        if (addedEdges != null) for (var edge : addedEdges) {
            var from = logicalKey(edge.from()); var to = logicalKey(edge.to());
            if (from != null && to != null && !from.equals(to) && edges.stream().noneMatch(e -> e.from().equals(from) && e.to().equals(to))) {
                // User-created canvas connections are explicit execution dependencies.
                // Optional edges are reserved for the system-defined subtitle/render
                // enhancements and are not exposed by the canvas editor.
                edges.add(new WorkflowDefinition.Edge(from, to, WorkflowDefinition.DependencyType.REQUIRED));
            }
        }
        var gates = definition.gates().stream().filter(gate -> nodeKeys.contains(gate.afterNodeKey())).toList();
        var edited = new WorkflowDefinition(definition.definitionKey(), definition.definitionVersion(), nodes, edges, gates);
        validator.validate(edited);
        return edited;
    }

    public ValidationResult validateCanvasEdits(WorkflowDefinition definition, List<String> removedNodeIds, List<String> removedEdgeIds, List<CanvasEdge> addedEdges) {
        try {
            applyCanvasEdits(definition, removedNodeIds, removedEdgeIds, addedEdges);
            return new ValidationResult(true, List.of());
        } catch (IllegalArgumentException error) {
            return new ValidationResult(false, List.of(error.getMessage() == null ? "流程图不满足执行约束" : error.getMessage()));
        }
    }

    private String logicalKey(String canvasId) {
        if (canvasId == null) return null;
        var index = canvasId.lastIndexOf(':');
        var arrow = canvasId.indexOf("->");
        if (arrow >= 0) return logicalKey(canvasId.substring(0, arrow));
        return index < 0 ? canvasId : canvasId.substring(index + 1);
    }

    private boolean edgeMatches(String canvasId, WorkflowDefinition.Edge edge) {
        if (canvasId == null) return false;
        var arrow = canvasId.indexOf("->");
        if (arrow < 0) return false;
        return edge.from().equals(logicalKey(canvasId.substring(0, arrow)))
            && edge.to().equals(logicalKey(canvasId.substring(arrow + 2)));
    }

    private CanvasGraph expandCanvas(WorkflowDefinition definition, List<String> assetIds) {
        var ids = assetIds == null || assetIds.isEmpty() ? List.of("asset-1") : assetIds;
        var nodes = new ArrayList<CanvasNode>(); var byLogical = new LinkedHashMap<String, List<String>>();
        for (int ai = 0; ai < ids.size(); ai++) {
            int col = 0;
            for (var node : definition.nodes()) if (node.scope() == WorkflowDefinition.NodeScope.ASSET) {
                var id = "asset:" + ids.get(ai) + ":" + node.nodeKey();
                byLogical.computeIfAbsent(node.nodeKey(), k -> new ArrayList<>()).add(id);
                nodes.add(new CanvasNode(id, node.nodeKey(), chineseLabel(node.nodeKey()), node.toolName(), node.toolVersion(), "ASSET", ids.get(ai), ai, 30 + col++ * 194, 24 + ai * 172, OPTIONAL_NODE_KEYS.contains(node.nodeKey())));
            }
        }
        int col = 0;
        for (var node : definition.nodes()) if (node.scope() == WorkflowDefinition.NodeScope.WORKFLOW) {
            var id = "workflow:" + node.nodeKey(); byLogical.put(node.nodeKey(), List.of(id));
            nodes.add(new CanvasNode(id, node.nodeKey(), chineseLabel(node.nodeKey()), node.toolName(), node.toolVersion(), "WORKFLOW", null, null, 30 + col++ * 194, 24 + ids.size() * 172, OPTIONAL_NODE_KEYS.contains(node.nodeKey())));
        }
        var edges = new ArrayList<CanvasEdge>();
        for (var edge : definition.edges()) {
            var froms = byLogical.getOrDefault(edge.from(), List.of()); var tos = byLogical.getOrDefault(edge.to(), List.of());
            if (froms.size() == tos.size()) for (int i = 0; i < froms.size(); i++) edges.add(new CanvasEdge(froms.get(i) + "->" + tos.get(i), froms.get(i), tos.get(i), edge.dependencyType().name(), true));
            else for (var from : froms) for (var to : tos) edges.add(new CanvasEdge(from + "->" + to, from, to, edge.dependencyType().name(), true));
        }
        return new CanvasGraph(nodes, edges);
    }

    private String chineseLabel(String key) { return switch (key) {
        case "video_probe" -> "视频探测"; case "video_proxy_generate" -> "生成代理视频"; case "video_shot_detect" -> "镜头切分";
        case "vision_quality_score" -> "质量评分"; case "vision_vlm_analyze" -> "视觉语义分析"; case "source_transcribe" -> "源音频转写";
        case "shot_ranking" -> "镜头排序"; case "story_plan" -> "故事编排"; case "highlight_selection" -> "高光选择";
        case "timeline_compose" -> "时间线合成"; case "bgm_select" -> "背景音乐"; case "subtitle_compose" -> "字幕编排";
        case "video_render" -> "最终渲染"; default -> key; };
    }
    private String chineseReason(String key) { return "系统受控能力节点：" + chineseLabel(key); }

    public record WorkflowCapabilities(boolean vlmAnalysis, boolean sourceTranscription, boolean subtitles, boolean bgm) {
        public static WorkflowCapabilities defaults() { return new WorkflowCapabilities(true, true, true, true); }
        public WorkflowCapabilities normalized() { return new WorkflowCapabilities(true, sourceTranscription, subtitles, bgm); }
    }
    public record WorkflowIntentView(String goal, String targetDuration, String explanation, WorkflowCapabilities capabilities) {}
    public record NodeExplanation(String nodeKey, String label, String reason, String tool, boolean optional,
                                  String automationPolicy, boolean requiresUserConfirmation, int maxAttempts,
                                  String resourceGroup) {}
    public record WorkflowPlanPreview(WorkflowIntentView intent, WorkflowDefinition definition, WorkflowDefinition defaultDefinition,
                                      List<NodeExplanation> explanations, boolean defaultSelected, CanvasGraph canvas, boolean llmUsed,
                                      List<String> governanceWarnings, boolean requiresConfirmation,
                                      List<String> requiredGates, String automationMode) {}
    public record CanvasGraph(List<CanvasNode> nodes, List<CanvasEdge> edges) {}
    public record CanvasNode(String id, String logicalNodeKey, String label, String toolName, String toolVersion, String scope,
                             String assetId, Integer assetIndex, int x, int y, boolean optional) {}
    public record CanvasEdge(String id, String from, String to, String dependencyType, boolean deletable) {}
    public record ValidationResult(boolean valid, List<String> errors) {}
}
