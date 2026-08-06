package com.yizhixianyu.agentvideo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.TaskRunEntity;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowRunEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.storage.ArtifactStorage;
import com.yizhixianyu.agentvideo.project.ProjectEntity;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** One paginated server-side query for the audit page; avoids browser N+1 requests. */
@RestController
@RequestMapping("/api/v1/llm-audits")
public class LlmAuditController {
    private final AuthService auth;
    private final ProjectService projects;
    private final WorkflowRunRepository workflows;
    private final TaskRunRepository tasks;
    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final ObjectMapper mapper;
    private final RedisDraftService redis;

    public LlmAuditController(AuthService auth, ProjectService projects, WorkflowRunRepository workflows,
                              TaskRunRepository tasks, ArtifactRepository artifacts, ArtifactStorage storage,
                              ObjectMapper mapper, ObjectProvider<RedisDraftService> redisProvider) {
        this.auth = auth; this.projects = projects; this.workflows = workflows; this.tasks = tasks;
        this.artifacts = artifacts; this.storage = storage; this.mapper = mapper; this.redis = redisProvider.getIfAvailable();
    }

    @GetMapping
    public AuditPage list(@RequestParam(required = false) String projectId,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "30") int size,
                          HttpServletRequest request) {
        var user = auth.requireUser(request);
        var cacheKey = "avp:v1:llm:audit:list:" + user.id() + ":" + (projectId == null ? "all" : projectId)
            + ":" + page + ":" + size;
        if (redis != null) {
            String cached = null; try { var stored = redis.get(cacheKey); cached = stored == null ? null : stored.json(); } catch (RuntimeException ignored) { }
            if (cached != null) {
                try { return mapper.readValue(cached, new TypeReference<AuditPage>() {}); } catch (Exception ignored) { }
            }
        }
        List<ProjectEntity> owned = projectId == null || projectId.isBlank()
            ? projects.list(user.id()) : List.of(projects.getRequiredForUser(projectId, user.id()));
        var projectNames = owned.stream().collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getName));
        var runs = owned.stream().flatMap(p -> workflows.findByProjectIdOrderByCreatedAtDesc(p.getId()).stream()).toList();
        var taskList = tasks.findByWorkflowRunIdInOrderByCreatedAtAsc(runs.stream().map(WorkflowRunEntity::getId).toList());
        var taskToRun = taskList.stream().collect(Collectors.toMap(TaskRunEntity::getId, TaskRunEntity::getWorkflowRunId));
        var storyArtifacts = artifacts.findByProducerTaskRunIdIn(taskList.stream().map(TaskRunEntity::getId).toList()).stream()
            .filter(a -> "STORY_PLAN".equals(a.getType())).toList();
        var records = storyArtifacts.stream().map(a -> toRecord(a, taskToRun, projectNames, runs)).filter(Objects::nonNull)
            .sorted(Comparator.comparing(AuditRecord::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        var from = Math.min(Math.max(0, page) * Math.max(1, size), records.size());
        var to = Math.min(from + Math.max(1, size), records.size());
        var response = new AuditPage(records.subList(from, to), records.size(), page, size);
        if (redis != null) {
            try { redis.save(cacheKey, mapper.writeValueAsString(response), java.time.Duration.ofMinutes(10)); } catch (Exception ignored) { }
        }
        return response;
    }

    private AuditRecord toRecord(ArtifactEntity artifact, Map<String, String> taskToRun,
                                 Map<String, String> projectNames, List<WorkflowRunEntity> runs) {
        var runId = taskToRun.get(artifact.getProducerTaskRunId()); if (runId == null) return null;
        var run = runs.stream().filter(r -> r.getId().equals(runId)).findFirst().orElse(null); if (run == null) return null;
        try (InputStream input = storage.resource(artifact.getStorageUri()).getInputStream()) {
            JsonNode audit = mapper.readTree(input).path("llmAudit"); if (audit.isMissingNode() || audit.isNull()) return null;
            var errors = new ArrayList<String>(); audit.path("validationErrors").forEach(n -> errors.add(n.asText()));
            return new AuditRecord(audit.path("requestId").asText(runId + ":story-plan"), run.getProjectId(),
                projectNames.getOrDefault(run.getProjectId(), ""), runId, audit.path("provider").asText("none"),
                audit.path("model").asText("none"), audit.path("durationMs").asLong(0),
                "LLM".equals(audit.path("finalSource").asText()) ? "ai" : "fallback", errors,
                audit.path("timestamp").asText(run.getCreatedAt() == null ? null : run.getCreatedAt().toString()));
        } catch (Exception ignored) { return null; }
    }

    public record AuditPage(List<AuditRecord> items, int total, int page, int size) {}
    public record AuditRecord(String id, String projectId, String projectName, String runId, String provider,
                              String model, long latencyMs, String result, List<String> errors, String createdAt) {}
}
