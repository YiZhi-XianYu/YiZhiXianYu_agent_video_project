package com.yizhixianyu.agentvideo.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.cache.DraftConflictException;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import com.yizhixianyu.agentvideo.cache.StoredDraft;
import com.yizhixianyu.agentvideo.execution.TaskRunEntity;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowRunEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventEntity;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventRepository;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.asset.AssetRepository;
import com.yizhixianyu.agentvideo.asset.AssetEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowAssetRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled planner context. MySQL remains authoritative; Redis is a rebuildable snapshot. */
@Service
public class BlackboardService {
    private final AgentSessionRepository sessions;
    private final AgentSessionTurnRepository turns;
    private final WorkflowRunRepository workflows;
    private final TaskRunRepository tasks;
    private final ArtifactRepository artifacts;
    private final AgentTraceEventRepository traces;
    private final ProjectService projects;
    private final AssetRepository assetRepository;
    private final WorkflowAssetRepository workflowAssets;
    private final ObjectMapper mapper;
    private final RedisDraftService redis;
    private final Duration ttl;

    public BlackboardService(AgentSessionRepository sessions, AgentSessionTurnRepository turns,
                             WorkflowRunRepository workflows, TaskRunRepository tasks,
                             ArtifactRepository artifacts, AgentTraceEventRepository traces,
                             ProjectService projects, AssetRepository assetRepository,
                             WorkflowAssetRepository workflowAssets, ObjectMapper mapper,
                             ObjectProvider<RedisDraftService> redisProvider,
                             @Value("${app.redis.agent-blackboard-ttl-seconds:1800}") long ttlSeconds) {
        this.sessions = sessions; this.turns = turns; this.workflows = workflows; this.tasks = tasks;
        this.artifacts = artifacts; this.traces = traces; this.projects = projects;
        this.assetRepository = assetRepository; this.workflowAssets = workflowAssets; this.mapper = mapper;
        this.redis = redisProvider.getIfAvailable();
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    @Transactional(readOnly = true)
    public BlackboardView get(String userId, String sessionId) {
        var session = requireOwned(userId, sessionId);
        var key = key(sessionId);
        if (redis != null) {
            try {
                var cached = redis.get(key);
                if (cached != null) {
                    try { return fromJson(cached.json()); }
                    catch (RuntimeException staleSnapshot) {
                        try { redis.delete(key); } catch (RuntimeException ignored) { }
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        var view = rebuild(session);
        saveSnapshot(key, view, null);
        return view;
    }

    @Transactional(readOnly = true)
    public BlackboardView refresh(String userId, String sessionId, Long expectedRevision) {
        var session = requireOwned(userId, sessionId);
        var view = rebuild(session);
        if (redis != null) saveSnapshot(key(sessionId), view, expectedRevision);
        return view;
    }

    @Transactional(readOnly = true)
    public AgentSessionEntity requireOwned(String userId, String sessionId) {
        var session = sessions.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Agent Session not found"));
        projects.getRequiredForUser(session.getProjectId(), userId);
        return session;
    }

    private BlackboardView rebuild(AgentSessionEntity session) {
        var workflow = session.getCurrentWorkflowRunId() == null ? null : workflows.findById(session.getCurrentWorkflowRunId()).orElse(null);
        var turnViews = turns.findBySessionIdOrderBySequenceNumberAsc(session.getId()).stream()
            .map(t -> new TurnView(t.getId(), t.getSequenceNumber(), t.getRole(), t.getContent(), t.getPlanId(), t.getWorkflowRunId())).toList();
        var taskViews = workflow == null ? List.<TaskView>of() : tasks.findByWorkflowRunIdOrderByCreatedAtAsc(workflow.getId()).stream()
            .map(t -> new TaskView(t.getId(), t.getNodeKey(), t.getToolName(), t.getToolVersion(), t.getStatus().name(), t.getAttempt(), t.getProgress(), t.getErrorMessage(), t.getAssetId())).toList();
        var artifactViews = workflow == null ? List.<ArtifactView>of() : artifacts.findByProducerTaskRunIdIn(taskViews.stream().map(TaskView::taskRunId).toList()).stream()
            .map(a -> new ArtifactView(a.getId(), a.getExternalArtifactId(), a.getType(), a.getContentHash(), a.getProducerTaskRunId())).toList();
        var workflowArtifacts = workflow == null ? List.<ArtifactEntity>of()
            : artifacts.findByProducerTaskRunIdIn(taskViews.stream().map(TaskView::taskRunId).toList());
        var allProjectAssets = assetRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            session.getProjectId(), AssetEntity.STATUS_AVAILABLE);
        var selectedAssetIds = workflow == null ? java.util.Set.<String>of()
            : workflowAssets.findByWorkflowRunIdOrderByPositionIndexAsc(workflow.getId()).stream()
                .map(item -> item.getAssetId()).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        var assetViews = allProjectAssets.stream().map(asset -> assetView(
            asset, selectedAssetIds.contains(asset.getId()), taskViews, workflowArtifacts,
            recentProjectArtifacts(session.getProjectId(), asset.getId()))).toList();
        var bgm = selectedBgm(workflowArtifacts.isEmpty()
            ? recentProjectArtifacts(session.getProjectId(), null) : workflowArtifacts);
        var traceViews = traces.findBySessionIdOrderByOccurredAtAsc(session.getId()).stream()
            .map(t -> new TraceView(t.getEventType(), t.getTraceId(), t.getWorkflowRunId(), t.getTaskRunId(), t.getExecutionId(), t.getOccurredAt())).toList();
        var latestFailure = traceViews.stream().filter(t -> "TASK_FAILED".equals(t.eventType()) || "TASK_FALLBACK_RETRY".equals(t.eventType()))
            .reduce((first, second) -> second).orElse(null);
        var activeTask = workflow == null ? null : tasks.findByWorkflowRunIdOrderByCreatedAtAsc(workflow.getId()).stream()
            .filter(t -> t.getStatus() == com.yizhixianyu.agentvideo.execution.TaskStatus.RUNNING
                || t.getStatus() == com.yizhixianyu.agentvideo.execution.TaskStatus.DISPATCHING
                || t.getStatus() == com.yizhixianyu.agentvideo.execution.TaskStatus.RETRY_WAIT)
            .findFirst().orElse(null);
        var runtime = workflow == null ? new RuntimeView(null, null, 0, null, null, null, null, null, null, false, List.of(), null) :
            new RuntimeView(workflow.getStatus().name(), workflow.getCurrentGateKey(), workflow.getProgress(),
                workflow.getErrorMessage(), nextAction(workflow, activeTask), workflow.getCompletedAt(),
                activeTask == null ? null : activeTask.getNodeKey(), activeTask == null ? null : activeTask.getStatus().name(),
                activeTask == null ? null : activeTask.getErrorMessage(), activeTask != null && activeTask.getStatus() == com.yizhixianyu.agentvideo.execution.TaskStatus.RETRY_WAIT,
                actions(workflow, activeTask), latestFailure == null ? null : latestFailure.eventType());
        return new BlackboardView(1L, session.getId(), session.getUserId(), session.getProjectId(), session.getNaturalLanguageGoal(),
            session.getTargetDurationMs(), session.getStatus(), session.getCurrentPlanId(), session.getDagVersion(),
            session.getCurrentWorkflowRunId(), session.getCurrentGateKey(), runtime, assetViews, bgm,
            turnViews, taskViews, artifactViews, traceViews);
    }

    private AssetView assetView(AssetEntity asset, boolean selected, List<TaskView> taskViews,
                                List<ArtifactEntity> workflowArtifacts, List<ArtifactEntity> projectArtifacts) {
        var taskIds = taskViews.stream().filter(task -> asset.getId().equals(task.assetId()))
            .map(TaskView::taskRunId).collect(java.util.stream.Collectors.toSet());
        var related = workflowArtifacts.stream().filter(a -> taskIds.contains(a.getProducerTaskRunId())).toList();
        if (related.isEmpty()) related = projectArtifacts;
        var metadata = latestPayload(related, "VIDEO_METADATA");
        var shots = latestPayload(related, "SHOT_LIST");
        var scenes = summarizeTags(related, "SCENE_TAGS", "sceneTags");
        var objects = summarizeTags(related, "OBJECT_TAGS", "objectTags");
        var people = summarizeTags(related, "PERSON_TAGS", "personTags");
        Integer width = integer(metadata.get("width"));
        Integer height = integer(metadata.get("height"));
        return new AssetView(asset.getId(), asset.getFileName(), asset.getSizeBytes(), selected,
            integer(metadata.get("durationMs")), width, height,
            width == null || height == null ? null : width >= height ? "LANDSCAPE" : "PORTRAIT",
            bool(metadata.get("hasAudio")), integer(shots.get("shotCount")), scenes, objects, people);
    }

    private BgmView selectedBgm(List<ArtifactEntity> workflowArtifacts) {
        var audio = workflowArtifacts.stream().filter(a -> "BGM_AUDIO".equals(a.getType()))
            .reduce((first, second) -> second).orElse(null);
        var selection = workflowArtifacts.stream().filter(a -> "BGM_SELECTION".equals(a.getType()))
            .reduce((first, second) -> second).map(a -> parseMap(a.getMetadataJson())).orElse(Map.of());
        if (audio == null && selection.isEmpty()) return null;
        var metadata = audio == null ? Map.<String, Object>of() : parseMap(audio.getMetadataJson());
        var candidate = metadata.get("candidate") instanceof Map<?, ?> map ? stringMap(map) : metadata;
        return new BgmView(String.valueOf(selection.getOrDefault("mode", audio == null ? "NONE" : "SELECTED")),
            text(candidate, "title", "name", "fileName"), text(candidate, "artist", "author"),
            text(candidate, "provider"), text(candidate, "providerTrackId", "trackId"),
            audio == null ? null : audio.getExternalArtifactId());
    }

    private List<ArtifactEntity> recentProjectArtifacts(String projectId, String assetId) {
        var types = assetId == null
            ? List.of("BGM_AUDIO", "BGM_SELECTION")
            : List.of("VIDEO_METADATA", "SHOT_LIST", "SCENE_TAGS", "OBJECT_TAGS", "PERSON_TAGS");
        var result = new java.util.ArrayList<ArtifactEntity>();
        for (var type : types) {
            for (var artifact : artifacts.findTop100ByProjectIdAndTypeOrderByCreatedAtDesc(projectId, type)) {
                var payload = parseMap(artifact.getMetadataJson());
                var sourceAssetId = text(payload, "sourceAssetId", "assetId");
                if (assetId == null || assetId.equals(sourceAssetId)) result.add(artifact);
            }
        }
        return result;
    }

    private Map<String, Object> latestPayload(List<ArtifactEntity> values, String type) {
        return values.stream().filter(a -> type.equals(a.getType())).reduce((first, second) -> second)
            .map(a -> parseMap(a.getMetadataJson())).orElse(Map.of());
    }

    private List<String> summarizeTags(List<ArtifactEntity> values, String type, String tagKey) {
        var payload = latestPayload(values, type);
        var counts = new java.util.LinkedHashMap<String, Integer>();
        if (payload.get("shots") instanceof List<?> shotList) for (var item : shotList) {
            if (!(item instanceof Map<?, ?> shot) || !(shot.get(tagKey) instanceof List<?> tags)) continue;
            for (var tag : tags) if (tag instanceof Map<?, ?> value) {
                var label = String.valueOf(value.get("labelZh") == null ? value.get("label") : value.get("labelZh")).trim();
                if (!label.isBlank() && !"null".equals(label)) counts.merge(label, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(8).map(Map.Entry::getKey).toList();
    }

    private Map<String, Object> parseMap(String json) {
        try { return mapper.readValue(json == null ? "{}" : json, new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }
    private Map<String, Object> stringMap(Map<?, ?> source) {
        var result = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
    private String text(Map<String, Object> map, String... keys) {
        for (var key : keys) { var value = map.get(key); if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value); }
        return null;
    }
    private Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private Boolean bool(Object value) { return value instanceof Boolean b ? b : null; }

    private String nextAction(WorkflowRunEntity workflow, TaskRunEntity activeTask) {
        return switch (workflow.getStatus()) {
            case PAUSED -> "请处理当前 Gate: " + workflow.getCurrentGateKey();
            case SUCCEEDED -> "成片已完成，可查看输出 Artifact";
            case FAILED -> "Workflow 失败，请检查错误并选择重试或修改方案";
            case RUNNING -> activeTask == null ? "正在准备下一个 Task" : humanTaskMessage(activeTask);
            default -> "等待 Workflow 启动";
        };
    }

    private String humanTaskMessage(TaskRunEntity task) {
        return switch (task.getNodeKey()) {
            case "video_probe" -> "正在探测视频素材";
            case "video_proxy_generate" -> "正在生成代理视频";
            case "video_shot_detect" -> "正在分析镜头切分";
            case "vision_vlm_analyze" -> "正在理解画面语义";
            case "shot_ranking" -> "正在筛选高质量镜头";
            case "story_plan" -> "已经生成初版故事计划";
            case "timeline_compose" -> "正在编排时间线";
            case "subtitle_compose" -> "正在生成字幕";
            case "bgm_select" -> "正在准备背景音乐候选";
            case "video_render" -> "正在渲染最终视频";
            default -> "正在执行 " + task.getNodeKey();
        };
    }

    private List<String> actions(WorkflowRunEntity workflow, TaskRunEntity task) {
        if (workflow.getStatus() == com.yizhixianyu.agentvideo.execution.RunStatus.PAUSED) return List.of("CONTINUE", "EDIT", "CANCEL");
        if (workflow.getStatus() == com.yizhixianyu.agentvideo.execution.RunStatus.FAILED) return List.of("RETRY", "EDIT", "CANCEL");
        if (task != null && task.getStatus() == com.yizhixianyu.agentvideo.execution.TaskStatus.RETRY_WAIT) return List.of("RETRY", "CANCEL");
        if (workflow.getStatus() == com.yizhixianyu.agentvideo.execution.RunStatus.RUNNING) return List.of("PAUSE", "CANCEL");
        return List.of();
    }

    private void saveSnapshot(String key, BlackboardView view, Long expectedRevision) {
        if (redis == null) return;
        try {
            redis.save(key, mapper.writeValueAsString(view), ttl, expectedRevision);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Failed to serialize Blackboard snapshot", exc);
        }
    }

    private BlackboardView fromJson(String json) {
        try { return mapper.readValue(json, BlackboardView.class); }
        catch (JsonProcessingException exc) { throw new IllegalStateException("Blackboard snapshot is invalid", exc); }
    }

    private String key(String sessionId) { return "avp:v1:agent:blackboard:" + sessionId; }

    public record BlackboardView(Long revision, String sessionId, String userId, String projectId, String goal,
                                 Integer targetDurationMs, String status, String planId, Integer dagVersion,
                                 String workflowRunId, String currentGateKey, RuntimeView runtime,
                                 List<AssetView> assets, BgmView selectedBgm, List<TurnView> turns,
                                 List<TaskView> tasks, List<ArtifactView> artifacts, List<TraceView> traces) {}
    public record RuntimeView(String workflowStatus, String currentGateKey, int progress, String errorMessage,
                              String nextAction, java.time.Instant completedAt, String currentTaskNode,
                              String currentTaskStatus, String currentTaskError, boolean retryable,
                              List<String> availableActions, String latestFailureEvent) {}
    public record TurnView(String id, int sequenceNumber, String role, String content, String planId, String workflowRunId) {}
    public record AssetView(String assetId, String fileName, long sizeBytes, boolean usedByWorkflow,
                            Integer durationMs, Integer width, Integer height, String orientation,
                            Boolean hasAudio, Integer shotCount, List<String> sceneTags,
                            List<String> objectTags, List<String> personTags) {}
    public record BgmView(String mode, String title, String artist, String provider, String providerTrackId,
                          String audioArtifactId) {}
    public record TaskView(String taskRunId, String nodeKey, String toolName, String toolVersion, String status,
                           int attempt, int progress, String errorMessage, String assetId) {}
    public record ArtifactView(String id, String externalArtifactId, String type, String contentHash, String producerTaskRunId) {}
    public record TraceView(String eventType, String traceId, String workflowRunId, String taskRunId, String executionId, java.time.Instant occurredAt) {}
}
