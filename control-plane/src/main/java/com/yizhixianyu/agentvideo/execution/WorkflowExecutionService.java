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
import com.yizhixianyu.agentvideo.storage.ArtifactStorage;
import com.yizhixianyu.agentvideo.plan.CustomStoryPlanRepository;
import com.yizhixianyu.agentvideo.plan.StoryPlanPayloadValidator;
import com.yizhixianyu.agentvideo.plan.TimelinePayloadValidator;
import com.yizhixianyu.agentvideo.plan.TimelineComposer;
import com.yizhixianyu.agentvideo.workflow.MultiAssetAnalysisTemplate;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinitionValidator;
import com.yizhixianyu.agentvideo.workflow.ToolGovernanceCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import io.micrometer.core.instrument.Timer;
import com.yizhixianyu.agentvideo.observability.WorkflowMetrics;

import java.io.InputStream;
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

    private static final long MAX_BGM_UPLOAD_BYTES = 100L * 1024 * 1024;

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
    private final ArtifactStorage artifactStorage;
    private WorkflowMetrics workflowMetrics;
    private com.yizhixianyu.agentvideo.cache.RedisDraftService redisCache;
    private WorkflowConcurrencyService workflowConcurrency;
    private com.yizhixianyu.agentvideo.trace.AgentTraceService agentTrace;
    private com.yizhixianyu.agentvideo.agent.AgentSessionService agentSessions;
    private final Map<String, Timer.Sample> taskMetricSamples = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired(required = false)
    public void setWorkflowMetrics(WorkflowMetrics workflowMetrics) {
        this.workflowMetrics = workflowMetrics;
    }

    @Autowired(required = false)
    public void setRedisCache(org.springframework.beans.factory.ObjectProvider<com.yizhixianyu.agentvideo.cache.RedisDraftService> provider) {
        this.redisCache = provider.getIfAvailable();
    }

    @Autowired(required = false)
    public void setWorkflowConcurrency(ObjectProvider<WorkflowConcurrencyService> provider) {
        this.workflowConcurrency = provider.getIfAvailable();
    }

    @Autowired(required = false)
    public void setAgentTrace(ObjectProvider<com.yizhixianyu.agentvideo.trace.AgentTraceService> provider) {
        this.agentTrace = provider.getIfAvailable();
    }

    @Autowired(required = false)
    public void setAgentSessions(ObjectProvider<com.yizhixianyu.agentvideo.agent.AgentSessionService> provider) {
        this.agentSessions = provider.getIfAvailable();
    }


    @Autowired
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
        ArtifactStorage artifactStorage,
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
        this.artifactStorage = artifactStorage;
        this.eventPublisher = eventPublisher;
    }

    /** Compatibility constructor retained for existing tests and integrations. */
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
        String publicBaseUrl,
        int maxAttempts,
        long retryBaseDelayMs,
        long dispatchRecoveryTimeoutMs,
        int pollFailureLimit,
        Path artifactRoot,
        ApplicationEventPublisher eventPublisher
    ) {
        this(workflowRepository, workflowAssetRepository, taskRepository, dependencyRepository,
            toolExecutionRepository, artifactRepository, projectService, assetService, toolClient,
            objectMapper, analysisTemplate, definitionValidator, publicBaseUrl, maxAttempts,
            retryBaseDelayMs, dispatchRecoveryTimeoutMs, pollFailureLimit,
            new com.yizhixianyu.agentvideo.storage.LocalStorageService(artifactRoot), eventPublisher);
    }

    @Transactional
    public WorkflowRunEntity createVideoProxyRun(String projectId, String assetId, ProxyQuality proxyQuality) {
        projectService.getRequired(projectId);
        var asset = assetService.getRequiredAvailable(assetId);
        requireProjectAsset(projectId, asset);
        var workflow = workflowRepository.save(new WorkflowRunEntity(
            projectId, assetId, "VIDEO_PROXY_PIPELINE", proxyQuality
        ));
        workflow.start();
        if (workflowMetrics != null) workflowMetrics.workflowStarted();
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
        return createMultiAssetAnalysisRun(
            projectId,
            requestedAssetIds,
            proxyQuality,
            durationPrompt,
            autoMode,
            analysisTemplate.create(proxyQuality, durationPrompt, autoMode)
        );
    }

    @Transactional
    public WorkflowRunEntity attachAgentContext(String workflowRunId, String sessionId, String turnId,
                                                String planId, String traceId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        workflow.attachAgentContext(sessionId, turnId, planId, traceId);
        syncAgentRuntime(workflow);
        if (agentTrace != null) {
            agentTrace.record("WORKFLOW_ATTACHED_TO_SESSION", traceId, sessionId, turnId, planId,
                workflowRunId, null, null, null, "agent-runtime", null, workflow.getStatus().name(), Map.of());
        }
        return workflow;
    }

    @Transactional
    public WorkflowRunEntity createMultiAssetAnalysisRun(
        String projectId,
        List<String> requestedAssetIds,
        ProxyQuality proxyQuality,
        String durationPrompt,
        boolean autoMode,
        WorkflowDefinition definition
    ) {
        return createMultiAssetAnalysisRun(projectId, requestedAssetIds, proxyQuality, durationPrompt, autoMode, definition, null);
    }

    @Transactional
    public WorkflowRunEntity createMultiAssetAnalysisRun(
        String projectId,
        List<String> requestedAssetIds,
        ProxyQuality proxyQuality,
        String durationPrompt,
        boolean autoMode,
        WorkflowDefinition definition,
        AgentContext agentContext
    ) {
        projectService.getRequired(projectId);
        if (requestedAssetIds == null || requestedAssetIds.isEmpty()) {
            throw new IllegalArgumentException("At least one Asset is required");
        }
        var uniqueAssetIds = new LinkedHashSet<>(requestedAssetIds);
        if (uniqueAssetIds.size() != requestedAssetIds.size()) {
            throw new IllegalArgumentException("Asset list must not contain duplicates");
        }
        var assets = uniqueAssetIds.stream().map(assetService::getRequiredAvailable).toList();
        assets.forEach(asset -> requireProjectAsset(projectId, asset));

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
        if (agentContext != null) {
            workflow.attachAgentContext(agentContext.sessionId(), agentContext.turnId(), agentContext.planId(), agentContext.traceId());
        }
        workflow.start();
        if (workflowMetrics != null) workflowMetrics.workflowStarted();

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

        byte[] contentBytes = timelineJson.getBytes(StandardCharsets.UTF_8);
        var artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        var storedTimeline = artifactStorage.storeBytes(projectId, "artifacts/" + artifactId,
            "timeline.json", contentBytes, "application/json");

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
            storedTimeline.storageUri(), storedTimeline.mediaType(), storedTimeline.sizeBytes(),
            storedTimeline.contentHash(), timelineJson
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
        inheritOptionalRenderArtifacts(sourceWorkflowRunId, projectId, renderWorkflow.getId(), renderTask.getId());

        evaluateWorkflow(renderWorkflow.getId());
        return renderWorkflow.getId();
    }

    @Transactional
    public String applyCustomStoryPlan(String workflowRunId, Map<String, Object> customPlan) {
        StoryPlanPayloadValidator.validate(customPlan);
        var workflow = workflowRepository.findLockedById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        if (workflow.getStatus() != RunStatus.PAUSED
            || !"gate_story_edit".equals(workflow.getCurrentGateKey())) {
            throw new IllegalStateException("Workflow is not paused at the Story Plan gate: " + workflowRunId);
        }

        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var storyTask = tasks.stream()
            .filter(task -> "story_plan".equals(task.getNodeKey()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Story Plan task is missing: " + workflowRunId));
        if (storyTask.getStatus() != TaskStatus.SUCCEEDED) {
            throw new IllegalStateException("Story Plan task has not succeeded: " + workflowRunId);
        }

        var planJson = toJson(customPlan);
        saveJsonArtifact(workflow.getProjectId(), storyTask.getId(), "STORY_PLAN", "story-plan.json", planJson);

        var taskIds = tasks.stream().map(TaskRunEntity::getId).toList();
        var dependencies = dependencyRepository.findByTaskRunIdIn(taskIds);
        var descendantIds = descendantTaskIds(storyTask.getId(), dependencies);
        tasks.stream()
            .filter(task -> descendantIds.contains(task.getId()))
            .forEach(TaskRunEntity::resetForReexecution);

        workflow.completeCurrentGate();
        workflow.resume();
        evaluateWorkflow(workflowRunId);
        return workflowRunId;
    }

    @Transactional
    public String applyCustomTimeline(String workflowRunId, Map<String, Object> timeline) {
        var workflow = workflowRepository.findLockedById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        if (workflow.getStatus() != RunStatus.PAUSED
            || !"gate_timeline_preview".equals(workflow.getCurrentGateKey())) {
            throw new IllegalStateException("Workflow is not paused at the Timeline gate: " + workflowRunId);
        }

        validateCustomTimeline(timeline);
        var tasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        var timelineTask = tasks.stream()
            .filter(task -> "timeline_compose".equals(task.getNodeKey()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Timeline task is missing: " + workflowRunId));
        if (timelineTask.getStatus() != TaskStatus.SUCCEEDED) {
            throw new IllegalStateException("Timeline task has not succeeded: " + workflowRunId);
        }

        saveJsonArtifact(
            workflow.getProjectId(), timelineTask.getId(), "TIMELINE", "timeline.json", toJson(timeline)
        );

        var taskIds = tasks.stream().map(TaskRunEntity::getId).toList();
        var dependencies = dependencyRepository.findByTaskRunIdIn(taskIds);
        var descendantIds = descendantTaskIds(timelineTask.getId(), dependencies);
        tasks.stream()
            .filter(task -> descendantIds.contains(task.getId()))
            .forEach(TaskRunEntity::resetForReexecution);

        workflow.completeCurrentGate();
        workflow.resume();
        evaluateWorkflow(workflowRunId);
        return workflowRunId;
    }

    @Transactional
    public String selectBgmCandidate(String workflowRunId, String candidateArtifactId) {
        var workflow = requireBgmGate(workflowRunId);
        var bgmTask = requireBgmTask(workflowRunId);
        var candidate = artifactRepository.findByExternalArtifactId(candidateArtifactId)
            .filter(artifact -> bgmTask.getId().equals(artifact.getProducerTaskRunId()))
            .filter(artifact -> "BGM_CANDIDATE".equals(artifact.getType()))
            .orElseThrow(() -> new IllegalArgumentException(
                "BGM candidate does not belong to the current Workflow: " + candidateArtifactId
            ));

        var selectedMetadata = new LinkedHashMap<String, Object>();
        selectedMetadata.put("selected", true);
        selectedMetadata.put("selectedFromArtifactId", candidate.getExternalArtifactId());
        selectedMetadata.put("selectedAt", Instant.now().toString());
        selectedMetadata.put("candidate", parseJsonObject(candidate.getMetadataJson()));
        var selectedAudioArtifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        artifactRepository.save(new ArtifactEntity(
            selectedAudioArtifactId,
            workflow.getProjectId(),
            bgmTask.getId(),
            "BGM_AUDIO",
            candidate.getStorageUri(),
            candidate.getMediaType(),
            candidate.getSizeBytes(),
            candidate.getContentHash(),
            toJson(selectedMetadata)
        ));
        saveJsonArtifact(
            workflow.getProjectId(), bgmTask.getId(), "BGM_SELECTION", "bgm-selection.json",
            toJson(Map.of(
                "mode", "SELECTED",
                "selectedAudioArtifactId", selectedAudioArtifactId,
                "candidateArtifactId", candidate.getExternalArtifactId(),
                "selectedAt", Instant.now().toString()
            ))
        );

        completeBgmGate(workflowRunId, workflow);
        return workflowRunId;
    }

    @Transactional
    public String uploadBgm(
        String workflowRunId, MultipartFile file, String playbackMode, long durationMs
    ) {
        var workflow = requireBgmGate(workflowRunId);
        var bgmTask = requireBgmTask(workflowRunId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("BGM audio file is required");
        }
        if (file.getSize() > MAX_BGM_UPLOAD_BYTES) {
            throw new IllegalArgumentException("BGM audio file must not exceed 100 MB");
        }
        var normalizedMode = playbackMode == null ? "ONCE" : playbackMode.trim().toUpperCase();
        if (!Set.of("ONCE", "LOOP").contains(normalizedMode)) {
            throw new IllegalArgumentException("BGM playbackMode must be ONCE or LOOP");
        }
        if (durationMs < 0 || durationMs > Duration.ofHours(12).toMillis()) {
            throw new IllegalArgumentException("BGM durationMs is outside the supported range");
        }
        var originalName = sanitizeBgmFileName(file.getOriginalFilename());
        var extension = fileExtension(originalName);
        var mediaType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!Set.of(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac").contains(extension)
            || (!mediaType.isBlank() && !mediaType.startsWith("audio/")
                && !"application/octet-stream".equals(mediaType))) {
            throw new IllegalArgumentException("Unsupported BGM audio format");
        }

        var artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        long contentSize;
        String contentHash;
        ArtifactStorage.StoredObject storedAudio;
        try {
            storedAudio = artifactStorage.store(workflow.getProjectId(), "artifacts/" + artifactId, file);
            contentSize = storedAudio.sizeBytes();
            if (contentSize == 0) {
                throw new IllegalArgumentException("BGM audio file is empty");
            }
            contentHash = storedAudio.contentHash();
        } catch (IllegalArgumentException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to store uploaded BGM", exc);
        }

        var selectedAt = Instant.now().toString();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("selected", true);
        metadata.put("provider", "upload");
        metadata.put("title", originalName);
        metadata.put("artist", "用户上传");
        metadata.put("fileName", originalName);
        metadata.put("bgmDurationMs", durationMs);
        metadata.put("playbackMode", normalizedMode);
        metadata.put("selectedAt", selectedAt);
        artifactRepository.save(new ArtifactEntity(
            artifactId,
            workflow.getProjectId(),
            bgmTask.getId(),
            "BGM_AUDIO",
            storedAudio.storageUri(),
            mediaType.isBlank() || "application/octet-stream".equals(mediaType)
                ? audioMediaType(extension) : mediaType,
            contentSize,
            contentHash,
            toJson(metadata)
        ));
        saveJsonArtifact(
            workflow.getProjectId(), bgmTask.getId(), "BGM_SELECTION", "bgm-selection.json",
            toJson(Map.of(
                "mode", "UPLOADED",
                "selectedAudioArtifactId", artifactId,
                "playbackMode", normalizedMode,
                "durationMs", durationMs,
                "fileName", originalName,
                "selectedAt", selectedAt
            ))
        );

        completeBgmGate(workflowRunId, workflow);
        return workflowRunId;
    }

    @Transactional
    public String continueWithoutBgm(String workflowRunId) {
        var workflow = requireBgmGate(workflowRunId);
        var bgmTask = requireBgmTask(workflowRunId);
        saveJsonArtifact(
            workflow.getProjectId(), bgmTask.getId(), "BGM_SELECTION", "bgm-selection.json",
            toJson(Map.of("mode", "NONE", "selectedAt", Instant.now().toString()))
        );
        completeBgmGate(workflowRunId, workflow);
        return workflowRunId;
    }

    @Transactional
    public String refreshBgmCandidates(String workflowRunId) {
        var workflow = requireBgmGate(workflowRunId);
        var bgmTask = requireBgmTask(workflowRunId);
        var previousCandidates = artifactRepository.findByProducerTaskRunId(bgmTask.getId()).stream()
            .filter(artifact -> "BGM_CANDIDATE".equals(artifact.getType()))
            .toList();
        var excludedTrackIds = new LinkedHashSet<String>();
        var latestBatch = 0;
        for (var artifact : previousCandidates) {
            var metadata = parseJsonObject(artifact.getMetadataJson());
            var trackId = String.valueOf(metadata.getOrDefault("providerTrackId", "")).trim();
            if (!trackId.isBlank()) {
                excludedTrackIds.add(trackId);
            }
            var batch = metadata.get("recommendationBatch");
            if (batch instanceof Number number) {
                latestBatch = Math.max(latestBatch, number.intValue());
            }
        }

        var parameters = new LinkedHashMap<>(parseParameters(bgmTask));
        parameters.put("recommendationBatch", Math.min(latestBatch + 1, 100));
        parameters.put("recommendationSeed", workflowRunId);
        parameters.put("excludedTrackIds", excludedTrackIds.stream().limit(100).toList());
        bgmTask.updateParametersJson(toJson(parameters));
        bgmTask.resetForReexecution();
        workflow.resume();
        evaluateWorkflow(workflowRunId);
        return workflowRunId;
    }

    private WorkflowRunEntity requireBgmGate(String workflowRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        if (workflow.getStatus() != RunStatus.PAUSED
            || !"gate_bgm_review".equals(workflow.getCurrentGateKey())) {
            throw new IllegalStateException("Workflow is not paused at the BGM gate: " + workflowRunId);
        }
        return workflow;
    }

    private TaskRunEntity requireBgmTask(String workflowRunId) {
        return taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId).stream()
            .filter(task -> "bgm_select".equals(task.getNodeKey()))
            .filter(task -> task.getStatus() == TaskStatus.SUCCEEDED || task.getStatus() == TaskStatus.FAILED)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("BGM selection task has not finished: " + workflowRunId));
    }

    private void completeBgmGate(String workflowRunId, WorkflowRunEntity workflow) {
        workflow.completeCurrentGate();
        workflow.resume();
        evaluateWorkflow(workflowRunId);
    }

    private void validateCustomTimeline(Map<String, Object> timeline) {
        TimelinePayloadValidator.validate(timeline);
    }

    private Set<String> descendantTaskIds(
        String producerTaskId,
        List<TaskDependencyEntity> dependencies
    ) {
        var descendants = new LinkedHashSet<String>();
        var frontier = new ArrayList<String>();
        frontier.add(producerTaskId);
        while (!frontier.isEmpty()) {
            var upstreamId = frontier.remove(0);
            for (var dependency : dependencies) {
                if (!upstreamId.equals(dependency.getDependsOnTaskRunId())) continue;
                if (descendants.add(dependency.getTaskRunId())) {
                    frontier.add(dependency.getTaskRunId());
                }
            }
        }
        return descendants;
    }

    private ArtifactEntity saveJsonArtifact(
        String projectId,
        String producerTaskRunId,
        String type,
        String fileName,
        String json
    ) {
        var contentBytes = json.getBytes(StandardCharsets.UTF_8);
        var artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        var stored = artifactStorage.storeBytes(projectId, "artifacts/" + artifactId, fileName,
            contentBytes, "application/json");
        var saved = artifactRepository.save(new ArtifactEntity(
            artifactId,
            projectId,
            producerTaskRunId,
            type,
            stored.storageUri(), stored.mediaType(), stored.sizeBytes(), stored.contentHash(),
            json
        ));
        invalidateLlmAuditCache(type);
        return saved;
    }

    private void invalidateLlmAuditCache(String artifactType) {
        if (!"STORY_PLAN".equals(artifactType) || redisCache == null) return;
        try { redisCache.deleteByPrefix("avp:v1:llm:audit:list:"); }
        catch (RuntimeException ignored) { }
    }

    @Transactional
    public String createCustomTimelineRenderRun(String projectId, String sourceWorkflowRunId, Map<String, Object> timeline) {
        var sourceWorkflow = workflowRepository.findById(sourceWorkflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Source workflow run not found: " + sourceWorkflowRunId));
        validateCustomTimeline(timeline);
        var timelineJson = toJson(timeline);
        var contentBytes = timelineJson.getBytes(StandardCharsets.UTF_8);
        var artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        var storedTimeline = artifactStorage.storeBytes(projectId, "artifacts/" + artifactId,
            "timeline.json", contentBytes, "application/json");

        var renderWorkflow = workflowRepository.save(new WorkflowRunEntity(
            projectId, sourceWorkflow.getAssetId(), "CUSTOM_TIMELINE_RENDER", sourceWorkflow.getProxyQuality()
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
            artifactId, projectId, virtualTask.getId(), "TIMELINE", storedTimeline.storageUri(),
            storedTimeline.mediaType(), storedTimeline.sizeBytes(), storedTimeline.contentHash(), timelineJson
        ));

        var renderTask = taskRepository.save(new TaskRunEntity(
            renderWorkflow.getId(), null, "workflow:video_render", "video_render",
            "video.render", "1.1.0", "UPSTREAM_ARTIFACT", "{}"
        ));
        dependencyRepository.save(new TaskDependencyEntity(renderTask.getId(), virtualTask.getId()));
        inheritOptionalRenderArtifacts(sourceWorkflowRunId, projectId, renderWorkflow.getId(), renderTask.getId());
        evaluateWorkflow(renderWorkflow.getId());
        return renderWorkflow.getId();
    }

    /**
     * Carry optional render inputs into a custom render run without mutating the
     * source workflow's immutable artifacts. The new artifacts point to the same
     * immutable storage object and retain explicit lineage metadata.
     */
    private void inheritOptionalRenderArtifacts(
        String sourceWorkflowRunId,
        String projectId,
        String targetWorkflowRunId,
        String renderTaskRunId
    ) {
        var sourceTasks = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(sourceWorkflowRunId);
        var sourceTaskIds = sourceTasks.stream().map(TaskRunEntity::getId).toList();
        if (sourceTaskIds.isEmpty()) return;
        var sourceArtifacts = artifactRepository.findByProducerTaskRunIdIn(sourceTaskIds);
        for (var type : List.of("BGM_AUDIO", "SUBTITLE_SRT")) {
            sourceArtifacts.stream()
                .filter(artifact -> type.equals(artifact.getType()))
                .findFirst()
                .ifPresent(source -> {
                    var inheritedTask = taskRepository.save(new TaskRunEntity(
                        targetWorkflowRunId,
                        "inherited_" + type.toLowerCase(),
                        "inherited." + type.toLowerCase(),
                        "1.0.0",
                        null
                    ));
                    inheritedTask.markReady();
                    inheritedTask.markDispatching();
                    inheritedTask.markRunning();
                    inheritedTask.markSucceeded();

                    var inheritedId = "art_" + UUID.randomUUID().toString().replace("-", "");
                    var metadata = new LinkedHashMap<String, Object>();
                    metadata.put("inheritedFromArtifactId", source.getExternalArtifactId());
                    metadata.put("sourceWorkflowRunId", sourceWorkflowRunId);
                    metadata.put("sourceMetadataJson", source.getMetadataJson());
                    artifactRepository.save(new ArtifactEntity(
                        inheritedId,
                        projectId,
                        inheritedTask.getId(),
                        type,
                        source.getStorageUri(),
                        source.getMediaType(),
                        source.getSizeBytes(),
                        source.getContentHash(),
                        toJson(metadata)
                    ));
                    dependencyRepository.save(new TaskDependencyEntity(
                        renderTaskRunId,
                        inheritedTask.getId(),
                        WorkflowDefinition.DependencyType.OPTIONAL
                    ));
                });
        }
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
        if ("audio.bgm-select".equals(task.getToolName())) {
            parameters.putIfAbsent("recommendationBatch", 0);
            parameters.putIfAbsent("recommendationSeed", workflow.getId());
            var excludedTrackIds = new LinkedHashSet<String>();
            var configuredExclusions = parameters.get("excludedTrackIds");
            if (configuredExclusions instanceof List<?> values) {
                values.stream().map(String::valueOf).filter(value -> !value.isBlank())
                    .forEach(excludedTrackIds::add);
            }
            artifactRepository.findTop100ByProjectIdAndTypeOrderByCreatedAtDesc(
                workflow.getProjectId(), "BGM_CANDIDATE"
            ).stream().map(artifact -> parseJsonObject(artifact.getMetadataJson()))
                .map(metadata -> String.valueOf(metadata.getOrDefault("providerTrackId", "")).trim())
                .filter(value -> !value.isBlank())
                .forEach(excludedTrackIds::add);
            parameters.put("excludedTrackIds", excludedTrackIds.stream().limit(100).toList());
        }
        if ("video.proxy-generate".equals(task.getToolName())) {
            parameters.putIfAbsent("quality", workflow.getProxyQuality().value());
        }
        if ("video.shot-detect".equals(task.getToolName())) {
            if (asset == null) {
                throw new IllegalStateException("Shot detection requires an Asset-scoped Task");
            }
            parameters.put("sourceAssetId", asset.getId());
        }
        var inputs = resolveInputs(task, asset);
        if ("video.render".equals(task.getToolName())) {
            parameters.put("bgmPlaybackMode", resolveBgmPlaybackMode(inputs));
        }
        if (workflowMetrics != null) {
            workflowMetrics.taskDispatched(task.getToolName());
            taskMetricSamples.put(task.getId(), workflowMetrics.taskStarted());
        }
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            task.getToolName(),
            task.getToolVersion(),
            idempotencyKey,
            inputs,
            parameters,
            publicBaseUrl + "/internal/tool-callbacks",
            new ToolServiceClient.TraceContext(UUID.randomUUID().toString(), workflow.getId(), task.getId())
        );
        if (agentTrace != null) {
            var governance = ToolGovernanceCatalog.policy(request.tool());
            agentTrace.record("TASK_DISPATCH_PREPARED", request.traceContext().traceId(), request.traceContext().sessionId(),
                request.traceContext().turnId(), request.traceContext().planId(), workflowRunId, taskRunId,
                null, null, "workflow-dispatcher", request.tool(), "DISPATCHING",
                Map.of("attempt", task.getAttempt(), "idempotencyKey", idempotencyKey,
                    "automationPolicy", governance.automationPolicy(),
                    "requiresUserConfirmation", governance.requiresUserConfirmation(),
                    "sideEffectLevel", governance.sideEffectLevel(),
                    "resourceGroup", governance.resourceGroup(),
                    "maxAttempts", governance.maxAttempts(),
                    "allowFallback", governance.allowFallback()));
        }
        return new DispatchContext(idempotencyKey, request, task.getAttempt());
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
        var artifacts = latestArtifactsByProducerAndType(
            artifactRepository.findByProducerTaskRunIdIn(dependencyIds)
        );
        var bgmSelection = artifacts.stream()
            .filter(artifact -> "BGM_SELECTION".equals(artifact.getType()))
            .findFirst()
            .map(artifact -> parseJsonObject(artifact.getMetadataJson()))
            .orElse(Map.of());
        var bgmSelectionMode = String.valueOf(bgmSelection.getOrDefault("mode", "AUTO"));
        var selectedBgmArtifactId = String.valueOf(
            bgmSelection.getOrDefault("selectedAudioArtifactId", "")
        );
        var inputs = new LinkedHashMap<String, ToolServiceClient.ArtifactInput>();
        var counts = new HashMap<String, Integer>();
        for (var artifact : artifacts) {
            if (!acceptedInputTypes(task.getToolName()).contains(artifact.getType())) {
                continue;
            }
            if ("video.render".equals(task.getToolName()) && "BGM_AUDIO".equals(artifact.getType())) {
                if ("NONE".equals(bgmSelectionMode)) {
                    continue;
                }
                if (Set.of("SELECTED", "UPLOADED").contains(bgmSelectionMode)
                    && !selectedBgmArtifactId.equals(artifact.getExternalArtifactId())) {
                    continue;
                }
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

    private String sanitizeBgmFileName(String name) {
        var value = name == null || name.isBlank() ? "bgm.mp3" : Path.of(name).getFileName().toString();
        return value.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String fileExtension(String fileName) {
        var index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase();
    }

    private String audioMediaType(String extension) {
        return switch (extension) {
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".m4a" -> "audio/mp4";
            case ".aac" -> "audio/aac";
            case ".ogg" -> "audio/ogg";
            case ".flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
    }

    private String resolveBgmPlaybackMode(Map<String, ToolServiceClient.ArtifactInput> inputs) {
        var bgm = inputs.get("bgm");
        if (bgm == null) return "ONCE";
        var playbackMode = artifactRepository.findByExternalArtifactId(bgm.artifactId())
            .map(artifact -> parseJsonObject(artifact.getMetadataJson()))
            .map(metadata -> String.valueOf(metadata.getOrDefault("playbackMode", "ONCE")))
            .orElse("ONCE").toUpperCase();
        return Set.of("ONCE", "LOOP").contains(playbackMode) ? playbackMode : "ONCE";
    }

    private List<ArtifactEntity> latestArtifactsByProducerAndType(List<ArtifactEntity> artifacts) {
        var latest = new LinkedHashMap<String, ArtifactEntity>();
        artifacts.stream()
            .sorted(java.util.Comparator.comparing(ArtifactEntity::getCreatedAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
            .forEach(artifact -> latest.put(
                artifact.getProducerTaskRunId() + "\u0000" + artifact.getType(), artifact
            ));
        return new ArrayList<>(latest.values());
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
    public boolean markAccepted(
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
            return false;
        }
        var expectedKey = task.getNodeKey() + ":" + task.getId() + ":" + task.getAttempt();
        if (!expectedKey.equals(idempotencyKey)) {
            // A late delivery from an older attempt must be acknowledged by
            // the broker but must never create or replace a business execution.
            return false;
        }
        task.markRunning();
        toolExecutionRepository.findByTaskRunIdAndIdempotencyKey(taskRunId, idempotencyKey)
            .ifPresentOrElse(
                execution -> execution.replaceAcceptance(accepted.executionId(), accepted.status()),
            () -> toolExecutionRepository.save(new ToolExecutionEntity(
                taskRunId, idempotencyKey, accepted.executionId(), accepted.status()
            ))
        );
        if (agentTrace != null) {
            agentTrace.record("TOOL_CLAIMED", null, null, null, null, workflowRunId, taskRunId,
                null, accepted.executionId(), "rabbit-worker", null, accepted.status(),
                Map.of("idempotencyKey", idempotencyKey));
        }
        return true;
    }

    @Transactional
    public void markDispatchFailed(String workflowRunId, String taskRunId, String message) {
        workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var task = taskRepository.findLockedById(taskRunId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.DISPATCHING) {
            return;
        }
        if (task.canRetry(effectiveMaxAttempts(task))) {
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
            if (agentTrace != null) {
                agentTrace.record("TOOL_RESULT_DUPLICATE", null, null, null, null, workflowRunId,
                    execution.getTaskRunId(), null, response.executionId(), "control-plane", response.tool(),
                    response.status(), Map.of("idempotencyKey", String.valueOf(response.idempotencyKey())));
            }
            return;
        }
        var task = taskRepository.findLockedById(execution.getTaskRunId()).orElseThrow();
        if (response.idempotencyKey() != null && !response.idempotencyKey().isBlank()
            && !response.idempotencyKey().equals(execution.getIdempotencyKey())) {
            // A late callback from an older Worker attempt is intentionally
            // accepted at the HTTP boundary but cannot mutate current state.
            return;
        }
        var expectedKey = task.getNodeKey() + ":" + task.getId() + ":" + task.getAttempt();
        if (!expectedKey.equals(execution.getIdempotencyKey())) {
            if (agentTrace != null) {
                agentTrace.record("TOOL_RESULT_STALE", null, null, null, null, workflowRunId,
                    execution.getTaskRunId(), null, response.executionId(), "control-plane", response.tool(),
                    response.status(), Map.of("idempotencyKey", String.valueOf(response.idempotencyKey())));
            }
            return;
        }
        execution.updateStatus(response.status());

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
                    invalidateLlmAuditCache(output.type());
                }
            }
            task.markSucceeded();
            recordTaskMetric(task, "SUCCEEDED");
            evaluateWorkflow(task.getWorkflowRunId());
            return;
        }
        if ("FAILED".equals(response.status()) || "CANCELLED".equals(response.status())) {
            var message = response.error() == null ? "Tool execution failed" : response.error().message();
            var retryable = response.error() != null && response.error().retryable();
            if (retryable && task.canRetry(effectiveMaxAttempts(task))) {
                task.scheduleRetry(message, nextRetryAt(task), false);
            } else {
                task.markFailed(message);
            }
            recordTaskMetric(task, response.status());
            evaluateWorkflow(task.getWorkflowRunId());
        }
    }

    private void recordTaskMetric(TaskRunEntity task, String status) {
        if (workflowMetrics == null || task == null) return;
        workflowMetrics.taskFinished(task.getToolName(), status, taskMetricSamples.remove(task.getId()));
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
        Map<String, TaskRunEntity> tasksById,
        WorkflowRunEntity workflow
    ) {
        if (definition == null || definition.gates() == null || definition.gates().isEmpty()) {
            return null;
        }
        // Follow definition order and ignore completed Gates. A downstream task can depend on
        // several gated producers; returning the first completed Gate would skip a later review.
        for (var gate : definition.gates()) {
            if (workflow.hasCompletedGate(gate.gateKey())) {
                continue;
            }
            var producerSucceeded = upstream.stream().anyMatch(up ->
                gate.afterNodeKey().equals(up.getNodeKey())
                    && (up.getStatus() == TaskStatus.SUCCEEDED
                        || ("gate_bgm_review".equals(gate.gateKey()) && up.getStatus() == TaskStatus.FAILED))
            );
            if (producerSucceeded) {
                return gate;
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
                gate.afterNodeKey().equals(task.getNodeKey())
                    && (task.getStatus() == TaskStatus.SUCCEEDED
                        || ("gate_bgm_review".equals(gate.gateKey())
                            && task.getStatus() == TaskStatus.FAILED))
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

    private WorkflowDefinition.Gate findGovernanceGateForTask(TaskRunEntity task, WorkflowRunEntity workflow) {
        var policy = ToolGovernanceCatalog.policy(task.getToolName());
        if (!policy.requiresUserConfirmation() || workflow.hasCompletedGate(governanceGateKey(task))) {
            return null;
        }
        // BGM already has a pre-render selection Gate. Render itself needs a
        // distinct pre-execution confirmation because its existing review Gate
        // occurs only after the rendered Artifact has been produced.
        if (!"video.render".equals(task.getToolName())) {
            return null;
        }
        return new WorkflowDefinition.Gate(
            governanceGateKey(task), task.getNodeKey(), "工具执行确认", "该工具具有高副作用，确认后才会开始执行。"
        );
    }

    private String governanceGateKey(TaskRunEntity task) {
        return "gate_governance_" + task.getNodeKey();
    }

    private int effectiveMaxAttempts(TaskRunEntity task) {
        return Math.max(1, Math.min(maxAttempts, ToolGovernanceCatalog.policy(task.getToolName()).maxAttempts()));
    }

    @Transactional
    public void continueWorkflow(String workflowRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        if (workflow.getStatus() != RunStatus.PAUSED) {
            throw new IllegalStateException("Workflow is not paused: " + workflowRunId);
        }
        var completedGateKey = workflow.getCurrentGateKey();
        workflow.completeCurrentGate();
        workflow.resume();
        if (agentTrace != null && completedGateKey != null && completedGateKey.startsWith("gate_governance_")) {
            var nodeKey = completedGateKey.substring("gate_governance_".length());
            var task = taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId).stream()
                .filter(item -> nodeKey.equals(item.getNodeKey())).findFirst().orElse(null);
            agentTrace.record("TOOL_GOVERNANCE_APPROVED", null, null, null, null,
                workflowRunId, task == null ? null : task.getId(), null, null, "user-gate",
                task == null ? null : task.getToolName(), "APPROVED",
                Map.of("gateKey", completedGateKey, "confirmationSource", "USER"));
        }
        evaluateWorkflow(workflowRunId);
    }

    private void evaluateWorkflow(String workflowRunId) {
        var workflow = workflowRepository.findLockedById(workflowRunId).orElseThrow();
        var previousWorkflowStatus = workflow.getStatus();
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
                    var gate = findGateForTask(definition, task, upstream, tasksById, workflow);
                    if (gate != null && !workflow.isAutoMode()) {
                        workflow.pause(gate.gateKey());
                        // The downstream task stays PENDING until the user continues the Gate.
                        // Return now so this transaction commits instead of evaluating the same Gate forever.
                        syncAgentRuntime(workflow);
                        return;
                    }
                    var governanceGate = findGovernanceGateForTask(task, workflow);
                    if (governanceGate != null && !workflow.isAutoMode()) {
                        workflow.pause(governanceGate.gateKey());
                        if (agentTrace != null) {
                            var policy = ToolGovernanceCatalog.policy(task.getToolName());
                            agentTrace.record("TOOL_GOVERNANCE_BLOCKED", null, null, null, null,
                                workflowRunId, task.getId(), null, null, "workflow-governance",
                                task.getToolName(), "PAUSED", Map.of(
                                    "gateKey", governanceGate.gateKey(),
                                    "automationPolicy", policy.automationPolicy(),
                                    "requiresUserConfirmation", policy.requiresUserConfirmation(),
                                    "reason", "Tool requires confirmation before dispatch"
                                ));
                        }
                        syncAgentRuntime(workflow);
                        return;
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
                syncAgentRuntime(workflow);
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
            if (workflowMetrics != null && previousWorkflowStatus != workflow.getStatus()
                && (workflow.getStatus() == RunStatus.SUCCEEDED || workflow.getStatus() == RunStatus.FAILED)) {
                workflowMetrics.workflowCompleted(workflow.getStatus() == RunStatus.SUCCEEDED);
            }
            if (previousWorkflowStatus != workflow.getStatus()
                && (workflow.getStatus() == RunStatus.SUCCEEDED || workflow.getStatus() == RunStatus.FAILED)
                && workflowConcurrency != null) {
                workflowConcurrency.release(workflow.getProjectId(), workflow.getId());
            }
        } else if (workflow.getStatus() == RunStatus.RUNNING) {
            workflow.start();
            workflow.updateProgress((int) (terminal * 100 / tasks.size()));
        }
        syncAgentRuntime(workflow);
    }

    private void syncAgentRuntime(WorkflowRunEntity workflow) {
        if (agentSessions != null && workflow.getAgentSessionId() != null) {
            agentSessions.syncRuntimeFromWorkflow(
                workflow.getAgentSessionId(), workflow.getStatus().name(), workflow.getCurrentGateKey());
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
        if (workflow.getCurrentGateKey() != null && workflow.getCurrentGateKey().startsWith("gate_governance_")) {
            gates.add(new GateDef(
                workflow.getCurrentGateKey(), "工具执行确认", "该工具具有高副作用，确认后才会开始执行。"
            ));
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
                .sorted(java.util.Comparator.comparing(
                    ArtifactEntity::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())
                ))
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
        if (task.canRetry(effectiveMaxAttempts(task))) {
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

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Artifact metadata is invalid JSON", exc);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String sha256(Path path) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to hash Artifact", exc);
        }
    }

    public static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record DispatchContext(String idempotencyKey, ToolServiceClient.CreateToolExecutionRequest request, int attempt) {}
    public record AgentContext(String sessionId, String turnId, String planId, String traceId) {}
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
