package com.yizhixianyu.agentvideo.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.asset.AssetService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowExecutionService {

    private final WorkflowRunRepository workflowRepository;
    private final TaskRunRepository taskRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final ArtifactRepository artifactRepository;
    private final ProjectService projectService;
    private final AssetService assetService;
    private final ToolServiceClient toolClient;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowExecutionService(
        WorkflowRunRepository workflowRepository,
        TaskRunRepository taskRepository,
        ToolExecutionRepository toolExecutionRepository,
        ArtifactRepository artifactRepository,
        ProjectService projectService,
        AssetService assetService,
        ToolServiceClient toolClient,
        ObjectMapper objectMapper,
        @Value("${app.public-base-url}") String publicBaseUrl,
        ApplicationEventPublisher eventPublisher
    ) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.artifactRepository = artifactRepository;
        this.projectService = projectService;
        this.assetService = assetService;
        this.toolClient = toolClient;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public WorkflowRunEntity createVideoProxyRun(String projectId, String assetId, ProxyQuality proxyQuality) {
        projectService.getRequired(projectId);
        var asset = assetService.getRequired(assetId);
        if (!projectId.equals(asset.getProjectId())) {
            throw new IllegalArgumentException("Asset does not belong to project");
        }
        var workflow = workflowRepository.save(new WorkflowRunEntity(
            projectId, assetId, "VIDEO_PROXY_PIPELINE", proxyQuality
        ));
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
    public DispatchContext prepareDispatch(String workflowRunId, String taskRunId) {
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow();
        var task = taskRepository.findById(taskRunId).orElseThrow();
        var asset = assetService.getRequired(workflow.getAssetId());
        workflow.start();
        task.markDispatching();
        var idempotencyKey = task.getNodeKey() + ":" + task.getId() + ":" + task.getAttempt();
        Map<String, Object> parameters = "video.proxy-generate".equals(task.getToolName())
            ? Map.of("quality", workflow.getProxyQuality().value())
            : Map.of();
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            task.getToolName(),
            task.getToolVersion(),
            idempotencyKey,
            Map.of("video", new ToolServiceClient.ArtifactInput(asset.getId(), asset.getStorageUri(), asset.getFileName())),
            parameters,
            publicBaseUrl + "/internal/tool-callbacks",
            new ToolServiceClient.TraceContext(UUID.randomUUID().toString(), workflow.getId(), task.getId())
        );
        return new DispatchContext(idempotencyKey, request);
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
        workflowRepository.findById(workflowRunId).ifPresent(workflow -> workflow.fail(message));
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
        var workflow = workflowRepository.findById(task.getWorkflowRunId()).orElseThrow();

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
            advanceWorkflow(workflow, task);
            return;
        }
        if ("FAILED".equals(response.status()) || "CANCELLED".equals(response.status())) {
            var message = response.error() == null ? "Tool execution failed" : response.error().message();
            task.markFailed(message);
            workflow.fail(message);
        }
    }

    private void advanceWorkflow(WorkflowRunEntity workflow, TaskRunEntity completedTask) {
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflow.getId());
        var successors = tasks.stream()
            .filter(task -> completedTask.getId().equals(task.getDependsOnTaskRunId()))
            .filter(task -> task.getStatus() == TaskStatus.PENDING)
            .toList();
        for (var successor : successors) {
            successor.markReady();
            eventPublisher.publishEvent(new WorkflowDispatchRequested(workflow.getId(), successor.getId()));
        }

        var succeeded = tasks.stream().filter(task -> task.getStatus() == TaskStatus.SUCCEEDED).count();
        if (succeeded == tasks.size()) {
            workflow.succeed();
        } else {
            workflow.updateProgress((int) (succeeded * 100 / tasks.size()));
        }
    }

    @Transactional(readOnly = true)
    public WorkflowSnapshot getSnapshot(String workflowRunId) {
        var workflow = workflowRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var taskSnapshots = tasks.stream().map(task -> new TaskSnapshot(
            task.getId(), task.getNodeKey(), task.getToolName(), task.getToolVersion(), task.getStatus(),
            task.getDependsOnTaskRunId(), task.getProgress(), task.getAttempt(), task.getErrorMessage(),
            artifactRepository.findByProducerTaskRunId(task.getId()).stream()
                .map(artifact -> new ArtifactSnapshot(
                    artifact.getId(), artifact.getType(), artifact.getStorageUri(), artifact.getMediaType(),
                    artifact.getMetadataJson(), "/api/v1/artifacts/" + artifact.getId() + "/content"
                )).toList()
        )).toList();
        return new WorkflowSnapshot(
            workflow.getId(), workflow.getProjectId(), workflow.getAssetId(), workflow.getWorkflowType(),
            workflow.getProxyQuality().value(),
            workflow.getStatus(), workflow.getProgress(), workflow.getErrorMessage(), taskSnapshots
        );
    }

    @Transactional(readOnly = true)
    public List<ToolExecutionEntity> findPendingToolExecutions() {
        return toolExecutionRepository.findByStatusIn(List.of("QUEUED", "RUNNING"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Failed to serialize Artifact metadata", exc);
        }
    }

    public static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record DispatchContext(String idempotencyKey, ToolServiceClient.CreateToolExecutionRequest request) {
    }

    public record WorkflowDispatchRequested(String workflowRunId, String taskRunId) {
    }

    public record WorkflowSnapshot(
        String id, String projectId, String assetId, String workflowType, String proxyQuality,
        RunStatus status, int progress,
        String errorMessage, List<TaskSnapshot> tasks
    ) {
    }

    public record TaskSnapshot(
        String id, String nodeKey, String toolName, String toolVersion, TaskStatus status,
        String dependsOnTaskRunId, int progress, int attempt, String errorMessage, List<ArtifactSnapshot> artifacts
    ) {
    }

    public record ArtifactSnapshot(
        String id, String type, String storageUri, String mediaType, String metadataJson, String contentUrl
    ) {
    }
}
