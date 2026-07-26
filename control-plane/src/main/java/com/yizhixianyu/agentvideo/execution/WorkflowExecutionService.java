package com.yizhixianyu.agentvideo.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.asset.AssetEntity;
import com.yizhixianyu.agentvideo.asset.AssetService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import com.yizhixianyu.agentvideo.plan.CustomStoryPlanRepository;
import com.yizhixianyu.agentvideo.plan.TimelineComposer;
import com.yizhixianyu.agentvideo.workflow.MultiAssetAnalysisTemplate;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinitionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.Duration;

@Service
public class WorkflowExecutionService {

    private final WorkflowRunRepository workflowRepository;
    private final WorkflowAssetRepository workflowAssetRepository;
    private final TaskRunRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final ArtifactRepository artifactRepository;
    private final ProjectService projectService;
    private final AssetService assetService;
    private final ToolServiceClient toolClient;
    private final ObjectMapper objectMapper;
    private final MultiAssetAnalysisTemplate analysisTemplate;
    private final WorkflowDefinitionValidator definitionValidator;
    private final String publicBaseUrl;
    private final int maxAttempts;
    private final long retryBaseDelayMs;
    private final long dispatchRecoveryTimeoutMs;
    private final int pollFailureLimit;
    private final ApplicationEventPublisher eventPublisher;
    private final Path artifactRoot;

    public WorkflowExecutionService(
        WorkflowRunRepository workflowRepository,
        WorkflowAssetRepository workflowAssetRepository,
        TaskRunRepository taskRepository,
        TaskDependencyRepository dependencyRepository,
        ToolExecutionRepository toolExecutionRepository,
        ArtifactRepository artifactRepository,
        ProjectService projectService,
        AssetService assetService,
        ToolServiceClient toolClient,
        ObjectMapper objectMapper,
        MultiAssetAnalysisTemplate analysisTemplate,
        WorkflowDefinitionValidator definitionValidator,
        @Value("${app.public-base-url}") String publicBaseUrl,
        @Value("${app.workflow.max-attempts:3}") int maxAttempts,
        @Value("${app.workflow.retry-base-delay-ms:1000}") long retryBaseDelayMs,
        @Value("${app.workflow.dispatch-recovery-timeout-ms:30000}") long dispatchRecoveryTimeoutMs,
        @Value("${app.workflow.poll-failure-limit:10}") int pollFailureLimit,
        @Value("${app.artifact-root:runtime/artifacts}") Path artifactRoot,
        ApplicationEventPublisher eventPublisher
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowAssetRepository = workflowAssetRepository;
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.artifactRepository = artifactRepository;
        this.projectService = projectService;
        this.assetService = assetService;
        this.toolClient = toolClient;
        this.objectMapper = objectMapper;
        this.analysisTemplate = analysisTemplate;
        this.definitionValidator = definitionValidator;
        this.publicBaseUrl = publicBaseUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseDelayMs = Math.max(0, retryBaseDelayMs);
        this.dispatchRecoveryTimeoutMs = Math.max(1000, dispatchRecoveryTimeoutMs);
        this.pollFailureLimit = Math.max(1, pollFailureLimit);
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public WorkflowRunEntity createVideoProxyRun(String projectId, String assetId, ProxyQuality proxyQuality) {
        projectService.getRequired(projectId);
        var asset = assetService.getRequired(assetId);
        requireProjectAsset(projectId, asset);
        var workflow = workflowRepository.save(new WorkflowRunEntity(
            projectId, assetId, "VIDEO_PROXY_PIPELINE", proxyQuality
        ));
        workflow.start();
        var probeTask = taskRepository.save(new TaskRunEntity(
            workflow.getId(), "video_probe", "video.probe", "1.0.0", null
        ));
        taskRepository.save(new TaskRunEntity(
            workflow.getId(), "video_proxy_generate", "video.proxy-generate", "1.0.0", probeTask.getId()
        ));
        probeTask.markReady();
        eventPublisher.publishEvent(new WorkflowDispatchRequested(workflow.getId(), probeTask.getId()));
        return workflow;
    }

    @Transactional
    public WorkflowRunEntity createMultiAssetAnalysisRun(
        String projectId,
        List<String> requestedAssetIds,
        ProxyQuality proxyQuality
    ) {
        return createMultiAssetAnalysisRun(projectId, requestedAssetIds, proxyQuality, null, false);
    }

    @Transactional
    public WorkflowRunEntity createMultiAssetAnalysisRun(
        String projectId,
        List<String> requestedAssetIds,
        ProxyQuality proxyQuality,
        String durationPrompt,
        boolean autoMode
    ) {
        projectService.getRequired(projectId);
        if (requestedAssetIds == null || requestedAssetIds.isEmpty()) {
            throw new IllegalArgumentException("At least one Asset is required");
        }
        var uniqueAssetIds = new LinkedHashSet<>(requestedAssetIds);
        if (uniqueAssetIds.size() != requestedAssetIds.size()) {
            throw new IllegalArgumentException("Asset list must not contain duplicates");
        }
        var assets = uniqueAssetIds.stream().map(assetService::getRequired).toList();
        assets.forEach(asset -> requireProjectAsset(projectId, asset));

        var definition = analysisTemplate.create(proxyQuality, durationPrompt);
        definitionValidator.validate(definition);
        var workflow = workflowRepository.save(new WorkflowRunEntity(
            projectId,
            assets.get(0).getId(),
            "MULTI_ASSET_ANALYSIS",
            proxyQuality,
            definition.definitionKey(),
            definition.definitionVersion(),
            toJson(definition)
        ));
        workflow.setAutoMode(autoMode);
        workflow.setGatesJson(toJson(definition.gates()));
        workflow.start();

        for (var index = 0; index < assets.size(); index++) {
            var asset = assets.get(index);
            workflowAssetRepository.save(new WorkflowAssetEntity(workflow.getId(), asset.getId(), index));
        }
        expandTasks(workflow, assets, definition);
        evaluateWorkflow(workflow.getId());
        return workflow;
    }

    @Transactional
    public String createCustomRenderRun(String projectId, String sourceWorkflowRunId, Map<String, Object> customPlan) {
        var sourceWorkflow = workflowRepository.findById(sourceWorkflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Source workflow run not found: " + sourceWorkflowRunId));
        var proxyQuality = sourceWorkflow.getProxyQuality();

        var timelineJson = TimelineComposer.compose(customPlan, proxyQuality);

        var artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        var artifactDir = artifactRoot.resolve(artifactId);
        try {
            Files.createDirectories(artifactDir);
            Files.writeString(artifactDir.resolve("timeline.json"), timelineJson, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write TIMELINE artifact", e);
        }
        var storageUri = artifactDir.resolve("timeline.json").toUri().toString();
        byte[] contentBytes = timelineJson.getBytes(StandardCharsets.UTF_8);
        String contentHash;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            contentHash = HexFormat.of().formatHex(digest.digest(contentBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        var renderWorkflow = workflowRepository.save(new WorkflowRunEntity(
            projectId,
            sourceWorkflow.getAssetId(),
            "CUSTOM_PLAN_RENDER",
            proxyQuality
        ));
        renderWorkflow.start();

        var virtualTask = taskRepository.save(new TaskRunEntity(
            renderWorkflow.getId(), "timeline_compose_virtual", "timeline.compose", "1.1.0", null
        ));
        virtualTask.markReady();
        virtualTask.markDispatching();
        virtualTask.markRunning();
        virtualTask.markSucceeded();

        artifactRepository.save(new ArtifactEntity(
            artifactId, projectId, virtualTask.getId(), "TIMELINE",
            storageUri, "application/json", contentBytes.length, contentHash, timelineJson
        ));

        var renderTask = taskRepository.save(new TaskRunEntity(
            renderWorkflow.getId(),
            null,
            "workflow:video_render",
            "video_render",
            "video.render",
            "1.1.0",
            "UPSTREAM_ARTIFACT",
            "{}"
        ));

        dependencyRepository.save(new TaskDependencyEntity(renderTask.getId(), virtualTask.getId()));

        evaluateWorkflow(renderWorkflow.getId());
        return renderWorkflow.getId();
    }

    private void expandTasks(
        WorkflowRunEntity workflow,
        List<AssetEntity> assets,
        WorkflowDefinition definition
    ) {
        var tasksByInstance = new HashMap<String, TaskRunEntity>();
        for (var node : definition.nodes()) {
            if (node.scope() == WorkflowDefinition.NodeScope.WORKFLOW) {
                tasksByInstance.put(instanceKey(node, null), saveTask(workflow, null, node));
            } else {
                for (var asset : assets) {
                    tasksByInstance.put(instanceKey(node, asset), saveTask(workflow, asset, node));
                }
            }
        }
        for (var edge : definition.edges()) {
            var from = definition.nodes().stream().filter(node -> node.nodeKey().equals(edge.from())).findFirst().orElseThrow();
            var to = definition.nodes().stream().filter(node -> node.nodeKey().equals(edge.to())).findFirst().orElseThrow();
            if (to.scope() == WorkflowDefinition.NodeScope.ASSET) {
                for (var asset : assets) {
                    saveDependency(tasksByInstance, from, from.scope() == WorkflowDefinition.NodeScope.ASSET ? asset : null, to, asset, edge.dependencyType());
                }
            } else if (from.scope() == WorkflowDefinition.NodeScope.ASSET) {
                for (var asset : assets) {
                    saveDependency(tasksByInstance, from, asset, to, null, edge.dependencyType());
                }
            } else {
                saveDependency(tasksByInstance, from, null, to, null, edge.dependencyType());
            }
        }
    }

    private TaskRunEntity saveTask(
        WorkflowRunEntity workflow,
        AssetEntity asset,
        WorkflowDefinition.Node node
    ) {
        var assetId = asset == null ? null : asset.getId();
        return taskRepository.save(new TaskRunEntity(
            workflow.getId(), assetId, assetId == null ? "workflow:" + node.nodeKey() : assetId + ":" + node.nodeKey(),
            node.nodeKey(), node.toolName(), node.toolVersion(), node.inputBinding().name(),
            toJson(node.parameters() == null ? Map.of() : node.parameters())
        ));
    }

    private void saveDependency(
        Map<String, TaskRunEntity> tasks,
        WorkflowDefinition.Node from,
        AssetEntity fromAsset,
        WorkflowDefinition.Node to,
        AssetEntity toAsset,
        WorkflowDefinition.DependencyType dependencyType
    ) {
        dependencyRepository.save(new TaskDependencyEntity(
            tasks.get(instanceKey(to, toAsset)).getId(), tasks.get(instanceKey(from, fromAsset)).getId(), dependencyType
        ));
    }

    private String instanceKey(WorkflowDefinition.Node node, AssetEntity asset) {
        return (asset == null ? "workflow" : asset.getId()) + ":" + node.nodeKey();
    }

    @Transactional
    public DispatchContext prepareDispatch(String workflowRunId, String taskRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var task = taskRepository.findLockedById(taskRunId).orElseThrow();
        if (task.getStatus() != TaskStatus.READY && task.getStatus() != TaskStatus.DISPATCHING) {
            return null;
        }
        var asset = resolveTaskAsset(workflow, task);
        if (task.getStatus() == TaskStatus.READY) {
            task.markDispatching();
        } else {
            task.resumeDispatching();
        }
        var idempotencyKey = task.getNodeKey() + ":" + task.getId() + ":" + task.getAttempt();
        var parameters = new java.util.HashMap<>(parseParameters(task));
        if ("video.proxy-generate".equals(task.getToolName())) {
            parameters.putIfAbsent("quality", workflow.getProxyQuality().value());
        }
        if ("video.shot-detect".equals(task.getToolName())) {
            if (asset == null) {
                throw new IllegalStateException("Shot detection requires an Asset-scoped Task");
            }
            parameters.put("sourceAssetId", asset.getId());
        }
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            task.getToolName(),
            task.getToolVersion(),
            idempotencyKey,
            resolveInputs(task, asset),
            parameters,
            publicBaseUrl + "/internal/tool-callbacks",
            new ToolServiceClient.TraceContext(UUID.randomUUID().toString(), workflow.getId(), task.getId())
        );
        return new DispatchContext(idempotencyKey, request);
    }

    private Map<String, ToolServiceClient.ArtifactInput> resolveInputs(TaskRunEntity task, AssetEntity asset) {
        if (!"UPSTREAM_ARTIFACT".equals(task.getInputBinding())) {
            if (asset == null) {
                throw new IllegalStateException("Project Asset input requires an Asset-scoped Task");
            }
            return Map.of("video", new ToolServiceClient.ArtifactInput(
                asset.getId(), asset.getStorageUri(), asset.getFileName()
            ));
        }
        var dependencyIds = dependencyRepository.findByTaskRunId(task.getId()).stream()
            .map(TaskDependencyEntity::getDependsOnTaskRunId)
            .toList();
        var artifacts = artifactRepository.findByProducerTaskRunIdIn(dependencyIds);
        var inputs = new LinkedHashMap<String, ToolServiceClient.ArtifactInput>();
        var counts = new HashMap<String, Integer>();
        for (var artifact : artifacts) {
            if (!acceptedInputTypes(task.getToolName()).contains(artifact.getType())) {
                continue;
            }
            var baseKey = inputKey(artifact.getType());
            var index = counts.merge(baseKey, 1, Integer::sum) - 1;
            var key = index == 0 ? baseKey : baseKey + index;
            inputs.put(key, new ToolServiceClient.ArtifactInput(
                artifact.getExternalArtifactId(), artifact.getStorageUri(), artifact.getType()
            ));
        }
        if (inputs.isEmpty()) {
            throw new IllegalStateException("Required upstream Artifact is missing for " + task.getInstanceKey());
        }
        return inputs;
    }

    private AssetEntity resolveTaskAsset(WorkflowRunEntity workflow, TaskRunEntity task) {
        if (task.getAssetId() != null) {
            return assetService.getRequired(task.getAssetId());
        }
        if (task.getInstanceKey() != null && task.getInstanceKey().startsWith("workflow:")) {
            return null;
        }
        return workflow.getAssetId() == null ? null : assetService.getRequired(workflow.getAssetId());
    }

    private Set<String> acceptedInputTypes(String toolName) {
        return switch (toolName) {
            case "video.shot-detect" -> Set.of("VIDEO_PROXY");
            case "vision.quality-score" -> Set.of("VIDEO_PROXY", "SHOT_LIST");
            case "vision.scene-classify" -> Set.of("SHOT_LIST");
            case "vision.object-detect" -> Set.of("SHOT_LIST");
            case "vision.person-detect" -> Set.of("SHOT_LIST");
            case "vision.vlm-analyze" -> Set.of("SHOT_LIST");
            case "audio.source-transcribe" -> Set.of("VIDEO_PROXY");
            case "decision.shot-rank" -> Set.of("SHOT_QUALITY");
            case "planning.story-template" -> Set.of("SHOT_RANKING", "SCENE_TAGS", "OBJECT_TAGS", "PERSON_TAGS");
            case "decision.highlight-select" -> Set.of("STORY_PLAN", "SHOT_RANKING");
            case "timeline.compose" -> Set.of("HIGHLIGHT_SET");
            case "audio.bgm-select" -> Set.of("STORY_PLAN", "TIMELINE");
            case "subtitle.compose" -> Set.of("TIMELINE", "SOURCE_TRANSCRIPT");
            case "video.render" -> Set.of("TIMELINE", "BGM_AUDIO", "SUBTITLE_SRT");
            default -> Set.of();
        };
    }

    private String inputKey(String artifactType) {
        return switch (artifactType) {
            case "VIDEO_PROXY" -> "video";
            case "SHOT_LIST" -> "shots";
            case "SHOT_QUALITY" -> "quality";
            case "SHOT_RANKING" -> "ranking";
            case "STORY_PLAN" -> "story";
            case "HIGHLIGHT_SET" -> "highlights";
            case "SCENE_TAGS" -> "scene";
            case "OBJECT_TAGS" -> "object";
            case "PERSON_TAGS" -> "person";
            case "TIMELINE" -> "timeline";
            case "SOURCE_TRANSCRIPT" -> "transcript";
            case "BGM_AUDIO" -> "bgm";
            case "SUBTITLE_SRT" -> "subtitle";
            default -> "artifact";
        };
    }

    @Transactional
    public void markAccepted(
        String workflowRunId,
        String taskRunId,
        String idempotencyKey,
        ToolServiceClient.AcceptedExecution accepted
    ) {
        if (accepted == null || accepted.executionId() == null) {
            throw new IllegalStateException("Tool Service returned an invalid acceptance response");
        }
        workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var task = taskRepository.findLockedById(taskRunId).orElseThrow();
        if (task.getStatus() != TaskStatus.DISPATCHING && task.getStatus() != TaskStatus.RUNNING) {
            return;
        }
        task.markRunning();
        toolExecutionRepository.findByTaskRunIdAndIdempotencyKey(taskRunId, idempotencyKey)
            .ifPresentOrElse(
                execution -> execution.replaceAcceptance(accepted.executionId(), accepted.status()),
                () -> toolExecutionRepository.save(new ToolExecutionEntity(
                    taskRunId, idempotencyKey, accepted.executionId(), accepted.status()
                ))
            );
    }

    @Transactional
    public void markDispatchFailed(String workflowRunId, String taskRunId, String message) {
        workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var task = taskRepository.findLockedById(taskRunId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.DISPATCHING) {
            return;
        }
        if (task.canRetry(maxAttempts)) {
            task.scheduleRetry(message, nextRetryAt(task), true);
        } else {
            task.markFailed(message);
        }
        evaluateWorkflow(workflowRunId);
    }

    @Transactional
    public void applyToolResult(ToolServiceClient.ToolExecutionResponse response) {
        var workflowRunId = toolExecutionRepository.findWorkflowRunIdByExternalExecutionId(response.executionId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Tool execution: " + response.executionId()));
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var execution = toolExecutionRepository.findLockedByExternalExecutionId(response.executionId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Tool execution: " + response.executionId()));
        if (execution.isTerminal()) {
            return;
        }
        execution.updateStatus(response.status());
        var task = taskRepository.findLockedById(execution.getTaskRunId()).orElseThrow();

        if ("RUNNING".equals(response.status())) {
            task.markRunning();
            task.updateProgress(response.progress());
            return;
        }
        if ("QUEUED".equals(response.status())) {
            return;
        }
        if ("SUCCEEDED".equals(response.status())) {
            for (var output : response.outputs() == null ? List.<ToolServiceClient.ArtifactOutput>of() : response.outputs()) {
                if (artifactRepository.findByExternalArtifactId(output.artifactId()).isEmpty()) {
                    artifactRepository.save(new ArtifactEntity(
                        output.artifactId(), workflow.getProjectId(), task.getId(), output.type(), output.uri(),
                        output.mediaType(), output.size(), output.contentHash(), toJson(output.metadata())
                    ));
                }
            }
            task.markSucceeded();
            evaluateWorkflow(task.getWorkflowRunId());
            return;
        }
        if ("FAILED".equals(response.status()) || "CANCELLED".equals(response.status())) {
            var message = response.error() == null ? "Tool execution failed" : response.error().message();
            var retryable = response.error() != null && response.error().retryable();
            if (retryable && task.canRetry(maxAttempts)) {
                task.scheduleRetry(message, nextRetryAt(task), false);
            } else {
                task.markFailed(message);
            }
            evaluateWorkflow(task.getWorkflowRunId());
        }
    }


    /** 安全反序列化 WorkflowDefinition，忽略格式错误 */
    /**
     * 安全反序列化 WorkflowDefinition。
     * 如果 JSON 中缺少 gates 字段（旧版本定义），返回无 Gate 的 definition 以保持向后兼容。
     * 反序列化失败时记录警告日志并返回 null，Gate 将不会触发。
     */
    private WorkflowDefinition safeParseDefinition(WorkflowRunEntity workflow) {
        var json = workflow.getDefinitionJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            // 反序列化失败通常是因为 JSON 格式是旧版本的 WorkflowDefinition（缺少 gates 字段）
            // 此时返回 null，Gate 不会触发，Workflow 将按旧行为全自动运行
            System.err.println("[WARN] Failed to parse WorkflowDefinition JSON for workflow "
                + workflow.getId() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 查找与指定任务的上游节点关联的 Gate。
     * 规则：如果某个上游 Task 完成后应该触发 Gate（即 definition 中有一个 Gate 的 afterNodeKey
     * 等于该上游 Task 的 nodeKey），且该 Gate 的下游 Task 正是当前 task，则返回该 Gate。
     */

    /**
     * 从 definitionJson 中直接提取 Gate 列表（避免完整 WorkflowDefinition 反序列化失败）。
     * 使用 Jackson Tree API，即使 Node 结构无法反序列化也能提取 gates。
     */
    private List<WorkflowDefinition.Gate> parseGatesFromJson(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank()) {
            return List.of();
        }
        try {
            var root = objectMapper.readTree(definitionJson);
            var gatesNode = root.isArray() ? root : root.get("gates");
            if (gatesNode == null || !gatesNode.isArray()) {
                return List.of();
            }
            var gates = new java.util.ArrayList<WorkflowDefinition.Gate>();
            for (var node : gatesNode) {
                gates.add(new WorkflowDefinition.Gate(
                    node.get("gateKey").asText(),
                    node.get("afterNodeKey").asText(),
                    node.get("label").asText(),
                    node.get("description").asText()
                ));
            }
            return gates;
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse gates from definition JSON: " + e.getMessage());
            return List.of();
        }
    }

    private WorkflowDefinition.Gate findGateForTask(
        WorkflowDefinition definition,
        TaskRunEntity task,
        List<TaskRunEntity> upstream,
        Map<String, TaskRunEntity> tasksById
    ) {
        if (definition == null || definition.gates() == null || definition.gates().isEmpty()) {
            return null;
        }
        for (var up : upstream) {
            if (up.getStatus() != TaskStatus.SUCCEEDED) continue;
            for (var gate : definition.gates()) {
                if (gate.afterNodeKey().equals(up.getNodeKey())) {
                    return gate;
                }
            }
        }
        return null;
    }

    private WorkflowDefinition.Gate findTerminalGate(
        WorkflowDefinition definition,
        List<TaskRunEntity> tasks,
        WorkflowRunEntity workflow
    ) {
        if (workflow.isAutoMode() || definition == null || definition.gates() == null) {
            return null;
        }
        for (var gate : definition.gates()) {
            if (workflow.hasCompletedGate(gate.gateKey())) {
                continue;
            }
            var producerSucceeded = tasks.stream().anyMatch(task ->
                gate.afterNodeKey().equals(task.getNodeKey()) && task.getStatus() == TaskStatus.SUCCEEDED
            );
            if (producerSucceeded) {
                return gate;
            }
        }
        return null;
    }

    private boolean isOptionalEnhancement(TaskRunEntity task) {
        return Set.of(
            "audio.source-transcribe",
            "subtitle.compose",
            "audio.bgm-select"
        ).contains(task.getToolName());
    }

    @Transactional
    public void continueWorkflow(String workflowRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        if (workflow.getStatus() != RunStatus.PAUSED) {
            throw new IllegalStateException("Workflow is not paused: " + workflowRunId);
        }
        workflow.completeCurrentGate();
        workflow.resume();
        evaluateWorkflow(workflowRunId);
    }

    private void evaluateWorkflow(String workflowRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var tasksById = tasks.stream().collect(Collectors.toMap(TaskRunEntity::getId, Function.identity()));
        var dependencies = dependencyRepository.findByTaskRunIdIn(tasksById.keySet().stream().toList()).stream()
            .collect(Collectors.groupingBy(TaskDependencyEntity::getTaskRunId));

        // 解析 WorkflowDefinition 和 gates（优先完整反序列化，失败则直接提取 gates）
        var definition = safeParseDefinition(workflow);
        if (definition == null || definition.gates() == null || definition.gates().isEmpty()) {
            // 完整反序列化失败时，尝试直接从 JSON 提取 gates
            var directGates = parseGatesFromJson(workflow.getDefinitionJson());
            if (!directGates.isEmpty()) {
                // 构造一个最小 WorkflowDefinition 仅携带 gates 信息
                definition = new WorkflowDefinition(
                    workflow.getDefinitionKey() != null ? workflow.getDefinitionKey() : "",
                    workflow.getDefinitionVersion() != null ? workflow.getDefinitionVersion() : 0,
                    List.of(), List.of(), directGates
                );
            }
        }

        var changed = true;
        while (changed) {
            changed = false;
            for (var task : tasks) {
                if (task.getStatus() != TaskStatus.PENDING) {
                    continue;
                }
                var upstream = dependencies.getOrDefault(task.getId(), List.of()).stream()
                    .map(item -> tasksById.get(item.getDependsOnTaskRunId()))
                    .toList();
                var dependencyRows = dependencies.getOrDefault(task.getId(), List.of());
                var requiredUpstreamFailed = dependencyRows.stream()
                    .filter(item -> item.getDependencyType() == WorkflowDefinition.DependencyType.REQUIRED)
                    .map(item -> tasksById.get(item.getDependsOnTaskRunId()))
                    .anyMatch(item -> item.getStatus() == TaskStatus.FAILED || item.getStatus() == TaskStatus.SKIPPED);
                var allUpstreamTerminal = upstream.stream().allMatch(this::isTerminal);
                if (requiredUpstreamFailed) {
                    task.markSkipped("A required upstream Task did not succeed");
                    changed = true;
                } else if (allUpstreamTerminal) {
                    /* Gate 检查：如果上游 Node 关联了 Gate 且非 auto 模式，暂停 Workflow */
                    var gate = findGateForTask(definition, task, upstream, tasksById);
                    if (gate != null && !workflow.isAutoMode() && !workflow.hasCompletedGate(gate.gateKey())) {
                        workflow.pause(gate.gateKey());
                        changed = true;
                        continue;
                    }
                    task.markReady();
                    eventPublisher.publishEvent(new WorkflowDispatchRequested(workflowRunId, task.getId()));
                    changed = true;
                }
            }
        }

        var terminal = tasks.stream().filter(this::isTerminal).count();
        if (terminal == tasks.size()) {
            var pendingGate = findTerminalGate(definition, tasks, workflow);
            if (pendingGate != null) {
                workflow.pause(pendingGate.gateKey());
                return;
            }
            var failed = tasks.stream()
                .filter(task -> !isOptionalEnhancement(task))
                .filter(task -> task.getStatus() == TaskStatus.FAILED)
                .findFirst();
            if (failed.isPresent()) {
                workflow.fail(failed.get().getErrorMessage());
            } else if (tasks.stream()
                .filter(task -> !isOptionalEnhancement(task))
                .allMatch(task -> task.getStatus() == TaskStatus.SUCCEEDED)) {
                workflow.succeed();
            } else {
                workflow.fail("One or more required Tasks were skipped");
            }
        } else if (workflow.getStatus() == RunStatus.RUNNING) {
            workflow.start();
            workflow.updateProgress((int) (terminal * 100 / tasks.size()));
        }
    }
    private boolean isTerminal(TaskRunEntity task) {
        return task.getStatus() == TaskStatus.SUCCEEDED
            || task.getStatus() == TaskStatus.FAILED
            || task.getStatus() == TaskStatus.SKIPPED;
    }

    @Transactional(readOnly = true)
    public WorkflowSnapshot getSnapshot(String workflowRunId) {
        var workflow = workflowRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var taskIds = tasks.stream().map(TaskRunEntity::getId).toList();

        // 解析 gates —— 优先使用 gatesJson 字段（更可靠），fallback 到完整反序列化
        var gates = new java.util.ArrayList<GateDef>();
        var gatesJson = workflow.getGatesJson();
        if (gatesJson != null && !gatesJson.isBlank()) {
            for (var g : parseGatesFromJson(gatesJson)) {
                gates.add(new GateDef(g.gateKey(), g.label(), g.description()));
            }
        } else {
            var def = safeParseDefinition(workflow);
            if (def != null && def.gates() != null) {
                for (var g : def.gates()) {
                    gates.add(new GateDef(g.gateKey(), g.label(), g.description()));
                }
            }
        }
        var dependencies = dependencyRepository.findByTaskRunIdIn(taskIds).stream()

            .collect(Collectors.groupingBy(TaskDependencyEntity::getTaskRunId));
        var taskSnapshots = tasks.stream().map(task -> new TaskSnapshot(
            task.getId(), task.getAssetId(), task.getInstanceKey(), task.getNodeKey(), task.getToolName(),
            task.getToolVersion(), task.getStatus(),
            dependencies.getOrDefault(task.getId(), List.of()).stream()
                .map(TaskDependencyEntity::getDependsOnTaskRunId).toList(),
            task.getProgress(), task.getAttempt(), task.getRetryCount(), task.getNextAttemptAt(),
            task.getErrorMessage(),
            artifactRepository.findByProducerTaskRunId(task.getId()).stream()
                .map(artifact -> new ArtifactSnapshot(
                    artifact.getId(), artifact.getExternalArtifactId(), artifact.getType(), artifact.getStorageUri(),
                    artifact.getMediaType(), artifact.getMetadataJson(),
                    "/api/v1/artifacts/" + artifact.getId() + "/content"
                )).toList()
        )).toList();
        var assets = workflowAssets(workflow).stream().map(asset -> new AssetSnapshot(
            asset.getId(), asset.getFileName(), asset.getSizeBytes(), asset.getStatus()
        )).toList();
        return new WorkflowSnapshot(
            workflow.getId(), workflow.getProjectId(), workflow.getAssetId(), workflow.getWorkflowType(),
            workflow.getDefinitionKey(), workflow.getDefinitionVersion(), workflow.getProxyQuality().value(),
            workflow.getStatus(), workflow.getProgress(), workflow.getErrorMessage(),
            workflow.isAutoMode(), workflow.getCurrentGateKey(),
            gates,
            assets, taskSnapshots
        );
    }

    @Transactional(readOnly = true)
    public List<WorkflowHistoryItem> listProjectRuns(String projectId) {
        projectService.getRequired(projectId);
        return workflowRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
            .map(workflow -> {
                var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflow.getId());
                return new WorkflowHistoryItem(
                    workflow.getId(), workflow.getWorkflowType(), workflow.getDefinitionKey(),
                    workflow.getDefinitionVersion(), workflow.getProxyQuality().value(), workflow.getStatus(),
                    workflow.getProgress(), workflow.getErrorMessage(), workflowAssets(workflow).size(), tasks.size(),
                    workflow.getCreatedAt(), workflow.getStartedAt(), workflow.getCompletedAt()
                );
            }).toList();
    }

    private List<AssetEntity> workflowAssets(WorkflowRunEntity workflow) {
        var links = workflowAssetRepository.findByWorkflowRunIdOrderByPositionIndexAsc(workflow.getId());
        if (!links.isEmpty()) {
            return links.stream().map(link -> assetService.getRequired(link.getAssetId())).toList();
        }
        return workflow.getAssetId() == null ? List.of() : List.of(assetService.getRequired(workflow.getAssetId()));
    }

    @Transactional(readOnly = true)
    public List<ToolExecutionEntity> findPendingToolExecutions() {
        return toolExecutionRepository.findByStatusIn(List.of("QUEUED", "RUNNING"));
    }

    @Transactional
    public void recordPollFailure(String externalExecutionId, String message) {
        var workflowRunId = toolExecutionRepository.findWorkflowRunIdByExternalExecutionId(externalExecutionId)
            .orElse(null);
        if (workflowRunId == null) {
            return;
        }
        workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var execution = toolExecutionRepository.findLockedByExternalExecutionId(externalExecutionId).orElse(null);
        if (execution == null || execution.isTerminal()) {
            return;
        }
        if (execution.recordPollFailure() < pollFailureLimit) {
            return;
        }
        execution.updateStatus("LOST");
        var task = taskRepository.findLockedById(execution.getTaskRunId()).orElseThrow();
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.DISPATCHING) {
            return;
        }
        if (task.canRetry(maxAttempts)) {
            task.scheduleRetry("Tool execution became unreachable: " + message, nextRetryAt(task), false);
        } else {
            task.markFailed("Tool execution became unreachable: " + message);
        }
        evaluateWorkflow(workflowRunId);
    }

    @Transactional(readOnly = true)
    public List<String> findRunningWorkflowIds() {
        return workflowRepository.findByStatus(RunStatus.RUNNING).stream()
            .map(WorkflowRunEntity::getId).toList();
    }

    @Transactional
    public void recoverWorkflow(String workflowRunId) {
        var now = Instant.now();
        workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        for (var task : tasks) {
            if (task.getStatus() == TaskStatus.READY) {
                eventPublisher.publishEvent(new WorkflowDispatchRequested(workflowRunId, task.getId()));
            } else if (task.getStatus() == TaskStatus.DISPATCHING
                && task.getUpdatedAt().plusMillis(dispatchRecoveryTimeoutMs).isBefore(now)) {
                eventPublisher.publishEvent(new WorkflowDispatchRequested(workflowRunId, task.getId()));
            } else if (task.getStatus() == TaskStatus.RETRY_WAIT
                && (task.getNextAttemptAt() == null || !task.getNextAttemptAt().isAfter(now))) {
                task.releaseRetry(now);
                eventPublisher.publishEvent(new WorkflowDispatchRequested(workflowRunId, task.getId()));
            }
        }
        evaluateWorkflow(workflowRunId);
    }

    private Instant nextRetryAt(TaskRunEntity task) {
        var exponent = Math.max(0, Math.min(task.getRetryCount(), 10));
        var delay = retryBaseDelayMs * (1L << exponent);
        return Instant.now().plus(Duration.ofMillis(delay));
    }

    private Map<String, Object> parseParameters(TaskRunEntity task) {
        if (task.getParametersJson() == null || task.getParametersJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(task.getParametersJson(), new TypeReference<>() {});
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Task parameters are invalid for " + task.getInstanceKey(), exc);
        }
    }

    private void requireProjectAsset(String projectId, AssetEntity asset) {
        if (!projectId.equals(asset.getProjectId())) {
            throw new IllegalArgumentException("Asset does not belong to project: " + asset.getId());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Failed to serialize JSON", exc);
        }
    }

    public static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record DispatchContext(String idempotencyKey, ToolServiceClient.CreateToolExecutionRequest request) {}
    public record WorkflowDispatchRequested(String workflowRunId, String taskRunId) {}

    public record WorkflowSnapshot(
        String id, String projectId, String assetId, String workflowType, String definitionKey,
        Integer definitionVersion, String proxyQuality, RunStatus status, int progress, String errorMessage,
        boolean autoMode, String currentGateKey,
        List<GateDef> gates,
        List<AssetSnapshot> assets, List<TaskSnapshot> tasks
    ) {}

    /** Gate 定义视图（前端展示用） */
    public record GateDef(String gateKey, String label, String description) {}

    public record AssetSnapshot(String id, String fileName, long sizeBytes, String status) {}

    public record WorkflowHistoryItem(
        String id, String workflowType, String definitionKey, Integer definitionVersion, String proxyQuality,
        RunStatus status, int progress, String errorMessage, int assetCount, int taskCount,
        Instant createdAt, Instant startedAt, Instant completedAt
    ) {}

    public record TaskSnapshot(
        String id, String assetId, String instanceKey, String nodeKey, String toolName, String toolVersion,
        TaskStatus status, List<String> dependencyTaskRunIds, int progress, int attempt,
        int retryCount, Instant nextAttemptAt, String errorMessage,
        List<ArtifactSnapshot> artifacts
    ) {}

    public record ArtifactSnapshot(
        String id, String externalArtifactId, String type, String storageUri, String mediaType,
        String metadataJson, String contentUrl
    ) {}
}
