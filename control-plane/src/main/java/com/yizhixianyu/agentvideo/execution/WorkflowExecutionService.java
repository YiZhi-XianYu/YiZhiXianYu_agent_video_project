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
import com.yizhixianyu.agentvideo.workflow.MultiAssetAnalysisTemplate;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinitionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ApplicationEventPublisher eventPublisher;

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

        var definition = analysisTemplate.create(proxyQuality);
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
        workflow.start();

        for (var index = 0; index < assets.size(); index++) {
            var asset = assets.get(index);
            workflowAssetRepository.save(new WorkflowAssetEntity(workflow.getId(), asset.getId(), index));
            expandTasks(workflow, asset, definition);
        }
        evaluateWorkflow(workflow.getId());
        return workflow;
    }

    private void expandTasks(WorkflowRunEntity workflow, AssetEntity asset, WorkflowDefinition definition) {
        var tasksByNode = definition.nodes().stream().collect(Collectors.toMap(
            WorkflowDefinition.Node::nodeKey,
            node -> taskRepository.save(new TaskRunEntity(
                workflow.getId(),
                asset.getId(),
                asset.getId() + ":" + node.nodeKey(),
                node.nodeKey(),
                node.toolName(),
                node.toolVersion(),
                node.inputBinding().name(),
                toJson(node.parameters() == null ? Map.of() : node.parameters())
            ))
        ));
        for (var edge : definition.edges()) {
            dependencyRepository.save(new TaskDependencyEntity(
                tasksByNode.get(edge.to()).getId(), tasksByNode.get(edge.from()).getId()
            ));
        }
    }

    @Transactional
    public DispatchContext prepareDispatch(String workflowRunId, String taskRunId) {
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow();
        var task = taskRepository.findById(taskRunId).orElseThrow();
        var asset = assetService.getRequired(task.getAssetId() == null ? workflow.getAssetId() : task.getAssetId());
        task.markDispatching();
        var idempotencyKey = task.getNodeKey() + ":" + task.getId() + ":" + task.getAttempt();
        var parameters = new java.util.HashMap<>(parseParameters(task));
        if ("video.proxy-generate".equals(task.getToolName())) {
            parameters.putIfAbsent("quality", workflow.getProxyQuality().value());
        }
        if ("video.shot-detect".equals(task.getToolName())) {
            parameters.put("sourceAssetId", asset.getId());
        }
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            task.getToolName(),
            task.getToolVersion(),
            idempotencyKey,
            Map.of("video", resolveVideoInput(task, asset)),
            parameters,
            publicBaseUrl + "/internal/tool-callbacks",
            new ToolServiceClient.TraceContext(UUID.randomUUID().toString(), workflow.getId(), task.getId())
        );
        return new DispatchContext(idempotencyKey, request);
    }

    private ToolServiceClient.ArtifactInput resolveVideoInput(TaskRunEntity task, AssetEntity asset) {
        if (!"UPSTREAM_ARTIFACT".equals(task.getInputBinding())) {
            return new ToolServiceClient.ArtifactInput(asset.getId(), asset.getStorageUri(), asset.getFileName());
        }
        var dependencyIds = dependencyRepository.findByTaskRunId(task.getId()).stream()
            .map(TaskDependencyEntity::getDependsOnTaskRunId)
            .toList();
        var expectedType = "video.shot-detect".equals(task.getToolName()) ? "VIDEO_PROXY" : null;
        var artifact = artifactRepository.findByProducerTaskRunIdIn(dependencyIds).stream()
            .filter(item -> expectedType == null || expectedType.equals(item.getType()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Required upstream Artifact is missing for " + task.getInstanceKey()));
        return new ToolServiceClient.ArtifactInput(
            artifact.getExternalArtifactId(), artifact.getStorageUri(), artifact.getType()
        );
    }

    @Transactional
    public void markAccepted(String taskRunId, String idempotencyKey, ToolServiceClient.AcceptedExecution accepted) {
        if (accepted == null || accepted.executionId() == null) {
            throw new IllegalStateException("Tool Service returned an invalid acceptance response");
        }
        var task = taskRepository.findById(taskRunId).orElseThrow();
        task.markRunning();
        toolExecutionRepository.save(new ToolExecutionEntity(
            taskRunId, idempotencyKey, accepted.executionId(), accepted.status()
        ));
    }

    @Transactional
    public void markDispatchFailed(String workflowRunId, String taskRunId, String message) {
        taskRepository.findById(taskRunId).ifPresent(task -> task.markFailed(message));
        evaluateWorkflow(workflowRunId);
    }

    @Transactional
    public void applyToolResult(ToolServiceClient.ToolExecutionResponse response) {
        var execution = toolExecutionRepository.findByExternalExecutionId(response.executionId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Tool execution: " + response.executionId()));
        if ("SUCCEEDED".equals(execution.getStatus()) || "FAILED".equals(execution.getStatus())) {
            return;
        }
        execution.updateStatus(response.status());
        var task = taskRepository.findById(execution.getTaskRunId()).orElseThrow();

        if ("RUNNING".equals(response.status())) {
            task.markRunning();
            task.updateProgress(response.progress());
            return;
        }
        if ("QUEUED".equals(response.status())) {
            return;
        }
        if ("SUCCEEDED".equals(response.status())) {
            var workflow = workflowRepository.findById(task.getWorkflowRunId()).orElseThrow();
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
            task.markFailed(message);
            evaluateWorkflow(task.getWorkflowRunId());
        }
    }

    private synchronized void evaluateWorkflow(String workflowRunId) {
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow();
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var tasksById = tasks.stream().collect(Collectors.toMap(TaskRunEntity::getId, Function.identity()));
        var dependencies = dependencyRepository.findByTaskRunIdIn(tasksById.keySet().stream().toList()).stream()
            .collect(Collectors.groupingBy(TaskDependencyEntity::getTaskRunId));

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
                if (upstream.stream().anyMatch(item -> item.getStatus() == TaskStatus.FAILED
                    || item.getStatus() == TaskStatus.SKIPPED)) {
                    task.markSkipped("A required upstream Task did not succeed");
                    changed = true;
                } else if (upstream.stream().allMatch(item -> item.getStatus() == TaskStatus.SUCCEEDED)) {
                    task.markReady();
                    eventPublisher.publishEvent(new WorkflowDispatchRequested(workflowRunId, task.getId()));
                    changed = true;
                }
            }
        }

        var terminal = tasks.stream().filter(this::isTerminal).count();
        var succeeded = tasks.stream().filter(task -> task.getStatus() == TaskStatus.SUCCEEDED).count();
        if (terminal == tasks.size()) {
            var failed = tasks.stream().filter(task -> task.getStatus() == TaskStatus.FAILED).findFirst();
            if (failed.isPresent()) {
                workflow.fail(failed.get().getErrorMessage());
            } else if (succeeded == tasks.size()) {
                workflow.succeed();
            } else {
                workflow.fail("One or more Tasks were skipped");
            }
        } else if (workflow.getStatus() == RunStatus.RUNNING || terminal > 0) {
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
        var dependencies = dependencyRepository.findByTaskRunIdIn(taskIds).stream()
            .collect(Collectors.groupingBy(TaskDependencyEntity::getTaskRunId));
        var taskSnapshots = tasks.stream().map(task -> new TaskSnapshot(
            task.getId(), task.getAssetId(), task.getInstanceKey(), task.getNodeKey(), task.getToolName(),
            task.getToolVersion(), task.getStatus(),
            dependencies.getOrDefault(task.getId(), List.of()).stream()
                .map(TaskDependencyEntity::getDependsOnTaskRunId).toList(),
            task.getProgress(), task.getAttempt(), task.getErrorMessage(),
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
            workflow.getStatus(), workflow.getProgress(), workflow.getErrorMessage(), assets, taskSnapshots
        );
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
        List<AssetSnapshot> assets, List<TaskSnapshot> tasks
    ) {}

    public record AssetSnapshot(String id, String fileName, long sizeBytes, String status) {}

    public record TaskSnapshot(
        String id, String assetId, String instanceKey, String nodeKey, String toolName, String toolVersion,
        TaskStatus status, List<String> dependencyTaskRunIds, int progress, int attempt, String errorMessage,
        List<ArtifactSnapshot> artifacts
    ) {}

    public record ArtifactSnapshot(
        String id, String externalArtifactId, String type, String storageUri, String mediaType,
        String metadataJson, String contentUrl
    ) {}
}
